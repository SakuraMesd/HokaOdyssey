package ink.hoka.odyssey.server.tall;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.EntityTracker;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.heightmap.Heightmap;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.BlockActionPacket;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.BlockEntityDataPacket;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.minestom.server.network.packet.server.play.data.ChunkData;
import net.minestom.server.network.packet.server.play.data.LightData;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.snapshot.ChunkSnapshot;
import net.minestom.server.snapshot.SnapshotImpl;
import net.minestom.server.snapshot.SnapshotUpdater;
import net.minestom.server.utils.PacketSendingUtils;
import net.minestom.server.world.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Столбец кубовой карты (CUBIC-CHUNKS.md §4.1, этап 4 шаг 9; map.yml {@code store: cubes}): свой Chunk вместо
 * DynamicChunk — без рефлексии и List.copyOf на 1876 секций. На этом шаге все кубы столбца в памяти.
 * <ul>
 *   <li>кубы — разреженно (TallSections): пустой = общая {@link TallSections#EMPTY}, {@link #getSection} не бросает;</li>
 *   <li>setBlock — как DynamicChunk (блоки с данными, тикающие, onPlace/onDestroy) + HeightIndex и свет (ColumnState);</li>
 *   <li>карты высот — из HeightIndex ({@link CubeHeightmap}), без спуска по секциям;</li>
 *   <li>игроку — скелет (кэш), блоки — кубами (CubeStreamer); BlockChange/BlockEntityData/BlockAction — только тем,
 *       у кого куб в окне ({@link CubeStreamer#sentCube}); пересылка столбца — ресинк кубов;</li>
 *   <li>reset ничего не стирает (защита данных), copy/updateSnapshot — только по своим кубам.</li>
 *   <li>шаг 10, ленивая загрузка ({@link #initLazy}): кубы с диска и от генератора — по запросу ({@link #request},
 *       CubeStore), до того куб «не в памяти»: секция общая пустая, getBlock — воздух, запись — исключение (§4.2),
 *       сохранение его не трогает (на диске остаётся как было). Высоты — из заголовка/генератора.</li>
 *   <li>шаг 11, выгрузка кубов ({@link #pickUnload}, {@link #unloadCubes}): загруженный куб без интереса 30 с →
 *       UNLOADING (запись — исключение) → сохранить столбец → убрать из памяти (ABSENT). Попросили куб, пока он
 *       выгружается, или не сохранилось — остаётся в памяти (READY).</li>
 * </ul>
 * Не на кубовом протоколе (map.yml без {@code cubes: true}) отдаёт полный столбец, как TallChunk.
 */
public final class CubeColumn extends Chunk implements TallColumn {

    private static final Logger LOG = LoggerFactory.getLogger("tall");
    private static final int MIN_SECTION = TallWorld.MIN_Y >> 4;
    private static final int CUBES = TallWorld.HEIGHT >> 4;

    private final TallSections cubes = new TallSections(CUBES);
    private final List<Section> view = Collections.unmodifiableList(cubes);
    /** Блоки с данными/обработчиком (ключ — CoordConversion.chunkBlockIndex) и тикающие — как в DynamicChunk. */
    private final Int2ObjectOpenHashMap<Block> entries = new Int2ObjectOpenHashMap<>(0);
    private final Int2ObjectOpenHashMap<Block> tickable = new Int2ObjectOpenHashMap<>(0);
    private final ColumnState st = new ColumnState();
    private final CubeHeightmap motion = new CubeHeightmap(this, HeightIndex.Kind.MOTION);
    private final CubeHeightmap surface = new CubeHeightmap(this, HeightIndex.Kind.SURFACE);
    /** Скелет столбца для кубового протокола: сбрасывается при смене биома и высот motion/surface. */
    private final CachedPacket skeleton = new CachedPacket(this::skeletonPacket);
    /** Шаг 12: свет кубов (CubeLight) — на кубовом протоколе вместо ColumnLight. */
    private final CubeLight.Store lightStore = new CubeLight.Store(new LightCol(), this);

    // ───────────── шаг 10: состояния кубов ─────────────

    static final byte READY = 0, ABSENT = 1, QUEUED = 2, LOADING = 3, UNLOADING = 4;
    private static final VarHandle ST = MethodHandles.arrayElementVarHandle(byte[].class);
    /** Состояние куба (по умолчанию READY: столбец без ленивой загрузки — все кубы в памяти). Меняется под {@link #q}. */
    private final byte[] state = new byte[CUBES];
    /** Очередь: приоритет запрошенных кубов, ждущие будущие, сколько грузится. */
    private final Object q = new Object();
    private it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap queuedPrio;
    private Int2ObjectOpenHashMap<CompletableFuture<Void>> waiters;
    private int loadingNow;
    /** Шаг 11: загруженные (не пустые «от рождения») кубы → когда к ним последний раз был интерес, мс. Под {@link #q}. */
    private final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap held = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap(0);
    /** Сколько кубов сейчас выгружается (UNLOADING). Под {@link #q}. */
    private int unloadingNow;
    /** Кэш клиентов (0.51): посчитанные хэши содержимого кубов (CubeHash). Сброс при любой записи в куб. Под {@link #q}. */
    private final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap hashes = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap(0);
    /** Ленивая загрузка включена (initLazy). */
    private volatile boolean lazy;
    /** Откуда читать кубы с диска (null — только генератор) и какие кубы брать с диска (заголовок при загрузке). */
    private CubeRegionLoader source;
    private BitSet fromDisk = new BitSet();

    /**
     * Верх колонки в кубе не в памяти: генератор аналитически, куб с диска — с запасом (уточнит {@link HeightIndex#fix}),
     * а если по отметке в нём нет блоков с высотой этого вида (шаг 13, CubeMark) — мимо. Выгружающийся куб ещё в
     * памяти (спуск идёт под замком записи, выгрузка убирает данные под ним же) — по палитре, не по генератору.
     */
    private final HeightIndex.Unknown unknown = new HeightIndex.Unknown() {
        @Override public boolean unknown(int i) { return !inMemory(i); }
        @Override public int top(int i, int x, int z, int fromY, HeightIndex.Kind k) {
            int y0 = (i + MIN_SECTION) << 4, hi = Math.min(fromY, y0 + 15);
            if (fromDisk.get(i)) {
                if (st.clear(k, i)) return HeightIndex.NONE;
                // маска колонок неба: в этой колонке куба нет блока с высотой — мимо (без неё — «верх куба» с запасом)
                if (k == HeightIndex.Kind.SKY && st.skyCol(i, z << 4 | x) == 0) return HeightIndex.NONE;
                return hi;
            }
            return gen.highest(chunkX * 16 + x, chunkZ * 16 + z, y0, hi, k);
        }
    };

    /** Генератор карты (map.yml generator) — один на столбец, на всё время жизни. */
    private final CubeGen gen;

    public CubeColumn(Instance instance, int chunkX, int chunkZ) {
        super(instance, chunkX, chunkZ, false);                             // генератор Minestom не зовём: CubeGen сам
        this.gen = ink.hoka.odyssey.server.tall.gen.CubeGens.of(instance);
        if (minSection != MIN_SECTION || maxSection - minSection != CUBES)
            throw new IllegalStateException("CubeColumn только для высокой карты (" + minSection + "…" + maxSection + ")");
    }

    /** Новый столбец (в хранилище нет): кубы генератором (как TallChunk.generated); на ленивой карте — по запросу. */
    public static CubeColumn generated(Instance instance, int chunkX, int chunkZ) {
        CubeColumn c = new CubeColumn(instance, chunkX, chunkZ);
        if (instance instanceof net.minestom.server.instance.InstanceContainer ic && ic.getChunkLoader() instanceof CubeRegionLoader l && l.lazyFor(chunkX)) {
            c.initLazy(l, new BitSet(), null);
            return c;
        }
        c.fillGen(null);
        c.afterLoad();
        return c;
    }

    /**
     * Ленивый столбец (шаг 10): кубы с диска (onDisk) и непустые у генератора — «не в памяти», остальные — пустые READY.
     * heights — высоты из заголовка региона ([вид][z*16+x]) или null — по генератору. Вместо afterLoad.
     */
    void initLazy(CubeRegionLoader src, BitSet onDisk, short[][] heights) {
        CubeGen g = gen;
        source = src;
        fromDisk = new BitSet(CUBES);                                      // сразу на все кубы: дальше без перевыделения (читают без замка)
        fromDisk.or(onDisk);
        for (int i = 0; i < CUBES; i++)
            if (onDisk.get(i) || g.hasBlocks(chunkX, i + MIN_SECTION, chunkZ)) state[i] = ABSENT;
        if (heights != null) st.heights.load(heights);
        else {
            st.heights.fromGen(g, chunkX, chunkZ, TallWorld.MIN_Y, TallWorld.MIN_Y + TallWorld.HEIGHT - 1);
            st.heights.raiseOver(onDisk, MIN_SECTION, new HeightIndex.Clear() {   // кубы с диска — с запасом (уточнит fix при загрузке)
                @Override public boolean clear(HeightIndex.Kind k, int i) { return st.clear(k, i); }
                @Override public boolean clear(HeightIndex.Kind k, int i, int c) {
                    return st.clear(k, i) || (k == HeightIndex.Kind.SKY && st.skyCol(i, c) == 0);
                }
            });
        }
        lazy = true;
        skeleton.invalidate();
        st.loaded();
        TallWorld.loaded(this);
        if (CubeLight.active(this)) CubeLight.columnLoaded(this);
    }

    public boolean isLazy() { return lazy; }

    @Override
    public boolean ready(int i) {
        return i < 0 || i >= CUBES || (byte) ST.getVolatile(state, i) == READY;
    }

    @Override
    public void request(int i, int prio) {
        if (!lazy || i < 0 || i >= CUBES) return;
        synchronized (q) {
            byte s = state[i];
            if (s == READY || s == LOADING) return;
            if (queuedPrio == null) queuedPrio = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
            if (s == UNLOADING) {                                         // выгружается: не грузить, а оставить в памяти (unloadCubes)
                queuedPrio.put(i, Math.min(prio, queuedPrio.getOrDefault(i, Integer.MAX_VALUE)));
                return;
            }
            if (s == QUEUED) {
                if (prio >= queuedPrio.getOrDefault(i, Integer.MAX_VALUE)) return;   // уже в очереди не хуже
            } else ST.setVolatile(state, i, QUEUED);
            queuedPrio.put(i, prio);
        }
        CubeStore.enqueue(this, i, prio);                                 // дубль с худшим приоритетом рабочий пропустит
    }

    /** Будущее «куб i в памяти» (запрашивает загрузку). Столбец выгрузят раньше — завершится ошибкой. */
    CompletableFuture<Void> whenReady(int i, int prio) {
        if (ready(i)) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> f;
        synchronized (q) {
            if (ready(i)) return CompletableFuture.completedFuture(null);
            if (!isLoaded()) return CompletableFuture.failedFuture(new IllegalStateException("столбец выгружен"));
            if (waiters == null) waiters = new Int2ObjectOpenHashMap<>();
            f = waiters.computeIfAbsent(i, k -> new CompletableFuture<>());
        }
        request(i, prio);
        return f;
    }

    /** Есть запрошенные/грузящиеся кубы или ждущие (столбец не выгружать). */
    public boolean busy() {
        synchronized (q) {
            return loadingNow > 0 || unloadingNow > 0 || (queuedPrio != null && !queuedPrio.isEmpty()) || (waiters != null && !waiters.isEmpty());
        }
    }

    /** В столбце нет загруженных кубов (все, кроме пустых «от рождения», не в памяти) и ничего не грузится/выгружается. */
    public boolean noCubes() {
        synchronized (q) {
            return held.isEmpty() && loadingNow == 0 && unloadingNow == 0
                    && (queuedPrio == null || queuedPrio.isEmpty()) && (waiters == null || waiters.isEmpty());
        }
    }

    /** Сколько кубов в каждом состоянии: [READY непустых, не в памяти, в очереди, грузится, выгружается]. */
    int[] stateCounts() {
        int[] n = new int[5];
        for (int i = 0; i < CUBES; i++) {
            byte s = (byte) ST.getVolatile(state, i);
            if (s == READY) { if (cubes.isReal(i)) n[0]++; }
            else n[s]++;
        }
        return n;
    }

    /** Состояние куба i словом (для /hocubes dump). */
    String stateName(int i) {
        if (i < 0 || i >= CUBES) return "вне мира";
        return switch ((byte) ST.getVolatile(state, i)) {
            case READY -> "READY";
            case ABSENT -> "не в памяти";
            case QUEUED -> "в очереди";
            case UNLOADING -> "выгружается";
            default -> "грузится";
        } + (fromDisk.get(i) ? ", на диске" : "");
    }

    /** Сколько мс к загруженному кубу i не было интереса (−1 — куб не из загруженных: пустой «от рождения» или не в памяти). */
    long idleMs(int i, long now) {
        synchronized (q) { return held.containsKey(i) ? now - held.get(i) : -1; }
    }

    /** Загруженных (непустых «от рождения») кубов в памяти — для /hocubes stats. */
    int heldCount() { synchronized (q) { return held.size(); } }

    // ───────────── шаг 11: выгрузка кубов ─────────────

    /** К кубу i есть интерес сейчас (запись в него). */
    private void touch(int i) {
        synchronized (q) { held.put(i, System.currentTimeMillis()); }
    }

    /** Данные куба в памяти (READY или выгружается) — сохранение их пишет. */
    @Override
    public boolean inMemory(int i) {
        if (i < 0 || i >= CUBES) return true;
        byte s = (byte) ST.getVolatile(state, i);
        return s == READY || s == UNLOADING;
    }

    /**
     * Тик (CubeStore.scan): кубы из keep (окна игроков, закрепления, сущности) — интерес сейчас; загруженные кубы без
     * интереса дольше idleMs — в UNLOADING (запись в них — исключение). Ответ — их номера, выгружает {@link #unloadCubes}.
     */
    int[] pickUnload(BitSet keep, long now, long idleMs) {
        if (!lazy || source == null || !isLoaded()) return new int[0];
        it.unimi.dsi.fastutil.ints.IntArrayList out = new it.unimi.dsi.fastutil.ints.IntArrayList();
        synchronized (q) {
            for (var it = held.int2LongEntrySet().fastIterator(); it.hasNext(); ) {
                var e = it.next();
                int i = e.getIntKey();
                if (state[i] != READY) continue;
                if (keep.get(i)) { e.setValue(now); continue; }
                if (now - e.getLongValue() < idleMs) continue;
                if ((queuedPrio != null && queuedPrio.containsKey(i)) || (waiters != null && waiters.containsKey(i))) continue;
                ST.setVolatile(state, i, UNLOADING);
                unloadingNow++;
                out.add(i);
            }
        }
        return out.toIntArray();
    }

    /**
     * Поток выгрузки (CubeStore): сохранить столбец (UNLOADING-кубы пишутся — они в памяти), затем под замком записи
     * убрать кубы из памяти. Не сохранилось, куб снова «изменён», столбец выгружен или куб попросили, пока он
     * выгружался, — оставить в памяти (READY). Ответ: сколько кубов убрано.
     */
    int unloadCubes(int[] is) {
        BitSet seen = new BitSet();                                         // кубы, судьба которых решена
        try { return unloadCubes(is, seen); }
        finally {                                                           // сбой посередине: нетронутые — снова READY (данные на месте)
            synchronized (q) {
                for (int i : is)
                    if (!seen.get(i) && state[i] == UNLOADING) { ST.setVolatile(state, i, READY); unloadingNow--; held.put(i, System.currentTimeMillis()); }
            }
        }
    }

    private int unloadCubes(int[] is, BitSet seen) {
        boolean saved = false;
        // карту уже выгрузили (Maps сохранил всё и закрыл файлы регионов): не писать — снова открыли бы файлы; куб остаётся
        if (source != null && isLoaded() && instance.isRegistered()) {
            try { source.save(this); saved = true; }
            catch (Exception e) { CubeStore.unloadFailed(this, e); }
        }
        long now = System.currentTimeMillis();
        int dropped = 0;
        List<CompletableFuture<Void>> done = new ArrayList<>();
        it.unimi.dsi.fastutil.ints.IntArrayList again = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap requeue = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        lockWriteLock();
        try {
            BitSet disk = st.diskCubes;
            for (int i : is) {
                synchronized (q) {
                    seen.set(i);
                    if (state[i] != UNLOADING) continue;
                    boolean wanted = (queuedPrio != null && queuedPrio.containsKey(i)) || (waiters != null && waiters.containsKey(i));
                    if (!saved || !isLoaded() || wanted || st.isDirty(i)) {   // остаётся в памяти как был
                        ST.setVolatile(state, i, READY);
                        unloadingNow--;
                        held.put(i, now);
                        if (queuedPrio != null) queuedPrio.remove(i);
                        var f = waiters == null ? null : waiters.remove(i);
                        if (f != null) done.add(f);
                        if (wanted) again.add(i);
                        continue;
                    }
                    // откуда грузить в следующий раз: после сохранения на диске ровно то, что в заголовке
                    if (disk != null) fromDisk.set(i, disk.get(i));
                }
                // данные — прочь (замок записи: читающие под замком чтения увидят либо куб, либо пустоту). Куб уже
                // на диске — что бы ни случилось дальше, он «не в памяти» (READY с пустой секцией потерял бы его)
                try {
                    cubes.set(i, TallSections.EMPTY);
                    dropHash(i);
                    int y0 = (i + MIN_SECTION) << 4;
                    if (!entries.isEmpty()) entries.keySet().removeIf((int k) -> {
                        int y = CoordConversion.chunkBlockIndexGetY(k);
                        return y >= y0 && y <= y0 + 15;
                    });
                    if (!tickable.isEmpty()) tickable.keySet().removeIf((int k) -> {
                        int y = CoordConversion.chunkBlockIndexGetY(k);
                        return y >= y0 && y <= y0 + 15;
                    });
                } finally {
                    synchronized (q) {
                        unloadingNow--;
                        held.remove(i);
                        int pr = queuedPrio == null ? Integer.MIN_VALUE : queuedPrio.getOrDefault(i, Integer.MIN_VALUE);
                        if (pr != Integer.MIN_VALUE) { ST.setVolatile(state, i, QUEUED); requeue.put(i, pr); }   // попросили в последний миг — загрузить снова
                        else ST.setVolatile(state, i, ABSENT);
                    }
                }
                dropped++;
                if (CubeLight.active(this)) CubeLight.cubeUnloaded(this, i);
            }
        } finally { unlockWriteLock(); }
        for (var f : done) f.complete(null);
        for (int i : again) CubeStore.readyAgain(this, i);                  // кто-то ждал — дослать игрокам
        for (var e : requeue.int2IntEntrySet()) CubeStore.enqueue(this, e.getIntKey(), e.getIntValue());
        return dropped;
    }

    /** Рабочий поток CubeStore: прочитать куб i (регион или генератор) и положить в столбец. */
    void loadNow(int i, int prio) {
        synchronized (q) {
            if (state[i] != QUEUED) return;                               // дубль из очереди / уже загружен
            if (queuedPrio != null && queuedPrio.getOrDefault(i, prio) < prio) return;   // есть запрос важнее — он и загрузит
            ST.setVolatile(state, i, LOADING);
            if (queuedPrio != null) queuedPrio.remove(i);
            loadingNow++;
        }
        boolean done = false;
        try {
            if (!isLoaded() || !instance.isRegistered()) return;            // карта выгружена — файлы регионов закрыты
            CubeRegionLoader.Loaded d;
            try {
                d = fromDisk.get(i) && source != null ? source.loadCube(chunkX, i, chunkZ)
                        : CubeRegionLoader.generate(gen, chunkX, i, chunkZ);
            } catch (Exception e) {                                       // сбой чтения: НЕ генерировать вместо (затёрли бы диск)
                CubeStore.failed(this, i, prio, e);
                return;
            }
            done = install(i, d);
        } finally {
            if (!done) synchronized (q) {
                if (state[i] == LOADING) ST.setVolatile(state, i, ABSENT);
                loadingNow--;
            }
        }
    }

    /** Положить прочитанный куб (замок записи — коротко). false — столбец уже выгружен. */
    private boolean install(int i, CubeRegionLoader.Loaded d) {
        List<Block> handlers = new ArrayList<>();
        List<Integer> handlerAt = new ArrayList<>();
        int[] skyOld = null, skyNew = null;
        boolean stale = false;
        lockWriteLock();
        try {
            if (!isLoaded() || state[i] != LOADING) return false;
            // секцию дописать до публикации: свет/CubeStreamer читают палитры без замка — не видеть её посреди правки
            Section sec = d.section();
            if (sec == null && !d.data().isEmpty()) sec = new Section();
            for (var e : d.data().int2ObjectEntrySet()) {
                int k = e.getIntKey();
                int x = CoordConversion.chunkBlockIndexGetX(k), y = CoordConversion.chunkBlockIndexGetY(k), z = CoordConversion.chunkBlockIndexGetZ(k);
                if ((y >> 4) - MIN_SECTION == i) sec.blockPalette().set(x & 15, y & 15, z & 15, e.getValue().stateId());
            }
            if (sec != null) cubes.set(i, sec);
            dropHash(i);
            for (var e : d.data().int2ObjectEntrySet()) {
                int k = e.getIntKey();
                Block b = e.getValue();
                int y = CoordConversion.chunkBlockIndexGetY(k);
                if ((y >> 4) - MIN_SECTION != i) continue;                    // не из этого куба — не трогать
                entries.put(k, b);
                if (b.handler() != null && b.handler().isTickable()) tickable.put(k, b);
                if (b.handler() != null) { handlers.add(b); handlerAt.add(k); }
            }
            cubes.release(i);
            if (CubeCacheModel.anyActive() && cubes.isReal(i)) storeHash(i, CubeHash.of(cubes.get(i)));   // кэш клиентов: версия куба — здесь, в рабочем потоке
            int[] top0 = new int[256];
            for (int c = 0; c < 256; c++) top0[c] = st.heights.skyTop(c);
            if (st.heights.fix(cubes, MIN_SECTION, i, unknown)) {           // шаг 13: skyTop уточнился — пересвет полосы
                skyOld = new int[256];
                skyNew = new int[256];
                for (int c = 0; c < 256; c++) { skyOld[c] = top0[c]; skyNew[c] = st.heights.skyTop(c); }
            }
            int y0 = (i + MIN_SECTION) << 4;
            int was = CubeMark.rel(d.mark());
            stale = was != CubeMark.NONE && was != CubeMark.rel(y0, st.heights::skyTop);   // высоты сдвинулись, пока куб лежал на диске
            st.setClear(i, d.mark());                                   // данные в памяти = на диске: биты из его отметки
            if (fromDisk.get(i) && source != null) st.setSkyCols(i, CubeMark.skyCols(sec == null ? null : sec.blockPalette()));   // маска колонок неба — как на диске
            if (d.missing()) { st.markDirty(i); st.modified = true; }     // в заголовке есть, в регионе нет — при сохранении убрать
            synchronized (q) { ST.setVolatile(state, i, READY); loadingNow--; held.put(i, System.currentTimeMillis()); }
            floorDirty = true;                                              // op 16: сразу за READY (не ждать cubeLoaded)
        } finally { unlockWriteLock(); }
        skeleton.invalidate();
        if (CubeLight.active(this)) {                                       // шаг 12: 3×3×3 вокруг куба; шаг 13: + полоса skyTop
            if (skyOld != null) CubeLight.skyFixed(this, skyOld, skyNew);
            CubeLight.cubeLoaded(this, i, stale);
        }
        else st.relight(this);
        for (int n = 0; n < handlers.size(); n++) {                         // как при загрузке столбца (setBlock → onPlace)
            Block b = handlers.get(n);
            Point at = CoordConversion.chunkBlockIndexGetGlobal(handlerAt.get(n), chunkX, chunkZ);
            try { b.handler().onPlace(new BlockHandler.Placement(b, Block.AIR, instance, at)); }
            catch (Throwable t) { LOG.warn("onPlace при загрузке куба {}: {}", at, t.toString()); }
        }
        CompletableFuture<Void> f;
        synchronized (q) { f = waiters == null ? null : waiters.remove(i); }
        if (f != null) f.complete(null);
        CubeStore.ready(this, i);
        return true;
    }

    /** Столбец лёг в инстанс (Minestom: сразу за cacheChunk): свет — отложенный COLUMN считать сейчас. */
    @Override
    protected void onLoad() {
        super.onLoad();
        if (CubeLight.active(this)) CubeLight.columnCached(this);
    }

    /** Столбец выгружен: ждущие — ошибкой, очередь — забыть (рабочие увидят isLoaded = false). */
    @Override
    protected void unload() {
        super.unload();
        if (TallWorld.isCubes(instance)) CubeLight.columnGone(this);
        List<CompletableFuture<Void>> fs;
        synchronized (q) {
            fs = waiters == null ? List.of() : new ArrayList<>(waiters.values());
            if (waiters != null) waiters.clear();
            if (queuedPrio != null) {
                for (int i : queuedPrio.keySet()) if (state[i] == QUEUED) ST.setVolatile(state, i, ABSENT);
                queuedPrio.clear();
            }
        }
        for (var f : fs) f.completeExceptionally(new IllegalStateException("столбец " + chunkX + " " + chunkZ + " выгружен"));
    }

    // ───────────── свет кубами (шаг 12) ─────────────

    CubeLight.Store lightStore() { return lightStore; }

    /** op 16: кэш CubeLight.Column.floorInfo (верх skyTop, верхний непустой куб) и флаг «устарел» — сбрасывают события света. */
    private volatile long floorInfo;
    private volatile boolean floorDirty = true;

    /** Высоты/кубы столбца сменились — floorInfo посчитать заново при следующем запросе. */
    void floorDirty() { floorDirty = true; }

    /** Доступ CubeLight к кубам столбца: палитры читаются без замка (как ColumnLight); гонку правит событие правки. */
    private final class LightCol implements CubeLight.Column {
        @Override public int cx() { return chunkX; }
        @Override public int cz() { return chunkZ; }
        @Override public boolean ready(int i) { return CubeColumn.this.ready(i); }

        @Override public boolean maybeBlocks(int i) {
            byte s = (byte) ST.getVolatile(state, i);
            if (s != READY && s != UNLOADING) return true;                  // не в памяти — может быть что угодно
            return cubes.get(i).blockPalette().count() > 0;
        }

        @Override public int skyTop(int c) { return st.heights.skyTop(c); }

        /**
         * Этап 2: зовёт поток света — копия под замком чтения столбца (setBlock/install/выгрузка — под замком записи):
         * getAll посреди set с ростом палитры мог бы упасть или дать мусор. Дальше расчёт без замков.
         */
        @Override public boolean ids(int i, int[] out) {
            lockReadLock();
            try {
                Palette p = cubes.get(i).blockPalette();
                if (p.count() == 0) return false;
                int v = p.singleValue();
                if (v >= 0) java.util.Arrays.fill(out, v);
                else p.getAll((x, y, z, id) -> out[y << 8 | z << 4 | x] = id);
                return true;
            } finally { unlockReadLock(); }
        }

        @Override public int id(int i, int x, int y, int z) {
            lockReadLock();
            try { return cubes.get(i).blockPalette().get(x, y, z); }
            finally { unlockReadLock(); }
        }
        @Override public CubeLight.Store store() { return lightStore; }
        @Override public boolean mayEmit(int i) { return CubeLight.mayEmit(cubes.get(i).blockPalette()); }

        @Override public long floorInfo() {
            if (floorDirty) {                                               // сначала снять флаг: правка во время счёта поставит его снова
                floorDirty = false;
                floorInfo = CubeLight.computeFloorInfo(this);
            }
            return floorInfo;
        }
    }

    // ───────────── TallColumn ─────────────

    @Override public ColumnState st() { return st; }
    @Override public Section materialize(int index) { dropHash(index); return cubes.materialize(index); }
    @Override public boolean isReal(int i) { return cubes.isReal(i); }
    @Override public int realSections() { return cubes.real(); }
    @Override public void putSection(int i, Section s) { cubes.set(i, s); dropHash(i); }

    // ───────────── кэш клиентов (0.51): версия куба ─────────────

    /**
     * Хэш содержимого куба i (CubeHash.of): посчитанный или сейчас. Звать под замком чтения/записи столбца (иначе
     * запись посреди подсчёта). Куб не в памяти — 0 («неизвестно»).
     */
    @Override
    public long cubeHash(int i) {
        if (i < 0 || i >= CUBES || !inMemory(i)) return 0;
        synchronized (q) {
            long h = hashes.get(i);
            if (h != 0) return h;
        }
        return cubeHashFresh(i);
    }

    /** Хэш куба i заново по содержимому (для ссылки op 13 — только он). Под замком чтения/записи столбца. */
    @Override
    public long cubeHashFresh(int i) {
        if (i < 0 || i >= CUBES || !inMemory(i)) return 0;
        long h = CubeHash.of(cubes.get(i));
        storeHash(i, h);
        return h;
    }

    private void storeHash(int i, long h) { synchronized (q) { hashes.put(i, h); } }

    private void dropHash(int i) { synchronized (q) { hashes.remove(i); } }

    /** Посчитанных хэшей (для /hocubes stats). */
    int hashCount() { synchronized (q) { return hashes.size(); } }

    @Override
    public Map<Integer, Block> dataBlocks(int y0, int y1) {
        Map<Integer, Block> m = new HashMap<>();
        for (var e : entries.int2ObjectEntrySet()) {
            int y = CoordConversion.chunkBlockIndexGetY(e.getIntKey());
            if (y >= y0 && y <= y1) m.put(e.getIntKey(), e.getValue());
        }
        return m;
    }

    @Override
    public void afterLoad() {
        synchronized (q) { hashes.clear(); }
        // кэш клиентов: версии кубов не-ленивой карты — здесь (поток загрузки), а не в тике при уходе куба от игрока
        if (CubeCacheModel.anyActive()) for (int i = 0; i < CUBES; i++)
            if (cubes.isReal(i) && cubes.get(i).blockPalette().singleValue() < 0) storeHash(i, CubeHash.of(cubes.get(i)));
        st.heights.recompute(cubes, MIN_SECTION);
        skeleton.invalidate();
        st.loaded();                                                        // как на диске / как сгенерирован
        TallWorld.loaded(this);                                          // при заливке setBlock пометил свет — слать не надо
        if (CubeLight.active(this)) CubeLight.columnLoaded(this);
    }

    // ───────────── блоки и биомы ─────────────

    @Override
    public void setBlock(int x, int y, int z, Block block, BlockHandler.Placement placement, BlockHandler.Destroy destroy) {
        assertWriteLock();
        int i = (y >> 4) - MIN_SECTION;
        if (i < 0 || i >= CUBES) {
            LOG.warn("CubeColumn: блок вне мира y {} (столбец {} {})", y, chunkX, chunkZ);
            return;
        }
        if ((byte) ST.getVolatile(state, i) != READY) {                     // §4.2: куба нет в памяти / выгружается — запись потеряла бы данные
            CubeStore.violation(this, i, "setBlock");
            throw new IllegalStateException("cube not loaded: " + chunkX + " " + (i + MIN_SECTION) + " " + chunkZ);
        }
        Section sec = block.isAir() ? cubes.get(i) : cubes.materialize(i);   // воздух в пустую — нечего менять
        if (sec != TallSections.EMPTY) sec.blockPalette().set(x & 15, y & 15, z & 15, block.stateId());
        dropHash(i);                                                        // версия куба (кэш клиентов) — заново

        final int index = CoordConversion.chunkBlockIndex(x, y, z);
        final BlockHandler handler = block.handler();
        final Block last;
        if (handler != null || block.hasNbt() || block.registry().isBlockEntity()) last = entries.put(index, block);
        else last = entries.remove(index);
        if (handler != null && handler.isTickable()) tickable.put(index, block);
        else tickable.remove(index);

        // высоты, память, свет, «на диск» — до обработчиков: они могут ставить блоки сами
        int m0 = st.heights.get(HeightIndex.Kind.MOTION, x, z), s0 = st.heights.get(HeightIndex.Kind.SURFACE, x, z);
        int t0 = st.heights.get(HeightIndex.Kind.SKY, x, z);
        st.heights.update(cubes, MIN_SECTION, x, y, z, block.stateId(), lazy ? unknown : null);
        if (block.isAir()) cubes.release(i);                               // куб опустел — память назад
        if (m0 != st.heights.get(HeightIndex.Kind.MOTION, x, z) || s0 != st.heights.get(HeightIndex.Kind.SURFACE, x, z))
            skeleton.invalidate();
        boolean cubeLight = CubeLight.active(this);
        st.changed(this, i, !cubeLight);
        if (cubeLight)                                                      // шаг 12: свет кубами — 3×3×3 (+ полоса skyTop)
            CubeLight.edit(this, chunkX << 4 | (x & 15), y, chunkZ << 4 | (z & 15), t0, st.heights.get(HeightIndex.Kind.SKY, x, z));
        if (lazy) touch(i);                                                 // пишут — интерес (шаг 11: не выгружать)

        // обработчики — как DynamicChunk
        final Point at = CoordConversion.chunkBlockRelativeGetGlobal(x & 15, y, z & 15, chunkX, chunkZ);
        if (last != null && last.handler() != null)
            last.handler().onDestroy(Objects.requireNonNullElseGet(destroy, () -> new BlockHandler.Destroy(last, block, instance, at)));
        if (handler != null)
            handler.onPlace(Objects.requireNonNullElseGet(placement,
                    () -> new BlockHandler.Placement(block, Objects.requireNonNullElseGet(last, () -> getBlock(at, Condition.TYPE)), instance, at)));
    }

    @Override
    public Block getBlock(int x, int y, int z, Condition condition) {
        assertReadLock();
        int i = (y >> 4) - MIN_SECTION;
        if (i < 0 || i >= CUBES) return Block.AIR;
        if (condition != Condition.TYPE) {
            final Block e = entries.isEmpty() ? null : entries.get(CoordConversion.chunkBlockIndex(x, y, z));
            if (e != null || condition == Condition.CACHED) return e;
        }
        int id = cubes.get(i).blockPalette().get(x & 15, y & 15, z & 15);
        return Objects.requireNonNullElse(Block.fromStateId(id), Block.AIR);
    }

    @Override
    public void setBiome(int x, int y, int z, RegistryKey<Biome> biome) {
        assertWriteLock();
        int i = (y >> 4) - MIN_SECTION;
        if (i < 0 || i >= CUBES) return;
        if ((byte) ST.getVolatile(state, i) != READY) {
            CubeStore.violation(this, i, "setBiome");
            throw new IllegalStateException("cube not loaded: " + chunkX + " " + (i + MIN_SECTION) + " " + chunkZ);
        }
        int id = MinecraftServer.getBiomeRegistry().getId(biome);
        if (id == -1) throw new IllegalStateException("Biome has not been registered: " + biome.key());
        cubes.materialize(i).biomePalette().set((x & 15) / 4, (y & 15) / 4, (z & 15) / 4, id);
        dropHash(i);
        cubes.release(i);                                                  // пустой куб с биомом как у общей — назад
        skeleton.invalidate();
        st.changed(this, i, false);
        if (lazy) touch(i);
    }

    @Override
    public RegistryKey<Biome> getBiome(int x, int y, int z) {
        assertReadLock();
        int i = Math.max(0, Math.min(CUBES - 1, (y >> 4) - MIN_SECTION));
        int id = cubes.get(i).biomePalette().get((x & 15) / 4, (y & 15) / 4, (z & 15) / 4);
        RegistryKey<Biome> key = MinecraftServer.getBiomeRegistry().getKey(id);
        if (key == null) throw new IllegalStateException("Biome with id " + id + " is not registered");
        return key;
    }

    // ───────────── секции и высоты ─────────────

    /** Неизменяемый вид: пустые — общая EMPTY (писать в неё нельзя — /hocubes stats ловит). */
    @Override public List<Section> getSections() { return view; }

    /** Секция по номеру (Y >> 4); вне мира — тоже EMPTY: никогда не бросает. */
    @Override
    public Section getSection(int section) {
        int i = section - MIN_SECTION;
        return i >= 0 && i < CUBES ? cubes.get(i) : TallSections.EMPTY;
    }

    @Override public Heightmap motionBlockingHeightmap() { return motion; }
    @Override public Heightmap worldSurfaceHeightmap() { return surface; }

    /** Высоты ведёт HeightIndex (из палитр) — с диска не берём. */
    @Override public void loadHeightmapsFromNBT(CompoundBinaryTag heightmaps) { assertWriteLock(); }

    @Override
    public void tick(long time) {
        if (tickable.isEmpty()) return;
        tickable.int2ObjectEntrySet().fastForEach(e -> {
            final Block b = e.getValue();
            final BlockHandler h = b.handler();
            if (h == null) return;
            // куб выгружается: обработчик, ставящий блок, получил бы исключение (§4.2) — пропустить тик
            if (lazy && (byte) ST.getVolatile(state, (CoordConversion.chunkBlockIndexGetY(e.getIntKey()) >> 4) - MIN_SECTION) != READY) return;
            h.tick(new BlockHandler.Tick(b, instance, CoordConversion.chunkBlockIndexGetGlobal(e.getIntKey(), chunkX, chunkZ)));
        });
    }

    // ───────────── отправка игрокам ─────────────

    @Override
    public SendablePacket getFullDataPacket() {
        if (!TallWorld.isCubes(instance)) return fullPacket();
        return skeleton;
    }

    /** Скелет: все секции пустые (биомы настоящие), высоты из HeightIndex, без света и блок-энтити. */
    private ChunkDataPacket skeletonPacket() {
        var biomeSer = Palette.biomeSerializer(MinecraftServer.getBiomeRegistry().size());
        Palette air = Palette.blocks();
        byte[] data;
        Map<Heightmap.Type, long[]> hm;
        lockReadLock();
        try {
            hm = Map.of(motion.type(), motion.getNBT(), surface.type(), surface.getNBT());
            data = NetworkBuffer.makeArray(buf -> {
                for (int i = 0; i < CUBES; i++) {
                    buf.write(NetworkBuffer.SHORT, (short) 0);
                    buf.write(Palette.BLOCK_SERIALIZER, air);
                    buf.write(biomeSer, cubes.get(i).biomePalette());
                }
            });
        } finally { unlockReadLock(); }
        var empty = new LightData(new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of());
        return new ChunkDataPacket(chunkX, chunkZ, new ChunkData(hm, data, Map.of()), empty);
    }

    /** Полный столбец (карта не на кубовом протоколе): блоки всех секций, блок-энтити, свет ColumnLight. */
    private ChunkDataPacket fullPacket() {
        LightData light = light();                                         // вне замка — как DynamicChunk
        var biomeSer = Palette.biomeSerializer(MinecraftServer.getBiomeRegistry().size());
        lockReadLock();
        try {
            Map<Heightmap.Type, long[]> hm = Map.of(motion.type(), motion.getNBT(), surface.type(), surface.getNBT());
            byte[] data = NetworkBuffer.makeArray(buf -> {
                for (int i = 0; i < CUBES; i++) {
                    Section s = cubes.get(i);
                    buf.write(NetworkBuffer.SHORT, (short) s.blockPalette().count());
                    buf.write(Palette.BLOCK_SERIALIZER, s.blockPalette());
                    buf.write(biomeSer, s.biomePalette());
                }
            });
            return new ChunkDataPacket(chunkX, chunkZ, new ChunkData(hm, data, new HashMap<>(entries)), light);
        } finally { unlockReadLock(); }
    }

    /**
     * Пересылка столбца (BlockPlacementListener.refresh, ломание воздуха, ChunkBatch, generateChunk): на кубовой
     * карте скелет стёр бы у игрока все кубы столбца — вместо него ресинк кубов (CubeStreamer.resync, §4.2).
     */
    @Override
    public void sendChunk(Player player) {
        if (TallWorld.isCubes(instance) && CubeStreamer.resync(player, this)) return;
        player.sendChunk(this);
    }

    @Override
    public void sendChunk() {
        // кубовая карта: без getViewers() — в Minestom он на каждый вызов обходит 65×65 столбцов трекера (CubeStreamer.seers)
        for (Player p : TallWorld.isCubes(instance) ? CubeStreamer.seers(this) : List.copyOf(getViewers())) sendChunk(p);
    }

    /**
     * Пакеты про блок (BlockChange, BlockEntityData, BlockAction — InstanceContainer.setBlock, сундуки) — только
     * тем, у кого этот куб есть (CubeStreamer.sentCube): у остальных куба нет, он придёт целиком при входе в окно.
     * Остальное (выгрузка столбца и т.п.) — всем зрителям.
     */
    @Override
    public void sendPacketToViewers(SendablePacket packet) {
        Point pos = blockPos(packet);
        if (pos == null || !TallWorld.isCubes(instance) || !(packet instanceof ServerPacket sp)) {
            java.util.Collection<Player> to = TallWorld.isCubes(instance) ? CubeStreamer.seers(this) : new java.util.ArrayList<>(getViewers());
            if (packet instanceof ServerPacket sp) PacketSendingUtils.sendGroupedPacket(to, sp);
            else to.forEach(p -> p.sendPacket(packet));
            return;
        }
        // кому куб отправлен — и кольцу 33 (его нет в getViewers): иначе правка там у игрока не видна до пересылки куба
        PacketSendingUtils.sendGroupedPacket(CubeStreamer.receivers(this), sp, p -> CubeStreamer.sentCube(p, pos));
    }

    private static Point blockPos(SendablePacket p) {
        if (p instanceof BlockChangePacket b) return b.blockPosition();
        if (p instanceof BlockEntityDataPacket b) return b.blockPosition();
        if (p instanceof BlockActionPacket b) return b.blockPosition();
        return null;
    }

    // ───────────── копия, снимок, сброс ─────────────

    /** Копия мира (InstanceContainer.copy): свои кубы клонами, блоки с данными; «изменён» — при сохранении все кубы. */
    @Override
    public Chunk copy(Instance instance, int chunkX, int chunkZ) {
        assertReadLock();
        for (int i = 0; i < CUBES; i++)                                     // копия без незагруженных кубов потеряла бы их
            if (state[i] != READY) throw new IllegalStateException("CubeColumn.copy: куб " + (i + MIN_SECTION) + " не в памяти — сначала loadCubes");
        CubeColumn c = new CubeColumn(instance, chunkX, chunkZ);
        for (int i = 0; i < CUBES; i++) if (cubes.isReal(i)) c.cubes.set(i, cubes.get(i).clone());
        c.entries.putAll(entries);
        c.tickable.putAll(tickable);
        c.afterLoad();
        c.st.diskCubes = null;
        c.st.modified = true;
        return c;
    }

    /**
     * ChunkBatch с полной заменой зовёт reset перед записью. Стирать столбец кубовой карты нельзя (потеря данных
     * при сохранении): ничего не делаем, пишем в лог — ChunkBatch на кубовых картах не использовать.
     */
    @Override
    public void reset() {
        assertWriteLock();
        LOG.error("CubeColumn.reset ({} {}) — не стираю: ChunkBatch на кубовых картах не использовать", chunkX, chunkZ,
                new IllegalStateException("reset"));
    }

    @Override
    public void invalidate() { skeleton.invalidate(); }

    @Override
    public ChunkSnapshot updateSnapshot(SnapshotUpdater updater) {
        Section[] secs = new Section[CUBES];
        Int2ObjectOpenHashMap<Block> ent;
        lockReadLock();
        try {
            for (int i = 0; i < CUBES; i++) secs[i] = cubes.isReal(i) ? cubes.get(i).clone() : TallSections.EMPTY;   // снимок только читают
            ent = entries.clone();
        } finally { unlockReadLock(); }
        int[] ids = instance.getEntityTracker().chunkEntities(chunkX, chunkZ, EntityTracker.Target.ENTITIES)
                .stream().mapToInt(Entity::getEntityId).toArray();
        return new SnapshotImpl.Chunk(minSection, chunkX, chunkZ, secs, ent, ids, updater.reference(instance),
                tagHandler().readableCopy());
    }
}
