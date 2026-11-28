package ink.hoka.odyssey.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

/**
 * 0.90 «Плавность» — ровные кадры, как в большинстве игр: глаз видит не средний FPS, а неровность. 300…700 FPS
 * вразнобой (кадр то 1,4 мс, то 3,5 мс) дёргается сильнее, чем ровные 300. Поэтому:
 * <ol>
 *   <li><b>Свой потолок кадров.</b> Раз в 0,5 с смотрим, какой кадр игра выдаёт почти всегда (90-й процентиль
 *   «естественного» кадра за 1,5 с) и ставим потолок чуть ниже — ступенью из {@link #STEPS}. Вниз — сразу (иначе
 *   неровно), вверх — только если запас держится {@link #UP_HOLD_NS}. Потолок игрока («Макс. частота кадров») не
 *   превышается.</li>
 *   <li><b>Ровная выдача.</b> Кадр показывается (swap) строго по сетке времени: ждём до срока, последние ~1,5 мс —
 *   не сном (на Windows сон ±1 мс), а коротким ожиданием в цикле. Ваниль ждёт после показа и неточно.</li>
 * </ol>
 * Мало FPS — та же логика: ровные 60 вместо скачущих 45…80. Не мешает: с вертикальной синхронизацией, в меню, в
 * свёрнутом окне и в «отошёл от клавиатуры» работает обычный ограничитель игры. Ключ smooth в config/hoka_odyssey.json.
 */
public final class Smooth {

    private Smooth() {}

    /** Режимы: 0 — выкл (как в игре), 1 — авто (потолок по силам машины), 2 — авто «с запасом» (ещё ровнее, ниже FPS). */
    public static final int OFF = 0, AUTO = 1, STABLE = 2;
    /** 0.90: режимы 3…8 — авто с потолком: ровные «до N» (игрок хочет стабильные 500, а не скачки 700…900). */
    static final int[] CAPS = {120, 144, 165, 240, 360, 500};
    public static final int CAP_500 = 8, MAX_MODE = 2 + CAPS.length, DEFAULT = CAP_500;

    /** Потолок режима (без потолка — Integer.MAX_VALUE). */
    static int cap(int m) { return m > STABLE && m <= MAX_MODE ? CAPS[m - STABLE - 1] : Integer.MAX_VALUE; }
    /** Ступени потолка: ровные значения, частые у мониторов (60/120/144/165/240…). */
    static final int[] STEPS = {30, 40, 48, 60, 72, 90, 100, 120, 144, 165, 180, 200, 240, 280, 320, 360, 400, 450, 500, 560, 640, 720, 800, 900, 1000};
    static final long UP_HOLD_NS = 2_000_000_000L, DOWN_HOLD_NS = 1_000_000_000L, EVAL_NS = 500_000_000L, WINDOW_NS = 2_000_000_000L;
    /** Сколько последних мс до срока ждать в цикле (точно), а не сном. */
    static final long SPIN_NS = 500_000L;

    private static int mode = load();
    private static int target;                                                   // текущий потолок (0 — ещё не выбран)
    private static long deadline, afterWait, lastEval, upSince;
    private static final long[] natT = new long[2048], natD = new long[2048];      // время и длительность «естественных» кадров
    private static int natN, natHead;

    public static int mode() { return mode; }

    public static void set(int v) { mode = Math.max(OFF, Math.min(MAX_MODE, v)); target = 0; }

    public static void save() { VerticalView.put("smooth", mode); }

    /** Текущий потолок (для F3/отладки); 0 — не действует. */
    public static int target() { return target; }

    private static int load() {
        try {
            JsonObject o = VerticalView.read();
            return o.has("smooth") ? Math.max(OFF, Math.min(MAX_MODE, o.get("smooth").getAsInt())) : DEFAULT;
        } catch (Throwable e) {                                                // и без игры (тесты)
            return DEFAULT;
        }
    }

    public static Component valueText(int v) {
        if (v > STABLE) return Component.translatable("options.hoka_odyssey.smooth.cap", cap(v));
        return Component.translatable(switch (v) {
            case OFF -> "options.hoka_odyssey.smooth.off";
            case STABLE -> "options.hoka_odyssey.smooth.stable";
            default -> "options.hoka_odyssey.smooth.auto";
        });
    }

    public static net.minecraft.client.OptionInstance<Integer> option() {
        return new net.minecraft.client.OptionInstance<>("options.hoka_odyssey.smooth",
                net.minecraft.client.OptionInstance.cachedConstantTooltip(Component.translatable("options.hoka_odyssey.smooth.tooltip")),
                (caption, v) -> net.minecraft.client.Options.genericValueLabel(caption, valueText(v)),
                new net.minecraft.client.OptionInstance.IntRange(OFF, MAX_MODE, false), mode, v -> { set(v); save(); });
    }

    /** Берёт ли «Плавность» ограничение кадров на себя в этом кадре. */
    public static boolean active(Minecraft mc) {
        if (mode == OFF || mc.level == null || mc.options.enableVsync().get()) return false;
        var tr = mc.getFramerateLimitTracker();
        return tr.getThrottleReason() == com.mojang.blaze3d.platform.FramerateLimitTracker.FramerateThrottleReason.NONE;
    }

    /**
     * Перед показом кадра (swap): учесть кадр, выбрать потолок, дождаться срока по сетке. userLimit — «Макс. частота
     * кадров» игрока (260 — без ограничения).
     */
    public static void beforePresent(Minecraft mc) {
        long now = System.nanoTime();
        if (!active(mc)) { target = 0; deadline = 0; afterWait = now; return; }
        if (afterWait != 0) record(now, now - afterWait);                      // хвост прошлого кадра + работа этого
        if (now - lastEval >= EVAL_NS) { lastEval = now; choose(now, mc.options.framerateLimit().get()); }
        int t = target;
        if (t > 0) {
            long step = 1_000_000_000L / t;
            long d = deadline + step;
            if (deadline == 0 || now - d > step) d = now;                        // отстали больше чем на кадр — с текущего
            deadline = d;
            waitUntil(d);
        }
        afterWait = System.nanoTime();
    }

    /** Насколько сон потока опаздывает (нс, держим наибольшее с медленным спадом): на Windows от ~0,5 до ~15 мс. */
    private static long parkLate = 2_000_000L;

    /**
     * Ждать до срока d: сном — пока до срока больше, чем сон может опоздать, остаток — коротким ожиданием в цикле.
     * Опоздание сна меряем на ходу: где таймер грубый, спим меньше и чаще ждём в цикле.
     */
    static void waitUntil(long d) {
        long left;
        while ((left = d - System.nanoTime()) > parkLate + SPIN_NS) {
            long want = left - parkLate - SPIN_NS / 2, t0 = System.nanoTime();
            LockSupport.parkNanos(want);
            long late = System.nanoTime() - t0 - want;
            parkLate = late > parkLate ? Math.min(late, 20_000_000L) : parkLate - ((parkLate - Math.max(late, 200_000L)) >> 5);
        }
        while (d - System.nanoTime() > 0) Thread.onSpinWait();
    }

    private static void record(long now, long dur) {
        natT[natHead] = now;
        natD[natHead] = Math.min(dur, 200_000_000L);
        natHead = (natHead + 1) % natT.length;
        if (natN < natT.length) natN++;
    }

    /** Потолок по кадрам последних 1,5 с. */
    private static void choose(long now, int userLimit) {
        long[] a = new long[natN];
        int n = 0;
        for (int i = 0; i < natN; i++) {
            int k = Math.floorMod(natHead - 1 - i, natT.length);
            if (now - natT[k] > WINDOW_NS) break;
            a[n++] = natD[k];
        }
        if (n < 20) return;
        Arrays.sort(a, 0, n);
        // 0.92: типичный кадр (75-й процентиль), а не редкие тяжёлые: подгрузка чанка не роняет потолок
        long p75 = a[Math.min(n - 1, (int) (n * 0.75))];
        double margin = mode == STABLE ? 1.25 : 1.06;
        int want = pick(1e9 / (p75 * margin), Math.min(userLimit >= 260 ? Integer.MAX_VALUE : userLimit, cap(mode)));
        target = next(target, want, now);
    }

    /** Наибольшая ступень не выше fps и потолка limit (Integer.MAX_VALUE — без потолка). */
    static int pick(double fps, int limit) {
        int cap = limit;
        int best = STEPS[0];
        for (int s : STEPS) if (s <= fps && s <= cap) best = s;
        return Math.min(best, cap);
    }

    /** Вниз — сразу; вверх — когда запас держится UP_HOLD_NS. */
    /**
     * 0.92: спокойнее. Вниз — только если тяжело {@link #DOWN_HOLD_NS} подряд и не больше чем на 2 ступени за раз
     * (раньше — сразу и до любой ступени: одна подгрузка → 120 FPS на 3+ с); вверх — когда запас держится UP_HOLD_NS.
     */
    static int next(int cur, int want, long now) {
        if (cur == 0) { upSince = downSince = 0; return want; }
        if (want == cur) { upSince = downSince = 0; return cur; }
        if (want < cur) {
            upSince = 0;
            if (downSince == 0) downSince = now;
            if (now - downSince < DOWN_HOLD_NS) return cur;
            downSince = 0;
            int i = Arrays.binarySearch(STEPS, cur);
            int floor = i > 0 ? STEPS[Math.max(0, i - 2)] : want;
            return Math.max(want, floor);
        }
        downSince = 0;
        if (upSince == 0) upSince = now;
        if (now - upSince >= UP_HOLD_NS) { upSince = 0; return want; }
        return cur;
    }

    private static long downSince;

    /** Тесты: сброс состояния. */
    static void resetForTest() { downSince = 0; target = 0; deadline = 0; afterWait = 0; lastEval = 0; upSince = 0; natN = 0; natHead = 0; }
}
