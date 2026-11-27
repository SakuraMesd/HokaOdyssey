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

    /** Высота карты в кубах: низ и число (своя у каждой карты — тип измерения). */
    final int MIN, CUBES;

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

        /**
         * op 16 (0.54): наибольший skyTop колонок столбца (старшие 32 бита) и индекс верхнего непустого куба в памяти
         * (младшие, −1 — нет). Не в памяти — не в счёт: свет сервера их блоки не видит, пока куб не загрузится (LOAD →
         * пересчёт → явный свет изменившимся). CubeColumn кэширует до события столбца.
         */
        default long floorInfo() { return computeFloorInfo(this); }
        /** Кубов в столбце (высота карты); по умолчанию — как у старых карт. */
        default int cubes() { return TallWorld.HEIGHT >> 4; }
    }

    /** floorInfo без кэша: перебор 256 колонок и кубов сверху вниз до первого непустого в памяти. */
    static long computeFloorInfo(Column c) {
        int top = Integer.MIN_VALUE;
        for (int k = 0; k < 256; k++) top = Math.max(top, c.skyTop(k));
        int high = -1;
        for (int i = c.cubes() - 1; i >= 0; i--) if (c.ready(i) && c.maybeBlocks(i)) { high = i; break; }
        return (long) top << 32 | (high & 0xFFFFFFFFL);
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
        /** Высота мира (по умолчанию — как у старых карт). */
        default TallWorld.Dims dims() { return TallWorld.LEGACY; }
    }

    /**
     * Свет посчитанного куба (неизменяемый): массивы-полубайты 2048 Б; null — неба по формуле F / блоков 0.
     * Публикуется целиком — читатели (тик, рассылка) берут без замков и видят либо старый, либо новый.
     */
    record Rec(byte[] sky, byte[] blk) {}

    /** Посчитан, оба массива по умолчанию (F / 0) — общий. */
    static final Rec PLAIN = new Rec(null, null);

    /**
     * Свет кубов одного столбца. recs пишет только движок (под его замком), читают все без замков (этап 2: тик берёт
     * опубликованное, пока поток света считает). changed/emit* — только под замком движка.
     */
    static final class Store {
        final Column col;
        /** Столбец сервера (для рассылки) или null (тест). */
        final CubeColumn owner;
        /** Посчитанные кубы (null — не посчитан: PT/F) — страницами по 64, страница появляется при первой записи. */
        private final java.util.concurrent.atomic.AtomicReferenceArray<java.util.concurrent.atomic.AtomicReferenceArray<Rec>> pages =
                new java.util.concurrent.atomic.AtomicReferenceArray<>((4096 + 63) >> 6);   // до 4094 кубов (Y ±32752)
        /** Свет куба изменился с прошлой рассылки. */
        final BitSet changed = new BitSet();
        /** Кэш {@link Column#mayEmit}: известно / «светящиеся могут быть». Сбрасывают события куба и столбца. */
        final BitSet emitKnown = new BitSet(), emitYes = new BitSet();
        private volatile int arrays;

        Store(Column col, CubeColumn owner) { this.col = col; this.owner = owner; }

        /** Опубликованный свет куба i или null (не посчитан). Любой поток. */
        Rec rec(int i) {
            if (i < 0 || i >= col.cubes()) return null;
            var p = pages.get(i >> 6);
            return p == null ? null : p.get(i & 63);
        }

        boolean done(int i) { return rec(i) != null; }

        byte[] sky(int i) { Rec r = rec(i); return r == null ? null : r.sky; }

        byte[] blk(int i) { Rec r = rec(i); return r == null ? null : r.blk; }

        /** Опубликовать (null — «не посчитан»). Один писатель — движок под замком. */
        void put(int i, Rec r) {
            var p = pages.get(i >> 6);
            if (p == null) {
                if (r == null) return;
                p = new java.util.concurrent.atomic.AtomicReferenceArray<>(64);
                pages.set(i >> 6, p);
            }
            Rec o = p.getAndSet(i & 63, r);
            int d = cnt(r) - cnt(o);
            if (d != 0) arrays += d;
        }

        private static int cnt(Rec r) { return r == null ? 0 : (r.sky != null ? 1 : 0) + (r.blk != null ? 1 : 0); }

        /** Сколько кубов с массивами (для /hocubes stats). */
        int arrays() { return arrays; }
    }

    // ───────────── движки по мирам ─────────────

    private static final Map<Instance, CubeLight> ENGINES = new ConcurrentHashMap<>();
    private static final AtomicBoolean FLUSH = new AtomicBoolean();

    // мир уже снят (поздние события выгрузки) — без потока: иначе на каждое событие новый поток, который сразу умрёт
    static CubeLight of(Instance inst) { return ENGINES.computeIfAbsent(inst, i -> new CubeLight(new InstWorld(i), ASYNC && i.isRegistered())); }

    /** Тест: движок мира с потоком света независимо от hoka.light.async (до загрузки столбцов). */
    static CubeLight asyncFor(Instance inst) { return ENGINES.computeIfAbsent(inst, i -> new CubeLight(new InstWorld(i), true)); }

    /** Столбец считает свет кубами (кубовый протокол + CubeColumn); иначе — ColumnLight, как раньше. */
    static boolean active(TallColumn c) { return c instanceof CubeColumn && TallWorld.isCubes(c.getInstance()); }

    /** Блок сменился (x, y, z — мировые; t0/t1 — skyTop колонки до и после). Любой поток. */
    static void edit(CubeColumn c, int x, int y, int z, int t0, int t1) { c.floorDirty(); of(c.getInstance()).editAt(x, y, z, t0, t1); }

    /** Куб загрузился; stale — его отметка высот не совпала с HeightIndex (шаг 13: для stats — куб считается в любом случае). */
    static void cubeLoaded(CubeColumn c, int i, boolean stale) {
        c.floorDirty();
        CubeLight e = of(c.getInstance());
        if (stale) e.markStale++;
        e.cubeLoadedAt(c.getChunkX(), i + e.MIN, c.getChunkZ());
    }

    /** Шаг 13: skyTop колонок столбца уточнился при загрузке куба (HeightIndex.fix): old/now по клеткам z<<4|x. */
    static void skyFixed(CubeColumn c, int[] old, int[] now) { c.floorDirty(); of(c.getInstance()).skyFixedAt(c.getChunkX(), c.getChunkZ(), old, now); }

    static void cubeUnloaded(CubeColumn c, int i) { c.floorDirty(); CubeLight e = of(c.getInstance()); e.cubeUnloadedAt(c.getChunkX(), i + e.MIN, c.getChunkZ()); }

    static void columnLoaded(CubeColumn c) { c.floorDirty(); of(c.getInstance()).columnLoadedAt(c.getChunkX(), c.getChunkZ()); }

    /** Столбец лёг в инстанс (CubeColumn.onLoad, после cacheChunk). */
    static void columnCached(CubeColumn c) { of(c.getInstance()).columnCachedAt(c.getChunkX(), c.getChunkZ()); }

    /** Столбец выгружен: не ждать его в pendingCols (мир уже без движка — ничего). */
    static void columnGone(CubeColumn c) {
        CubeLight l = ENGINES.get(c.getInstance());
        if (l != null) l.columnGoneAt(c.getChunkX(), c.getChunkZ());
    }

    // ───────────── op 16 SKY_FLOOR (0.54): небо по умолчанию ─────────────

    /** Нижняя секция Y «небо по умолчанию» столбца c (см. {@link #floorAt}); MAX — правила нет. Любой поток, без замка. */
    static int skyFloor(CubeColumn c) { return of(c.getInstance()).floorAt(c.getChunkX(), c.getChunkZ()); }

    /**
     * Секция Y, начиная с которой каждый куб в памяти столбца (cx, cz) по свету сервера — небо всё 15, блоки 0.
     * С запасом: небо — выше skyTop 3×3 столбцов на 2+ куба (источник 15 во всех клетках; плоская задача PT-куба тоже
     * 15: g.max &lt; низ−16); блоки — на 2 куба выше верхнего непустого 3×3 (свет блоков не дальше 14 клеток, т.е. не
     * дальше соседнего куба). Незагруженный соседний столбец — не в счёт (свет своих клеток от него не зависит);
     * свой не загружен — MAX. Смена света ниже/выше — явной записью (relightCubes), правило не нужно поднимать.
     */
    int floorAt(int cx, int cz) {
        if (!w.sky() || w.column(cx, cz) == null) return Integer.MAX_VALUE;
        int top = Integer.MIN_VALUE, high = -1;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Column n = w.column(cx + dx, cz + dz);
            if (n == null) continue;
            long f = n.floorInfo();
            top = Math.max(top, (int) (f >> 32));
            high = Math.max(high, (int) f);
        }
        int a = Math.floorDiv(top + 31, 16) + 1;
        int b = high < 0 ? Integer.MIN_VALUE : high + MIN + 2;
        return Math.max(a, b);
    }

    /**
     * Наибольший skyTop 3×3 столбцов вокруг (cx, cz) из кэша floorInfo; хоть один столбец не загружен — MIN_VALUE
     * (тогда быстрый путь {@link #skyArray} не годится: у незагруженного высоты генератора).
     */
    private int maxTop3x3(int cx, int cz) {
        int top = Integer.MIN_VALUE;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Column n = w.column(cx + dx, cz + dz);
            if (n == null) return Integer.MIN_VALUE;
            top = Math.max(top, (int) (n.floorInfo() >> 32));
        }
        return top;
    }

    /** Свет кубов idx (по возрастанию) столбца для игрока; explicit — «всё 15» массивом и «блоков нет» битом. */
    static LightData data(CubeColumn c, List<Integer> idx, boolean explicit) {
        return of(c.getInstance()).data(c.lightStore(), idx, explicit);
    }

    /** У куба i столбца есть свет блоков (не «всё 0»): сначала пересчёт по накопленным событиям. */
    static boolean blockLit(CubeColumn c, int i) { return of(c.getInstance()).blockLit(c.lightStore(), i); }

    boolean blockLit(Store st, int i) {
        if (!async) synchronized (this) { safeProcess(); }
        return st.blk(i) != null;
    }

    /** Раз за тик: пересчитать по событиям и разослать изменившиеся кубы (поток света — только рассылка готового). */
    static void flushAll() {
        FLUSH.set(false);
        for (var e : ENGINES.entrySet()) {
            if (!e.getKey().isRegistered()) { e.getValue().stop(); ENGINES.remove(e.getKey()); continue; }
            CubeLight l = e.getValue();
            if (l.async) l.drainOut(OUT_BUDGET_NS);
            else l.flush();
        }
    }

    /**
     * Конец тика, в котором игрок поставил/сломал блок: правки — сразу, рассылка в том же тике. Поток света берёт
     * правки первыми; тик ждёт его не дольше EDIT_WAIT_NS (не успел — тень придёт следующим тиком).
     */
    static void flushEdits() {
        FLUSH_END.set(false);
        for (var e : ENGINES.entrySet()) {
            if (!e.getKey().isRegistered()) continue;
            CubeLight l = e.getValue();
            if (l.async) {
                long want = l.editsPosted.get(), t0 = System.nanoTime();
                while (l.editsDone.get() < want && System.nanoTime() - t0 < EDIT_WAIT_NS) java.util.concurrent.locks.LockSupport.parkNanos(50_000);
                l.drainOut(OUT_BUDGET_NS);
            } else if (!l.edits.isEmpty()) l.flush();
        }
    }

    /** Ожидание правок в конце тика и бюджет рассылки готового света на тик, нс. */
    static final long EDIT_WAIT_NS = 2_000_000, OUT_BUDGET_NS = 3_000_000;

    /** Для TAB: время прошлого замера загрузки потока света и его счётчик. */
    private static long busyAt, busyWas;

    /**
     * Очереди света всех миров (для вкладки TAB): [правки, прочие, из них LOAD, UNLOAD, COLUMN, SKY, столбцов ждут
     * кэша, столбцов к рассылке, загрузка потока света ‰ с прошлого вызова].
     */
    static synchronized int[] queued() {
        int[] r = new int[9];
        long busy = 0;
        for (CubeLight l : ENGINES.values()) {
            r[0] += l.edits.size();
            for (int t = Ev.LOAD; t <= Ev.GONE; t++) r[1] += Math.max(0, l.qType.get(t));   // size() очереди — обход всех узлов
            for (int t = 0; t < 4; t++) r[2 + t] += Math.max(0, l.qType.get(Ev.LOAD + t));
            r[6] += l.pendingNow;
            r[7] += l.outbox.size();
            busy += l.busyNanos;
        }
        long now = System.nanoTime();
        if (busyAt != 0 && now > busyAt) r[8] = (int) Math.min(1000, (busy - busyWas) * 1000 / (now - busyAt));
        busyAt = now;
        busyWas = busy;
        return r;
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
        long editsFree = 0, slow = 0, busy = 0, resolved = 0, expired = 0, retried = 0, sentOut = 0, gridHit = 0, gridMiss = 0;
        int pend = 0, out = 0, ev = 0, ed = 0, threads = 0;
        int[] qt = new int[4];
        for (var e : ENGINES.values()) {
            editsFree += e.editsUnbudgeted; slow += e.slowBatches; busy += e.busyNanos;
            resolved += e.colsResolved; expired += e.colsExpired; retried += e.retried; sentOut += e.outSent;
            gridHit += e.gridHits; gridMiss += e.gridMisses;
            pend += e.pendingNow; out += e.outbox.size(); ed += e.edits.size();
            for (int t = Ev.LOAD; t <= Ev.GONE; t++) ev += Math.max(0, e.qType.get(t));
            for (int t = 0; t < 4; t++) qt[t] += Math.max(0, e.qType.get(Ev.LOAD + t));
            if (e.worker != null) threads++;
        }
        return "свет кубами: " + (threads > 0 ? "поток света (" + threads + " шт.), занят всего " + busy / 1_000_000 + " мс" : "в тике (hoka.light.async=false)")
                + "; очередь: правок " + ed + ", прочих " + ev + " (LOAD " + qt[0] + ", UNLOAD " + qt[1] + ", COLUMN " + qt[2] + ", SKY " + qt[3] + ")"
                + ", столбцов к рассылке " + out + ", разослано столбцов " + sentOut
                + "; пересчётов " + runs + ", кубов посчитано " + cubes + (trivial > 0 ? " (из них «всё 0» сразу " + trivial + ")" : "")
                + (runs > 0 ? String.format(", в среднем %.2f мс, последний %.2f мс, максимум %.2f мс", ns / 1e6 / runs, last / 1e6, max / 1e6) : "")
                + ", массивов света в памяти " + arrays + (miss > 0 ? ", промахов рамки " + miss : "")
                + (stale > 0 ? ", кубов с устаревшей отметкой высот " + stale : "")
                + ", COLUMN раньше кэша столбца " + deferred + " (дождались " + resolved + ", не дождались " + expired + ", ждут " + pend + ")"
                + (lost > 0 ? ", кубов без столбца " + lost : "")
                + (fails > 0 ? ", СБОЕВ пересчёта " + fails + " (событий повторено " + retried + ")" : "") + (cuts > 0 ? ", отложено по бюджету тика " + cuts : "")
                + ", правок вне бюджета " + editsFree + ", «всё 15» быстрым путём " + fastFull
                + ", сетка 48×48 из кэша " + gridHit + "/" + (gridHit + gridMiss)
                + (slow > 0 ? ", партий > " + SLOW_BATCH_MS + " мс " + slow : "");
    }

    // ───────────── движок ─────────────

    private record Ev(int type, int cx, int cy, int cz, int x, int y, int z, int t0, int t1, int[] old, int[] now, int tries) {
        /** CACHED — столбец лёг в инстанс (Chunk.onLoad, после cacheChunk); GONE — выгружен. */
        static final int EDIT = 0, LOAD = 1, UNLOAD = 2, COLUMN = 3, SKY = 4, CACHED = 5, GONE = 6;

        Ev(int type, int cx, int cy, int cz, int x, int y, int z, int t0, int t1, int[] old, int[] now) { this(type, cx, cy, cz, x, y, z, t0, t1, old, now, 0); }

        Ev(int type, int cx, int cy, int cz, int x, int z, int t0, int t1) { this(type, cx, cy, cz, x, cy << 4, z, t0, t1, null, null, 0); }

        /** То же событие на повтор после сбоя пересчёта. */
        Ev retry() { return new Ev(type, cx, cy, cz, x, y, z, t0, t1, old, now, tries + 1); }
    }

    private final World w;
    private final ConcurrentLinkedQueue<Ev> events = new ConcurrentLinkedQueue<>();
    /** Правки блоков (EDIT) — своя очередь: считаются первыми, в конце того же тика (S3), не ждут потоковых LOAD/COLUMN. */
    private final ConcurrentLinkedQueue<Ev> edits = new ConcurrentLinkedQueue<>();
    /** Событий в events по типам (TAB, stats); правки — edits.size(). */
    private final java.util.concurrent.atomic.AtomicIntegerArray qType = new java.util.concurrent.atomic.AtomicIntegerArray(8);
    /** События, взятые текущей партией: при сбое — повторить (не больше 2 раз), а не потерять. */
    private final ArrayList<Ev> taken = new ArrayList<>();
    private static final AtomicBoolean FLUSH_END = new AtomicBoolean();
    /** Хранилища с изменённым светом (ждут рассылки). Под замком. */
    private final Set<Store> touched = new HashSet<>();
    /** Высоты генератора по незагруженным столбцам (детерминированы — кэш между тиками). */
    private final Long2ObjectOpenHashMap<int[]> genTops = new Long2ObjectOpenHashMap<>();
    /** Отладка. */
    volatile long runs, cubesComputed, nanos, maxNanos, lastNanos, frameMiss, markStale;

    // ───────────── этап 2: поток света ─────────────

    /** Свет считает свой поток (сервер; выкл — -Dhoka.light.async=false), иначе вызывающий (тесты, старый путь в тике). */
    static final boolean ASYNC = !"false".equalsIgnoreCase(System.getProperty("hoka.light.async"));
    /**
     * Этап 2: движок (события, pendingCols, missed, infCache, genTops, Store.changed) — у потока света под замком
     * движка; результат — Store.put (читатели без замков), затем столбец в outbox; тик только рассылает (drainOut).
     * Правило: каждое изменение мира шлёт событие ПОСЛЕ того, как стало видно, пересчёт идемпотентен — промежуточное
     * состояние, прочитанное потоком, исправит следующее событие.
     */
    final boolean async;
    volatile Thread worker;
    private volatile boolean stopped;
    /** Готовый свет к рассылке: хранилище → кубы (копия changed). Пишет поток света, разбирает тик. */
    private final ConcurrentLinkedQueue<Out> outbox = new ConcurrentLinkedQueue<>();
    private record Out(Store st, BitSet cubes) {}
    private final Object outLock = new Object();
    /** Правок поставлено / посчитано и выложено в outbox (ожидание в flushEdits). */
    final java.util.concurrent.atomic.AtomicLong editsPosted = new java.util.concurrent.atomic.AtomicLong();
    final java.util.concurrent.atomic.AtomicLong editsDone = new java.util.concurrent.atomic.AtomicLong();
    /** Отладка: время работы потока света, нс; столбцов разослано из outbox; COLUMN дождались/не дождались кэша; повторов после сбоя. */
    volatile long busyNanos, outSent, colsResolved, colsExpired, retried;
    /** Отладка: столбцов ждут кэша (pendingCols.size после партии). */
    volatile int pendingNow;
    /** Поток спит без событий; промахи рамки / ожидание столбцов — не чаще раза в EXTRA_NS. */
    private static final long PARK_NS = 50_000_000L, EXTRA_NS = 50_000_000L;
    private static final java.util.concurrent.atomic.AtomicInteger THREADS = new java.util.concurrent.atomic.AtomicInteger();

    CubeLight(World w) { this(w, w instanceof InstWorld && ASYNC); }

    /** async — свой поток света (тест параллельного расчёта зовёт с true на поддельном мире). */
    CubeLight(World w, boolean async) {
        this.w = w;
        this.MIN = w.dims().minSection();
        this.CUBES = w.dims().cubes();
        this.async = async;
        this.budgetNs = w instanceof InstWorld && !async ? TICK_BUDGET_NS : Long.MAX_VALUE;
        if (async) {                                                        // последним: поля уже заданы
            Thread t = new Thread(this::loop, "cube-light-" + THREADS.incrementAndGet());
            t.setDaemon(true);
            worker = t;
            t.start();
        }
    }

    /** Остановить поток света (мир выгружен / тест). */
    void stop() {
        stopped = true;
        Thread t = worker;
        if (t != null) java.util.concurrent.locks.LockSupport.unpark(t);
    }

    private void wake() {
        Thread t = worker;
        if (t != null) java.util.concurrent.locks.LockSupport.unpark(t);
    }

    /** Поток света простаивает: очереди событий пусты, партии нет (промахи рамки, ждущие столбцы и outbox — не в счёт). Для тестов. */
    boolean idle() { return !pending() && !busy; }
    private volatile boolean busy;

    /** Поток света: партии по BATCH_CUBES (правки — отдельной партией первыми), после каждой — готовое в outbox. */
    private void loop() {
        long lastExtra = 0;
        while (!stopped) {
            if (w instanceof InstWorld iw && !iw.inst().isRegistered()) { stopped = true; ENGINES.remove(iw.inst(), this); break; }
            boolean work = pending();
            long now = System.nanoTime();
            if (!work) {
                boolean extra = !missed.isEmpty() || !pendingCols.isEmpty();     // грубое чтение без замка: пишет только этот поток
                if (!extra || now - lastExtra < EXTRA_NS && !pendScanCut) {
                    java.util.concurrent.locks.LockSupport.parkNanos(extra ? Math.max(1, EXTRA_NS - (now - lastExtra)) : PARK_NS);
                    continue;
                }
                lastExtra = now;
            }
            int nEdit = 0;
            busy = true;
            try {
                synchronized (this) {
                    try { nEdit = batch(BATCH_CUBES, edits.isEmpty() ? 0 : Integer.MAX_VALUE); }
                    catch (RuntimeException | StackOverflowError ex) { failed(ex); }
                    publish();
                }
            } catch (Throwable t) {
                LOG.error("свет кубами: поток света", t);
            } finally {
                busy = false;
            }
            if (nEdit > 0) editsDone.addAndGet(nEdit);
            busyNanos += System.nanoTime() - now;
            if (!outbox.isEmpty()) kick();
        }
    }

    /** Сбой партии: в лог (не чаще раза в 10 с), взятые события — повторить (до 2 раз). Под замком движка. */
    private void failed(Throwable ex) {
        failures++;
        long now = System.currentTimeMillis();
        if (now - lastFailLog >= 10_000) { lastFailLog = now; LOG.error("свет кубами: сбой пересчёта (всего {}), событий на повтор {}", failures, taken.size(), ex); }
        int lostEdits = 0;
        for (Ev e : taken) {
            // GONE не повторять: в конце очереди он снял бы ожидание уже новой загрузки того же столбца (свет не посчитался бы)
            if (e.tries >= 2 || e.type == Ev.GONE) { if (e.type == Ev.EDIT) lostEdits++; continue; }
            retried++;
            if (e.type == Ev.EDIT) edits.add(e.retry());
            else { qType.incrementAndGet(e.type); events.add(e.retry()); }
        }
        if (lostEdits > 0) editsDone.addAndGet(lostEdits);                   // иначе flushEdits ждал бы 2 мс каждый тик с правками
        taken.clear();
    }

    /** Изменившиеся хранилища — в outbox (копия changed). Под замком движка, поток света. */
    private void publish() {
        if (touched.isEmpty()) return;
        for (Store st : touched) {
            if (st.changed.isEmpty()) continue;
            outbox.add(new Out(st, (BitSet) st.changed.clone()));
            st.changed.clear();
        }
        touched.clear();
    }

    /** Тик: разослать готовый свет (столбец целиком за раз, не дольше budget нс; остаток — следующим тиком). */
    void drainOut(long budget) {
        synchronized (outLock) {
            if (outbox.isEmpty()) return;
            long t0 = System.nanoTime();
            java.util.LinkedHashMap<Store, BitSet> m = new java.util.LinkedHashMap<>();
            for (Out o; (o = outbox.poll()) != null; ) {
                BitSet b = m.get(o.st);
                if (b == null) m.put(o.st, o.cubes); else b.or(o.cubes);
            }
            int n = 0;
            for (var it = m.entrySet().iterator(); it.hasNext(); ) {
                if (n > 0 && System.nanoTime() - t0 > budget) break;
                var en = it.next();
                it.remove();
                n++;
                Store st = en.getKey();
                CubeColumn c = st.owner;
                if (c == null || !c.isLoaded()) continue;
                for (Player p : CubeStreamer.receivers(c)) CubeStreamer.relightCubes(p, c, en.getValue());   // и кольцо 33
            }
            outSent += n;
            for (var en : m.entrySet()) outbox.add(new Out(en.getKey(), en.getValue()));
            if (!outbox.isEmpty()) kick();
        }
    }

    /** Тест: забрать готовое из outbox (как рассылка тика) — кубы по хранилищам. */
    java.util.Map<Store, BitSet> takeOut() {
        java.util.Map<Store, BitSet> m = new java.util.HashMap<>();
        for (Out o; (o = outbox.poll()) != null; ) m.merge(o.st, o.cubes, (a, b) -> { a.or(b); return a; });
        return m;
    }

    private void post(Ev e) {
        qType.incrementAndGet(e.type);
        events.add(e);
        if (async) wake(); else kick();
    }

    /** Правка: в конец текущего тика (flushEdits) — пересчёт и рассылка в том же тике, что и установка блока. */
    private void postEdit(Ev e) {
        edits.add(e);
        if (async) { editsPosted.incrementAndGet(); wake(); }
        if (FLUSH_END.compareAndSet(false, true)) {
            try { MinecraftServer.getSchedulerManager().scheduleEndOfTick(CubeLight::flushEdits); }
            catch (Throwable t) { FLUSH_END.set(false); kick(); }       // нет сервера (тест) — как прочие события
        }
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

    void editAt(int x, int y, int z, int t0, int t1) { postEdit(new Ev(Ev.EDIT, x >> 4, y >> 4, z >> 4, x, y, z, t0, t1, null, null)); }
    void cubeLoadedAt(int cx, int cy, int cz) { post(new Ev(Ev.LOAD, cx, cy, cz, 0, 0, 0, 0)); }
    void cubeUnloadedAt(int cx, int cy, int cz) { post(new Ev(Ev.UNLOAD, cx, cy, cz, 0, 0, 0, 0)); }
    void columnLoadedAt(int cx, int cz) { post(new Ev(Ev.COLUMN, cx, 0, cz, 0, 0, 0, 0)); }
    void skyFixedAt(int cx, int cz, int[] old, int[] now) { post(new Ev(Ev.SKY, cx, 0, cz, 0, 0, 0, 0, 0, old, now)); }
    /** Столбец лёг в инстанс (после cacheChunk): отложенный COLUMN — в проход сразу, без перебора pendingCols. */
    void columnCachedAt(int cx, int cz) { post(new Ev(Ev.CACHED, cx, 0, cz, 0, 0, 0, 0)); }
    void columnGoneAt(int cx, int cz) { post(new Ev(Ev.GONE, cx, 0, cz, 0, 0, 0, 0)); }

    /** Есть необработанные события. */
    boolean pending() { return !events.isEmpty() || !edits.isEmpty(); }

    /** Правок в очереди (тесты). */
    int editsQueued() { return edits.size(); }

    synchronized void flush() {
        safeProcess();
        send();
        // дождаться столбцов / досчитать промахи / события сверх бюджета тика — в следующем тике
        if (!pendingCols.isEmpty() || !missed.isEmpty() || pending()) kick();
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
    /** Правок за окно тика без бюджета (S3): дальше — по бюджету, как прочие (массовые вставки Axiom/WorldEdit). */
    static final int EDIT_FREE = 64;
    private int editsInWin;
    /** Отладка: правок посчитано вне бюджета; партий дольше SLOW_BATCH_MS. */
    volatile long editsUnbudgeted, slowBatches;
    static final long SLOW_BATCH_MS = 50;
    private static volatile long lastSlowLog;

    /**
     * process() без обрыва вызывающего (рассылка игроку, тик): сбой — в лог со стеком (не чаще раза в 10 с) и счётчик.
     * Выдача света после сбоя всё равно идёт: непосчитанные кубы — по плоской задаче/F, а их досчитает промах рамки.
     */
    private void safeProcess() {
        long b = budgetNs, t = System.nanoTime();
        if (b != Long.MAX_VALUE) {                                           // бюджет общий на окно тика (flush + все data/blockLit)
            if (t - winStart >= 50_000_000L) { winStart = t; winSpent = 0; editsInWin = 0; }
            // правки игроков — первыми и без бюджета (до EDIT_FREE за окно): тень в коробке не ждёт потоковых событий
            if (!edits.isEmpty() && editsInWin < EDIT_FREE) {
                try {
                    while (!edits.isEmpty() && editsInWin < EDIT_FREE) {
                        int n = batch(BATCH_CUBES, EDIT_FREE - editsInWin);
                        if (n == 0) break;
                        editsInWin += n;
                        editsUnbudgeted += n;
                    }
                } catch (RuntimeException ex) {
                    failures++;
                    long now = System.currentTimeMillis();
                    if (now - lastFailLog >= 10_000) { lastFailLog = now; LOG.error("свет кубами: сбой пересчёта правок (всего {})", failures, ex); }
                }
                t = System.nanoTime();                                       // правки вне бюджета — не в счёт окна
            }
            b -= winSpent;
            if (b <= 0) { if (pending()) kick(); return; }                 // выбран — досчитаем в следующем тике
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
            int n = batch(limited ? BATCH_CUBES : Integer.MAX_VALUE, 0);
            if (n > 0) editsDone.addAndGet(n);                                 // правки, взятые не потоком света (level/box)
        } while (pending() && System.nanoTime() - start < budget);
        if (pending()) budgetCuts++;
        if (async) { publish(); if (!outbox.isEmpty()) kick(); }            // level/box забрали события у потока — разослать их итог
    }

    /** Следующее событие: сначала правки; onlyEdits — только они. */
    private Ev next(boolean onlyEdits) {
        Ev e = edits.poll();
        if (e == null && !onlyEdits) {
            e = events.poll();
            if (e != null) qType.decrementAndGet(e.type);
        }
        if (e != null) taken.add(e);
        return e;
    }

    /** Перебор pendingCols (запасной путь к CACHED и сроки): в тике/тестах — каждую партию, у потока света — раз в PEND_SCAN_MS. */
    private long lastPendScan;
    /** 0.61: прошлый перебор pendingCols оборван по размеру партии — следующая партия перебирает сразу. */
    private boolean pendScanCut;
    private static final long PEND_SCAN_MS = 1_000;

    /**
     * Одна партия событий: берём, пока кубов в пересчёте меньше limit. editCap &gt; 0 — только правки, не больше editCap
     * (без отложенных COLUMN и промахов рамки). Ответ — сколько правок взято.
     */
    private int batch(int limit, int editCap) {
        boolean onlyEdits = editCap > 0;
        taken.clear();
        Ev e = next(onlyEdits);
        long nowMs = System.currentTimeMillis();
        boolean scan = !onlyEdits && !pendingCols.isEmpty() && (!async || pendScanCut || nowMs - lastPendScan >= PEND_SCAN_MS);
        if (e == null && (onlyEdits || !scan && missed.isEmpty())) return 0;
        long t0 = System.nanoTime();
        int nEdit = 0, nLoad = 0, nUnload = 0, nCol = 0, nSky = 0;
        Ctx x = new Ctx();
        LongOpenHashSet k = new LongOpenHashSet(), weak = new LongOpenHashSet();
        LongLinkedOpenHashSet cols = new LongLinkedOpenHashSet();
        if (scan) {                                                          // отложенные COLUMN: столбец появился — в проход
            lastPendScan = nowMs;
            pendScanCut = false;
            for (var it = pendingCols.long2LongEntrySet().iterator(); it.hasNext(); ) {
                // 0.61: не больше партии (limit): первая загрузка карты — сотни дождавшихся столбцов одной партией
                // давали «партия 16 с»; остальные — следующими партиями сразу, без ожидания PEND_SCAN_MS
                if (limit != Integer.MAX_VALUE && cols.size() * 32 >= limit) { pendScanCut = true; break; }
                var pe = it.next();
                long ck = pe.getLongKey();
                if (w.column((int) (ck >> 32), (int) ck) != null) { cols.add(ck); it.remove(); colsResolved++; }
                else if (nowMs > pe.getLongValue()) { it.remove(); colsExpired++; }
            }
        }
        if (!onlyEdits && !missed.isEmpty()) { weak.addAll(missed); missed.clear(); }
        for (; e != null; e = k.size() + cols.size() * 32 < limit && (!onlyEdits || nEdit < editCap) ? next(onlyEdits) : null) {
            switch (e.type) {
                case Ev.EDIT -> {
                    nEdit++;
                    infCache.remove(key(e.cx, e.cy, e.cz));
                    forgetEmit(e.cx, e.cy, e.cz);
                    addNear(k, weak, e.x, e.y, e.z, MIN, CUBES);
                    if (e.t0 != e.t1) { x.recordOld(e.x, e.z, e.t0); x.range(e.x >> 4, e.z >> 4, Math.min(e.t0, e.t1), Math.max(e.t0, e.t1)); }
                }
                case Ev.LOAD -> { nLoad++; infCache.remove(key(e.cx, e.cy, e.cz)); forgetEmit(e.cx, e.cy, e.cz); add3(k, e.cx, e.cy, e.cz); }
                case Ev.UNLOAD -> {
                    nUnload++;
                    infCache.remove(key(e.cx, e.cy, e.cz));
                    forgetEmit(e.cx, e.cy, e.cz);
                    Column c = w.column(e.cx, e.cz);
                    // куб уже снова в памяти (перезагрузили / повтор после сбоя) — свет не стирать: его пересчитает LOAD
                    if (c != null && !c.ready(e.cy - MIN)) drop(c.store(), e.cy - MIN, false);
                    add3(weak, e.cx, e.cy, e.cz);                             // соседи могли перестать быть PT
                }
                case Ev.COLUMN -> {
                    nCol++;
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
                case Ev.CACHED -> {                                          // дождались: отложенный COLUMN — в этот проход
                    long ck = colKey(e.cx, e.cz);
                    if (pendingCols.containsKey(ck)) { pendingCols.remove(ck); cols.add(ck); colsResolved++; }
                }
                case Ev.GONE -> pendingCols.remove(colKey(e.cx, e.cz));     // выгружен — не ждать
                case Ev.SKY -> {                                             // как правка skyTop, только без блока
                    nSky++;
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
        taken.clear();                                                      // дошли до конца — повторять нечего
        pendingNow = pendingCols.size();
        long dt = System.nanoTime() - t0;
        runs++;
        nanos += dt;
        lastNanos = dt;
        if (dt > maxNanos) maxNanos = dt;
        if (dt > SLOW_BATCH_MS * 1_000_000L) {                               // поймать, что даёт секундные пересчёты
            slowBatches++;
            long now = System.currentTimeMillis();
            if (now - lastSlowLog >= 5_000) {
                lastSlowLog = now;
                LOG.warn("свет кубами: партия {} мс — правок {}, загрузок {}, выгрузок {}, столбцов {} (всего с отложенными {}), SKY {}, кубов в пересчёте {}, полос skyTop {}",
                        dt / 1_000_000, nEdit, nLoad, nUnload, nCol, cols.size(), nSky, s.size(), x.ranges.size());
            }
        }
        return nEdit;
    }

    /** Куб из множества событий: посчитанный — в заливку; ставший PT — массивы прочь (onlyNew — только непосчитанные). */
    private void pick(Ctx x, long key, LongArrayList s, boolean onlyNew) {
        int cx = kx(key), cy = ky(key), cz = kz(key), i = cy - MIN;
        if (i < 0 || i >= CUBES) return;
        Column c = x.col(cx, cz);
        if (c == null || !c.ready(i)) return;
        Store st = c.store();
        if (x.nonPT(cx, cy, cz)) { if (!onlyNew || !st.done(i)) s.add(key); }
        else if (st.done(i)) drop(st, i, true);
    }

    private void drop(Store st, int i, boolean mark) {
        if (i < 0 || i >= CUBES) return;
        boolean had = st.done(i);
        st.put(i, null);
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
    static void addNear(LongOpenHashSet k, LongOpenHashSet weak, int x, int y, int z, int MIN, int CUBES) {
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
        addNear(k, weak, x, y, z, TallWorld.MIN_Y >> 4, TallWorld.HEIGHT >> 4);
        return new long[][]{k.toLongArray(), weak.toLongArray()};
    }

    /** Расстояние по оси от клетки l (0…15) до соседнего куба в сторону s (−1/0/+1). */
    private static int gap(int l, int s) { return s == 0 ? 0 : s < 0 ? l + 1 : 16 - l; }

    private void add3(LongOpenHashSet k, int cx, int cy, int cz) {
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
        Rec r = st.rec(i);
        boolean ch = r == null || r.sky != null || r.blk != null
                || (w.sky() && x.fChanged(kx(key), kz(key), ky(key) << 4));   // небо по F было не 0 до правки skyTop
        st.put(i, PLAIN);
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
                // кэш прохода сказал «нет» (сосед раньше в этом же проходе), а столбец уже в мире и его CACHED позади —
                // не ждать скана раз в секунду: следующей партией
                if (w.column(cx, cz) != null) columnCachedAt(cx, cz);
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
                if (c.ready(i) && st.done(i) && lateralDiff(x, c, i)) add3(k, cx, i + MIN, cz);
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
        Rec r = c.store().rec(i);
        byte[] sa = r == null ? null : r.sky, ba = r == null ? null : r.blk;
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
    private void addNonPT(Ctx x, int cx, int cz, int a, int b, LongOpenHashSet k) {
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
                if (c != null && c.store().done(i) && ((i + MIN) << 4) > top) continue;
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
            if (st.done(i)) { drop(st, i, true); continue; }             // был посчитанным — стал PT
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
            Rec o = st.rec(i);
            byte[] os = o == null ? null : o.sky, ob = o == null ? null : o.blk;
            boolean ch = o == null || !Arrays.equals(os, ns) || !Arrays.equals(ob, nbk)
                    || (sun && os == null && ns == null && x.fChanged(kx(key), kz(key), y0));
            st.put(i, ns == null && nbk == null ? PLAIN : new Rec(ns, nbk));    // публикация целиком (читают без замка)
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
            Rec r = nc.store().rec(ni);
            if (r != null) { stored = true; ss = r.sky; sb = r.blk; }
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
    /** Отладка: кубов «всё 15» быстрым путём (без сетки высот). */
    static volatile long fastFull;
    static { Arrays.fill(FULL, (byte) 0xFF); }

    /**
     * Свет кубов idx для игрока. Поток света: только чтение опубликованного (без замка движка и пересчёта), сетки 48×48
     * — из общего кэша; иначе (тесты) — сначала пересчёт по событиям под замком движка.
     */
    LightData data(Store st, List<Integer> idx, boolean explicit) {
        if (!async) synchronized (this) { safeProcess(); }
        synchronized (gridCache) { return read(st, idx, explicit); }
    }

    private LightData read(Store st, List<Integer> idx, boolean explicit) {
        Ctx x = new Ctx();
        x.shared = true;
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
            byte[] b = st.blk(i);
            if (b != null) { blk.set(i + 1); ba.add(b); }
            else if (explicit) eb.set(i + 1);
        }
        if (sky.isEmpty() && blk.isEmpty() && eb.isEmpty()) return null;
        return new LightData(sky, blk, es, eb, sa, ba);
    }

    /** Небо куба i массивом (FULL/ZERO — общие, не менять). */
    private byte[] skyArray(Ctx x, Column c, int i) {
        Rec r = c.store().rec(i);                                         // один раз: поток света мог опубликовать новый
        int cy = i + MIN, y0 = cy << 4;
        if (r != null) {
            if (r.sky != null) return r.sky;
        } else if (c.ready(i)) {
            // PT-куб высоко над крышами 3×3 (как pt2D: g.max < низ−16) — «всё 15» без сетки 48×48 (кэш floorInfo)
            int t = maxTop3x3(c.cx(), c.cz());
            if (t != Integer.MIN_VALUE && t < y0 - 16) { fastFull++; return FULL; }
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
            return col == null ? null : col.store().blk(i);
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
        /** Сетки «сейчас» — из общего кэша gridCache (выдача игрокам; вызывающий держит его замок). */
        boolean shared;

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
            if (shared && !before) {                                         // JFR s55: сетка с сортировкой заново на каждый data() — 0,95 мс/тик
                Grid c = gridCache.getAndMoveToLast(k);
                if (c != null && Arrays.equals(c.a, a)) { g = c; gridHits++; }
                else {
                    g = new Grid(a);
                    gridMisses++;
                    gridCache.putAndMoveToLast(k, g);
                    if (gridCache.size() > GRID_CACHE) gridCache.removeFirst();
                }
            } else g = new Grid(a);
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

    /**
     * Сетки 48×48 между вызовами data() (с плоскими задачами по рангам): ключ — столбец, годна, пока те же высоты 9
     * столбцов (сравнение 2304 чисел дешевле сортировки и двух проходов расстояний). Под своим замком.
     */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<Grid> gridCache = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
    private static final int GRID_CACHE = 1024;
    /** Отладка: сетка из кэша / построена. */
    volatile long gridHits, gridMisses;

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

    /** Высоты генератора столбца (кэш; зовут и поток света, и выдача в тике — под своим замком, генератор — вне его). */
    private int[] genTops(int cx, int cz) {
        long k = colKey(cx, cz);
        int[] t;
        synchronized (genTops) { t = genTops.get(k); }
        if (t != null) return t;
        t = new int[256];
        for (int c = 0; c < 256; c++) t[c] = w.genTop(cx * 16 + (c & 15), cz * 16 + (c >> 4));
        synchronized (genTops) {
            if (genTops.size() > 8192) genTops.clear();
            genTops.put(k, t);
        }
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
        return ((long) (cx & 0xFFFFFF) << 40) | ((long) (cz & 0xFFFFFF) << 16) | (cy & 0xFFFF);   // cy со знаком (±2047)
    }

    static int kx(long k) { return (int) (k >> 40); }
    static int kz(long k) { return (int) ((k << 24) >> 40); }
    static int ky(long k) { return (short) (k & 0xFFFF); }

    static long colKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    // ───────────── мир сервера ─────────────

    private record InstWorld(Instance inst) implements World {
        @Override public TallWorld.Dims dims() { return TallWorld.dims(inst); }
        @Override public boolean sky() { return inst.getCachedDimensionType().hasSkylight(); }

        @Override public Column column(int cx, int cz) {
            return inst.getChunk(cx, cz) instanceof CubeColumn c && c.isLoaded() ? c.lightStore().col : null;
        }

        @Override public int genTop(int x, int z) {
            return ink.hoka.odyssey.server.tall.gen.CubeGens.of(inst).highest(x, z, TallWorld.dims(inst).minY(), TallWorld.dims(inst).maxY(), HeightIndex.Kind.SKY);
        }

        @Override public boolean genBlocks(int cx, int cy, int cz) { return ink.hoka.odyssey.server.tall.gen.CubeGens.of(inst).hasBlocks(cx, cy, cz); }
    }
}
