package ink.hoka.odyssey.client;

import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 0.57: движок света на клиенте (поток «hoka-light»). Правила — как у сервера (CubeLight): небо 15 выше верха колонки
 * T (skyTop), дальше растекание с ослаблением max(1, lightBlock) и проверкой полных граней; свет блоков от
 * lightEmission. Известны кубы окна блоков (окно op 5 ± 1 — кольцо сервер шлёт блоками) в загруженных столбцах;
 * остальное — формула F (небо y &gt; T ? 15 : 0, блоки 0; незагруженный столбец — 0). Свет ≤ 15, поэтому окно op 5
 * посчитано точно: вклад клеток за кольцом в окно — 0, а отвесное небо задано T.
 * <p>T колонки: skyTop сервера (op 18), если он выше окна блоков (остров над окном); иначе — по блокам окна
 * (правка игрока видна сразу); блоков нет — skyTop сервера, если он ниже окна, иначе низ окна − 1.</p>
 * <p>Пересчёт: грязные кубы D → область R = D + соседи (3×3×3) одной заливкой с рамкой из опубликованного света
 * кубов за R (как у сервера: изменения в D не дальше 15 клеток — рамка от них не зависит). Кубы R, у которых свет
 * заведомо F (весь выше T — небо 15; нет источников в 3×3×3 — 0), считаются без заливки. Палитры секций читаются
 * без замка: гонку с записью главного потока чинит следующее событие правки (как на сервере).</p>
 * Выход — {@link Out} (свет кубов в формате ServerLight.Cube, верхи колонок), применяет главный поток (LocalLight).
 */
final class LocalLightEngine implements Runnable {

    static final int EV_COLUMN = 1, EV_GONE = 2, EV_CUBE = 3, EV_EDIT = 4, EV_TOPS = 5, EV_WINDOW = 6;

    /** Событие главного потока. data: COLUMN — LevelChunkSection[], TOPS — int[256] skyTop. */
    record Ev(int type, int x, int y, int z, Object data, long seq) {}

    /** Результат: свет кубов (cube null — запись снять), верхи колонок, какие грязные кубы посчитаны. */
    static final class Out {
        final long[] pos;
        final ServerLight.Cube[] cubes;
        final long[] done;
        final long[] topKeys;
        final int[][] tops;
        final boolean edit;
        Out(long[] pos, ServerLight.Cube[] cubes, long[] done, long[] topKeys, int[][] tops, boolean edit) {
            this.pos = pos; this.cubes = cubes; this.done = done; this.topKeys = topKeys; this.tops = tops; this.edit = edit;
        }
    }

    static final ServerLight.Cube SKY15 = new ServerLight.Cube((byte) 2, null, (byte) 1, null);
    static final ServerLight.Cube DARK = new ServerLight.Cube((byte) 1, null, (byte) 1, null);
    /** «Ничего нет» (как HeightIndex.NONE у сервера — ниже любого мира). Незагруженный столбец — {@link #NO_SKY}. */
    static final int NONE = -(1 << 24), NO_SKY = 1 << 24;

    final ConcurrentLinkedQueue<Ev> in = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Out> out = new ConcurrentLinkedQueue<>();
    /** Последняя правка (seq), свет которой уже в {@link #out}; сколько Out с правками выдано. */
    final AtomicLong editsDone = new AtomicLong(), editOuts = new AtomicLong();
    private final boolean sky;
    private final int minSec;
    private volatile boolean stop;
    final Thread thread;

    // ── отладка (читает F3/лог) ──
    volatile long batches, cubesBfs, cubesTrivial, published, nanos, maxNanos, failures, events;

    LocalLightEngine(boolean sky, int minSec) {
        this.sky = sky;
        this.minSec = minSec;
        thread = new Thread(this, "hoka-light");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
    }

    void start() { thread.start(); }

    void post(Ev e) {
        in.add(e);
        LockSupport.unpark(thread);
    }

    void shutdown() {
        stop = true;
        LockSupport.unpark(thread);
    }

    // ───────────── состояние (только поток света) ─────────────

    private static final class Col {
        final int cx, cz;
        final LevelChunkSection[] secs;
        /** skyTop сервера (op 18), по клеткам z<<4|x. */
        final int[] srv = new int[256];
        /** Действующий верх T. */
        final int[] top = new int[256];
        int minT = NONE, maxT = NONE;
        /** Верхи уже отданы главному потоку. */
        boolean sent;
        /** Опубликованный свет по секции Y. */
        final Int2ObjectOpenHashMap<ServerLight.Cube> pub = new Int2ObjectOpenHashMap<>();
        /** Кубы, блоки которых уже пришли (op 2/13) после скелета; индекс sy − minSec. */
        final java.util.BitSet got = new java.util.BitSet();
        /** Есть ли светящиеся блоки: 0 — не знаем, 1 — нет, 2 — да. */
        final Int2ByteOpenHashMap emit = new Int2ByteOpenHashMap();
        Col(int cx, int cz, LevelChunkSection[] secs) {
            this.cx = cx; this.cz = cz; this.secs = secs;
            Arrays.fill(srv, NONE);
            Arrays.fill(top, NONE);
        }
    }

    private final Long2ObjectOpenHashMap<Col> cols = new Long2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet(), editDirty = new LongLinkedOpenHashSet();
    private final LongOpenHashSet topsDirty = new LongOpenHashSet(), topsOut = new LongOpenHashSet();
    /** Окно блоков (секции Y); lo &gt; hi — окна нет. */
    private int lo = 1, hi = 0;
    private long editSeen;
    private final List<long[]> removed = new ArrayList<>();

    // сведения о блоках кубов (LRU), NO_BLOCKS — куб без значимых для света блоков
    private static final int INF_CACHE = 1024;
    private static final short[] NO_BLOCKS = new short[0];
    private final Long2ObjectLinkedOpenHashMap<short[]> infCache = new Long2ObjectLinkedOpenHashMap<>();

    @Override
    public void run() {
        while (!stop) {
            boolean ev;
            try {
                ev = drain();
            } catch (Throwable t) {                                            // палитру меняли в этот момент — дальше
                failures++;
                if (failures < 5) HokaOdysseyClient.LOG.warn("свет на клиенте: сбой события ({})", t.toString(), t);
                continue;
            }
            if (stop) break;
            try {
                if (!topsDirty.isEmpty()) recomputeTops();
                if (!editDirty.isEmpty()) {
                    while (!editDirty.isEmpty() && !stop) batch(editDirty, true);
                }
                if (editDirty.isEmpty() && editsDone.get() < editSeen) {
                    if (!removed.isEmpty() || !topsOut.isEmpty()) emit(new LongArrayList(), new ArrayList<>(), new long[0], true);
                    editsDone.set(editSeen);
                }
                if (!dirty.isEmpty()) { batch(dirty, false); continue; }
                if (!removed.isEmpty() || !topsOut.isEmpty()) emit(new LongArrayList(), new ArrayList<>(), new long[0], false);
            } catch (Throwable t) {
                failures++;
                if (failures < 5) HokaOdysseyClient.LOG.warn("свет на клиенте: сбой пересчёта ({})", t.toString(), t);
                continue;
            }
            if (!ev && in.isEmpty()) LockSupport.parkNanos(this, 50_000_000L);
        }
    }

    /** События → состояние. true — что-то было. */
    private boolean drain() {
        boolean any = false;
        Ev e;
        while ((e = in.poll()) != null) {
            any = true;
            events++;
            switch (e.type()) {
                case EV_COLUMN -> {
                    long k = ChunkPos.asLong(e.x(), e.z());
                    Col old = cols.get(k);
                    Col c = new Col(e.x(), e.z(), (LevelChunkSection[]) e.data());
                    if (old != null) {
                        System.arraycopy(old.srv, 0, c.srv, 0, 256);
                        c.pub.putAll(old.pub);
                    }
                    cols.put(k, c);
                    forgetInf(e.x(), e.z());
                    topsDirty.add(k);
                    for (int sy = lo; sy <= hi; sy++) dirty.add(SectionPos.asLong(e.x(), sy, e.z()));
                }
                case EV_GONE -> {
                    long k = ChunkPos.asLong(e.x(), e.z());
                    cols.remove(k);
                    topsDirty.remove(k);
                    forgetInf(e.x(), e.z());
                }
                case EV_CUBE, EV_EDIT -> {
                    int cx = e.x() >> 4, cz = e.z() >> 4, sy = e.y() >> 4;
                    if (e.type() == EV_CUBE) { cx = e.x(); cz = e.z(); sy = e.y(); }
                    long k = ChunkPos.asLong(cx, cz);
                    Col c = cols.get(k);
                    if (e.type() == EV_EDIT) editSeen = Math.max(editSeen, e.seq());
                    if (c == null) continue;
                    long key = SectionPos.asLong(cx, sy, cz);
                    infCache.remove(key);
                    c.emit.remove(sy);
                    if (e.type() == EV_CUBE) {
                        if (sy - minSec >= 0) c.got.set(sy - minSec, e.data() != Boolean.FALSE);
                        topsDirty.add(k);
                        if (known(cx, sy, cz)) dirty.add(key);
                    } else {
                        if (known(cx, sy, cz)) { editDirty.add(key); dirty.remove(key); }
                        try {
                            cellTop(c, (e.z() & 15) << 4 | (e.x() & 15), true);
                        } catch (RuntimeException ex) {                  // гонка с записью палитры — весь столбец заново
                            topsDirty.add(k);
                        }
                    }
                }
                case EV_TOPS -> {
                    Col c = cols.get(ChunkPos.asLong(e.x(), e.z()));
                    if (c == null) continue;
                    System.arraycopy((int[]) e.data(), 0, c.srv, 0, 256);
                    topsDirty.add(ChunkPos.asLong(e.x(), e.z()));
                }
                case EV_WINDOW -> window(e.x(), e.y());
                default -> {}
            }
        }
        return any;
    }

    private void forgetInf(int cx, int cz) {
        if (infCache.isEmpty()) return;
        infCache.keySet().removeIf((java.util.function.LongPredicate) q -> SectionPos.x(q) == cx && SectionPos.z(q) == cz);
    }

    /** Окно блоков сменилось: новые слои — пересчёт, ушедшие — запись снять (куб стал неизвестным). */
    private void window(int nlo, int nhi) {
        int olo = lo, ohi = hi;
        lo = nlo;
        hi = nhi;
        for (Col c : cols.values()) {
            topsDirty.add(ChunkPos.asLong(c.cx, c.cz));
            for (int sy = nlo; sy <= nhi; sy++) if (olo > ohi || sy < olo || sy > ohi) dirty.add(SectionPos.asLong(c.cx, sy, c.cz));
            if (olo <= ohi) for (int sy = olo; sy <= ohi; sy++) {
                if (sy >= nlo && sy <= nhi) continue;
                if (c.pub.remove(sy) != null) removed.add(new long[]{SectionPos.asLong(c.cx, sy, c.cz)});
                long key = SectionPos.asLong(c.cx, sy, c.cz);
                dirty.remove(key);
                editDirty.remove(key);
            }
        }
    }

    private boolean known(int cx, int sy, int cz) {
        return sy >= lo && sy <= hi && !YardColumns.isYard(cx) && cols.containsKey(ChunkPos.asLong(cx, cz));
    }

    /** Блоки куба пришли (вне мира — «пришли»: там пусто). */
    private boolean got(Col c, int sy) {
        int i = sy - minSec;
        return i < 0 || i >= c.secs.length || c.got.get(i);
    }

    private LevelChunkSection section(Col c, int sy) {
        int i = sy - minSec;
        if (i < 0 || i >= c.secs.length) return null;
        return c.secs[i];
    }

    // ───────────── верхи колонок ─────────────

    private void recomputeTops() {
        var it = topsDirty.iterator();
        while (it.hasNext()) {
            long k = it.nextLong();
            it.remove();
            Col c = cols.get(k);
            if (c == null) continue;
            int[] now = columnTops(c);
            boolean ch = false;
            for (int cell = 0; cell < 256; cell++) {
                if (now[cell] == c.top[cell]) continue;
                markBetween(c, c.top[cell], now[cell], false);
                c.top[cell] = now[cell];
                ch = true;
            }
            if (ch || !c.sent) { minMax(c); topsOut.add(k); c.sent = true; }
        }
    }

    private static void minMax(Col c) {
        int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
        for (int t : c.top) { mn = Math.min(mn, t); mx = Math.max(mx, t); }
        c.minT = mn;
        c.maxT = mx;
    }

    /** Одна клетка (правка): пересчитать T, сменился — пометить кубы между старым и новым. */
    private void cellTop(Col c, int cell, boolean edit) {
        int t = cellTopValue(c, cell);
        if (t == c.top[cell]) return;
        markBetween(c, c.top[cell], t, edit);
        c.top[cell] = t;
        minMax(c);
        topsOut.add(ChunkPos.asLong(c.cx, c.cz));
    }

    /** Кубы столбца, где у клеток сменилось «источник неба / нет» (Y в (min, max]). */
    private void markBetween(Col c, int a, int b, boolean edit) {
        if (lo > hi) return;
        int y0 = Math.min(a, b) + 1, y1 = Math.max(a, b);
        int s0 = Math.max(lo, Math.floorDiv(Math.max(y0, -(1 << 20)), 16)), s1 = Math.min(hi, Math.floorDiv(Math.min(y1, 1 << 20), 16));
        for (int sy = s0; sy <= s1; sy++) {
            long key = SectionPos.asLong(c.cx, sy, c.cz);
            if (edit) { editDirty.add(key); dirty.remove(key); }
            else if (!editDirty.contains(key)) dirty.add(key);
        }
    }

    private int cellTopValue(Col c, int cell) {
        int s = c.srv[cell];
        if (lo > hi) return s;
        int hiY = (hi << 4) + 15, loY = lo << 4;
        if (s > hiY) return s;
        int x = cell & 15, z = cell >> 4;
        for (int sy = hi; sy >= lo; sy--) {
            // куб ещё не пришёл (у клиента воздух скелета): верх сервера в нём — ему и верим (без вспышки света)
            if (!got(c, sy) && s >= sy << 4) return Math.min(s, (sy << 4) + 15);
            LevelChunkSection sec = section(c, sy);
            if (sec == null || sec.hasOnlyAir()) continue;
            for (int ly = 15; ly >= 0; ly--) {
                int inf = LightInfo.info(sec.getBlockState(x, ly, z));
                if ((inf & LightInfo.SKY_SELF) != 0) return (sy << 4) + ly;
                if ((inf & LightInfo.SKY_BELOW) != 0) return (sy << 4) + ly - 1;
            }
        }
        return s < loY ? s : loY - 1;
    }

    private static final java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> SKY_STOP =
            st -> (LightInfo.info(st) & (LightInfo.SKY_SELF | LightInfo.SKY_BELOW)) != 0;

    private int[] columnTops(Col c) {
        int[] r = new int[256];
        if (lo > hi) { System.arraycopy(c.srv, 0, r, 0, 256); return r; }
        int hiY = (hi << 4) + 15, loY = lo << 4;
        boolean[] done = new boolean[256];
        int left = 256;
        for (int cell = 0; cell < 256; cell++) if (c.srv[cell] > hiY) { r[cell] = c.srv[cell]; done[cell] = true; left--; }
        for (int sy = hi; sy >= lo && left > 0; sy--) {
            if (!got(c, sy)) {                                     // куб не пришёл — верх сервера в нём (см. cellTopValue)
                int b = sy << 4;
                for (int cell = 0; cell < 256; cell++) {
                    if (done[cell] || c.srv[cell] < b) continue;
                    r[cell] = Math.min(c.srv[cell], b + 15);
                    done[cell] = true;
                    left--;
                }
            }
            LevelChunkSection sec = section(c, sy);
            if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(SKY_STOP)) continue;
            for (int cell = 0; cell < 256; cell++) {
                if (done[cell]) continue;
                int x = cell & 15, z = cell >> 4;
                for (int ly = 15; ly >= 0; ly--) {
                    int inf = LightInfo.info(sec.getBlockState(x, ly, z));
                    int t;
                    if ((inf & LightInfo.SKY_SELF) != 0) t = (sy << 4) + ly;
                    else if ((inf & LightInfo.SKY_BELOW) != 0) t = (sy << 4) + ly - 1;
                    else continue;
                    r[cell] = t;
                    done[cell] = true;
                    left--;
                    break;
                }
            }
        }
        for (int cell = 0; cell < 256; cell++) if (!done[cell]) { int s = c.srv[cell]; r[cell] = s < loY ? s : loY - 1; }
        return r;
    }

    /** T в мировой клетке; незагруженный столбец — неба нет. */
    private int topAt(int wx, int wz) {
        Col c = col(wx >> 4, wz >> 4);
        return c == null ? NO_SKY : c.top[(wz & 15) << 4 | (wx & 15)];
    }

    private Col lastCol;

    private Col col(int cx, int cz) {
        Col c = lastCol;
        if (c != null && c.cx == cx && c.cz == cz && cols.get(ChunkPos.asLong(cx, cz)) == c) return c;
        c = cols.get(ChunkPos.asLong(cx, cz));
        if (c != null) lastCol = c;
        return c;
    }

    // ───────────── сведения о блоках ─────────────

    /** Сведения о блоках известного куба; null — значимых для света блоков нет. */
    private short[] cubeInf(Col c, int cx, int sy, int cz) {
        long k = SectionPos.asLong(cx, sy, cz);
        short[] a = infCache.getAndMoveToLast(k);
        if (a != null) return a == NO_BLOCKS ? null : a;
        LevelChunkSection sec = section(c, sy);
        if (sec == null || sec.hasOnlyAir()) a = NO_BLOCKS;
        else {
            a = new short[4096];
            boolean any = false;
            net.minecraft.world.level.block.state.BlockState last = null;
            short v = 0;
            for (int q = 0; q < 4096; q++) {
                var st = sec.getBlockState(q & 15, q >> 8, (q >> 4) & 15);
                if (st != last) { last = st; v = LightInfo.light(st); }
                a[q] = v;
                if (v != 0) any = true;
            }
            if (!any) a = NO_BLOCKS;
        }
        infCache.putAndMoveToLast(k, a);
        if (infCache.size() > INF_CACHE) infCache.removeFirst();
        return a == NO_BLOCKS ? null : a;
    }

    private boolean mayEmit(Col c, int sy) {
        byte b = c.emit.get(sy);
        if (b == 0) {
            LevelChunkSection sec = section(c, sy);
            boolean yes = sec != null && !sec.hasOnlyAir() && sec.maybeHas(st -> st.getLightEmission() > 0);
            b = (byte) (yes ? 2 : 1);
            c.emit.put(sy, b);
        }
        return b == 2;
    }

    // ───────────── пересчёт ─────────────

    private static final int[] IN_DELTA = {1, -1, 16, -16, -256, 256};
    private static final int[] WRAP = {-15, 15, -240, 240, 3840, -3840};
    private static final int[][] DIR = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}, {0, 1, 0}};
    private static final byte[] EDGE = new byte[4096];
    static {
        for (int q = 0; q < 4096; q++) {
            int x = q & 15, z = (q >> 4) & 15, y = q >> 8, e = 0;
            if (x == 15) e |= 1; if (x == 0) e |= 2; if (z == 15) e |= 4; if (z == 0) e |= 8; if (y == 0) e |= 16; if (y == 15) e |= 32;
            EDGE[q] = (byte) e;
        }
    }

    /** Клетка грани d (0 +x, 1 −x, 2 +z, 3 −z, 4 −y, 5 +y), a/b — координаты на грани (одни для d и d^1). */
    static int face(int d, int a, int b) {
        int f = (d == 0 || d == 2 || d == 5) ? 15 : 0;
        return switch (d) {
            case 0, 1 -> a << 8 | b << 4 | f;
            case 2, 3 -> a << 8 | f << 4 | b;
            default -> f << 8 | a << 4 | b;
        };
    }

    private static final class Buckets {
        final int[][] a = new int[16][1024];
        final int[] n = new int[16];
        void add(int l, int g) {
            int[] x = a[l];
            if (n[l] == x.length) a[l] = x = Arrays.copyOf(x, x.length * 2);
            x[n[l]++] = g;
        }
        void clear() { Arrays.fill(n, 0); }
    }

    private short[] wInf = new short[0];
    private byte[] wSky = new byte[0], wBlk = new byte[0];
    private int[] wNb = new int[0];
    private final Buckets qs = new Buckets(), qb = new Buckets();
    private final byte[] fs = new byte[256], fb = new byte[256];
    /** Область текущего пересчёта (кубы в ней без заливки — по F: их новый свет ещё не опубликован). */
    private LongOpenHashSet curR = new LongOpenHashSet();
    private final short[] fi = new short[256];

    /** Взять плитку грязных кубов из set (4×8×4 вокруг первого) и пересчитать. */
    private void batch(LongLinkedOpenHashSet set, boolean edit) {
        long seed = set.firstLong();
        int tx = SectionPos.x(seed) >> 2, ty = SectionPos.y(seed) >> 3, tz = SectionPos.z(seed) >> 2;
        LongArrayList d = new LongArrayList();
        for (int dx = 0; dx < 4; dx++) for (int dy = 0; dy < 8; dy++) for (int dz = 0; dz < 4; dz++) {
            long k = SectionPos.asLong((tx << 2) + dx, (ty << 3) + dy, (tz << 2) + dz);
            if (set.remove(k)) d.add(k);
        }
        if (d.isEmpty()) { set.remove(seed); d.add(seed); }
        long t0 = System.nanoTime();
        LongArrayList pos = new LongArrayList();
        List<ServerLight.Cube> cubes = new ArrayList<>();
        try {
            compute(d, pos, cubes);
        } catch (RuntimeException ex) {                                     // палитру меняли в этот момент — ещё раз
            failures++;
            if (failures < 5) HokaOdysseyClient.LOG.warn("свет на клиенте: повтор пересчёта ({})", ex.toString());
            if (failures < 10_000) for (int i = 0; i < d.size(); i++) set.add(d.getLong(i));
            return;
        }
        long dt = System.nanoTime() - t0;
        nanos += dt;
        if (dt > maxNanos) maxNanos = dt;
        batches++;
        emit(pos, cubes, d.toLongArray(), edit);
    }

    private void emit(LongArrayList pos, List<ServerLight.Cube> cubes, long[] done, boolean edit) {
        for (long[] r : removed) { pos.add(r[0]); cubes.add(null); }
        removed.clear();
        long[] tk = null;
        int[][] tv = null;
        if (!topsOut.isEmpty()) {
            tk = new long[topsOut.size()];
            tv = new int[tk.length][];
            int i = 0;
            for (var it = topsOut.iterator(); it.hasNext(); ) {
                long k = it.nextLong();
                Col c = cols.get(k);
                tk[i] = k;
                tv[i++] = c == null ? null : c.top.clone();
            }
            topsOut.clear();
        }
        if (pos.isEmpty() && tk == null && done.length == 0) return;
        published += pos.size();
        out.add(new Out(pos.toLongArray(), cubes.toArray(new ServerLight.Cube[0]), done, tk, tv, edit));
        if (edit) editOuts.incrementAndGet();
    }

    private void compute(LongArrayList d, LongArrayList pos, List<ServerLight.Cube> cubes) {
        // область R = D + соседи, только известные кубы
        LongOpenHashSet rs = new LongOpenHashSet();
        LongArrayList r = new LongArrayList();
        for (int i = 0; i < d.size(); i++) {
            long k = d.getLong(i);
            int cx = SectionPos.x(k), cy = SectionPos.y(k), cz = SectionPos.z(k);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                long n = SectionPos.asLong(cx + dx, cy + dy, cz + dz);
                if (!rs.contains(n) && known(cx + dx, cy + dy, cz + dz)) { rs.add(n); r.add(n); }
            }
        }
        if (r.isEmpty()) return;
        curR = rs;
        // вид кубов: 1 — небо 15 и блоки 0, 2 — всё 0, 0 — заливка
        int n = r.size();
        byte[] kind = new byte[n];
        Long2IntOpenHashMap slot = new Long2IntOpenHashMap();
        slot.defaultReturnValue(-1);
        int m = 0;
        for (int j = 0; j < n; j++) {
            long k = r.getLong(j);
            int cx = SectionPos.x(k), cy = SectionPos.y(k), cz = SectionPos.z(k);
            kind[j] = trivial(cx, cy, cz);
            if (kind[j] == 0) slot.put(k, m++);
        }
        cubesTrivial += n - m;
        cubesBfs += m;
        if (m > 0) fill(r, kind, slot, m);
        // в выдачу — изменившиеся
        int[] fy = new int[256];
        for (int j = 0; j < n; j++) {
            long k = r.getLong(j);
            int cx = SectionPos.x(k), cy = SectionPos.y(k), cz = SectionPos.z(k);
            Col c = col(cx, cz);
            ServerLight.Cube nc;
            if (kind[j] == 1) nc = SKY15;
            else if (kind[j] == 2) nc = DARK;
            else nc = result(c, cy, slot.get(k) << 12, fy);
            ServerLight.Cube old = c.pub.get(cy);
            if (old != null && (old == nc || old.same(nc))) continue;
            c.pub.put(cy, nc);
            pos.add(k);
            cubes.add(nc);
        }
    }

    /** 1 — весь куб выше T и рядом нет свечения; 2 — источников неба и света в 3×3×3 нет; 0 — считать. */
    private byte trivial(int cx, int cy, int cz) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Col c = col(cx + dx, cz + dz);
            if (c == null) continue;
            for (int dy = -1; dy <= 1; dy++) if (known(cx + dx, cy + dy, cz + dz) && mayEmit(c, cy + dy)) return 0;
        }
        if (!sky) return 2;
        int y0 = cy << 4;
        Col own = col(cx, cz);
        if (y0 > own.maxT) return 1;
        int mn = Integer.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Col c = col(cx + dx, cz + dz);
            if (c != null) mn = Math.min(mn, c.minT);
        }
        return mn >= y0 + 31 ? (byte) 2 : 0;
    }

    /** Заливка кубов kind 0 (слоты 0…m−1) с рамкой из соседей вне заливки. */
    private void fill(LongArrayList r, byte[] kind, Long2IntOpenHashMap slot, int m) {
        int cells = m << 12;
        if (wSky.length < cells) { wSky = new byte[cells]; wBlk = new byte[cells]; wInf = new short[cells]; }
        if (wNb.length < m * 6) wNb = new int[m * 6];
        Arrays.fill(wSky, 0, cells, (byte) 0);
        Arrays.fill(wBlk, 0, cells, (byte) 0);
        byte[] sl = wSky, bl = wBlk;
        short[] inf = wInf;
        int[] nb = wNb;
        qs.clear();
        qb.clear();
        for (int j = 0; j < r.size(); j++) {
            if (kind[j] != 0) continue;
            long k = r.getLong(j);
            int s = slot.get(k), base = s << 12;
            int cx = SectionPos.x(k), cy = SectionPos.y(k), cz = SectionPos.z(k);
            Col c = col(cx, cz);
            short[] a = cubeInf(c, cx, cy, cz);
            if (a == null) Arrays.fill(inf, base, base + 4096, (short) 0);
            else System.arraycopy(a, 0, inf, base, 4096);
            for (int dd = 0; dd < 6; dd++) nb[s * 6 + dd] = slot.get(SectionPos.asLong(cx + DIR[dd][0], cy + DIR[dd][1], cz + DIR[dd][2]));
            int y0 = cy << 4;
            if (sky) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {   // источники неба
                int wx = (cx << 4) + x, wz = (cz << 4) + z;
                int t = c.top[z << 4 | x];
                int ntop = Math.max(Math.max(topAt(wx - 1, wz), topAt(wx + 1, wz)), Math.max(topAt(wx, wz - 1), topAt(wx, wz + 1)));
                for (int ly = Math.max(0, Math.min(16, t + 1 - y0)); ly < 16; ly++) {
                    int g = base | ly << 8 | z << 4 | x;
                    sl[g] = 15;
                    int y = y0 + ly;
                    if (y - 1 <= t || ntop >= y) qs.add(15, g);               // внутри «моря» 15 растекаться некуда
                }
            }
            if (a != null) for (int q = 0; q < 4096; q++) {                    // источники блоков
                int e = (a[q] >> 10) & 15;
                if (e == 0) continue;
                bl[base + q] = (byte) e;
                if (e >= 2) qb.add(e, base + q);
            }
        }
        for (int j = 0; j < r.size(); j++) {                                   // рамка
            if (kind[j] != 0) continue;
            long k = r.getLong(j);
            int s = slot.get(k), base = s << 12;
            int cx = SectionPos.x(k), cy = SectionPos.y(k), cz = SectionPos.z(k);
            for (int d = 0; d < 6; d++) {
                if (nb[s * 6 + d] >= 0) continue;
                frame(cx + DIR[d][0], cy + DIR[d][1], cz + DIR[d][2], d);
                for (int a = 0; a < 16; a++) for (int b = 0; b < 16; b++) {
                    int f = a << 4 | b, g = base + face(d, a, b);
                    int qi = inf[g], lb = qi & 15;
                    if (lb >= 15) continue;
                    if (((fi[f] >> (4 + (d ^ 1))) & 1) != 0 || ((qi >> (4 + d)) & 1) != 0) continue;
                    int cost = Math.max(1, lb);
                    if (sky) {
                        int v = fs[f] - cost;
                        if (v > sl[g]) { sl[g] = (byte) v; if (v >= 2) qs.add(v, g); }
                    }
                    int v = fb[f] - cost;
                    if (v > bl[g]) { bl[g] = (byte) v; if (v >= 2) qb.add(v, g); }
                }
            }
        }
        if (sky) bfs(sl, inf, nb, qs);
        bfs(bl, inf, nb, qb);
    }

    /** Растекание по корзинам уровней 15…2 (как у сервера). */
    private static void bfs(byte[] l, short[] inf, int[] nb, Buckets q) {
        for (int lvl = 15; lvl >= 2; lvl--) {
            int[] b = q.a[lvl];
            for (int j = 0, m = q.n[lvl]; j < m; j++) {
                int g = b[j];
                if (l[g] != lvl) continue;
                int idx = g & 4095, fi = inf[g], edge = EDGE[idx];
                for (int d = 0; d < 6; d++) {
                    int n;
                    if ((edge >> d & 1) == 0) n = g + IN_DELTA[d];
                    else {
                        int ns = nb[(g >>> 12) * 6 + d];
                        if (ns < 0) continue;
                        n = ns << 12 | (idx + WRAP[d]);
                    }
                    int ni = inf[n], lb = ni & 15;
                    if (lb >= 15) continue;
                    if (((fi >> (4 + d)) & 1) != 0 || ((ni >> (4 + (d ^ 1))) & 1) != 0) continue;
                    int nl = lvl - Math.max(1, lb);
                    if (nl <= l[n]) continue;
                    l[n] = (byte) nl;
                    if (nl >= 2) q.add(nl, n);
                }
            }
            q.n[lvl] = 0;
        }
    }

    /** Грань соседа (ncx, ncy, ncz), обращённая к нам (мы смотрим в сторону d), → fs/fb/fi. */
    private void frame(int ncx, int ncy, int ncz, int d) {
        Col nc = col(ncx, ncz);
        boolean kn = nc != null && known(ncx, ncy, ncz);
        short[] ci = kn ? cubeInf(nc, ncx, ncy, ncz) : null;
        ServerLight.Cube p = kn && !curR.contains(SectionPos.asLong(ncx, ncy, ncz)) ? nc.pub.get(ncy) : null;
        int y0 = ncy << 4;
        for (int a = 0; a < 16; a++) for (int b = 0; b < 16; b++) {
            int f = a << 4 | b, o = face(d ^ 1, a, b);
            int ox = o & 15, oz = (o >> 4) & 15, oy = o >> 8;
            int sv, bv;
            if (nc == null) { sv = 0; bv = 0; }
            else if (p != null) { sv = sky ? p.sky(ox, oy, oz) : 0; bv = Math.max(0, p.blk(ox, oy, oz)); }
            else { sv = sky && y0 + oy > nc.top[oz << 4 | ox] ? 15 : 0; bv = 0; }
            fs[f] = (byte) sv;
            fb[f] = (byte) bv;
            fi[f] = ci != null ? ci[o] : 0;
        }
    }

    /** Свет посчитанного куба → ServerLight.Cube (формула F, если совпал; иначе массивы). */
    private ServerLight.Cube result(Col c, int cy, int base, int[] fy) {
        int y0 = cy << 4;
        byte sm;
        byte[] sd = null;
        if (!sky) sm = 1;
        else {
            boolean f = true, all15 = true, all0 = true;
            for (int col = 0; col < 256; col++) {
                fy[col] = Math.max(0, Math.min(16, c.top[col] + 1 - y0));
                if (fy[col] != 0) all15 = false;
                if (fy[col] != 16) all0 = false;
            }
            for (int q = 0; q < 4096 && f; q++) if (wSky[base + q] != ((q >> 8) >= fy[q & 255] ? 15 : 0)) f = false;
            if (f) {
                if (all15) sm = 2;
                else if (all0) sm = 1;
                else {
                    sm = 0;
                    sd = new byte[256];
                    for (int col = 0; col < 256; col++) sd[col] = (byte) fy[col];
                }
            } else {
                sm = 3;
                sd = pack(wSky, base);
            }
        }
        byte bm = 1;
        byte[] bd = null;
        for (int q = 0; q < 4096; q++) if (wBlk[base + q] != 0) { bm = 3; bd = pack(wBlk, base); break; }
        if (sm == 2 && bm == 1) return SKY15;
        if (sm == 1 && bm == 1) return DARK;
        return new ServerLight.Cube(sm, sd, bm, bd);
    }

    private static byte[] pack(byte[] l, int base) {
        byte[] a = new byte[2048];
        for (int q = 0; q < 4096; q += 2) a[q >> 1] = (byte) ((l[base + q] & 15) | (l[base + q + 1] & 15) << 4);
        return a;
    }

    String stats() {
        int pubs = 0, arrays = 0;
        try {
            for (Col c : cols.values()) {
                pubs += c.pub.size();
                for (ServerLight.Cube q : c.pub.values()) if (q.sky() == 3 || q.blk() == 3) arrays++;
            }
        } catch (RuntimeException ignored) {                                    // читаем без замка — для отладки годится
        }
        return "свет на клиенте: пересчётов " + batches + ", кубов заливкой " + cubesBfs + ", без заливки " + cubesTrivial
                + ", выдано " + published + (batches > 0 ? String.format(", в среднем %.2f мс, максимум %.2f мс", nanos / 1e6 / batches, maxNanos / 1e6) : "")
                + ", кубов с записью " + pubs + " (массивов " + arrays + "), событий " + events + (failures > 0 ? ", сбоев " + failures : "");
    }
}
