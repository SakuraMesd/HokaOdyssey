package ink.hoka.odyssey.server.tall;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.network.packet.server.play.data.LightData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Свет по кубам с обменом краями (CUBIC-CHUNKS.md §4.6, этап 4 шаг 12) — для кубовых карт на CubeColumn.
 * <p>Правила света — как у игры: небо 15 выше skyTop колонки (HeightIndex), дальше растекание с ослаблением
 * max(1, lightBlock) и проверкой граней формы (плиты, ступени; полные кубы — только lightBlock); свет блоков от
 * lightEmission по тем же правилам. Свет идёт через границы кубов и столбцов.</p>
 * <p>Кубы трёх видов:</p>
 * <ul>
 *   <li><b>«сквозной» (PT)</b>: он и 26 соседей пусты, и ни один skyTop колонок 48×48 вокруг не попадает в полосу
 *       [низ−16, верх+30]. Тогда свет куба одинаков по высоте и равен плоской задаче 48×48 (расстояние до колонки-
 *       источника): считается на лету из высот, не хранится. Почти весь воздух — такие кубы (часто «всё 15»);</li>
 *   <li><b>посчитанный</b> (остальные READY): массивы неба/блоков в {@link Store}; совпал с формулой F (небо
 *       y &gt; skyTop ? 15 : 0, блоки 0) — массив не хранится;</li>
 *   <li><b>не READY / столбец не загружен</b>: для соседей — по формуле F (высоты столбца или генератора).</li>
 * </ul>
 * <p>Пересчёт: событие в кубе C (правка блока, загрузка куба) — область 3×3×3 кубов вокруг C одной заливкой,
 * рамка — грани кубов кольца 5×5×5 (посчитанные — из Store, PT — из высот, прочие — F). Свет не уходит дальше 15
 * блоков, поэтому кольцо от правки не зависит и пересчёт точный без двухфазного алгоритма игры. Сменился skyTop
 * колонки — ещё посчитанные кубы 3×3 столбцов между старым и новым значением (PT меняются сами). Загрузился столбец —
 * его кубы, затем 3×3×3 вокруг тех, у кого боковая грань не совпала с F по генератору (так их видели соседи).</p>
 * <p>Всё в тике под замком движка (один на мир); события — из любых потоков в очередь. Игрокам — только кубы,
 * у которых свет изменился (CubeStreamer.relightCubes).</p>
 */
final class CubeLight {

    static final int MIN = TallWorld.MIN_Y >> 4, CUBES = TallWorld.HEIGHT >> 4;

    // ───────────── доступ к миру (CubeColumn или тестовый) ─────────────

    /** Столбец для расчёта. Палитры читаются без замка (как раньше ColumnLight): гонку правит событие правки. */
    interface Column {
        int cx();
        int cz();
        /** Куб i в памяти (READY). */
        boolean ready(int i);
        /** В кубе могут быть блоки (не READY — считаем, что да). */
        boolean maybeBlocks(int i);
        /** skyTop колонки c = z<<4|x (HeightIndex). */
        int skyTop(int c);
        /** Состояния блоков куба i в out[y<<8|z<<4|x]; false — куб пуст. */
        boolean ids(int i, int[] out);
        /** Состояние одного блока куба i (локальные 0…15). */
        int id(int i, int x, int y, int z);
        Store store();
        /**
         * В READY-кубе i могут быть светящиеся блоки (false — точно нет). По умолчанию «да»: куб считается полной
         * заливкой, как раньше. Нужен для быстрого пути мира без неба ({@link #compute}).
         */
        default boolean mayEmit(int i) { return true; }
    }

    /**
     * Палитра блоков куба может содержать светящийся блок (lightEmission &gt; 0): по значениям палитры, без распаковки
     * 4096 клеток. Одно значение — оно; косвенная палитра — её список (лишние старые значения только «да» в запас);
     * прямая палитра или нет доступа к полям — «да» (полный расчёт, как раньше).
     */
    static boolean mayEmit(net.minestom.server.instance.palette.Palette p) {
        if (p.count() == 0) return false;
        int v = p.singleValue();
        if (v >= 0) return emission(v) > 0;
        if (PAL_LIST == null || p.getClass() != PAL_IMPL || p.bitsPerEntry() > net.minestom.server.instance.palette.Palette.BLOCK_PALETTE_MAX_BITS) return true;
        try {
            if (!(PAL_LIST.get(p) instanceof it.unimi.dsi.fastutil.ints.IntArrayList l)) return true;
            int[] a = l.elements();
            int n = Math.min(l.size(), a.length);
            for (int k = 0; k < n; k++) if (emission(a[k]) > 0) return true;
            return false;
        } catch (RuntimeException ex) {                                      // палитру меняют в другом потоке — в запас «да»
            return true;
        }
    }

    /** lightEmission состояния блока (0…15). */
    static int emission(int id) { return (inf(id) >> 10) & 15; }

    /** PaletteImpl.paletteToValueList (значения косвенной палитры) — для {@link #mayEmit(net.minestom.server.instance.palette.Palette)}. */
    private static final Class<?> PAL_IMPL;
    private static final java.lang.invoke.VarHandle PAL_LIST;
    static {
        Class<?> c = null;
        java.lang.invoke.VarHandle h = null;
        try {
            c = Class.forName("net.minestom.server.instance.palette.PaletteImpl");
            h = java.lang.invoke.MethodHandles.privateLookupIn(c, java.lang.invoke.MethodHandles.lookup())
                    .findVarHandle(c, "paletteToValueList", it.unimi.dsi.fastutil.ints.IntArrayList.class);
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("cubes").warn("нет доступа к PaletteImpl.paletteToValueList ({}) — свет без неба считается полностью", t.toString());
            c = null;
            h = null;
        }
        PAL_IMPL = c;
        PAL_LIST = h;
    }

    /** Мир для расчёта. */
    interface World {
        /** У измерения есть небо. */
        boolean sky();
        /** Загруженный столбец или null. */
        Column column(int cx, int cz);
        /** skyTop колонки незагруженного столбца (генератор). */
        int genTop(int x, int z);
        /** В кубе незагруженного столбца могут быть блоки (генератор). */
        boolean genBlocks(int cx, int cy, int cz);
    }

    /** Свет кубов одного столбца. Читать/писать — под замком движка. */
    static final class Store {
        final Column col;
        /** Столбец сервера (для рассылки) или null (тест). */
        final CubeColumn owner;
        /** Посчитанные кубы: массивы-полубайты 2048 Б; нет массива — по формуле F (небо) / 0 (блоки). */
        final Int2ObjectOpenHashMap<byte[]> sky = new Int2ObjectOpenHashMap<>(0), blk = new Int2ObjectOpenHashMap<>(0);
        /** Куб посчитан (не PT). */
        final BitSet done = new BitSet();
        /** Свет куба изменился с прошлой рассылки. */
        final BitSet changed = new BitSet();
        /** Кэш {@link Column#mayEmit}: известно / «светящиеся могут быть». Сбрасывают события куба и столбца. */
        final BitSet emitKnown = new BitSet(), emitYes = new BitSet();

        Store(Column col, CubeColumn owner) { this.col = col; this.owner = owner; }

        /** Сколько кубов с массивами (для /hocubes stats). */
        int arrays() { return sky.size() + blk.size(); }
    }

    // ───────────── движки по мирам ─────────────

    private static final Map<Instance, CubeLight> ENGINES = new ConcurrentHashMap<>();
    private static final AtomicBoolean FLUSH = new AtomicBoolean();

    static CubeLight of(Instance inst) { return ENGINES.computeIfAbsent(inst, i -> new CubeLight(new InstWorld(i))); }

    /** Столбец считает свет кубами (кубовый протокол + CubeColumn); иначе — ColumnLight, как раньше. */
    static boolean active(TallColumn c) { return c instanceof CubeColumn && TallWorld.isCubes(c.getInstance()); }

    /** Блок сменился (x, y, z — мировые; t0/t1 — skyTop колонки до и после). Любой поток. */
    static void edit(CubeColumn c, int x, int y, int z, int t0, int t1) { of(c.getInstance()).editAt(x, y, z, t0, t1); }

    /** Куб загрузился; stale — его отметка высот не совпала с HeightIndex (шаг 13: для stats — куб считается в любом случае). */
    static void cubeLoaded(CubeColumn c, int i, boolean stale) {
        CubeLight e = of(c.getInstance());
        if (stale) e.markStale++;
        e.cubeLoadedAt(c.getChunkX(), i + MIN, c.getChunkZ());
    }

    /** Шаг 13: skyTop колонок столбца уточнился при загрузке куба (HeightIndex.fix): old/now по клеткам z<<4|x. */
    static void skyFixed(CubeColumn c, int[] old, int[] now) { of(c.getInstance()).skyFixedAt(c.getChunkX(), c.getChunkZ(), old, now); }

    static void cubeUnloaded(CubeColumn c, int i) { of(c.getInstance()).cubeUnloadedAt(c.getChunkX(), i + MIN, c.getChunkZ()); }

    static void columnLoaded(CubeColumn c) { of(c.getInstance()).columnLoadedAt(c.getChunkX(), c.getChunkZ()); }

    /** Свет кубов idx (по возрастанию) столбца для игрока; explicit — «всё 15» массивом и «блоков нет» битом. */
    static LightData data(CubeColumn c, List<Integer> idx, boolean explicit) {
        return of(c.getInstance()).data(c.lightStore(), idx, explicit);
    }

    /** У куба i столбца есть свет блоков (не «всё 0»): сначала пересчёт по накопленным событиям. */
    static boolean blockLit(CubeColumn c, int i) { return of(c.getInstance()).blockLit(c.lightStore(), i); }

    synchronized boolean blockLit(Store st, int i) {
        safeProcess();
        return st.done.get(i) && st.blk.containsKey(i);
    }

    /** Раз за тик: пересчитать по событиям и разослать изменившиеся кубы. */
    static void flushAll() {
        FLUSH.set(false);
        for (var e : ENGINES.entrySet()) {
            if (!e.getKey().isRegistered()) { ENGINES.remove(e.getKey()); continue; }
            e.getValue().flush();
        }
    }

    static String stats() {
        long runs = 0, cubes = 0, trivial = 0, ns = 0, max = 0, last = 0, miss = 0, stale = 0, deferred = 0, lost = 0, fails = 0;
        int arrays = 0;
        for (var e : ENGINES.values()) {
            runs += e.runs; cubes += e.cubesComputed; trivial += e.cubesTrivial; ns += e.nanos; max = Math.max(max, e.maxNanos); last = Math.max(last, e.lastNanos);
            miss += e.frameMiss;
            stale += e.markStale;
            deferred += e.colsDeferred; lost += e.lostCubes; fails += e.failures;
        }
        for (var inst : ENGINES.keySet())
            for (var ch : inst.getChunks()) if (ch instanceof CubeColumn cc) arrays += cc.lightStore().arrays();
        long cuts = 0;
        for (var e : ENGINES.values()) cuts += e.budgetCuts;
        return "свет кубами: пересчётов " + runs + ", кубов посчитано " + cubes + (trivial > 0 ? " (из них «всё 0» сразу " + trivial + ")" : "")
                + (runs > 0 ? String.format(", в среднем %.2f мс, последний %.2f мс, максимум %.2f мс", ns / 1e6 / runs, last / 1e6, max / 1e6) : "")
                + ", массивов света в памяти " + arrays + (miss > 0 ? ", промахов рамки " + miss : "")
                + (stale > 0 ? ", кубов с устаревшей отметкой высот " + stale : "")
                + (deferred > 0 ? ", COLUMN отложено " + deferred : "") + (lost > 0 ? ", кубов без столбца " + lost : "")
                + (fails > 0 ? ", СБОЕВ пересчёта " + fails : "") + (cuts > 0 ? ", отложено по бюджету тика " + cuts : "");
    }

    // ───────────── движок ─────────────

    private record Ev(int type, int cx, int cy, int cz, int x, int y, int z, int t0, int t1, int[] old, int[] now) {
        static final int EDIT = 0, LOAD = 1, UNLOAD = 2, COLUMN = 3, SKY = 4;

        Ev(int type, int cx, int cy, int cz, int x, int z, int t0, int t1) { this(type, cx, cy, cz, x, cy << 4, z, t0, t1, null, null); }
    }

    private final World w;
    private final ConcurrentLinkedQueue<Ev> events = new ConcurrentLinkedQueue<>();
    /** Хранилища с изменённым светом (ждут рассылки). Под замком. */
    private final Set<Store> touched = new HashSet<>();
    /** Высоты генератора по незагруженным столбцам (детерминированы — кэш между тиками). */
    private final Long2ObjectOpenHashMap<int[]> genTops = new Long2ObjectOpenHashMap<>();
    /** Отладка. */
    volatile long runs, cubesComputed, nanos, maxNanos, lastNanos, frameMiss, markStale;

    CubeLight(World w) { this.w = w; this.budgetNs = w instanceof InstWorld ? TICK_BUDGET_NS : Long.MAX_VALUE; }

    private void post(Ev e) {
        events.add(e);
        kick();
    }

    /** Пересчёт в следующем тике (flushAll). */
    private static void kick() {
        if (FLUSH.compareAndSet(false, true)) {
            try { MinecraftServer.getSchedulerManager().scheduleNextTick(CubeLight::flushAll); }
            catch (Throwable t) { FLUSH.set(false); }                     // нет сервера (тест) — пересчёт по data/process
        }
    }

    /**
     * Столбцы, чьё событие COLUMN пришло раньше, чем столбец появился в инстансе (загрузчик шлёт его из afterLoad/
     * initLazy до того, как Minestom положит столбец в карту): ключ → крайний срок (мс). Повторяются в каждом process,
     * пока столбец не появится; не появился к сроку — загрузка не удалась/выгружен (новая загрузка пришлёт COLUMN заново).
     * Под замком движка.
     */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap pendingCols = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private static final long PENDING_COL_MS = 10_000;
    /**
     * Кубы, которые рамка нашла непосчитанными, хотя они не PT (промах рамки: событие их загрузки/столбца потерялось
     * или ещё не дошло) — в следующем process посчитать (как слабые: только непосчитанные). Под замком движка.
     */
    private final LongOpenHashSet missed = new LongOpenHashSet();
    /** Отладка: отложенных COLUMN, кубов без столбца в пересчёте, сбоев пересчёта. */
    volatile long colsDeferred, lostCubes, failures;
    private static volatile long lastFailLog;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("cubes");

    void editAt(int x, int y, int z, int t0, int t1) { post(new Ev(Ev.EDIT, x >> 4, y >> 4, z >> 4, x, y, z, t0, t1, null, null)); }
    void cubeLoadedAt(int cx, int cy, int cz) { post(new Ev(Ev.LOAD, cx, cy, cz, 0, 0, 0, 0)); }
    void cubeUnloadedAt(int cx, int cy, int cz) { post(new Ev(Ev.UNLOAD, cx, cy, cz, 0, 0, 0, 0)); }
    void columnLoadedAt(int cx, int cz) { post(new Ev(Ev.COLUMN, cx, 0, cz, 0, 0, 0, 0)); }
    void skyFixedAt(int cx, int cz, int[] old, int[] now) { post(new Ev(Ev.SKY, cx, 0, cz, 0, 0, 0, 0, 0, old, now)); }

    /** Есть необработанные события. */
    boolean pending() { return !events.isEmpty(); }

    synchronized void flush() {
        safeProcess();
        send();
        // дождаться столбцов / досчитать промахи / события сверх бюджета тика — в следующем тике
        if (!pendingCols.isEmpty() || !missed.isEmpty() || !events.isEmpty()) kick();
    }

    /**
     * Бюджет пересчёта на вызов из тика (flush/data), нс: события сверх него ждут следующего тика (их кубы до того —
     * по прежнему свету, потом рассылка разницы). MAX — без лимита (тесты, level/box). Событие делится только целиком:
     * часть его кубов по устаревшей рамке дала бы «призрачный» свет.
     */
    volatile long budgetNs;
    static final long TICK_BUDGET_NS = 6_000_000;
    /** Сколько кубов в событиях одной партии при бюджете (дальше — следующая партия/тик). */
    static final int BATCH_CUBES = 192;
    /** Отладка: вызовов, оставивших события на следующий тик по бюджету. */
    volatile long budgetCuts;

    /**
     * process() без обрыва вызывающего (рассылка игроку, тик): сбой — в лог со стеком (не чаще раза в 10 с) и счётчик.
     * Выдача света после сбоя всё равно идёт: непосчитанные кубы — по плоской задаче/F, а их досчитает промах рамки.
     */
    private void safeProcess() {
        long b = budgetNs, t = System.nanoTime();
        if (b != Long.MAX_VALUE) {                                           // бюджет общий на окно тика (flush + все data/blockLit)
            if (t - winStart >= 50_000_000L) { winStart = t; winSpent = 0; }
            b -= winSpent;
            if (b <= 0) { if (!events.isEmpty()) kick(); return; }          // выбран — досчитаем в следующем тике
        }
        try { process(b); }
        catch (RuntimeException ex) {
            failures++;
            long now = System.currentTimeMillis();
            if (now - lastFailLog >= 10_000) { lastFailLog = now; LOG.error("свет кубами: сбой пересчёта (всего {})", failures, ex); }
        } finally {
            if (budgetNs != Long.MAX_VALUE) winSpent += System.nanoTime() - t;
        }
    }

    /** Окно бюджета (≈ тик): начало и потрачено, нс. Под замком движка. */
    private long winStart, winSpent;

    private void send() {
        if (touched.isEmpty()) return;
        List<Store> list = new java.util.ArrayList<>(touched);              // рассылка может снова тронуть хранилища (пересчёт по запросу)
        touched.clear();
        for (Store st : list) {
            CubeColumn c = st.owner;
            if (c != null && c.isLoaded() && !st.changed.isEmpty())
                for (Player p : CubeStreamer.receivers(c)) CubeStreamer.relightCubes(p, c, st.changed);   // и кольцо 33
            st.changed.clear();
        }
    }

    /** Пересчитать по всем накопленным событиям (без бюджета). */
    synchronized void process() { process(Long.MAX_VALUE); }

    /** Пересчёт партиями, пока есть события и не вышел бюджет (нс). */
    synchronized void process(long budget) {
        long start = System.nanoTime();
        boolean limited = budget != Long.MAX_VALUE;
        do {
            batch(limited ? BATCH_CUBES : Integer.MAX_VALUE);
        } while (!events.isEmpty() && System.nanoTime() - start < budget);
        if (!events.isEmpty()) budgetCuts++;
    }

    /** Одна партия событий: берём, пока кубов в пересчёте меньше limit. */
    private void batch(int limit) {
        Ev e = events.poll();
        if (e == null && pendingCols.isEmpty() && missed.isEmpty()) return;
        long t0 = System.nanoTime();
        Ctx x = new Ctx();
        LongOpenHashSet k = new LongOpenHashSet(), weak = new LongOpenHashSet();
        LongLinkedOpenHashSet cols = new LongLinkedOpenHashSet();
        if (!pendingCols.isEmpty()) {                                        // отложенные COLUMN: столбец появился — в проход
            long now = System.currentTimeMillis();
            for (var it = pendingCols.long2LongEntrySet().iterator(); it.hasNext(); ) {
                var pe = it.next();
                long ck = pe.getLongKey();
                if (w.column((int) (ck >> 32), (int) ck) != null) { cols.add(ck); it.remove(); }
                else if (now > pe.getLongValue()) it.remove();
            }
        }
        if (!missed.isEmpty()) { weak.addAll(missed); missed.clear(); }
        for (; e != null; e = k.size() + cols.size() * 32 < limit ? events.poll() : null) {
            switch (e.type) {
                case Ev.EDIT -> {
                    infCache.remove(key(e.cx, e.cy, e.cz));
                    forgetEmit(e.cx, e.cy, e.cz);
                    addNear(k, weak, e.x, e.y, e.z);
                    if (e.t0 != e.t1) { x.recordOld(e.x, e.z, e.t0); x.range(e.x >> 4, e.z >> 4, Math.min(e.t0, e.t1), Math.max(e.t0, e.t1)); }
                }
                case Ev.LOAD -> { infCache.remove(key(e.cx, e.cy, e.cz)); forgetEmit(e.cx, e.cy, e.cz); add3(k, e.cx, e.cy, e.cz); }
                case Ev.UNLOAD -> {
                    infCache.remove(key(e.cx, e.cy, e.cz));
                    forgetEmit(e.cx, e.cy, e.cz);
                    Column c = w.column(e.cx, e.cz);
                    if (c != null) drop(c.store(), e.cy - MIN, false);
                    add3(weak, e.cx, e.cy, e.cz);                             // соседи могли перестать быть PT
                }
                case Ev.COLUMN -> {
                    // кэш ≤ INF_CACHE записей: пройти его дешевле, чем 1876 удалений по ключу (JFR s49: 127 выборок)
                    if (infCache.size() < CUBES) {
                        for (var it = infCache.keySet().iterator(); it.hasNext(); ) {
                            long kk = it.nextLong();
                            if (kx(kk) == e.cx && kz(kk) == e.cz) it.remove();
                        }
                    } else for (int i = 0; i < CUBES; i++) infCache.remove(key(e.cx, i + MIN, e.cz));
                    Column cc = w.column(e.cx, e.cz);
                    if (cc != null) cc.store().emitKnown.clear();
                    cols.add(colKey(e.cx, e.cz));
                }
                case Ev.SKY -> {                                             // как правка skyTop, только без блока
                    for (int c = 0; c < 256; c++) {
                        int a = e.old[c], b = e.now[c];
                        if (a == b) continue;
                        x.recordOld(e.cx * 16 + (c & 15), e.cz * 16 + (c >> 4), a);
                        x.range(e.cx, e.cz, Math.min(a, b), Math.max(a, b));
                    }
                }
            }
        }
        if (!cols.isEmpty()) columnPass(x, cols, k);
        for (var r : x.ranges.long2ObjectEntrySet()) rangePass(x, r.getLongKey(), r.getValue(), k);
        LongArrayList s = new LongArrayList();
        for (long key : k) pick(x, key, s, false);
        for (long key : weak) if (!k.contains(key)) pick(x, key, s, true);
        compute(x, s);
        long dt = System.nanoTime() - t0;
        runs++;
        nanos += dt;
        lastNanos = dt;
        if (dt > maxNanos) maxNanos = dt;
    }

    /** Куб из множества событий: посчитанный — в заливку; ставший PT — массивы прочь (onlyNew — только непосчитанные). */
    private void pick(Ctx x, long key, LongArrayList s, boolean onlyNew) {
        int cx = kx(key), cy = ky(key), cz = kz(key), i = cy - MIN;
        if (i < 0 || i >= CUBES) return;
        Column c = x.col(cx, cz);
        if (c == null || !c.ready(i)) return;
        Store st = c.store();
        if (x.nonPT(cx, cy, cz)) { if (!onlyNew || !st.done.get(i)) s.add(key); }
        else if (st.done.get(i)) drop(st, i, true);
    }

    private void drop(Store st, int i, boolean mark) {
        if (i < 0 || i >= CUBES) return;
        boolean had = st.done.get(i);
        st.sky.remove(i);
        st.blk.remove(i);
        st.done.clear(i);
        if (had && mark) mark(st, i);
    }

    private void mark(Store st, int i) {
        st.changed.set(i);
        touched.add(st);
    }

    /**
     * Правка блока (x, y, z): свет от неё меняется не дальше 14 блоков (уровень ≤ 15, шаг −1), поэтому в пересчёт —
     * только кубы 3×3×3, чья ближняя клетка в пределах 14 по манхэттену (7…12 вместо 27); результат тот же. Дальние
     * из 27 — слабыми: досчитать, только если ещё не посчитаны (стали не PT из-за блока). Смена skyTop — rangePass.
     */
    static void addNear(LongOpenHashSet k, LongOpenHashSet weak, int x, int y, int z) {
        int cx = x >> 4, cy = y >> 4, cz = z >> 4, lx = x & 15, ly = y & 15, lz = z & 15;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (cy + dy < MIN || cy + dy >= MIN + CUBES) continue;
            int d = gap(lx, dx) + gap(ly, dy) + gap(lz, dz);
            (d <= 14 ? k : weak).add(key(cx + dx, cy + dy, cz + dz));
        }
    }

    /** Для тестов: кубы пересчёта правки — [в пересчёт, слабые]. */
    static long[][] nearKeys(int x, int y, int z) {
        LongOpenHashSet k = new LongOpenHashSet(), weak = new LongOpenHashSet();
        addNear(k, weak, x, y, z);
        return new long[][]{k.toLongArray(), weak.toLongArray()};
    }

    /** Расстояние по оси от клетки l (0…15) до соседнего куба в сторону s (−1/0/+1). */
    private static int gap(int l, int s) { return s == 0 ? 0 : s < 0 ? l + 1 : 16 - l; }

    private static void add3(LongOpenHashSet k, int cx, int cy, int cz) {
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
            if (cy + dy >= MIN && cy + dy < MIN + CUBES) k.add(key(cx + dx, cy + dy, cz + dz));
    }

    // ───────────── быстрый путь без неба ─────────────

    /** Отладка: кубов, посчитанных быстрым путём «без неба и без светящихся рядом — всё 0». */
    volatile long cubesTrivial;

    /** Блоки куба сменились — признак «светящиеся могут быть» узнать заново. */
    private void forgetEmit(int cx, int cy, int cz) {
        int i = cy - MIN;
        if (i < 0 || i >= CUBES) return;
        Column c = w.column(cx, cz);
        if (c != null) c.store().emitKnown.clear(i);
    }

    /** В READY-кубе i столбца c могут быть светящиеся (кэш в Store). */
    private static boolean emits(Column c, int i) {
        Store st = c.store();
        if (st.emitKnown.get(i)) return st.emitYes.get(i);
        boolean e;
        try { e = c.mayEmit(i); } catch (RuntimeException ex) { e = true; }
        st.emitKnown.set(i);
        if (e) st.emitYes.set(i); else st.emitYes.clear(i);
        return e;
    }

    /**
     * Свет блоков куба может быть не 0: в нём или в одном из 26 соседей (READY) есть светящийся блок. Свет блоков
     * уходит не дальше 14 блоков, а куб через одного — уже в 17+ блоках: дальше 3×3×3 он не достаёт. Не READY
     * соседи и в полной заливке дают 0 (рамка «не посчитан» — блоков 0), так что ответ «нет» точный.
     */
    private boolean emitNear(Ctx x, long key) {
        int cx = kx(key), cy = ky(key), cz = kz(key), i = cy - MIN;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Column c = x.col(cx + dx, cz + dz);
            if (c == null) continue;
            for (int j = Math.max(0, i - 1); j <= Math.min(CUBES - 1, i + 1); j++)
                if (c.ready(j) && c.maybeBlocks(j) && emits(c, j)) return true;
        }
        return false;
    }

    /** Куб под толщей: skyTop всех колонок 48×48 вокруг не ниже y0+30 — неба нет ни в нём, ни на его гранях. */
    private static boolean buried(Ctx x, long key) {
        return x.grid(kx(key), kz(key), false).min >= (ky(key) << 4) + 30;
    }

    /** Светящихся рядом нет, неба нет (мир без неба или куб под толщей): свет — «всё 0», без палитр и рамок. */
    private void zeroLight(Ctx x, long key) {
        Store st = x.col(kx(key), kz(key)).store();
        int i = ky(key) - MIN;
        boolean ch = !st.done.get(i) || st.sky.containsKey(i) || st.blk.containsKey(i)
                || (w.sky() && x.fChanged(kx(key), kz(key), ky(key) << 4));   // небо по F было не 0 до правки skyTop
        st.sky.remove(i);
        st.blk.remove(i);
        st.done.set(i);
        if (ch) mark(st, i);
    }

    // ───────────── загрузка столбца ─────────────

    /**
     * Столбцы загрузились: соседи считали их по F генератора. Сначала посчитать их кубы; где боковая грань (или PT-
     * полоса) не совпала с F генератора, где skyTop или заполненность не совпали с генератором — 3×3×3 в общий пересчёт.
     */
    private void columnPass(Ctx x, LongLinkedOpenHashSet cols, LongOpenHashSet k) {
        LongArrayList s1 = new LongArrayList();
        List<Column> loaded = new ArrayList<>();
        for (long ck : cols) {
            int cx = (int) (ck >> 32), cz = (int) ck;
            // через кэш пересчёта, как и compute(): прямой w.column расходился с ним — сосед, записанный в кэш как
            // «нет» (nonPTBits соседнего столбца раньше в этом же проходе), здесь уже был, его кубы шли в compute,
            // а там x.col давал null → NPE в cubeInf, и все вынутые события пересчёта терялись.
            Column c = x.col(cx, cz);
            if (c == null) {                                                 // ещё не в инстансе (или кэш «нет») — повторить позже
                if (!pendingCols.containsKey(ck)) { pendingCols.put(ck, System.currentTimeMillis() + PENDING_COL_MS); colsDeferred++; }
                continue;
            }
            loaded.add(c);
            int[] g = genTops(cx, cz);
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int cell = 0; cell < 256; cell++) {
                int t = c.skyTop(cell);
                if (t == g[cell]) continue;
                x.recordOld(cx * 16 + (cell & 15), cz * 16 + (cell >> 4), g[cell]);
                lo = Math.min(lo, Math.min(t, g[cell]));
                hi = Math.max(hi, Math.max(t, g[cell]));
            }
            if (lo <= hi) x.range(cx, cz, lo, hi);
            for (int i = 0; i < CUBES; i++)
                if (c.maybeBlocks(i) != w.genBlocks(cx, i + MIN, cz)) add3(k, cx, i + MIN, cz);
            BitSet np = x.nonPTBits(cx, cz);
            for (int i = np.nextSetBit(0); i >= 0; i = np.nextSetBit(i + 1)) if (c.ready(i)) s1.add(key(cx, i + MIN, cz));
        }
        compute(x, s1);
        for (Column c : loaded) {
            int cx = c.cx(), cz = c.cz();
            Store st = c.store();
            BitSet np = x.nonPTBits(cx, cz);
            for (int i = np.nextSetBit(0); i >= 0; i = np.nextSetBit(i + 1))
                if (c.ready(i) && st.done.get(i) && lateralDiff(x, c, i)) add3(k, cx, i + MIN, cz);
            if (!w.sky()) continue;
            for (int i = 0; i < CUBES; ) {                                   // полосы PT: плоская задача против F
                if (np.get(i)) { i++; continue; }
                int r0 = i;
                while (i < CUBES && !np.get(i)) i++;
                if (ptEdgesDiffer(x, c, r0 + MIN)) addNonPT(x, cx, cz, r0 - 1, i, k);
            }
        }
    }

    /** Боковые грани посчитанного куба i отличаются от F по генератору (так этот столбец видели соседи). */
    private boolean lateralDiff(Ctx x, Column c, int i) {
        Store st = c.store();
        byte[] sa = st.sky.get(i), ba = st.blk.get(i);
        if (ba == null && !w.sky()) return false;                            // без неба сравнивать нечего
        int[] g = genTops(c.cx(), c.cz());
        int y0 = (i + MIN) << 4;
        for (int d = 0; d < 4; d++)
            for (int a = 0; a < 16; a++) for (int b = 0; b < 16; b++) {
                int q = face(d, a, b);
                if (ba != null && nib(ba, q) != 0) return true;
                if (!w.sky()) continue;
                int col = q & 255, y = y0 + (q >> 8);
                int v = sa != null ? nib(sa, q) : (y > c.skyTop(col) ? 15 : 0);
                if (v != (y > g[col] ? 15 : 0)) return true;
            }
        return false;
    }

    /** Края плоской задачи PT-полосы столбца (с куба cy) отличаются от F. */
    private boolean ptEdgesDiffer(Ctx x, Column c, int cy) {
        byte[] two = x.pt2D(c.cx(), cy, c.cz(), false);
        int y0 = cy << 4;
        for (int a = 0; a < 16; a++)
            for (int cell : new int[]{a << 4, a << 4 | 15, a, 15 << 4 | a})       // края x = 0/15, z = 0/15
                if (two[cell] != (c.skyTop(cell) < y0 ? 15 : 0)) return true;
        return false;
    }

    /** Посчитанные (не PT) кубы 3×3 столбцов вокруг (cx, cz) с индексами [a, b] — в пересчёт. */
    private static void addNonPT(Ctx x, int cx, int cz, int a, int b, LongOpenHashSet k) {
        a = Math.max(0, a);
        b = Math.min(CUBES - 1, b);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            BitSet np = x.nonPTBits(cx + dx, cz + dz);
            for (int i = np.nextSetBit(a); i >= 0 && i <= b; i = np.nextSetBit(i + 1)) k.add(key(cx + dx, i + MIN, cz + dz));
        }
    }

    // ───────────── смена skyTop ─────────────

    /**
     * skyTop колонок столбца (cx, cz) менялся в [lo, hi]: посчитанные кубы 3×3 столбцов в этой полосе (±2 куба) —
     * в пересчёт; у PT-кубов свет сменился без расчёта — сравнить плоскую задачу до/после и пометить разницу.
     */
    private void rangePass(Ctx x, long ck, int[] r, LongOpenHashSet k) {
        int ccx = (int) (ck >> 32), ccz = (int) ck;
        // куб зависит от skyTop t, если t в его полосе [y0−16, y0+30] (PT/не PT, плоская задача): y0 в [lo−30, hi+16]
        int a = Math.max(0, ((r[0] - 30) >> 4) - MIN), b = Math.min(CUBES - 1, ((r[1] + 16) >> 4) - MIN);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            int cx = ccx + dx, cz = ccz + dz;
            BitSet np = x.nonPTBits(cx, cz);
            Column c = x.col(cx, cz);
            int top = x.maxTop(cx, cz);
            for (int i = np.nextSetBit(a); i >= 0 && i <= b; i = np.nextSetBit(i + 1)) {
                // шаг 13: посчитанный куб целиком выше skyTop своих колонок и до, и после — небо «всё 15» без расчёта,
                // свет блоков от skyTop не зависит
                if (c != null && c.store().done.get(i) && ((i + MIN) << 4) > top) continue;
                k.add(key(cx, i + MIN, cz));
            }
            if (c == null) continue;
            BitSet seen = x.ptSeen.computeIfAbsent(colKey(cx, cz), q -> new BitSet());
            for (int i = a; i <= b; ) {
                if (np.get(i)) { i++; continue; }
                int r0 = i;
                while (i <= b && !np.get(i)) i++;
                ptRun(x, c, r0, i - 1, seen);
            }
        }
    }

    /** PT-полоса [r0, r1] столбца c: свет сейчас (один на полосу) против света до правок — разница в рассылку. */
    private void ptRun(Ctx x, Column c, int r0, int r1, BitSet seen) {
        Store st = c.store();
        byte[] now = w.sky() ? x.pt2D(c.cx(), r0 + MIN, c.cz(), false) : null;
        for (int i = r0; i <= r1; i++) {
            if (seen.get(i)) continue;
            seen.set(i);
            if (!c.ready(i)) continue;
            if (st.done.get(i)) { drop(st, i, true); continue; }         // был посчитанным — стал PT
            if (now == null) continue;
            byte[] old = x.pt2D(c.cx(), i + MIN, c.cz(), true);
            if (!Arrays.equals(old, now)) mark(st, i);
        }
    }

    // ───────────── заливка ─────────────

    private static final int[] IN_DELTA = {1, -1, 16, -16, -256, 256};
    private static final int[] WRAP = {-15, 15, -240, 240, 3840, -3840};
    private static final int[][] DIR = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}, {0, 1, 0}};

    /** Клетка грани куба со стороны d (0 +x, 1 −x, 2 +z, 3 −z, 4 −y, 5 +y), a/b — координаты на грани. */
    static int face(int d, int a, int b) {
        int f = (d == 0 || d == 2 || d == 5) ? 15 : 0;
        return switch (d) {
            case 0, 1 -> a << 8 | b << 4 | f;
            case 2, 3 -> a << 8 | f << 4 | b;
            default -> f << 8 | a << 4 | b;
        };
    }

    /** Бит d — сдвиг по направлению d выводит клетку за куб. */
    private static final byte[] EDGE = new byte[4096];
    static {
        for (int q = 0; q < 4096; q++) {
            int x = q & 15, z = (q >> 4) & 15, y = q >> 8, e = 0;
            if (x == 15) e |= 1; if (x == 0) e |= 2; if (z == 15) e |= 4; if (z == 0) e |= 8; if (y == 0) e |= 16; if (y == 15) e |= 32;
            EDGE[q] = (byte) e;
        }
    }

    /** Очередь по уровням 0…15 (массивы переиспользуются между пересчётами). */
    private static final class Buckets {
        final int[][] a = new int[16][256];
        final int[] n = new int[16];

        void add(int l, int g) {
            int[] x = a[l];
            if (n[l] == x.length) a[l] = x = Arrays.copyOf(x, x.length * 2);
            x[n[l]++] = g;
        }

        void clear() { Arrays.fill(n, 0); }
    }

    /** Рабочие массивы заливки (под замком движка; растут по нужде). */
    private short[] wInf = new short[0];
    private byte[] wSky = new byte[0], wBlk = new byte[0];
    private final Buckets qs = new Buckets(), qb = new Buckets();

    /** Посчитать кубы s одной заливкой (неба и блоков) и положить в Store; изменившиеся — в рассылку. */
    private void compute(Ctx x, LongArrayList s) {
        if (s.isEmpty()) return;
        // только кубы загруженных столбцов (по тому же кэшу, что и ниже) и без повторов. Столбца нет — куб не считаем:
        // его посчитает COLUMN при загрузке столбца.
        LongArrayList ok = new LongArrayList(s.size());
        LongOpenHashSet uniq = new LongOpenHashSet(s.size());
        for (int j = 0; j < s.size(); j++) {
            long key = s.getLong(j);
            if (!uniq.add(key)) continue;
            if (x.col(kx(key), kz(key)) == null) { lostCubes++; continue; }
            ok.add(key);
        }
        s = ok;
        int n = s.size();
        if (n == 0) return;
        cubesComputed += n;
        boolean sun = w.sky();
        {
            // без неба свет — только от светящихся блоков: у кубов без них в 3×3×3 он «всё 0» (JFR s49: на плоской
            // карте sun:false ~80% планировщика уходило на распаковку палитр и рамки ради нулей). Сначала они — в
            // Store, потом заливка остальных видит их через рамку (уже посчитаны, свет 0 — как и было бы).
            // С небом так же под толщей: все skyTop 48×48 вокруг ≥ y0+30 — источники неба дальше 15 блоков от
            // любой клетки куба (небо 0, и F тоже 0).
            LongArrayList heavy = new LongArrayList(n);
            for (int j = 0; j < n; j++) {
                long key = s.getLong(j);
                if ((sun && !buried(x, key)) || emitNear(x, key)) heavy.add(key);
                else { zeroLight(x, key); cubesTrivial++; }
            }
            s = heavy;
            n = s.size();
            if (n == 0) return;
        }
        Long2IntOpenHashMap slot = new Long2IntOpenHashMap(n * 2);
        slot.defaultReturnValue(-1);
        for (int j = 0; j < n; j++) slot.put(s.getLong(j), j);
        int cells = n << 12;
        if (wInf.length < cells) { wInf = new short[cells]; wSky = new byte[cells]; wBlk = new byte[cells]; }
        short[] inf = wInf;
        byte[] sl = sun ? wSky : null, bl = wBlk;
        Arrays.fill(inf, 0, cells, (short) 0);
        Arrays.fill(bl, 0, cells, (byte) 0);
        if (sun) Arrays.fill(sl, 0, cells, (byte) 0);
        qs.clear();
        qb.clear();
        int[] nb = new int[n * 6];
        int[] tops = sun ? new int[n * 324] : null;                          // 18×18 колонок с рамкой (только небу)
        Column[] cols = new Column[n];
        int[][] nt = new int[9][];
        for (int j = 0; j < n; j++) {
            long key = s.getLong(j);
            int cx = kx(key), cy = ky(key), cz = kz(key), i = cy - MIN, base = j << 12;
            Column c = x.col(cx, cz);
            cols[j] = c;
            short[] ci = cubeInf(c, cx, cy, cz);
            if (ci != null) System.arraycopy(ci, 0, inf, base, 4096);
            for (int d = 0; d < 6; d++) nb[j * 6 + d] = slot.get(key(cx + DIR[d][0], cy + DIR[d][1], cz + DIR[d][2]));
            int to = j * 324;
            if (sun) {
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) nt[(dz + 1) * 3 + dx + 1] = x.tops(cx + dx, cz + dz);
                for (int dz = -1; dz <= 16; dz++) for (int dx = -1; dx <= 16; dx++)
                    tops[to + (dz + 1) * 18 + dx + 1] = nt[((dz + 16) / 16) * 3 + (dx + 16) / 16][(dz & 15) << 4 | (dx & 15)];
            }
            int y0 = cy << 4;
            if (sun) for (int z = 0; z < 16; z++) for (int xx = 0; xx < 16; xx++) {   // источники неба
                int t = tops[to + (z + 1) * 18 + xx + 1];
                int ntop = Math.max(Math.max(tops[to + (z + 1) * 18 + xx], tops[to + (z + 1) * 18 + xx + 2]),
                        Math.max(tops[to + z * 18 + xx + 1], tops[to + (z + 2) * 18 + xx + 1]));
                for (int ly = Math.max(0, t + 1 - y0); ly < 16; ly++) {
                    int g = base | ly << 8 | z << 4 | xx;
                    sl[g] = 15;
                    int y = y0 + ly;
                    if (y - 1 <= t || ntop >= y) qs.add(15, g);               // внутри «моря» 15 растекаться некуда
                }
            }
            for (int q = 0; q < 4096; q++) {                                 // источники блоков
                int e = (inf[base + q] >> 10) & 15;
                if (e == 0) continue;
                bl[base + q] = (byte) e;
                if (e >= 2) qb.add(e, base + q);
            }
        }
        byte[] fs = new byte[256], fb = new byte[256];
        short[] fi = new short[256];
        for (int j = 0; j < n; j++) {                                        // рамка: грани соседей вне множества
            long key = s.getLong(j);
            int cx = kx(key), cy = ky(key), cz = kz(key);
            for (int d = 0; d < 6; d++) {
                if (nb[j * 6 + d] >= 0) continue;
                frame(x, cx + DIR[d][0], cy + DIR[d][1], cz + DIR[d][2], d, fs, fb, fi);
                int base = j << 12;
                for (int a = 0; a < 16; a++) for (int b = 0; b < 16; b++) {
                    int f = a << 4 | b, g = base + face(d, a, b);
                    int qi = inf[g], lb = qi & 15;
                    if (lb >= 15) continue;
                    if (((fi[f] >> (4 + (d ^ 1))) & 1) != 0 || ((qi >> (4 + d)) & 1) != 0) continue;
                    int cost = Math.max(1, lb);
                    if (sun) {
                        int v = fs[f] - cost;
                        if (v > sl[g]) { sl[g] = (byte) v; if (v >= 2) qs.add(v, g); }
                    }
                    int v = fb[f] - cost;
                    if (v > bl[g]) { bl[g] = (byte) v; if (v >= 2) qb.add(v, g); }
                }
            }
        }
        if (sun) bfs(sl, inf, nb, qs);
        bfs(bl, inf, nb, qb);
        int[] fy = new int[256];
        for (int j = 0; j < n; j++) {                                        // в Store, сравнить с прежним
            long key = s.getLong(j);
            int cy = ky(key), i = cy - MIN, base = j << 12, y0 = cy << 4, to = j * 324;
            byte[] ns = null, nbk = null;
            if (sun) {
                for (int col = 0; col < 256; col++)                          // F: с какого слоя колонка — источник
                    fy[col] = Math.max(0, Math.min(16, tops[to + ((col >> 4) + 1) * 18 + (col & 15) + 1] + 1 - y0));
                boolean f = true;
                for (int q = 0; q < 4096; q++)
                    if (sl[base + q] != ((q >> 8) >= fy[q & 255] ? 15 : 0)) { f = false; break; }
                if (!f) ns = pack(sl, base);
            }
            for (int q = 0; q < 4096; q++) if (bl[base + q] != 0) { nbk = pack(bl, base); break; }
            Store st = cols[j].store();
            byte[] os = st.sky.get(i), ob = st.blk.get(i);
            boolean ch = !st.done.get(i) || !Arrays.equals(os, ns) || !Arrays.equals(ob, nbk)
                    || (sun && os == null && ns == null && x.fChanged(kx(key), kz(key), y0));
            if (ns != null) st.sky.put(i, ns); else st.sky.remove(i);
            if (nbk != null) st.blk.put(i, nbk); else st.blk.remove(i);
            st.done.set(i);
            if (ch) mark(st, i);
        }
    }

    /** Растекание по корзинам уровней 15…2 (каждая клетка — один раз с наибольшим уровнем). */
    private static void bfs(byte[] l, short[] inf, int[] nb, Buckets q) {
        for (int lvl = 15; lvl >= 2; lvl--) {
            int[] b = q.a[lvl];                                              // в эту корзину не добавляют: уровень ниже
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

    /**
     * Грань соседа (ncx, ncy, ncz), обращённая к нам (мы смотрим в сторону d): свет неба, блоков и сведения о блоке
     * на 256 клетках (порядок a<<4|b как у {@link #face}). Посчитанный — из Store, PT — плоская задача, прочие — F.
     */
    private void frame(Ctx x, int ncx, int ncy, int ncz, int d, byte[] fs, byte[] fb, short[] fi) {
        int ni = ncy - MIN;
        Column nc = ni >= 0 && ni < CUBES ? x.col(ncx, ncz) : null;
        boolean ready = nc != null && nc.ready(ni);
        byte[] ss = null, sb = null, two = null;
        short[] ci = ready ? cubeInf(nc, ncx, ncy, ncz) : null;
        boolean stored = false;
        if (ready) {
            Store st = nc.store();
            if (st.done.get(ni)) { stored = true; ss = st.sky.get(ni); sb = st.blk.get(ni); }
            else {
                if (x.nonPT(ncx, ncy, ncz)) { frameMiss++; missed.add(key(ncx, ncy, ncz)); }   // не посчитан, хотя должен — досчитать в след. process
                if (w.sky()) two = x.pt2D(ncx, ncy, ncz, false);
            }
        }
        int y0 = ncy << 4;
        boolean sky = w.sky();
        for (int a = 0; a < 16; a++) for (int b = 0; b < 16; b++) {
            int f = a << 4 | b, o = face(d ^ 1, a, b);
            int ox = o & 15, oz = (o >> 4) & 15, oy = o >> 8;
            int v;
            if (!sky) v = 0;                                                 // без неба fs не читают (JFR s49: topAt ~530 выборок впустую)
            else if (stored && ss != null) v = nib(ss, o);
            else if (two != null) v = two[oz << 4 | ox];
            else v = y0 + oy > (nc != null ? nc.skyTop(oz << 4 | ox) : x.topAt(ncx * 16 + ox, ncz * 16 + oz)) ? 15 : 0;
            fs[f] = (byte) v;
            fb[f] = (byte) (stored && sb != null ? nib(sb, o) : 0);
            fi[f] = ci != null ? ci[o] : 0;
        }
    }

    // ───────────── выдача игрокам ─────────────

    private static final byte[] FULL = new byte[2048], ZERO = new byte[2048];
    static { Arrays.fill(FULL, (byte) 0xFF); }

    synchronized LightData data(Store st, List<Integer> idx, boolean explicit) {
        safeProcess();
        Ctx x = new Ctx();
        Column c = st.col;
        boolean sun = w.sky();
        BitSet sky = new BitSet(), blk = new BitSet(), es = new BitSet(), eb = new BitSet();
        List<byte[]> sa = new ArrayList<>(), ba = new ArrayList<>();
        List<Integer> sorted = new ArrayList<>(idx);
        sorted.sort(null);
        for (int i : sorted) {
            if (i < 0 || i >= CUBES) continue;
            if (sun) {
                byte[] a = skyArray(x, c, i);
                if (explicit || a != FULL || c.maybeBlocks(i)) { sky.set(i + 1); sa.add(a); }   // пустой «всё 15» — клиент и так 15
            }
            byte[] b = st.done.get(i) ? st.blk.get(i) : null;
            if (b != null) { blk.set(i + 1); ba.add(b); }
            else if (explicit) eb.set(i + 1);
        }
        if (sky.isEmpty() && blk.isEmpty() && eb.isEmpty()) return null;
        return new LightData(sky, blk, es, eb, sa, ba);
    }

    /** Небо куба i массивом (FULL/ZERO — общие, не менять). */
    private byte[] skyArray(Ctx x, Column c, int i) {
        Store st = c.store();
        int cy = i + MIN, y0 = cy << 4;
        if (st.done.get(i)) {
            byte[] a = st.sky.get(i);
            if (a != null) return a;
        } else if (c.ready(i)) {
            byte[] two = x.pt2D(c.cx(), cy, c.cz(), false);
            if (two == x.full2D) return FULL;
            if (two == x.zero2D) return ZERO;
            byte[] a = new byte[2048];
            for (int q = 0; q < 4096; q++) setNib(a, q, two[q & 255]);
            return a;
        }
        boolean all = true, none = true;                                     // по формуле F
        for (int col = 0; col < 256; col++) {
            int t = c.skyTop(col);
            if (t >= y0) all = false;
            if (t < y0 + 15) none = false;
        }
        if (all) return FULL;
        if (none) return ZERO;
        byte[] a = new byte[2048];
        for (int q = 0; q < 4096; q++) if (y0 + (q >> 8) > c.skyTop(q & 255)) setNib(a, q, 15);
        return a;
    }

    /** Уровень света в точке (для тестов и /hocubes): sky — небо, иначе блоки. Столбец не загружен — F. */
    synchronized int level(int x, int y, int z, boolean sky) {
        process();
        byte[] a = cubeArray(new Ctx(), x >> 4, y >> 4, z >> 4, sky);
        return a == null ? 0 : nib(a, (y & 15) << 8 | (z & 15) << 4 | (x & 15));
    }

    /** Свет коробки (тесты): out[((y−y0)·sz + z−z0)·sx + x−x0]. */
    synchronized byte[] box(int x0, int y0, int z0, int sx, int sy, int sz, boolean sky) {
        process();
        Ctx c = new Ctx();
        byte[] out = new byte[sx * sy * sz];
        for (int cx = x0 >> 4; cx <= (x0 + sx - 1) >> 4; cx++)
            for (int cz = z0 >> 4; cz <= (z0 + sz - 1) >> 4; cz++)
                for (int cy = y0 >> 4; cy <= (y0 + sy - 1) >> 4; cy++) {
                    byte[] a = cubeArray(c, cx, cy, cz, sky);
                    if (a == null) continue;
                    for (int y = Math.max(y0, cy << 4); y < Math.min(y0 + sy, (cy + 1) << 4); y++)
                        for (int z = Math.max(z0, cz << 4); z < Math.min(z0 + sz, (cz + 1) << 4); z++)
                            for (int x = Math.max(x0, cx << 4); x < Math.min(x0 + sx, (cx + 1) << 4); x++)
                                out[((y - y0) * sz + z - z0) * sx + x - x0] = (byte) nib(a, (y & 15) << 8 | (z & 15) << 4 | (x & 15));
                }
        return out;
    }

    /** Массив света куба (null — всё 0): то же, что увидит игрок; незагруженный — по F. */
    private byte[] cubeArray(Ctx c, int cx, int cy, int cz, boolean sky) {
        int i = cy - MIN;
        if (i < 0 || i >= CUBES) return null;
        Column col = c.col(cx, cz);
        if (!sky) {
            if (col == null || !col.store().done.get(i)) return null;
            return col.store().blk.get(i);
        }
        if (!w.sky()) return null;
        if (col != null) return skyArray(c, col, i);
        byte[] a = new byte[2048];
        for (int q = 0; q < 4096; q++) if ((cy << 4) + (q >> 8) > c.topAt(cx * 16 + (q & 15), cz * 16 + (q >> 4 & 15))) setNib(a, q, 15);
        return a;
    }

    // ───────────── кэши одного пересчёта ─────────────

    /** Кэши на один пересчёт/выдачу (мир между ними может меняться). */
    private final class Ctx {
        final Long2ObjectOpenHashMap<Column> cols = new Long2ObjectOpenHashMap<>();
        final LongOpenHashSet noCol = new LongOpenHashSet();
        final Long2ObjectOpenHashMap<int[]> tops = new Long2ObjectOpenHashMap<>();
        final Long2ObjectOpenHashMap<BitSet> occ = new Long2ObjectOpenHashMap<>(), nonPT = new Long2ObjectOpenHashMap<>();
        final Long2ObjectOpenHashMap<Grid> grids = new Long2ObjectOpenHashMap<>(), oldGrids = new Long2ObjectOpenHashMap<>();
        /** skyTop колонок до правок этого пересчёта: столбец → (клетка → старое значение). */
        final Long2ObjectOpenHashMap<Int2IntOpenHashMap> old = new Long2ObjectOpenHashMap<>();
        /** Полосы смены skyTop по столбцам: [lo, hi]. */
        final Long2ObjectOpenHashMap<int[]> ranges = new Long2ObjectOpenHashMap<>();
        final Long2ObjectOpenHashMap<BitSet> ptSeen = new Long2ObjectOpenHashMap<>();
        final byte[] full2D = new byte[256], zero2D = new byte[256];

        Ctx() { Arrays.fill(full2D, (byte) 15); }

        Column col(int cx, int cz) {
            long k = colKey(cx, cz);
            Column c = cols.get(k);
            if (c != null || noCol.contains(k)) return c;
            c = w.column(cx, cz);
            if (c == null) noCol.add(k); else cols.put(k, c);
            return c;
        }

        int[] tops(int cx, int cz) {
            long k = colKey(cx, cz);
            int[] t = tops.get(k);
            if (t != null) return t;
            Column c = col(cx, cz);
            if (c == null) t = genTops(cx, cz);
            else { t = new int[256]; for (int i = 0; i < 256; i++) t[i] = c.skyTop(i); }
            tops.put(k, t);
            return t;
        }

        int topAt(int x, int z) { return tops(x >> 4, z >> 4)[(z & 15) << 4 | (x & 15)]; }

        /** Наибольший skyTop колонок столбца — сейчас и до правок этого пересчёта. */
        int maxTop(int cx, int cz) {
            int m = Integer.MIN_VALUE;
            for (int t : tops(cx, cz)) m = Math.max(m, t);
            var o = old.get(colKey(cx, cz));
            if (o != null) for (int t : o.values()) m = Math.max(m, t);
            return m;
        }

        int oldTopAt(int x, int z) {
            var m = old.get(colKey(x >> 4, z >> 4));
            int cell = (z & 15) << 4 | (x & 15);
            return m != null && m.containsKey(cell) ? m.get(cell) : topAt(x, z);
        }

        void recordOld(int x, int z, int t) {
            var m = old.computeIfAbsent(colKey(x >> 4, z >> 4), q -> new Int2IntOpenHashMap());
            m.putIfAbsent((z & 15) << 4 | (x & 15), t);                       // первое значение за пересчёт
        }

        void range(int cx, int cz, int lo, int hi) {
            int[] r = ranges.get(colKey(cx, cz));
            if (r == null) ranges.put(colKey(cx, cz), new int[]{lo, hi});
            else { r[0] = Math.min(r[0], lo); r[1] = Math.max(r[1], hi); }
        }

        /** F в кубе (столбец, низ y0) сменилась из-за правки skyTop. */
        boolean fChanged(int cx, int cz, int y0) {
            var m = old.get(colKey(cx, cz));
            if (m == null) return false;
            int[] t = tops(cx, cz);
            for (var e : m.int2IntEntrySet()) {
                int a = Math.min(e.getIntValue(), t[e.getIntKey()]), b = Math.max(e.getIntValue(), t[e.getIntKey()]);
                if (a < y0 + 15 && b >= y0) return true;
            }
            return false;
        }

        BitSet occ(int cx, int cz) {
            long k = colKey(cx, cz);
            BitSet b = occ.get(k);
            if (b != null) return b;
            b = new BitSet(CUBES);
            Column c = col(cx, cz);
            for (int i = 0; i < CUBES; i++) if (c != null ? c.maybeBlocks(i) : w.genBlocks(cx, i + MIN, cz)) b.set(i);
            occ.put(k, b);
            return b;
        }

        /** Не-PT кубы столбца (битами по индексу): рядом блоки или skyTop в полосе куба. */
        BitSet nonPTBits(int cx, int cz) {
            long k = colKey(cx, cz);
            BitSet r = nonPT.get(k);
            if (r != null) return r;
            r = new BitSet(CUBES);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                BitSet o = occ(cx + dx, cz + dz);
                for (int i = o.nextSetBit(0); i >= 0; i = o.nextSetBit(i + 1)) r.set(Math.max(0, i - 1), Math.min(CUBES, i + 2));
            }
            for (int t : grid(cx, cz, false).uniq) {
                int a = Math.floorDiv(t - 15, 16) - MIN, b = Math.floorDiv(t + 16, 16) - MIN;
                a = Math.max(0, a);
                b = Math.min(CUBES - 1, b);
                if (a <= b) r.set(a, b + 1);
            }
            nonPT.put(k, r);
            return r;
        }

        /** Куб не PT (точечная проверка: 27 кубов и полоса высот). */
        boolean nonPT(int cx, int cy, int cz) {
            BitSet cached = nonPT.get(colKey(cx, cz));
            int i = cy - MIN;
            if (cached != null) return cached.get(i);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                Column c = col(cx + dx, cz + dz);
                BitSet o = c == null ? occ(cx + dx, cz + dz) : null;
                for (int j = Math.max(0, i - 1); j <= Math.min(CUBES - 1, i + 1); j++)
                    if (c != null ? c.maybeBlocks(j) : o.get(j)) return true;
            }
            Grid g = grid(cx, cz, false);
            int lo = (cy << 4) - 16, hi = (cy << 4) + 30;
            if (g.max < lo || g.min > hi) return false;
            int p = Arrays.binarySearch(g.uniq, lo);
            if (p < 0) p = -p - 1;
            return p < g.uniq.length && g.uniq[p] <= hi;
        }

        /** skyTop колонок 48×48 вокруг столбца (old — до правок пересчёта). */
        Grid grid(int cx, int cz, boolean before) {
            long k = colKey(cx, cz);
            var map = before ? oldGrids : grids;
            Grid g = map.get(k);
            if (g != null) return g;
            int[] a = new int[48 * 48];
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {     // 9 столбцов целиком, без поиска на клетку
                int[] t = tops(cx + dx, cz + dz);
                var m = before ? old.get(colKey(cx + dx, cz + dz)) : null;
                for (int z = 0; z < 16; z++) {
                    int row = ((dz + 1) * 16 + z) * 48 + (dx + 1) * 16;
                    System.arraycopy(t, z << 4, a, row, 16);
                    if (m != null) for (int x = 0; x < 16; x++) { int c = z << 4 | x; if (m.containsKey(c)) a[row + x] = m.get(c); }
                }
            }
            g = new Grid(a);
            map.put(k, g);
            return g;
        }

        /**
         * Свет PT-куба: плоская задача 48×48 (источник — колонка с skyTop ниже низа куба −16), 16×16 середины.
         * Общие full2D/zero2D — «всё 15» / «всё 0».
         */
        byte[] pt2D(int cx, int cy, int cz, boolean before) {
            Grid g = grid(cx, cz, before);
            int lim = (cy << 4) - 16;
            if (g.max < lim) return full2D;
            if (g.min >= lim) return zero2D;
            int rank = Arrays.binarySearch(g.uniq, lim);                    // сколько различных высот ниже lim —
            if (rank < 0) rank = -rank - 1;                                  // от него одного зависит маска источников
            byte[] out = g.cache.get(rank);
            if (out != null) return out;
            int[] d = new int[48 * 48];
            for (int q = 0; q < d.length; q++) d[q] = g.a[q] < lim ? 0 : 64;
            for (int z = 0; z < 48; z++) for (int x = 0; x < 48; x++) {
                int q = z * 48 + x;
                if (x > 0) d[q] = Math.min(d[q], d[q - 1] + 1);
                if (z > 0) d[q] = Math.min(d[q], d[q - 48] + 1);
            }
            for (int z = 47; z >= 0; z--) for (int x = 47; x >= 0; x--) {
                int q = z * 48 + x;
                if (x < 47) d[q] = Math.min(d[q], d[q + 1] + 1);
                if (z < 47) d[q] = Math.min(d[q], d[q + 48] + 1);
            }
            out = new byte[256];
            boolean f = true, zr = true;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int v = Math.max(0, 15 - d[(z + 16) * 48 + x + 16]);
                out[z << 4 | x] = (byte) v;
                if (v != 15) f = false;
                if (v != 0) zr = false;
            }
            if (f) out = full2D; else if (zr) out = zero2D;
            g.cache.put(rank, out);
            return out;
        }
    }

    /** Высоты 48×48: значения, мин/макс и различные значения по возрастанию. */
    private static final class Grid {
        final int[] a, uniq;
        final int min, max;
        /** Плоская задача по числу высот ниже порога. */
        final Int2ObjectOpenHashMap<byte[]> cache = new Int2ObjectOpenHashMap<>();

        Grid(int[] a) {
            this.a = a;
            int[] s = a.clone();
            Arrays.sort(s);
            min = s[0];
            max = s[s.length - 1];
            int n = 0;
            for (int i = 0; i < s.length; i++) if (i == 0 || s[i] != s[i - 1]) s[n++] = s[i];
            uniq = Arrays.copyOf(s, n);
        }
    }

    private int[] genTops(int cx, int cz) {
        long k = colKey(cx, cz);
        int[] t = genTops.get(k);
        if (t != null) return t;
        t = new int[256];
        for (int c = 0; c < 256; c++) t[c] = w.genTop(cx * 16 + (c & 15), cz * 16 + (c >> 4));
        if (genTops.size() > 8192) genTops.clear();
        genTops.put(k, t);
        return t;
    }

    // ───────────── сведения о блоках ─────────────

    /**
     * Сведения о блоках недавних кубов (LRU): палитру куба при каждой правке рядом читать дорого (~30 мкс на куб).
     * Сбрасывается событием куба (правка/загрузка/выгрузка) и загрузкой столбца — до чтения палитры в том же пересчёте.
     */
    private static final int INF_CACHE = 768;                               // ≤ 6 МБ
    private final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<short[]> infCache = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
    private static final short[] NO_BLOCKS = new short[0];
    private final int[] idsBuf = new int[4096];

    /** Сведения о блоках READY-куба (null — пуст). */
    private short[] cubeInf(Column c, int cx, int cy, int cz) {
        long k = key(cx, cy, cz);
        short[] a = infCache.getAndMoveToLast(k);
        if (a != null) return a == NO_BLOCKS ? null : a;
        if (!c.ids(cy - MIN, idsBuf)) a = NO_BLOCKS;
        else {
            a = new short[4096];
            short[] tab = INF;                                               // таблица один раз на куб
            for (int q = 0; q < 4096; q++) {
                int id = idsBuf[q];
                a[q] = (short) (id <= 0 ? 0 : id < tab.length && tab[id] != 0 ? tab[id] & 0x3FFF : inf(id));
            }
        }
        infCache.putAndMoveToLast(k, a);
        if (infCache.size() > INF_CACHE) infCache.removeFirst();
        return a == NO_BLOCKS ? null : a;
    }

    private static volatile short[] INF = new short[1 << 15];

    /**
     * lightBlock (биты 0–3), грани формы по направлениям 0…5 (биты 4–9; у полных кубов форма не мешает — как у игры,
     * useShapeForLightOcclusion), lightEmission (биты 10–13).
     */
    static int inf(int id) {
        if (id <= 0) return 0;
        short[] a = INF;
        if (id < a.length) {
            short v = a[id];
            if (v != 0) return v & 0x3FFF;
        }
        int hi = HeightIndex.info(id);
        int v = HeightIndex.lightBlock(hi);
        BlockFace[] f = {BlockFace.EAST, BlockFace.WEST, BlockFace.SOUTH, BlockFace.NORTH, BlockFace.BOTTOM, BlockFace.TOP};
        int faces = 0;
        for (int d = 0; d < 6; d++) if (HeightIndex.full(hi, f[d])) faces |= 1 << d;
        if (faces == 63) faces = 0;
        v |= faces << 4;
        Block b = Block.fromStateId(id);
        int e = b == null ? 0 : Math.max(0, Math.min(15, b.registry().lightEmission()));
        v |= e << 10;
        if (id >= a.length) { a = Arrays.copyOf(a, Integer.highestOneBit(id) << 1); INF = a; }
        a[id] = (short) (v | 0x4000);                                         // гонка безвредна
        return v;
    }

    // ───────────── полубайты и ключи ─────────────

    static int nib(byte[] a, int q) { int b = a[q >> 1]; return (q & 1) == 0 ? b & 15 : (b >> 4) & 15; }

    static void setNib(byte[] a, int q, int v) {
        int b = q >> 1;
        a[b] = (q & 1) == 0 ? (byte) ((a[b] & 0xF0) | (v & 15)) : (byte) ((a[b] & 0x0F) | ((v & 15) << 4));
    }

    private static byte[] pack(byte[] l, int base) {
        byte[] a = new byte[2048];
        for (int q = 0; q < 4096; q += 2) a[q >> 1] = (byte) ((l[base + q] & 15) | (l[base + q + 1] & 15) << 4);
        return a;
    }

    static long key(int cx, int cy, int cz) {
        return ((long) (cx & 0xFFFFFF) << 40) | ((long) (cz & 0xFFFFFF) << 16) | ((cy - MIN) & 0xFFFF);
    }

    static int kx(long k) { return (int) (k >> 40); }
    static int kz(long k) { return (int) ((k << 24) >> 40); }
    static int ky(long k) { return (int) (k & 0xFFFF) + MIN; }

    static long colKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    // ───────────── мир сервера ─────────────

    private record InstWorld(Instance inst) implements World {
        @Override public boolean sky() { return inst.getCachedDimensionType().hasSkylight(); }

        @Override public Column column(int cx, int cz) {
            return inst.getChunk(cx, cz) instanceof CubeColumn c && c.isLoaded() ? c.lightStore().col : null;
        }

        @Override public int genTop(int x, int z) {
            return ink.hoka.odyssey.server.tall.gen.CubeGens.of(inst).highest(x, z, TallWorld.MIN_Y, TallWorld.MIN_Y + TallWorld.HEIGHT - 1, HeightIndex.Kind.SKY);
        }

        @Override public boolean genBlocks(int cx, int cy, int cz) { return ink.hoka.odyssey.server.tall.gen.CubeGens.of(inst).hasBlocks(cx, cy, cz); }
    }
}
