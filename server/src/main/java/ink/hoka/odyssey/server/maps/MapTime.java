package ink.hoka.odyssey.server.maps;

import ink.hoka.odyssey.api.ModuleConfig;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.TaskSchedule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Время суток карты. Раздел map.yml:
 * <pre>
 * time: {cycle: true, speed: 1.0, start: 1000}
 * </pre>
 * cycle — идёт ли время; speed — множитель (1 = сутки 20 мин, 2 = 10 мин, 0.5 = 40 мин; 0 = стоп);
 * start — время (тики 0…23999) при загрузке карты. Нет раздела — как Minestom по умолчанию (идёт, speed 1).
 * <p>
 * speed 1 — штатный ход Minestom (timeRate 1, клиент сам ведёт время, сверка раз в секунду). Иное — timeRate 0 и
 * свой счётчик: дробный шаг копится, время шлётся пакетом в тот тик, когда сдвинулось (плавно и для 0.5, и для 10).
 */
public final class MapTime {

    public static final long DAY = 24_000;
    public static final double MAX_SPEED = 100;

    /** Настройки карты (неизменяемые). */
    public record Cfg(boolean cycle, double speed, Long start) {
        public static final Cfg DEFAULT = new Cfg(true, 1.0, null);
        /** Время идёт (цикл включён и скорость > 0). */
        public boolean running() { return cycle && speed > 0; }
        public Cfg withCycle(boolean c) { return new Cfg(c, speed, start); }
        public Cfg withSpeed(double s) { return new Cfg(cycle, s, start); }
        public Cfg withStart(Long s) { return new Cfg(cycle, speed, s); }
    }

    private static final class State { Cfg cfg = Cfg.DEFAULT; double acc; }
    private static final Map<Instance, State> STATES = new WeakHashMap<>();
    private static volatile boolean started;

    private MapTime() {}

    /** Прочитать раздел time: (нет — DEFAULT; кривые значения — по умолчанию). */
    public static Cfg read(ModuleConfig meta) {
        Object t = meta.get("time");
        if (t instanceof Boolean b) return Cfg.DEFAULT.withCycle(b);                        // time: false — стоит
        if (!(t instanceof Map<?, ?>)) return Cfg.DEFAULT;
        boolean cycle = meta.getBoolean("time.cycle", true);
        double speed = clampSpeed(meta.getDouble("time.speed", 1.0));
        Long start = meta.get("time.start") instanceof Number n ? Math.floorMod(n.longValue(), DAY) : null;
        return new Cfg(cycle, speed, start);
    }

    static double clampSpeed(double s) { return Double.isFinite(s) ? Math.max(0, Math.min(MAX_SPEED, s)) : 1.0; }

    /** Строка раздела для map.yml (одной строкой, flow-стиль). */
    static String yamlLine(Cfg c) {
        StringBuilder b = new StringBuilder("time: {cycle: ").append(c.cycle()).append(", speed: ").append(fmt(c.speed()));
        if (c.start() != null) b.append(", start: ").append(c.start());
        return b.append('}').toString();
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? String.format(Locale.ROOT, "%.1f", d) : String.valueOf(d);
    }

    /**
     * Записать раздел time: в map.yml, НЕ трогая остальной текст (комментарии карты ценны, ModuleConfig.save их стирает):
     * старый раздел верхнего уровня (строка «time:» и её вложенные строки) заменяется, нет — дописывается в конец.
     */
    static String patchText(String text, Cfg c) {
        String[] lines = text.split("\r?\n", -1);
        List<String> out = new ArrayList<>(lines.length + 1);
        boolean done = false, skipping = false;
        for (String l : lines) {
            if (skipping) {
                // вложенные строки раздела (с отступом) и пустые/комментарии внутри него — пропустить
                if (l.startsWith(" ") || l.startsWith("\t")) continue;
                skipping = false;
            }
            if (l.startsWith("time:") || l.equals("time")) {
                if (!done) { out.add(yamlLine(c)); done = true; }
                skipping = true;
                continue;
            }
            out.add(l);
        }
        if (!done) {
            // дописать перед хвостовыми пустыми строками
            int i = out.size();
            while (i > 0 && out.get(i - 1).isEmpty()) i--;
            out.add(i, yamlLine(c));
        }
        String r = String.join("\n", out);
        return r.endsWith("\n") ? r : r + "\n";
    }

    /** Сохранить раздел time: в map.yml и перечитать meta. */
    public static void save(ModuleConfig meta, Cfg c) throws IOException {
        Path f = meta.file();
        String text = Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
        Files.writeString(f, patchText(text, c), StandardCharsets.UTF_8);
        meta.reload();
    }

    /** Применить настройки к миру (при загрузке карты: with start — выставить время). */
    public static void apply(Instance inst, Cfg c, boolean setStart) {
        start();
        synchronized (STATES) {
            State s = STATES.computeIfAbsent(inst, k -> new State());
            s.cfg = c;
            s.acc = 0;
        }
        inst.setTimeRate(c.running() && c.speed() == 1.0 ? 1 : 0);
        inst.setTimeSynchronizationTicks(20);
        if (setStart && c.start() != null) inst.setTime(c.start());
        else inst.setTime(inst.getTime());                                // клиенту — новый флаг «время идёт»
    }

    public static Cfg cfg(Instance inst) {
        synchronized (STATES) { State s = STATES.get(inst); return s == null ? Cfg.DEFAULT : s.cfg; }
    }

    /** Время дня 0…23999. */
    public static long dayTime(Instance inst) { return Math.floorMod(inst.getTime(), DAY); }

    /** Свой ход для speed ≠ 1 (раз в тик). */
    private static synchronized void start() {
        if (started) return;
        started = true;
        MinecraftServer.getSchedulerManager().buildTask(MapTime::tick).repeat(TaskSchedule.tick(1)).schedule();
    }

    private static void tick() {
        List<Instance> move = new ArrayList<>();
        List<Long> by = new ArrayList<>();
        synchronized (STATES) {
            for (var e : STATES.entrySet()) {
                State s = e.getValue();
                if (!s.cfg.running() || s.cfg.speed() == 1.0) continue;
                s.acc += s.cfg.speed();
                long n = (long) Math.floor(s.acc);
                if (n <= 0) continue;
                s.acc -= n;
                move.add(e.getKey());
                by.add(n);
            }
        }
        for (int i = 0; i < move.size(); i++) {
            Instance inst = move.get(i);
            if (!inst.isRegistered()) continue;
            inst.setTime(inst.getTime() + by.get(i));                     // setTime сразу шлёт время игрокам карты
        }
    }

    /** Имя времени суток → тики; null — не имя и не число. */
    public static Long parse(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "day", "день" -> 1000L;
            case "noon", "полдень" -> 6000L;
            case "sunset", "закат" -> 12000L;
            case "night", "ночь" -> 13000L;
            case "midnight", "полночь" -> 18000L;
            case "sunrise", "рассвет" -> 23000L;
            default -> {
                try { yield Long.parseLong(s); } catch (NumberFormatException e) { yield null; }
            }
        };
    }

    /** Подпись времени: «06:00 (6000)» — 0 тиков = 06:00, как в ванили. */
    public static String label(long t) {
        long d = Math.floorMod(t, DAY);
        long min = (d * 60 / 1000 + 6 * 60) % (24 * 60);
        return String.format(Locale.ROOT, "%02d:%02d (%d)", min / 60, min % 60, d);
    }
}
