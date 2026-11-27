package ink.hoka.odyssey.server.tall;

import ink.hoka.odyssey.server.tall.region.ColumnHeader;
import ink.hoka.odyssey.server.tall.region.CubeCodec;
import ink.hoka.odyssey.server.tall.region.RegionFile;
import ink.hoka.odyssey.server.tall.region.RegionFiles;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Хранение высокой карты регионами кубов (CUBIC-CHUNKS.md §4.9, этап 4 шаг 7; map.yml {@code store: cubes}).
 * В памяти — CubeColumn (шаг 9, все кубы столбца; factory — можно TallChunk, как было).
 * <ul>
 *   <li>cols/r.&lt;rx&gt;.&lt;rz&gt;.hcl — заголовки столбцов (32×32 на файл): маска «куб на диске», высоты, биом;</li>
 *   <li>cubes/r.&lt;rx&gt;.&lt;ry&gt;.&lt;rz&gt;.hcr — кубы (16×16×16 на файл), CubeCodec.</li>
 * </ul>
 * Пишем только кубы, отличающиеся от CubeGen (блоки 4096, биомы, блоки с данными): нетронутое воссоздаст генератор.
 * Верфь кораблей (X ≥ YARD_X0) — все непустые кубы. Нет столбца в регионе, а .htw есть (htwDir) — читаем .htw
 * (ленивая миграция: при следующем сохранении столбец уйдёт в регионы).
 */
public final class CubeRegionLoader implements ChunkLoader {

    private static final Logger LOG = LoggerFactory.getLogger("tall");
    /** Высота карты в кубах (map.yml min_y/height; по умолчанию — как у старых карт: номера кубов на диске — от низа). */
    final int MIN_SECTION, CUBES;

    private final Path cols, cubes;
    /** Папка карты (кэш клиентов: CacheKeys берёт оттуда cubecache.id). */
    final Path mapDir;
    private final TallLoader htw;
    private final TallLoader.Factory factory;
    /** Замки столбцов: сохранение и загрузка одного столбца не идут одновременно (иначе прочтём полузаписанный). */
    private final Object[] locks = new Object[256];
    /** Шаг 10: кубы столбцов грузить по запросу (CubeStore), а не все при загрузке столбца. Ставит Maps. */
    private volatile boolean lazy;
    /** Шаг 11: выгружать кубы без интереса (только вместе с lazy). map.yml cube_unload, по умолчанию да. */
    private volatile boolean unload;
    /** Генератор карты (map.yml generator, ставит Maps до первого столбца); по умолчанию — тестовая земля. */
    private volatile CubeGen gen = FlatTallGen.INSTANCE;
    /** Тег заголовка столбца: каким генератором (id@версия) считались его высоты и кубы. */
    public static final String GEN_TAG = "gen";

    public void setGen(CubeGen g) { this.gen = java.util.Objects.requireNonNull(g); }

    public CubeGen gen() { return gen; }

    /** Генератор столбца из заголовка (нет тега — записан до генераторов-модулей, то есть flat@1). */
    static String genOf(ColumnHeader h) { return h.tags.getOrDefault(GEN_TAG, "flat@1"); }

    public void setUnload(boolean unload) { this.unload = unload; }

    /** Кубы выгружаются (ленивая карта и выгрузка не выключена). */
    public boolean unload() { return lazy && unload; }

    /** Объём регионов на диске: [байт заголовков, байт кубов, файлов]. Обходит папки — не в тике. */
    public long[] diskUsage() {
        long[] r = new long[3];
        for (int k = 0; k < 2; k++) {
            Path d = k == 0 ? cols : cubes;
            if (!Files.isDirectory(d)) continue;
            try (var st = Files.list(d)) {
                for (Path f : (Iterable<Path>) st::iterator) {
                    try { r[k] += Files.size(f); r[2]++; } catch (IOException ignored) { }
                }
            } catch (IOException ignored) { }
        }
        return r;
    }

    /** Включить ленивую загрузку кубов (только карта на кубовом протоколе: игроку шлются только READY-кубы). */
    public void setLazy(boolean lazy) { this.lazy = lazy; }

    public boolean lazy() { return lazy; }

    /** Столбец cx грузить лениво: верфь кораблей (X ≥ YARD_X0) — всегда целиком, как раньше. */
    boolean lazyFor(int chunkX) { return lazy && chunkX * 16 < TallChunk.YARD_X0; }

    /** mapDir — папка карты; htwDir — запасной источник .htw (null — без ленивой миграции). Столбцы — CubeColumn. */
    public CubeRegionLoader(Path mapDir, Path htwDir) { this(mapDir, htwDir, CubeColumn::new); }

    /** factory — чем создавать столбцы (CubeColumn::new на карте, TallChunk::new — старое поведение). */
    public CubeRegionLoader(Path mapDir, Path htwDir, TallLoader.Factory factory) {
        this.factory = factory;
        this.mapDir = mapDir;
        TallWorld.Dims dims = TallWorld.dims(mapDir.getFileName() == null ? null : mapDir.getFileName().toString());
        this.MIN_SECTION = dims.minSection();
        this.CUBES = dims.cubes();
        this.cols = mapDir.resolve("cols");
        this.cubes = mapDir.resolve("cubes");
        this.htw = htwDir != null && Files.isDirectory(htwDir) ? new TallLoader(htwDir, factory) : null;
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    private Object lock(int x, int z) { return locks[(x * 31 + z) & 255]; }

    private Path colFile(int x, int z) { return cols.resolve("r." + (x >> 5) + "." + (z >> 5) + ".hcl"); }
    private static int colIndex(int x, int z) { return (x & 31) | (z & 31) << 5; }

    private Path cubeFile(int x, int cy, int z) { return cubes.resolve("r." + (x >> 4) + "." + (cy >> 4) + "." + (z >> 4) + ".hcr"); }
    private static int cubeIndex(int x, int cy, int z) { return (x & 15) | (cy & 15) << 4 | (z & 15) << 8; }

    /** Заголовок столбца или null — столбца в регионе нет. */
    ColumnHeader header(int x, int z) throws IOException {
        byte[] b = RegionFiles.use(colFile(x, z), 1024, false, f -> f.read(colIndex(x, z)));
        return b == null ? null : ColumnHeader.decode(b);
    }

    /** Куб i столбца с диска или null — записи нет. */
    CubeCodec.Decoded cube(int x, int i, int z) throws IOException {
        int cy = i + MIN_SECTION;
        byte[] b = RegionFiles.use(cubeFile(x, cy, z), 4096, false, f -> f.read(cubeIndex(x, cy, z)));
        return b == null ? null : CubeCodec.decode(b);
    }

    @Override
    public Chunk loadChunk(Instance instance, int x, int z) {
        synchronized (lock(x, z)) {
            ColumnHeader h;
            try { h = header(x, z); }
            catch (RegionFile.Corrupt e) {
                LOG.error("заголовок столбца {} {} битый ({}) — беру .htw или генерирую", x, z, e.toString());
                h = null;
            } catch (IOException e) {
                // не повреждение, а сбой чтения (нет дескрипторов, диск занят): НЕ генерировать — иначе при
                // сохранении новый заголовок затрёт настоящий. Minestom отдаст ошибку, столбец попросят снова.
                throw new java.io.UncheckedIOException("столбец " + x + " " + z + ": заголовок не прочитан", e);
            }
            if (h == null) return fallback(instance, x, z);
            return fromRegion(instance, x, z, h);
        }
    }

    /** Столбца нет в регионе: .htw (ленивая миграция) или null — сгенерировать. */
    private Chunk fallback(Instance instance, int x, int z) {
        if (htw == null) return null;
        Chunk c = htw.loadChunk(instance, x, z);
        if (c instanceof TallColumn tc) {
            tc.st().diskCubes = null;                                  // в регионе ничего нет: при сохранении — все кубы
            tc.st().modified = true;                                   // перенести в регионы при ближайшем сохранении
        }
        return c;
    }

    private Chunk fromRegion(Instance instance, int x, int z, ColumnHeader h) {
        TallColumn c = factory.create(instance, x, z);
        BitSet disk = (BitSet) h.onDisk.clone();
        c.st().loadClear(CubeMark.decodeTag(h.tags.get(CubeMark.TAG), CUBES));   // шаг 13: «нет вклада в высоты» кубов на диске
        c.st().loadSkyCols(CubeMark.decodeCols(h.tags.get(CubeMark.COLS_TAG), CUBES));   // маски колонок неба кубов на диске
        if (c instanceof CubeColumn cc && lazyFor(x)) {                  // шаг 10: кубы — по запросу, высоты — из заголовка
            // генератор сменился (id/версия) — высоты заголовка от старого рельефа: по новому генератору + с запасом над кубами с диска
            if (genOf(h).equals(gen.tag()) && MIN_SECTION << 4 >= HeightIndex.LEGACY_NONE)   // старая «пустота» −15009 → NONE
                for (short[] a : h.heights) for (int q = 0; q < a.length; q++) if (a[q] == HeightIndex.LEGACY_NONE) a[q] = (short) HeightIndex.NONE;
            cc.initLazy(this, disk, genOf(h).equals(gen.tag()) ? h.heights : null);
            c.st().diskCubes = disk;
            return cc;
        }
        c.fillGen(disk);
        BitSet failed = new BitSet(), missing = new BitSet();
        for (int i = disk.nextSetBit(0); i >= 0 && i < CUBES; i = disk.nextSetBit(i + 1)) {
            CubeCodec.Decoded d;
            try { d = cube(x, i, z); }
            catch (RegionFile.Corrupt e) {
                // битая запись остаётся в регионе (вдруг восстановим руками); затрётся, только если куб поменяют
                LOG.error("куб {} {} {} битый ({}) — в памяти как генератор, запись в регионе не трогаю", x, i + MIN_SECTION, z, e.toString());
                failed.set(i);
                continue;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("куб " + x + " " + (i + MIN_SECTION) + " " + z + " не прочитан", e);
            }
            if (d == null) { failed.set(i); missing.set(i); continue; }   // в заголовке есть, в регионе нет — как генератор
            if (d.unknown() > 0) LOG.warn("куб {} {} {}: незнакомых имён {} (стали воздухом/биомом 0)", x, i + MIN_SECTION, z, d.unknown());
            Section s = new Section();
            s.blockPalette().copyFrom(d.blocks());
            s.biomePalette().copyFrom(d.biomes());
            c.putSection(i, s);
            int y0 = (i + MIN_SECTION) << 4;
            for (var e : d.data().entrySet()) {
                int k = e.getKey();
                c.lockWriteLock();
                try { c.setBlock(x * 16 + (k & 15), y0 + (k >> 8), z * 16 + ((k >> 4) & 15), e.getValue()); }
                finally { c.unlockWriteLock(); }
            }
        }
        if (!failed.isEmpty()) {                                        // битые/пропавшие — генератором
            BitSet skip = new BitSet(CUBES);
            skip.set(0, CUBES);
            skip.andNot(failed);
            c.fillGen(skip);
        }
        c.afterLoad();
        c.st().diskCubes = disk;
        if (!missing.isEmpty()) {                                       // пропавшие — при сохранении убрать из заголовка
            c.st().restoreDirty(missing);
            c.st().modified = true;
        }
        return c.chunk();
    }

    /**
     * Прочитанный куб для CubeColumn (шаг 10): секция (null — пустой), блоки с данными (ключ — chunkBlockIndex), missing;
     * mark — отметка высот с диска (CubeMark, 0 — нет: генератор или запись до шага 13).
     */
    record Loaded(Section section, it.unimi.dsi.fastutil.ints.Int2ObjectMap<Block> data, boolean missing, int mark) {
        Loaded(Section section, it.unimi.dsi.fastutil.ints.Int2ObjectMap<Block> data, boolean missing) { this(section, data, missing, 0); }
    }

    /**
     * Куб i столбца с диска (рабочий поток CubeStore, без замков столбца). Битый — как генератор, запись в регионе не
     * трогаем (как при загрузке столбца); в заголовке есть, а записи нет — генератор + missing. Сбой чтения — исключение
     * (НЕ генерировать: при сохранении затёрли бы настоящий куб).
     */
    Loaded loadCube(int x, int i, int z) throws IOException {
        CubeCodec.Decoded d;
        try { d = cube(x, i, z); }
        catch (RegionFile.Corrupt e) {
            LOG.error("куб {} {} {} битый ({}) — в памяти как генератор, запись в регионе не трогаю", x, i + MIN_SECTION, z, e.toString());
            return generate(gen, x, i, z, MIN_SECTION);
        }
        if (d == null) {
            Loaded g = generate(gen, x, i, z, MIN_SECTION);
            return new Loaded(g.section(), g.data(), true);
        }
        if (d.unknown() > 0) LOG.warn("куб {} {} {}: незнакомых имён {} (стали воздухом/биомом 0)", x, i + MIN_SECTION, z, d.unknown());
        Section s = new Section();
        s.blockPalette().copyFrom(d.blocks());
        s.biomePalette().copyFrom(d.biomes());
        var data = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<Block>();
        int y0 = (i + MIN_SECTION) << 4;
        for (var e : d.data().entrySet()) {
            int k = e.getKey();
            data.put(CoordConversion.chunkBlockIndex(k & 15, y0 + (k >> 8), (k >> 4) & 15), e.getValue());
        }
        return new Loaded(s, data, false, d.heightMark());
    }

    /**
     * Куб i столбца генератором g. Недетерминированный генератор: непустой куб — сразу «изменён» (missing → dirty),
     * иначе при выгрузке он пропал бы, а потом сгенерировался другим.
     */
    static Loaded generate(CubeGen g, int x, int i, int z, int minSection) {
        int cy = i + minSection;
        var data = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<Block>();
        if (!g.hasBlocks(x, cy, z)) return new Loaded(null, data, false);
        CubeGen.Cube cube = CubeGen.generate(g, x, cy, z);
        for (var e : cube.specials().int2ObjectEntrySet()) {             // Y ключа — в кубе
            int k = e.getIntKey();
            data.put(CoordConversion.chunkBlockIndex(CoordConversion.chunkBlockIndexGetX(k) & 15, (cy << 4) + CoordConversion.chunkBlockIndexGetY(k),
                    CoordConversion.chunkBlockIndexGetZ(k) & 15), e.getValue());
        }
        return new Loaded(cube.blank() ? null : cube.section(), data, !g.deterministic() && !cube.empty());   // воздух со своими биомами — секцией
    }

    @Override public boolean supportsParallelLoading() { return true; }
    @Override public boolean supportsParallelSaving() { return true; }

    @Override
    public void saveChunk(Chunk chunk) {
        if (!(chunk instanceof TallColumn c)) return;
        try { save(c); }
        catch (Exception e) { LOG.error("столбец {} {} не сохранён в регионы: {}", c.getChunkX(), c.getChunkZ(), e.toString()); }
    }

    /** Снимок куба: палитры (null — общая пустая секция) и блоки с данными (ключ — индекс в кубе). */
    private record Snap(int i, Palette blocks, Palette biomes, Map<Integer, Block> data) {}

    /** Отметка высот куба i (шаг 13): skyTop из снимка высот столбца, «нет вклада» — по снимку палитры. */
    private int mark(Snap s, short[] sky) {
        int y0 = (s.i + MIN_SECTION) << 4;
        return CubeMark.of(y0, c -> sky[c], s.blocks);
    }

    /** Сохранить столбец (бросает при ошибке; столбец тогда снова «изменён»). Ответ: сколько кубов записано. */
    int save(TallColumn c) throws IOException {
        int x = c.getChunkX(), z = c.getChunkZ();
        synchronized (lock(x, z)) {
            if (!c.isModified()) return 0;
            List<Snap> snaps = new ArrayList<>();
            BitSet disk, dirty;
            short[][] heights = new short[ColumnHeader.HEIGHT_KINDS][256];
            CubeGen g = gen;
            c.lockReadLock();                                          // снимок под замком столбца (setBlock Minestom — под его записью)
            try {
                c.st().modified = false;
                dirty = c.st().takeDirty();
                disk = c.st().diskCubes;
                List<Section> secs = c.getSections();
                for (int i = 0; i < CUBES; i++) {
                    if (!c.inMemory(i)) continue;                      // шаг 10/11: куба нет в памяти — на диске как был, не трогаем
                    boolean cand = disk == null ? (c.isReal(i) || g.hasBlocks(x, i + MIN_SECTION, z)) : dirty.get(i);
                    if (!cand) continue;
                    Section s = secs.get(i);
                    int y0 = (i + MIN_SECTION) << 4;
                    Map<Integer, Block> data = new HashMap<>();
                    for (var e : c.dataBlocks(y0, y0 + 15).entrySet()) {
                        int k = e.getKey();
                        data.put(CubeCodec.index(CoordConversion.chunkBlockIndexGetX(k), CoordConversion.chunkBlockIndexGetY(k),
                                CoordConversion.chunkBlockIndexGetZ(k)), e.getValue());
                    }
                    boolean real = c.isReal(i);
                    snaps.add(new Snap(i, real ? s.blockPalette().clone() : null, real ? s.biomePalette().clone() : null, data));
                }
                var kinds = HeightIndex.Kind.values();
                for (int k = 0; k < kinds.length && k < heights.length; k++)
                    for (int col = 0; col < 256; col++) heights[k][col] = (short) c.height(kinds[k], col & 15, col >> 4);
            } finally { c.unlockReadLock(); }
            try {
                BitSet newDisk = disk == null ? new BitSet(CUBES) : (BitSet) disk.clone();
                BitSet stale = new BitSet();
                boolean yard = x * 16 >= TallChunk.YARD_X0;
                int written = 0;
                for (Snap s : snaps) {
                    int cy = s.i + MIN_SECTION;
                    boolean keep;
                    // верфь и недетерминированный генератор: все непустые (генератор не воссоздаст)
                    if (yard || !g.deterministic()) keep = (s.blocks != null && s.blocks.count() > 0) || !s.data.isEmpty();
                    else {
                        CubeGen.Cube gc = g.hasBlocks(x, cy, z) ? CubeGen.generate(g, x, cy, z) : null;
                        keep = !sameAsGen(s, gc);
                    }
                    int mark = mark(s, heights[HeightIndex.Kind.SKY.ordinal()]);
                    c.st().setClear(s.i, mark);                         // куб в памяти: биты нужны, когда его выгрузят
                    c.st().setSkyCols(s.i, CubeMark.skyCols(s.blocks));
                    if (keep) {
                        byte[] b = CubeCodec.encode(s.blocks != null ? s.blocks : Palette.blocks(),
                                s.biomes != null ? s.biomes : Palette.biomes(), s.data, mark);
                        RegionFiles.use(cubeFile(x, cy, z), 4096, true, f -> { f.write(cubeIndex(x, cy, z), b); return null; });
                        newDisk.set(s.i);
                        written++;
                    } else if (newDisk.get(s.i) || disk == null) {
                        newDisk.clear(s.i);
                        stale.set(s.i);
                    }
                }
                if (disk == null) {                                     // столбца в памяти «с диска» не было — старый заголовок мог ссылаться на другие кубы
                    try {
                        ColumnHeader old = header(x, z);
                        if (old != null) { BitSet o = (BitSet) old.onDisk.clone(); o.andNot(newDisk); stale.or(o); }
                    } catch (IOException ignored) { }
                }
                ColumnHeader h = new ColumnHeader(CUBES);
                h.onDisk.or(newDisk);
                h.modified.or(newDisk);
                for (int k = 0; k < heights.length; k++) System.arraycopy(heights[k], 0, h.heights[k], 0, 256);
                h.biome = g.biomeAt(x * 16, z * 16).key().asString();
                h.tags.put(GEN_TAG, g.tag());
                String clear = CubeMark.encodeTag(c.st().clearFor(newDisk));
                if (clear != null) h.tags.put(CubeMark.TAG, clear);
                String cols = c.st().skyColsTag(newDisk);
                if (cols != null) h.tags.put(CubeMark.COLS_TAG, cols);
                byte[] hb = h.encode();
                // порядок: кубы → заголовок → убрать лишние кубы (упали посередине — заголовок ссылается только на записанное)
                // всё как у генератора — заголовок не нужен (4 КБ на столбец); но есть старый .htw — оставить пустой
                // заголовок, иначе при загрузке ленивая миграция вернёт из .htw прежние блоки
                if (newDisk.isEmpty() && (htw == null || !htw.has(x, z)))
                    RegionFiles.use(colFile(x, z), 1024, false, f -> { f.remove(colIndex(x, z)); return null; });
                else RegionFiles.use(colFile(x, z), 1024, true, f -> { f.write(colIndex(x, z), hb); return null; });
                c.st().diskCubes = newDisk;
                for (int i = stale.nextSetBit(0); i >= 0; i = stale.nextSetBit(i + 1)) {
                    int cy = i + MIN_SECTION;
                    RegionFiles.use(cubeFile(x, cy, z), 4096, false, f -> { f.remove(cubeIndex(x, cy, z)); return null; });
                }
                return written;
            } catch (IOException | RuntimeException e) {
                c.st().restoreDirty(dirty);
                c.st().modified = true;                                 // не записалось — в следующий раз
                throw e;
            }
        }
    }

    /** Куб совпадает с генератором (блоки, биомы, блоки с данными). gen null — генератор куб не заполняет. */
    static boolean sameAsGen(Snap s, CubeGen.Cube gen) {
        Palette gb = gen == null ? null : gen.section().blockPalette();
        Palette gbi = gen == null ? null : gen.section().biomePalette();
        if (!samePalette(s.blocks, gb, 16)) return false;
        if (!samePalette(s.biomes, gbi, 4)) return false;
        Map<Integer, Block> gd = new HashMap<>();
        if (gen != null) for (var e : gen.specials().int2ObjectEntrySet()) {
            int k = e.getIntKey();
            gd.put(CubeCodec.index(CoordConversion.chunkBlockIndexGetX(k), CoordConversion.chunkBlockIndexGetY(k),
                    CoordConversion.chunkBlockIndexGetZ(k)), e.getValue());
        }
        return sameData(s.data, gd);
    }

    /** Палитры равны по значениям (null — всё 0). */
    static boolean samePalette(Palette a, Palette b, int dim) {
        if (a == null && b == null) return true;
        if (a != null && b != null && a.count() != b.count()) return false;
        for (int y = 0; y < dim; y++) for (int zz = 0; zz < dim; zz++) for (int xx = 0; xx < dim; xx++) {
            int va = a == null ? 0 : a.get(xx, y, zz), vb = b == null ? 0 : b.get(xx, y, zz);
            if (va != vb) return false;
        }
        return true;
    }

    /** Блоки с данными равны (состояние и NBT; обработчик не сравниваем — он ставится по имени). */
    static boolean sameData(Map<Integer, Block> a, Map<Integer, Block> b) {
        if (a.size() != b.size()) return false;
        for (var e : a.entrySet()) {
            Block o = b.get(e.getKey());
            if (o == null || !o.state().equals(e.getValue().state()) || !Objects.equals(o.nbt(), e.getValue().nbt())) return false;
        }
        return true;
    }

    /**
     * pregen недетерминированного генератора: столбца в регионе нет — сгенерировать кубы cy0…cy1 и записать непустые
     * (как сохранение: кубы → заголовок). Столбец уже есть (правки игроков, прошлый pregen) — не трогаем.
     * Ответ: записано кубов, −1 — столбец уже был.
     */
    public int writeGenerated(int x, int z, int cy0, int cy1) throws IOException {
        CubeGen g = gen;
        synchronized (lock(x, z)) {
            if (header(x, z) != null) return -1;
            Section empty = new Section();
            List<Section> secs = new ArrayList<>(java.util.Collections.nCopies(CUBES, empty));
            Map<Integer, Map<Integer, Block>> datas = new HashMap<>();
            BitSet on = new BitSet(CUBES);
            for (int cy = Math.max(cy0, MIN_SECTION); cy <= Math.min(cy1, MIN_SECTION + CUBES - 1); cy++) {
                if (!g.hasBlocks(x, cy, z)) continue;
                CubeGen.Cube cube = CubeGen.generate(g, x, cy, z);
                if (cube.empty()) continue;
                int i = cy - MIN_SECTION;
                secs.set(i, cube.section());
                Map<Integer, Block> d = new HashMap<>();
                for (var e : cube.specials().int2ObjectEntrySet()) {
                    int k = e.getIntKey();
                    d.put(CubeCodec.index(CoordConversion.chunkBlockIndexGetX(k) & 15, CoordConversion.chunkBlockIndexGetY(k) & 15,
                            CoordConversion.chunkBlockIndexGetZ(k) & 15), e.getValue());
                }
                datas.put(i, d);
                on.set(i);
            }
            if (on.isEmpty()) return 0;
            HeightIndex hi = new HeightIndex();
            hi.recompute(secs, MIN_SECTION);
            short[][] heights = new short[ColumnHeader.HEIGHT_KINDS][256];
            var kinds = HeightIndex.Kind.values();
            for (int k = 0; k < kinds.length && k < heights.length; k++)
                for (int col = 0; col < 256; col++) heights[k][col] = (short) hi.get(kinds[k], col & 15, col >> 4);
            ColumnState st = new ColumnState();
            for (int i = on.nextSetBit(0); i >= 0; i = on.nextSetBit(i + 1)) {
                int cy = i + MIN_SECTION, ii = i;
                Section s = secs.get(i);
                int mark = CubeMark.of(cy << 4, c -> heights[HeightIndex.Kind.SKY.ordinal()][c], s.blockPalette());
                st.setClear(i, mark);
                st.setSkyCols(i, CubeMark.skyCols(s.blockPalette()));
                byte[] b = CubeCodec.encode(s.blockPalette(), s.biomePalette(), datas.get(ii), mark);
                RegionFiles.use(cubeFile(x, cy, z), 4096, true, f -> { f.write(cubeIndex(x, cy, z), b); return null; });
            }
            ColumnHeader h = new ColumnHeader(CUBES);
            h.onDisk.or(on);
            h.modified.or(on);
            for (int k = 0; k < heights.length; k++) System.arraycopy(heights[k], 0, h.heights[k], 0, 256);
            h.biome = g.biomeAt(x * 16, z * 16).key().asString();
            h.tags.put(GEN_TAG, g.tag());
            String clear = CubeMark.encodeTag(st.clearFor(on));
            if (clear != null) h.tags.put(CubeMark.TAG, clear);
            String cols = st.skyColsTag(on);
            if (cols != null) h.tags.put(CubeMark.COLS_TAG, cols);
            byte[] hb = h.encode();
            RegionFiles.use(colFile(x, z), 1024, true, f -> { f.write(colIndex(x, z), hb); return null; });
            return on.cardinality();
        }
    }

    /** Папки регионов (для /hocubes size). */
    public Path colsDir() { return cols; }
    public Path cubesDir() { return cubes; }

    /** Есть ли у столбца запись в регионе (для verify/конвертера). */
    boolean hasColumn(int x, int z) throws IOException { return header(x, z) != null; }

    /** Файлы регионов этой карты закрыть (выгрузка карты, перед переименованием папок). */
    public void close() { RegionFiles.closeUnder(cols); RegionFiles.closeUnder(cubes); }
}
