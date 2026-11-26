package ink.hoka.odyssey.client;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.locks.LockSupport;

/**
 * 0.57: свет считает клиент (сервер прислал op 19 LIGHT_MODE = 1). Главный поток отдаёт движку (LocalLightEngine,
 * свой поток) события — столбец, куб, правка, верхи неба (op 18), окно — и применяет готовый свет через ServerLight
 * (тот же путь: слои движка игры, пометки Sodium только где свет изменился). Своя правка блока — тень в том же
 * кадре: перед runLightUpdates ждём движок до 2 мс ({@link #frame}).
 * <p>Сервер без op 19 (старый) или режим 0 — всё как в 0.56: свет от сервера. Только главный поток (кроме движка).</p>
 */
public final class LocalLight {

    private LocalLight() {}

    /** Сервер сказал: свет на клиенте (op 19). На соединение. */
    private static volatile boolean wanted;
    /** Мир, для которого работает движок (null — режима нет). */
    private static volatile ClientLevel level;
    private static LocalLightEngine eng;
    private static long editSeq, editOutsSeen;

    /** Верхи неба T колонок от движка (для F там, где у куба нет записи), столбец → 256. */
    private static final Long2ObjectOpenHashMap<int[]> TOPS = new Long2ObjectOpenHashMap<>();
    /** Пометки сетки пришедших кубов, ждущие их свет (одна сборка вместо двух): секция → код, время. */
    private static final Long2LongOpenHashMap HELD = new Long2LongOpenHashMap();
    private static final it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue HELD_ORDER = new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue();
    static final long HOLD_NS = 300_000_000L, EDIT_WAIT_NS = 2_000_000L;
    public static long applied, heldMarks, heldTimeout, edits, editWaits, editLate;

    /** op 19 (главный поток, сразу по приходу). */
    static void mode(int m) {
        wanted = m == 1;
        if (!wanted && level != null) reset();
    }

    /**
     * op 12 (вход в инстанс, сервер ≥ 0.51 шлёт его всегда, новый — следом op 19): режим света — заново по op 19.
     * Старый сервер (без op 19) за прокси после нового — свет от сервера, а не «на клиенте» с прошлого входа.
     */
    static void instanceEntry() { wanted = false; }

    /** Режим «свет на клиенте» для этого мира. */
    public static boolean on(Level l) { return l != null && l == level; }

    public static boolean active() { return level != null; }

    private static boolean tall(ClientLevel l) { return TallWorld.net && l != null && l.getSectionsCount() > 64; }

    /** Движок для мира l (запускается при первом событии после op 19; мир сменился — заново). */
    private static boolean ensure(ClientLevel l) {
        if (!tall(l)) return false;
        if (level == l) return true;                                   // движок мира уже идёт (op 12 нового входа его не глушит)
        if (!wanted) return false;
        reset();
        level = l;
        eng = new LocalLightEngine(l.dimensionType().hasSkyLight(), l.getMinSectionY());
        eng.start();
        ServerLight.activate(l);                                       // небо/блоки игры — выкл (миксины ServerLight*)
        HokaOdysseyClient.LOG.info("свет на клиенте: включён");
        return true;
    }

    /** Мир сменился / вышли / режим выключен. */
    public static void reset() {
        if (eng != null) eng.shutdown();
        eng = null;
        level = null;
        TOPS.clear();
        HELD.clear();
        HELD_ORDER.clear();
        editSeq = 0;
        editOutsSeen = 0;
    }

    /** Отключились от сервера. */
    public static void disconnected() {
        reset();
        wanted = false;
    }

    // ───────────── события ─────────────

    /** Скелет столбца лёг (SparseLevelChunkMixin, после чтения секций). */
    public static void column(ClientLevel l, LevelChunk c) {
        if (YardColumns.isYard(c.getPos().x) || !ensure(l)) return;
        eng.post(new LocalLightEngine.Ev(LocalLightEngine.EV_COLUMN, c.getPos().x, 0, c.getPos().z, c.getSections(), 0));
    }

    /** Столбец забыт. */
    public static void columnGone(ClientLevel l, int x, int z) {
        if (!on(l)) return;
        TOPS.remove(ChunkPos.asLong(x, z));
        eng.post(new LocalLightEngine.Ev(LocalLightEngine.EV_GONE, x, 0, z, null, 0));
    }

    /** Куб пришёл / ушёл (gone — op 4) (CubeReceiver.put). */
    static void cube(ClientLevel l, int x, int y, int z, boolean gone) {
        if (!on(l) || YardColumns.isYard(x)) return;
        eng.post(new LocalLightEngine.Ev(LocalLightEngine.EV_CUBE, x, y, z, gone ? Boolean.FALSE : Boolean.TRUE, 0));
    }

    /** Блок сменился (LevelChunk.setBlockState): только если свет от этого может измениться. */
    public static void edit(Level l, BlockPos pos, BlockState was, BlockState now) {
        if (!on(l) || was == now || YardColumns.isYard(SectionPos.blockToSectionCoord(pos.getX()))) return;
        if (LightInfo.info(was) == LightInfo.info(now)) return;
        edits++;
        eng.post(new LocalLightEngine.Ev(LocalLightEngine.EV_EDIT, pos.getX(), pos.getY(), pos.getZ(), null, ++editSeq));
    }

    /** op 18: skyTop+1 сервера по столбцам (тройки cx, cz, 256 значений — как Parsed.heights). */
    static void tops(ClientLevel l, int[] d) {
        if (!ensure(l) || d == null) return;
        for (int b = 0; b + 258 <= d.length; b += 258) {
            if (YardColumns.isYard(d[b])) continue;
            int[] t = new int[256];
            for (int i = 0; i < 256; i++) t[i] = d[b + 2 + i] - 1;                // skyTop+1 → skyTop
            eng.post(new LocalLightEngine.Ev(LocalLightEngine.EV_TOPS, d[b], 0, d[b + 1], t, 0));
        }
    }

    /** op 5: окно [from, to]; блоки сервер шлёт ещё на куб шире (кольцо). */
    static void window(ClientLevel l, int from, int to) {
        if (!ensure(l)) return;
        eng.post(new LocalLightEngine.Ev(LocalLightEngine.EV_WINDOW, from - 1, to + 1, 0, null, 0));
    }

    // ───────────── пометки сетки кубов ─────────────

    /** Пометку сетки пришедшего куба придержать до его света (true — придержали). */
    static boolean hold(int x, int y, int z, int code) {
        if (level == null || code == 0) return false;
        long k = SectionPos.asLong(x, y, z);
        long now = System.nanoTime();
        long was = HELD.get(k);
        int c = (int) (was >>> 40) | code;
        if (!HELD.containsKey(k)) HELD_ORDER.enqueue(k);
        HELD.put(k, (long) c << 40 | (now >>> 20 & 0xFFFFFFFFFFL));
        heldMarks++;
        return true;
    }

    private static void release(long k) {
        if (!HELD.containsKey(k)) return;
        long v = HELD.remove(k);
        SectionDirty.mark(SectionPos.x(k), SectionPos.y(k), SectionPos.z(k), (int) (v >>> 40));
    }

    private static void expire() {
        long now = System.nanoTime() >>> 20 & 0xFFFFFFFFFFL, lim = HOLD_NS >>> 20;
        while (!HELD_ORDER.isEmpty()) {
            long k = HELD_ORDER.firstLong();
            if (!HELD.containsKey(k)) { HELD_ORDER.dequeueLong(); continue; }
            long at = HELD.get(k) & 0xFFFFFFFFFFL;
            if (now - at < lim) break;
            HELD_ORDER.dequeueLong();
            heldTimeout++;
            release(k);
        }
    }

    // ───────────── кадр ─────────────

    /**
     * Перед runLightUpdates (SectionDirtyFlushMixin): своя правка — подождать движок (≤ 2 мс) и применить сразу;
     * остальное — по бюджету кадра.
     */
    public static void frame() {
        ClientLevel l = level;
        if (l == null || eng == null) return;
        if (Minecraft.getInstance().level != l) { reset(); return; }
        if (eng.editsDone.get() < editSeq) {
            long t0 = System.nanoTime();
            editWaits++;
            while (eng.editsDone.get() < editSeq && System.nanoTime() - t0 < EDIT_WAIT_NS) LockSupport.parkNanos(50_000);
            if (eng.editsDone.get() < editSeq) editLate++;
        }
        long end = System.nanoTime() + FrameWatch.budgetNs(0.20, 200_000L, 1_500_000L);
        boolean first = true;
        LocalLightEngine.Out o;
        while ((o = eng.out.peek()) != null) {
            boolean must = editOutsSeen < eng.editOuts.get();             // впереди есть свет правки — всё до него сразу
            if (!must && !first && System.nanoTime() >= end) break;
            eng.out.poll();
            first = false;
            if (o.edit) editOutsSeen++;
            apply(l, o);
        }
        expire();
    }

    private static void apply(ClientLevel l, LocalLightEngine.Out o) {
        if (o.topKeys != null) for (int i = 0; i < o.topKeys.length; i++) {
            if (o.tops[i] == null) TOPS.remove(o.topKeys[i]); else TOPS.put(o.topKeys[i], o.tops[i]);
        }
        if (o.pos.length > 0) ServerLight.applyLocal(l, o.pos, o.cubes);
        applied += o.pos.length;
        for (long k : o.done) release(k);
    }

    // ───────────── F для кубов без записи ─────────────

    /** Небо по F в мировой клетке; -1 — верхов столбца нет. */
    static int skyF(int x, int y, int z) {
        int[] t = TOPS.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (t == null) return -1;
        return y > t[(z & 15) << 4 | (x & 15)] ? 15 : 0;
    }

    /** Запись F для куба sec; null — верхов нет. */
    static ServerLight.Cube cubeF(long sec) {
        int[] t = TOPS.get(ChunkPos.asLong(SectionPos.x(sec), SectionPos.z(sec)));
        if (t == null) return null;
        int y0 = SectionPos.y(sec) << 4;
        boolean all15 = true, all0 = true;
        byte[] sd = new byte[256];
        for (int c = 0; c < 256; c++) {
            int v = Math.max(0, Math.min(16, t[c] + 1 - y0));
            sd[c] = (byte) v;
            if (v != 0) all15 = false;
            if (v != 16) all0 = false;
        }
        if (all15) return LocalLightEngine.SKY15;
        if (all0) return LocalLightEngine.DARK;
        return new ServerLight.Cube((byte) 0, sd, (byte) 1, null);
    }

    /** F3: короткая строка (null — режима нет). */
    public static String debugLine() {
        LocalLightEngine e = eng;
        if (e == null) return null;
        return "Hoka свет (клиент): пересчётов " + e.batches + ", очередь выдачи " + e.out.size() + ", правок " + edits
                + " (не успели " + editLate + "), " + LagReport.stats();
    }

    public static String stats() {
        LocalLightEngine e = eng;
        return (e == null ? "свет на клиенте: " + (wanted ? "ждёт мир" : "выкл") : e.stats())
                + ", применено " + applied + ", правок " + edits + " (ждали " + editWaits + ", не успели " + editLate + ")"
                + ", пометок придержано " + heldMarks + " (по таймауту " + heldTimeout + ")";
    }
}
