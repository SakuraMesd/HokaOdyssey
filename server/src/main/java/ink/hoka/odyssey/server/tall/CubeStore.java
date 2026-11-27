package ink.hoka.odyssey.server.tall;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ленивая загрузка кубов (CUBIC-CHUNKS.md §4.3, этап 4 шаг 10). Состояния кубов живут в {@link CubeColumn}
 * (ABSENT → QUEUED → LOADING → READY), здесь — общая очередь с приоритетом и рабочие виртуальные потоки:
 * куб читается из региона (нет на диске — генератором) без замков столбца, кладётся в столбец коротким
 * замком записи. Тик ничего не ждёт.
 * <ul>
 *   <li>интерес к кубу — окна игроков (CubeStreamer), закрепления и loadCubes (Cubes), замороженные сущности;</li>
 *   <li>ставший READY куб — в очередь {@link #pollReady} (CubeStreamer дошлёт игрокам);</li>
 *   <li>запись в не-READY куб — IllegalStateException, счётчик, первый стек в лог (§4.2);</li>
 *   <li>шаг 11, выгрузка ({@link #scan}, раз в 5 с в тике): загруженный куб без интереса {@link #IDLE_MS} — окна
 *       игроков (+запас), закрепления, сущности (их рамка ±1 блок), запись — уходит в UNLOADING; поток выгрузки
 *       сохраняет столбец и убирает куб из памяти (CubeColumn.unloadCubes). map.yml {@code cube_unload: false} — выкл.</li>
 * </ul>
 */
public final class CubeStore {

    private CubeStore() {}

    private static final Logger LOG = LoggerFactory.getLogger("tall");
    /** Рабочих потоков чтения: диск/генератор — параллельно, но не всё сразу (приоритет должен работать). */
    static final int WORKERS = 8;

    private record Req(CubeColumn col, int i, int prio, long seq) implements Comparable<Req> {
        @Override public int compareTo(Req o) { return prio != o.prio ? Integer.compare(prio, o.prio) : Long.compare(seq, o.seq); }
    }

    private static final PriorityBlockingQueue<Req> QUEUE = new PriorityBlockingQueue<>();
    private static final AtomicLong SEQ = new AtomicLong();
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    /** Куб столбца стал READY — CubeStreamer шлёт его игрокам (в тике). */
    record Ready(CubeColumn col, int i) {}
    private static final ConcurrentLinkedQueue<Ready> READY = new ConcurrentLinkedQueue<>();

    /** Отладка (/hocubes stats). */
    static final AtomicLong loads = new AtomicLong(), loadErrors = new AtomicLong(), violations = new AtomicLong();
    /** Шаг 11: кубов выгружено / оставлено в памяти (попросили или не сохранилось) / сбоев сохранения при выгрузке. */
    static final AtomicLong unloads = new AtomicLong(), unloadKept = new AtomicLong(), unloadErrors = new AtomicLong();
    private static final AtomicBoolean STACK_LOGGED = new AtomicBoolean();
    private static volatile long lastErrorLog;

    static void enqueue(CubeColumn col, int i, int prio) {
        if (STARTED.compareAndSet(false, true))
            for (int w = 0; w < WORKERS; w++) Thread.ofVirtual().name("cube-load-" + w).start(CubeStore::work);
        QUEUE.add(new Req(col, i, prio, SEQ.incrementAndGet()));
    }

    private static void work() {
        for (;;) {
            Req r;
            try { r = QUEUE.take(); }
            catch (InterruptedException e) { return; }
            try { r.col.loadNow(r.i, r.prio); }
            catch (Throwable t) { LOG.error("загрузка куба {} {} {}: {}", r.col.getChunkX(), r.i + r.col.getMinSection(), r.col.getChunkZ(), t.toString(), t); }
        }
    }

    /** Чтение не удалось (сбой диска, не повреждение): куб остаётся не в памяти, повтор через 2 с. */
    static void failed(CubeColumn col, int i, int prio, Throwable e) {
        loadErrors.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastErrorLog > 10_000) {
            lastErrorLog = now;
            LOG.error("куб {} {} {} не прочитан ({}) — повторю; пока он не в памяти, писать в него нельзя",
                    col.getChunkX(), i + col.getMinSection(), col.getChunkZ(), e.toString());
        }
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { return; }
            if (col.isLoaded()) col.request(i, prio);
        });
    }

    static void ready(CubeColumn col, int i) {
        loads.incrementAndGet();
        READY.add(new Ready(col, i));
    }

    static Ready pollReady() { return READY.poll(); }

    /** Куб снова READY без загрузки (выгрузку отменили — его ждали): дослать игрокам. */
    static void readyAgain(CubeColumn col, int i) { READY.add(new Ready(col, i)); }

    /** Запись в не-READY куб (§4.2): счётчик и первый стек — в лог. */
    static void violation(CubeColumn col, int i, String what) {
        violations.incrementAndGet();
        if (STACK_LOGGED.compareAndSet(false, true))
            LOG.error("правило записи: {} в незагруженный куб {} {} {} — отменено (первый случай, дальше только счётчик)",
                    what, col.getChunkX(), i + col.getMinSection(), col.getChunkZ(), new IllegalStateException("cube not loaded"));
    }

    static int queued() { return QUEUE.size(); }

    // ───────────── шаг 11: выгрузка кубов ─────────────

    /**
     * Сколько куб без интереса остаётся в памяти: -Dhoka.cubes.idle-ms, по умолчанию 2 мин (было 30 с — вернулся
     * через полминуты, и всё читается/генерируется и светится заново). Не меньше 10 с.
     */
    static final long IDLE_MS = Math.max(10_000, Long.getLong("hoka.cubes.idle-ms", 120_000));
    /**
     * Куб столбца далеко от всех игроков (Чебышёв &gt; обзор + {@link #FAR_MARGIN}): столько, -Dhoka.cubes.far-idle-ms,
     * по умолчанию 20 с. В быстром полёте (1100 новых столбцов/с) 2 минуты держали ~130 тыс. столбцов, и тик Minestom
     * обходил их все (JFR s49: Ms-Tick почти целиком, тик без игроков 15→30 мс). Рядом — по-прежнему IDLE_MS.
     */
    static final long FAR_IDLE_MS = Math.max(5_000, Long.getLong("hoka.cubes.far-idle-ms", 20_000));
    static final int FAR_MARGIN = 8;
    /** Одновременных сохранений при выгрузке (диск не забивать). */
    private static final java.util.concurrent.Semaphore SAVERS = new java.util.concurrent.Semaphore(4);
    private static volatile long lastUnloadErrLog;

    /** На карте выгружаются кубы (ленивая + map.yml cube_unload не false). */
    public static boolean unloading(Instance inst) {
        return inst instanceof net.minestom.server.instance.InstanceContainer ic && ic.getChunkLoader() instanceof CubeRegionLoader l && l.unload();
    }

    public static void register() {
        net.minestom.server.MinecraftServer.getSchedulerManager().buildTask(CubeStore::scan)
                .delay(net.minestom.server.timer.TaskSchedule.seconds(5)).repeat(net.minestom.server.timer.TaskSchedule.seconds(5)).schedule();
    }

    /** В тике: по всем ленивым столбцам — обновить интерес, кубы без интереса — выгружать (в потоке). */
    static void scan() {
        long now = System.currentTimeMillis();
        for (Instance inst : net.minestom.server.MinecraftServer.getInstanceManager().getInstances()) {
            if (!unloading(inst)) continue;
            java.util.Map<Long, java.util.BitSet> ents = null;
            var players = inst.getPlayers();
            int np = players.size(), k = 0;
            int[] px = new int[np], pz = new int[np], pr = new int[np];
            for (var p : players) {
                if (k >= np) break;
                px[k] = p.getPosition().blockX() >> 4; pz[k] = p.getPosition().blockZ() >> 4;
                pr[k] = p.effectiveViewDistance() + FAR_MARGIN;
                k++;
            }
            for (Chunk ch : inst.getChunks()) {
                if (!(ch instanceof CubeColumn c) || !c.isLazy() || !c.isLoaded() || c.heldCount() == 0) continue;
                boolean far = true;
                for (int j = 0; j < k && far; j++)
                    if (Math.max(Math.abs(c.getChunkX() - px[j]), Math.abs(c.getChunkZ() - pz[j])) <= pr[j]) far = false;
                java.util.BitSet keep = new java.util.BitSet(c.getMaxSection() - c.getMinSection());
                if (!CubeStreamer.interest(c, keep)) continue;             // зритель без окна (только вошёл) — не трогать
                Cubes.pinned(inst, c.getChunkX(), c.getChunkZ(), keep);
                if (ents == null) ents = entityCubes(inst);
                var e = ents.get(colKey(c.getChunkX(), c.getChunkZ()));
                if (e != null) keep.or(e);
                int[] is = c.pickUnload(keep, now, far ? FAR_IDLE_MS : IDLE_MS);
                if (is.length > 0) submit(c, is);
            }
        }
    }

    private static long colKey(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    /** Кубы, занятые сущностями (рамка ±1 блок, в т.ч. соседние столбцы): столбец → индексы кубов. */
    private static java.util.Map<Long, java.util.BitSet> entityCubes(Instance inst) {
        java.util.Map<Long, java.util.BitSet> m = new java.util.HashMap<>();
        TallWorld.Dims dd = TallWorld.dims(inst);
        int min = dd.minSection(), n = dd.cubes();
        for (var en : inst.getEntities()) {
            var p = en.getPosition();
            var bb = en.getBoundingBox();
            int x0 = (int) Math.floor(p.x() + bb.minX() - 1) >> 4, x1 = (int) Math.floor(p.x() + bb.maxX() + 1) >> 4;
            int z0 = (int) Math.floor(p.z() + bb.minZ() - 1) >> 4, z1 = (int) Math.floor(p.z() + bb.maxZ() + 1) >> 4;
            int y0 = Math.max(0, ((int) Math.floor(p.y() + bb.minY() - 1) >> 4) - min), y1 = Math.min(n - 1, ((int) Math.floor(p.y() + bb.maxY() + 1) >> 4) - min);
            if (y0 > y1) continue;
            if (x1 - x0 > 8) x1 = x0 + 8;                                       // огромная рамка — не больше 9 столбцов по оси
            if (z1 - z0 > 8) z1 = z0 + 8;
            for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++)
                m.computeIfAbsent(colKey(cx, cz), k -> new java.util.BitSet(n)).set(y0, y1 + 1);
        }
        return m;
    }

    private static void submit(CubeColumn c, int[] is) {
        Thread.ofVirtual().name("cube-unload").start(() -> {
            try {
                SAVERS.acquire();
                try {
                    int n = c.unloadCubes(is);
                    unloads.addAndGet(n);
                    unloadKept.addAndGet(is.length - n);
                } finally { SAVERS.release(); }
            } catch (Throwable t) {
                LOG.error("выгрузка кубов столбца {} {}: {}", c.getChunkX(), c.getChunkZ(), t.toString(), t);
            }
        });
    }

    /** Сохранение при выгрузке не удалось: кубы остаются в памяти (и «изменёнными»), повтор — при следующем обходе. */
    static void unloadFailed(CubeColumn c, Throwable e) {
        unloadErrors.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastUnloadErrLog > 10_000) {
            lastUnloadErrLog = now;
            LOG.error("столбец {} {} не сохранён при выгрузке кубов ({}) — кубы оставлены в памяти", c.getChunkX(), c.getChunkZ(), e.toString());
        }
    }

    // ───────────── для ядра и модулей ─────────────

    /** На карте ленивая загрузка кубов (store: cubes + cubes: true, map.yml cube_lazy не false). */
    public static boolean lazy(Instance inst) {
        return inst instanceof net.minestom.server.instance.InstanceContainer ic && ic.getChunkLoader() instanceof CubeRegionLoader l && l.lazy();
    }

    /** Куб с блоком (x, y, z) в памяти. Столбец не загружен — false; вне мира по Y — true (там ничего нет). */
    public static boolean isReady(Instance inst, int x, int y, int z) {
        Chunk ch = inst.getChunk(x >> 4, z >> 4);
        if (ch == null) return false;
        return !(ch instanceof TallColumn tc) || tc.ready((y >> 4) - ch.getMinSection());
    }

    /**
     * Загрузить кубы cy0…cy1 столбца (cx, cz): сначала столбец (Minestom), потом кубы. Будущее завершается в потоке
     * загрузки — в тике не ждать.
     */
    static CompletableFuture<Void> load(Instance inst, int cx, int cz, int cy0, int cy1, int prio) {
        return inst.loadChunk(cx, cz).thenCompose(ch -> {
            if (!(ch instanceof CubeColumn c)) return CompletableFuture.completedFuture(null);
            List<CompletableFuture<Void>> fs = new ArrayList<>();
            int a = Math.max(0, cy0 - c.getMinSection()), b = Math.min(c.getMaxSection() - c.getMinSection() - 1, cy1 - c.getMinSection());
            for (int i = a; i <= b; i++) {
                var f = c.whenReady(i, prio);
                if (!f.isDone()) fs.add(f);
            }
            return fs.isEmpty() ? CompletableFuture.completedFuture(null) : CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new));
        });
    }
}
