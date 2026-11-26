package ink.hoka.odyssey.client;

import ink.hoka.odyssey.gen.GenArrayCube;
import ink.hoka.odyssey.gen.GenBounds;
import ink.hoka.odyssey.gen.PlanetGenerator;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 0.59: пул генерации нетронутых кубов на клиенте (op 21). Задачи — по кольцам от куба игрока ({@link #ring}: 0.63 —
 * по горизонтали, высота сжата втрое, позади взгляда дальше), в кольце — сначала впереди по взгляду; повернулся —
 * кольца пересобираются (не чаще 4 раз в секунду). 0.63: куб сначала ищется в дисковом кэше (GenDiskCache). Рабочие потоки (низкий приоритет) генерируют в свои массивы,
 * считают GenHash и собирают секцию; готовое — в RESULTS (не больше 256, обратное давление), вставляет главный поток
 * по бюджету кадра (CubeQueue.run → apply). Отмена: op 2/4/13 по кубу, новый скелет/Forget столбца, смена мира/слота.
 * Состояние задач (PENDING, BY_COL) — только главный поток; кольца — под LOCK.
 */
public final class GenPool {

    private GenPool() {}

    static final int RINGS = 128, MAX_RESULTS = 512;

    /** Текущий генератор: всё, что нужно рабочему потоку (неизменяемо). */
    record Job(int slot, PlanetGenerator gen, GenBounds bounds, long seed, Holder<Biome>[] biomes) {}

    static final class Task {
        final long pos;
        final Job job;
        final ClientLevel level;
        final PalettedContainerFactory f;
        final long expect;                       // хэш от сервера (0 — нет)
        final int uni, uniBiome;                 // однородный куб (op 21): состояние (-1 — генерировать) и биом
        volatile boolean cancelled;
        Task(long pos, Job job, ClientLevel level, PalettedContainerFactory f, long expect, int uni, int uniBiome) {
            this.pos = pos; this.job = job; this.level = level; this.f = f; this.expect = expect; this.uni = uni; this.uniBiome = uniBiome;
        }
    }

    static final class Result {
        final Task t;
        boolean near;                            // 0.63.2: взята из ближних колец — вставка вне очереди
        LevelChunkSection sec;
        long hash;
        int[] bes;                               // индексы y<<8|z<<4|x состояний с блок-сущностью (null — нет)
        Throwable err;
        long ns;
        boolean generated, disk;                 // 0.63: прошёл generate (писать на диск) / взят с диска
        Result(Task t) { this.t = t; }
    }

    private static final Object LOCK = new Object();
    @SuppressWarnings("unchecked")
    private static final ArrayDeque<Task>[] RING = new ArrayDeque[RINGS];
    /**
     * 0.63.4: кольца прошлой раскладки — перекладываются в RING порциями по бюджету кадра (restale), а не все разом
     * (повернулся при сотнях тысяч задач полосы — рывок главного потока, и рабочие ждали LOCK). Рабочие берут отсюда,
     * когда RING пуст. Под LOCK; staleAt — первое, возможно, непустое (RINGS — пусто всё).
     */
    @SuppressWarnings("unchecked")
    private static final ArrayDeque<Task>[] STALE = new ArrayDeque[RINGS];
    static { for (int i = 0; i < RINGS; i++) { RING[i] = new ArrayDeque<>(); STALE[i] = new ArrayDeque<>(); } }
    private static int staleAt = RINGS;
    /** Бюджет перекладки за кадр (нс). */
    static final long RESTALE_NS = 500_000L;
    private static int queued;                                  // под LOCK (RING и STALE)
    private static volatile Job job;
    private static final List<Thread> THREADS = new ArrayList<>();
    private static volatile int active;
    private static final ArrayBlockingQueue<Result> RESULTS = new ArrayBlockingQueue<>(MAX_RESULTS);
    /** 0.63.2: готовые ближние кубы (кольца 0..NEAR_RING) — без обратного давления, вставляются первыми. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Result> NEAR = new java.util.concurrent.ConcurrentLinkedQueue<>();
    static final int NEAR_RING = 3;
    /** 0.63.2: кольца полосы поверхности (вне окна ±V) — после всех колец окна. */
    static final int BAND_RING = 48;

    // главный поток
    private static final Long2ObjectOpenHashMap<Task> PENDING = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<ObjectArrayList<Task>> BY_COL = new Long2ObjectOpenHashMap<>();
    private static long playerSec = Long.MIN_VALUE;
    private static ClientLevel lastLevel;
    private static boolean wake;
    private static final GenArrayCube MAIN = new GenArrayCube(false);

    public static volatile long generated, genNs, errors, forced, dropped, fromDisk, insertNs, inserted;
    /** 0.63: дисковый кэш текущего генератора (null — выключен). */
    private static volatile GenDiskCache disk;

    static void disk(GenDiskCache d) { disk = d; }

    static Job job() { return job; }

    /** Число потоков по настройке (0 — выкл). */
    static int wanted() { return GenSetting.threads(); }

    /** Включить генератор (главный поток). */
    static void start(Job j) {
        cancelAll();
        job = j;
        int n = wanted();
        ensureThreads(n);
        active = n;
        synchronized (LOCK) { LOCK.notifyAll(); }
    }

    private static void ensureThreads(int n) {
        synchronized (THREADS) {
            while (THREADS.size() < n) {
                int idx = THREADS.size();
                Thread t = new Thread(() -> work(idx), "hoka-gen-" + idx);
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                THREADS.add(t);
                t.start();
            }
        }
    }

    /** Выключить (сброс слота, выход, настройка «Выкл»). */
    static void stop() {
        job = null;
        cancelAll();
    }

    static int threads() { return job == null ? 0 : active; }

    // ───────────── задачи (главный поток) ─────────────

    static boolean pending(long sec) { return !PENDING.isEmpty() && PENDING.containsKey(sec); }

    public static boolean waiting(int x, int y, int z) {
        return !PENDING.isEmpty() && Minecraft.getInstance().isSameThread() && PENDING.containsKey(SectionPos.asLong(x, y, z));
    }

    public static int pendingCount() { return PENDING.size(); }

    static void submit(ClientLevel level, long pos, long expect, int uni, int uniBiome) {
        Job j = job;
        if (j == null) return;
        cancel(pos);
        Task t = new Task(pos, j, level, level.palettedContainerFactory(), expect, uni, uniBiome);
        PENDING.put(pos, t);
        long col = ChunkPos.asLong(SectionPos.x(pos), SectionPos.z(pos));
        ObjectArrayList<Task> l = BY_COL.get(col);
        if (l == null) BY_COL.put(col, l = new ObjectArrayList<>(4));
        l.add(t);
        synchronized (LOCK) { enqueue(t); }                        // кольцо — от куба игрока на начало кадра (frame)
        wake = true;
    }

    /** Отменить задачу куба (op 2/4/13 по нему). */
    static void cancel(long pos) {
        if (PENDING.isEmpty()) return;
        Task t = PENDING.remove(pos);
        if (t == null) return;
        t.cancelled = true;
        long col = ChunkPos.asLong(SectionPos.x(pos), SectionPos.z(pos));
        ObjectArrayList<Task> l = BY_COL.get(col);
        if (l != null) { l.remove(t); if (l.isEmpty()) BY_COL.remove(col); }
    }

    /** Новый скелет / Forget столбца: его задачи больше не нужны. */
    public static void columnChanged(int x, int z) {
        if (BY_COL.isEmpty()) return;
        ObjectArrayList<Task> l = BY_COL.remove(ChunkPos.asLong(x, z));
        if (l == null) return;
        for (Task t : l) { t.cancelled = true; if (PENDING.get(t.pos) == t) PENDING.remove(t.pos); }
    }

    static void cancelAll() {
        for (Task t : PENDING.values()) t.cancelled = true;
        PENDING.clear();
        BY_COL.clear();
        synchronized (LOCK) { for (ArrayDeque<Task> r : RING) r.clear(); for (ArrayDeque<Task> r : STALE) r.clear(); staleAt = RINGS; queued = 0; }
        RESULTS.clear();
        NEAR.clear();
    }

    /**
     * Ванильная правка в куб, ждущий генерации (PacketDeferMixin): сгенерировать его сейчас на главном потоке и
     * вставить, чтобы правка легла поверх.
     */
    public static void forceNow(ClientLevel level, long sec) {
        if (PENDING.isEmpty()) return;
        Task t = PENDING.get(sec);
        if (t == null || t.level != level) return;
        cancel(sec);
        Result r = run(t, MAIN);
        forced++;
        finish(r);
    }

    // ───────────── приоритет ─────────────

    private static int px, py, pz;
    private static double lx, ly, lz;
    /** Взгляд на последней раскладке колец и когда она была. */
    private static double blx, bly, blz;
    private static long lastRebucket;

    /** Куб игрока сменился или игрок заметно повернулся — переложить задачи по кольцам (O(n)). */
    private static void updatePlayer(boolean rebucket) {
        var p = Minecraft.getInstance().player;
        if (p == null) return;
        long s = SectionPos.asLong(p.blockPosition());
        var look = p.getLookAngle();
        lx = look.x; ly = look.y; lz = look.z;
        long now = System.nanoTime();
        boolean turned = lx * blx + ly * bly + lz * blz < 0.7 && now - lastRebucket > 250_000_000L;
        if (s == playerSec && !turned) return;
        playerSec = s;
        px = SectionPos.x(s); py = SectionPos.y(s); pz = SectionPos.z(s);
        blx = lx; bly = ly; blz = lz;
        if (!rebucket) return;
        lastRebucket = now;
        synchronized (LOCK) {                                          // 0.63.4: O(колец), задачи переложит restale
            for (int i = 0; i < RINGS; i++) {
                ArrayDeque<Task> r = RING[i];
                if (r.isEmpty()) continue;
                if (STALE[i].isEmpty()) { RING[i] = STALE[i]; STALE[i] = r; }
                else { STALE[i].addAll(r); r.clear(); }
            }
            staleAt = 0;
        }
    }

    /** 0.63.4: порция перекладки STALE → RING по новой раскладке (ближние кольца прошлой — первыми). */
    private static void restale(long budgetNs) {
        if (staleAt >= RINGS) return;                                  // пишет только главный поток
        long end = System.nanoTime() + budgetNs;
        synchronized (LOCK) {
            int n = 0;
            while (staleAt < RINGS) {
                Task t = STALE[staleAt].pollFirst();
                if (t == null) { staleAt++; continue; }
                queued--;
                if (!t.cancelled) enqueue(t);
                if ((++n & 511) == 0 && System.nanoTime() >= end) break;
            }
        }
    }

    /**
     * 0.63.2: кольцо куба (dx, dy, dz от куба игрока). Куб в ±2 по всем осям — кольцо 0 (абсолютный приоритет, и под
     * землёй тоже). Окно (|dy| ≤ V+1) — по 3D-расстоянию (Чебышёв), позади взгляда на треть дальше (не больше +8).
     * Полоса поверхности (дальше окна по высоте) — после всех колец окна: BAND_RING + max(гориз., сверх окна/3).
     * Было (0.63): гориз. расстояние, высота сжата втрое — кубы окна под землёй ждали за полосой поверхности.
     */
    static int ring(int dx, int dy, int dz, boolean ahead) { return ring(dx, dy, dz, ahead, VerticalView.get()); }

    static int ring(int dx, int dy, int dz, boolean ahead, int v) {
        int adx = Math.abs(dx), ady = Math.abs(dy), adz = Math.abs(dz), h = Math.max(adx, adz);
        if (h <= 2 && ady <= 2) return 0;
        int ring;
        if (ady <= v + 1) {
            ring = Math.max(h, ady);
            if (!ahead) ring += Math.min(8, ring / 3);
            ring = Math.min(BAND_RING - 1, ring);
        } else {
            ring = BAND_RING + Math.max(h, (ady - v + 2) / 3);
        }
        return Math.min(RINGS - 1, ring);
    }

    /** Под LOCK. */
    private static void enqueue(Task t) {
        int dx = SectionPos.x(t.pos) - px, dy = SectionPos.y(t.pos) - py, dz = SectionPos.z(t.pos) - pz;
        boolean ahead = dx * lx + dy * ly + dz * lz >= 0;
        int ring = ring(dx, dy, dz, ahead);
        if (ahead) RING[ring].addFirst(t); else RING[ring].addLast(t);
        queued++;
    }

    // ───────────── рабочие потоки ─────────────

    private static void work(int idx) {
        GenArrayCube buf = new GenArrayCube(false);
        while (true) {
            Task t = null;
            boolean near = false;
            try {
                synchronized (LOCK) {
                    while (job == null || idx >= active || queued == 0) LOCK.wait(250);
                    int ri = 0;
                    for (ArrayDeque<Task> r : RING) { if ((t = r.pollFirst()) != null) break; ri++; }
                    near = ri <= NEAR_RING;
                    if (t == null)                                     // 0.63.4: ещё не переложенные — по прошлой раскладке
                        for (int i = staleAt; i < RINGS && (t = STALE[i].pollFirst()) == null; i++) {}
                    if (t != null) queued--;
                }
                if (t == null || t.cancelled || t.job != job) continue;
                Result res = run(t, buf);
                if (near) { res.near = true; NEAR.add(res); continue; }   // 0.63.2: ближний — мимо очереди готовых
                while (!RESULTS.offer(res, 100, TimeUnit.MILLISECONDS)) if (t.cancelled || t.job != job) break;
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                errors++;
            }
        }
    }

    /**
     * Сгенерировать куб и собрать секцию (любой поток). Путь — общий GenFingerprint.visible (границы → hasBlocks →
     * uniform → generate без секретов), тот же, что у сверки сервера; однородный из op 21 — заливка.
     */
    static Result run(Task t, GenArrayCube c) {
        Result r = new Result(t);
        long t0 = System.nanoTime();
        Job j = t.job;
        int x = SectionPos.x(t.pos), y = SectionPos.y(t.pos), z = SectionPos.z(t.pos);
        try {
            if (t.uni >= 0) { c.reset(x, y, z, j.seed()); c.fill(t.uni); c.fillBiome(t.uniBiome); }
            else visible(j, x, y, z, c, r);
            r.hash = c.visHash();
            if (r.generated) { GenDiskCache d = disk; if (d != null) d.put(x, y, z, c, r.hash); }
            build(r, c, t.f, j);
        } catch (Throwable e) {
            r.err = e;
            r.sec = null;
        }
        r.ns = System.nanoTime() - t0;
        return r;
    }

    /**
     * Как GenFingerprint.visible (границы → hasBlocks → uniform → generate), но 0.63: до подсказок генератора — дисковый
     * кэш (в нём только кубы, прошедшие generate; подсказки на холодных кэшах рельефа — главная цена куба).
     */
    private static void visible(Job j, int x, int y, int z, GenArrayCube c, Result r) {
        c.reset(x, y, z, j.seed());
        if (!j.bounds().cubeInside(x, y, z)) return;
        GenDiskCache d = disk;
        if (d != null && d.get(x, y, z, c)) { r.disk = true; fromDisk++; return; }
        if (!j.gen().hasBlocks(x, y, z)) return;
        int u = j.gen().uniform(x, y, z);
        if (u >= 0) { c.fill(u); return; }
        j.gen().generate(c);
        r.generated = true;
    }

    /** Биом по номеру генератора (нет — первый / по умолчанию фабрики). */
    static Holder<Biome> biome(Job j, PalettedContainerFactory f, int id) {
        Holder<Biome>[] hs = j.biomes();
        Holder<Biome> h = id >= 0 && id < hs.length ? hs[id] : null;
        if (h == null) h = hs.length > 0 ? hs[0] : f.defaultBiome();
        return h;
    }

    /**
     * 0.61: однородный куб (метка op 21 / весь куб одним блоком) — палитра из одного значения: без 4096 записей и без
     * 2 КБ массива на куб (толща камня — сотни тысяч кубов). Воздух — общая секция SparseSections. null — неизвестное
     * состояние.
     */
    static LevelChunkSection uniform(Job j, PalettedContainerFactory f, int state, int biome) {
        BlockState s = Block.BLOCK_STATE_REGISTRY.byId(state);
        if (s == null) return null;
        Holder<Biome> h = biome(j, f, biome);
        if (s.isAir()) return SparseSections.sharedAir(f, h);
        return new LevelChunkSection(new PalettedContainer<>(s, f.blockStatesStrategy()), new PalettedContainer<>(h, f.biomeStrategy()));
    }

    private static void build(Result r, GenArrayCube c, PalettedContainerFactory f, Job j) {
        int[] b0 = c.blocks;
        int u = b0[0];
        boolean one = true;
        for (int i = 1; i < 4096 && one; i++) one = b0[i] == u;
        if (one) {                                                       // 0.61: весь куб одним блоком — одно значение
            BlockState us = Block.BLOCK_STATE_REGISTRY.byId(u);
            if (us == null) throw new IllegalStateException("неизвестное состояние " + u);
            if (!us.hasBlockEntity()) {
                PalettedContainer<BlockState> st1 = us.isAir() ? f.createForBlockStates() : new PalettedContainer<>(us, f.blockStatesStrategy());
                r.sec = new LevelChunkSection(st1, biomes(c, f, j));
                r.bes = null;
                return;
            }
        }
        PalettedContainer<BlockState> st = f.createForBlockStates();
        int last = Integer.MIN_VALUE, air = 0;
        BlockState ls = null;
        int[] bes = null;
        int nb = 0;
        int[] b = c.blocks;
        for (int i = 0; i < 4096; i++) {
            int id = b[i];
            if (id == air) continue;
            if (id != last) {
                last = id;
                ls = Block.BLOCK_STATE_REGISTRY.byId(id);
                if (ls == null) throw new IllegalStateException("неизвестное состояние " + id);
            }
            st.getAndSetUnchecked(i & 15, i >> 8, (i >> 4) & 15, ls);
            if (ls.hasBlockEntity()) {
                if (bes == null) bes = new int[16];
                else if (nb == bes.length) bes = Arrays.copyOf(bes, nb * 2);
                bes[nb++] = i;
            }
        }
        r.sec = new LevelChunkSection(st, biomes(c, f, j));
        r.bes = bes == null ? null : Arrays.copyOf(bes, nb);
    }

    private static PalettedContainer<Holder<Biome>> biomes(GenArrayCube c, PalettedContainerFactory f, Job j) {
        int b = c.biomes[0];
        boolean one = true;
        for (int i = 1; i < 64 && one; i++) one = c.biomes[i] == b;
        Holder<Biome>[] hs = j.biomes();
        if (one) {                                                       // 0.61: один биом — одно значение
            Holder<Biome> h = b >= 0 && b < hs.length ? hs[b] : null;
            if (h == null) h = hs.length > 0 ? hs[0] : null;
            if (h != null) return new PalettedContainer<>(h, f.biomeStrategy());
        }
        PalettedContainer<Holder<Biome>> bi = f.createForBiomes();
        for (int i = 0; i < 64; i++) {
            int id = c.biomes[i];
            Holder<Biome> h = id >= 0 && id < hs.length ? hs[id] : null;
            if (h == null) h = hs.length > 0 ? hs[0] : null;
            if (h != null) bi.getAndSetUnchecked(i & 3, i >> 4, (i >> 2) & 3, h);
        }
        return bi;
    }

    // ───────────── вставка (главный поток) ─────────────

    /** Кадр: прежде всего — смена мира, взгляд/кольца, разбудить потоки, адаптация к долгим кадрам. */
    static void frame() {
        ClientLevel lvl = Minecraft.getInstance().level;
        if (lvl != lastLevel) { lastLevel = lvl; cancelAll(); }
        if (job == null) return;
        updatePlayer(!PENDING.isEmpty());
        restale(RESTALE_NS);
        int n = wanted();
        ensureThreads(n);
        long avg = FrameWatch.avgFrameNs();
        boolean near;                                                  // ближние кубы ждут (телепорт, вход) — все потоки:
        synchronized (LOCK) { near = !RING[0].isEmpty() || !RING[3].isEmpty(); }   // кадры долгие как раз от прогрузки
        int a = near ? n : avg > 50_000_000L ? 1 : avg > 33_000_000L ? Math.max(1, n / 2) : n;   // долгие кадры — меньше потоков
        synchronized (THREADS) { a = Math.min(a, THREADS.size()); }
        if (a != active) { active = a; wake = true; }
        if (wake) { wake = false; synchronized (LOCK) { LOCK.notifyAll(); } }
    }

    /** Вставить готовые кубы до end (хотя бы один). */
    static void apply(long end) {
        applyNear();
        if (RESULTS.isEmpty()) return;
        // свой бюджет (как у op 2): обычно доля кадра, потоки упёрлись в RESULTS — больше (они простаивают)
        int n = RESULTS.size();
        long b = n >= MAX_RESULTS ? (Minecraft.getInstance().isWindowActive() ? 4_000_000L : 20_000_000L)
                : n > MAX_RESULTS / 2 ? 2_000_000L : FrameWatch.budgetNs(0.25, 300_000L, 1_500_000L);
        long until = Math.max(end, System.nanoTime() + b);
        Result r;
        boolean first = true;
        while ((first || System.nanoTime() < until) && (r = RESULTS.poll()) != null) {
            first = false;
            Task t = r.t;
            if (t.cancelled || PENDING.get(t.pos) != t) { dropped++; continue; }
            cancel(t.pos);
            finish(r);
        }
    }

    /** 0.63.2: ближние готовые кубы — все сразу, вне бюджета (их мало: кольца 0..1). */
    private static void applyNear() {
        Result r;
        int n = 0;
        while (n < 256 && (r = NEAR.poll()) != null) {
            n++;
            Task t = r.t;
            if (t.cancelled || PENDING.get(t.pos) != t) { dropped++; continue; }
            cancel(t.pos);
            finish(r);
        }
    }

    private static void finish(Result r) {
        Task t = r.t;
        ClientLevel level = Minecraft.getInstance().level;
        if (t.level != level || t.job != job) { dropped++; return; }
        if (r.err != null || r.sec == null) {
            if (errors++ < 5) HokaOdysseyClient.LOG.warn("генерация куба {} упала — прошу у сервера", SectionPos.of(t.pos), r.err);
            CubeCache.want(t.pos, CubeCache.WANT_BLOCKS | CubeCache.WANT_NOGEN);   // 0.63.1: сбой — не расхождение
            ClientGen.failed();
            return;
        }
        if (t.f != level.palettedContainerFactory()) { dropped++; CubeCache.want(t.pos, CubeCache.WANT_BLOCKS | CubeCache.WANT_NOGEN); return; }
        generated++;
        genNs += r.ns;
        long t0 = System.nanoTime();
        ClientGen.placed(level, t.pos, r.sec, r.bes, r.hash, t.expect);
        insertNs += System.nanoTime() - t0;                            // 0.63: цена вставки на главном потоке
        inserted++;
    }

    public static String stats() {
        return "сгенерировано " + generated + (generated > 0 ? String.format(" (%.0f мкс/куб, вставка %.0f мкс)", genNs / 1e3 / generated,
                insertNs / 1e3 / Math.max(1, inserted)) : "") + (fromDisk > 0 ? ", с диска " + fromDisk : "")
                + ", ждут " + PENDING.size() + ", готово " + RESULTS.size() + "+" + NEAR.size() + ", синхронно " + forced + ", отброшено " + dropped
                + (errors > 0 ? ", ошибок " + errors : "");
    }
}
