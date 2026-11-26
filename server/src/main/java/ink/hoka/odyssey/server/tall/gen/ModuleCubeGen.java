package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.worldgen.*;
import ink.hoka.odyssey.server.tall.CubeGen;
import ink.hoka.odyssey.server.tall.HeightIndex;
import ink.hoka.odyssey.server.tall.TallChunk;
import ink.hoka.odyssey.server.tall.TallWorld;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.GenerationUnit;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Генератор модуля (WorldGenerator) как CubeGen ядра — одна точка подключения. Порядок для куба:
 * границы (Bounds, size) и верфь → индекс pregen (GenIndex: пусто/однородный) → hasBlocks/uniform модуля → generate.
 * highest: модуль; не умеет (UNKNOWN) — высоты из индекса; нет — по кубам сверху вниз (LRU ~512 кубов).
 * Исключение генератора — наверх (CubeStore.failed повторит), воздухом НЕ становится.
 */
public final class ModuleCubeGen implements CubeGen {

    public final GeneratorProvider provider;
    public final WorldGenerator w;
    /** Переносимый генератор (gen-api, целые номера) или null — старый на Block. */
    public final ink.hoka.odyssey.gen.PlanetGenerator pg;
    /** Сид секретов (map.yml secret_seed) — только переносимому; null — секретов нет. */
    private final Long secretSeed;
    /** Библиотека объектов карты (снимок при загрузке; хэш — в отпечатках). */
    public final ink.hoka.odyssey.gen.GenObjects objects;
    public final GenSettings settings;
    private final String tag;
    private final boolean det;
    /** Кубы по Y, где вообще могут быть блоки (Bounds ∩ мир). */
    public final int minCy, maxCy;
    private volatile GenIndex index;

    public ModuleCubeGen(GeneratorProvider provider, GenSettings settings) { this(provider, settings, null); }

    public ModuleCubeGen(GeneratorProvider provider, GenSettings settings, Long secretSeed) {
        this(provider, settings, secretSeed, null);
    }

    /** objects — пакет-снимок объектов карты (null/EMPTY — без объектов; только переносимому). */
    public ModuleCubeGen(GeneratorProvider provider, GenSettings settings, Long secretSeed, ink.hoka.odyssey.gen.GenObjects objects) {
        this.provider = provider;
        this.settings = settings;
        this.secretSeed = secretSeed;
        this.objects = objects == null || !(provider instanceof PortableProvider) ? ink.hoka.odyssey.gen.GenObjects.EMPTY : objects;
        this.w = provider instanceof PortableProvider pp ? pp.create(settings, secretSeed, this.objects) : provider.create(settings);
        this.pg = w instanceof PortableWorldGen p ? p.pg : null;
        this.tag = provider.id() + "@" + provider.version();
        this.det = provider.deterministic();
        int top = TallWorld.MIN_Y + TallWorld.HEIGHT - 1;
        this.minCy = Math.max(settings.bounds().minY(), TallWorld.MIN_Y) >> 4;
        this.maxCy = Math.min(settings.bounds().maxY(), top) >> 4;
    }

    /** Подключить индекс pregen (maps/&lt;карта&gt;/genidx); только для детерминированного генератора. */
    public ModuleCubeGen withIndex(Path genidxDir) {
        if (det && genidxDir != null) index = new GenIndex(genidxDir, this);
        return this;
    }

    public GenIndex index() { return index; }

    @Override public String tag() { return tag; }
    @Override public boolean deterministic() { return det; }
    public String id() { return provider.id(); }
    public Bounds bounds() { return settings.bounds(); }

    /** Генератор переносимый (gen-api): нетронутые кубы может генерировать клиент. */
    public boolean portable() { return pg != null; }

    public PortableProvider portableProvider() { return provider instanceof PortableProvider pp ? pp : null; }

    /** Контекст переносимого генератора (как у клиента) или null. */
    public ink.hoka.odyssey.gen.GenContext genContext() { return w instanceof PortableWorldGen p ? p.ctx : null; }

    // ───────────── без индекса (pregen, отпечаток) ─────────────

    /** Куб может быть непустым по границам и верфи (до индекса и модуля). */
    boolean inside(int cx, int cy, int cz) {
        if (cy < minCy || cy > maxCy) return false;
        if (cx * 16 >= TallChunk.YARD_X0) return false;
        return settings.bounds().cubeInside(cx, cy, cz);
    }

    boolean rawHasBlocks(int cx, int cy, int cz) { return inside(cx, cy, cz) && w.hasBlocks(cx, cy, cz); }

    /** Однородный куб по словам модуля (stateId) или -1. */
    int rawUniform(int cx, int cy, int cz) {
        if (pg != null) return Math.max(-1, pg.uniform(cx, cy, cz));
        Block b = w.uniform(cx, cy, cz);
        return b == null || b.hasNbt() || b.handler() != null ? -1 : b.stateId();
    }

    /** Куб генератором модуля (без индекса). Пустой по границам — пустая секция. */
    Cube rawCube(int cx, int cy, int cz) {
        if (!rawHasBlocks(cx, cy, cz)) return new Cube(new Section(), new Int2ObjectOpenHashMap<>(0));
        int u = rawUniform(cx, cy, cz);
        if (u >= 0) return uniformCube(u, 0);
        return generated(cx, cy, cz);
    }

    /** Куб только через generate модуля (без границ, hasBlocks и uniform) — проверка их честности (gentest). */
    Cube directCube(int cx, int cy, int cz) {
        return generated(cx, cy, cz);
    }

    /** generate модуля в новую секцию: переносимый — целыми числами (секреты поверх видимой части), старый — через CubeBuffer. */
    private Cube generated(int cx, int cy, int cz) {
        if (pg != null) return toCube(generateArray(cx, cy, cz, true));
        CubeBufferImpl buf = new CubeBufferImpl(cx, cy, cz, settings.seed());
        w.generate(buf);
        return buf.toCube();
    }

    /** Переносимый: generate в массивы (secrets — сервер, с секретами отдельно). */
    private ink.hoka.odyssey.gen.GenArrayCube generateArray(int cx, int cy, int cz, boolean secrets) {
        var c = new ink.hoka.odyssey.gen.GenArrayCube(secrets && secretSeed != null).reset(cx, cy, cz, settings.seed());
        pg.generate(c);
        return c;
    }

    /** Массивы → секция: видимая часть, поверх секреты; данные блок-сущностей (SNBT) — в specials. */
    static Cube toCube(ink.hoka.odyssey.gen.GenArrayCube c) {
        int[] b = c.blocks;
        if (!c.secretList().isEmpty()) {
            b = b.clone();
            for (var e : c.secretList().entrySet()) b[e.getKey()] = e.getValue();
        }
        final int[] fb = b, bio = c.biomes;
        Section s = new Section();
        boolean any = false;
        for (int v : fb) if (v != 0) { any = true; break; }
        if (any) s.blockPalette().setAll((x, y, z) -> fb[y << 8 | z << 4 | x]);
        boolean anyB = false;
        for (int v : bio) if (v != 0) { anyB = true; break; }
        if (anyB) s.biomePalette().setAll((x, y, z) -> bio[y << 4 | z << 2 | x]);
        Int2ObjectOpenHashMap<Block> sp = new Int2ObjectOpenHashMap<>(0);
        for (var e : c.dataList().entrySet()) {
            int i = e.getKey();
            Block blk = Block.fromStateId(fb[i]);
            if (blk == null || blk.isAir()) continue;
            try { blk = blk.withNbt(net.kyori.adventure.nbt.TagStringIO.tagStringIO().asCompound(e.getValue())); }
            catch (java.io.IOException ex) { throw new IllegalStateException("генератор: битый SNBT в " + i + ": " + ex.getMessage(), ex); }
            sp.put(net.minestom.server.coordinate.CoordConversion.chunkBlockIndex(i & 15, i >> 8, (i >> 4) & 15), blk);
        }
        return new Cube(s, sp);
    }

    /**
     * Видимая часть куба так, как её получит клиент по op 21 (границы и индекс сервера → однородный → generate без
     * секретов), с секретами отдельно (secrets): для выборочной сверки и op 22. Только переносимый генератор.
     */
    public ink.hoka.odyssey.gen.GenArrayCube visible(int cx, int cy, int cz, boolean secrets) {
        if (pg == null) throw new IllegalStateException("генератор не переносимый");
        if (!hasBlocks(cx, cy, cz)) return new ink.hoka.odyssey.gen.GenArrayCube(false).reset(cx, cy, cz, settings.seed());
        int[] u = uniform(cx, cy, cz);
        if (u != null) {
            var c = new ink.hoka.odyssey.gen.GenArrayCube(false).reset(cx, cy, cz, settings.seed());
            c.fill(u[0]);
            c.fillBiome(u[1]);
            return c;
        }
        return generateArray(cx, cy, cz, secrets);
    }

    /** Хэш видимой части (как посчитает клиент) — {@link #visible}. */
    public long visHash(int cx, int cy, int cz) { return visible(cx, cy, cz, false).visHash(); }

    /** Отпечаток переносимого генератора (op 20): {@link ink.hoka.odyssey.gen.GenFingerprint} по настройкам карты. */
    private volatile Long genFingerprint;

    public long genFingerprint() {
        Long f = genFingerprint;
        if (f != null) return f;
        Bounds b = settings.bounds();
        long v = ink.hoka.odyssey.gen.GenFingerprint.of(pg, new ink.hoka.odyssey.gen.GenBounds(b.minY(), b.maxY(), b.size()), settings.seed(),
                objects.hash());
        genFingerprint = v;
        return v;
    }

    /** У переносимого генератора есть сид секретов (секреты возможны — op 22). */
    public boolean secretsEnabled() { return pg != null && secretSeed != null; }

    /** Секреты могут быть в кубе (переносимый, есть сид секретов и генератор так говорит). */
    public boolean mayHaveSecrets(int cx, int cy, int cz) { return pg != null && secretSeed != null && inside(cx, cy, cz) && pg.mayHaveSecrets(cx, cy, cz); }

    static Cube uniformCube(int stateId, int biomeId) {
        Section s = new Section();
        if (stateId != 0) s.blockPalette().fill(stateId);
        if (biomeId != 0) s.biomePalette().fill(biomeId);
        return new Cube(s, new Int2ObjectOpenHashMap<>(0));
    }

    // ───────────── CubeGen ─────────────

    @Override
    public boolean hasBlocks(int cx, int cy, int cz) {
        if (!inside(cx, cy, cz)) return false;
        GenIndex ix = index;
        if (ix != null) {
            GenIndexFile.Col c = ix.col(cx, cz);
            if (c != null) {
                int p = c.at(cy);
                if (p == GenIndexFile.Col.OUT || p == 0) return false;     // вне диапазона или однородный воздух
                return true;
            }
        }
        return w.hasBlocks(cx, cy, cz);
    }

    /** Однородный куб: [stateId, biomeId] или null (индекс, затем модуль). */
    public int[] uniform(int cx, int cy, int cz) {
        if (!inside(cx, cy, cz)) return null;
        GenIndex ix = index;
        if (ix != null) {
            GenIndexFile f = ix.file(cx, cz);                                 // один раз: pregen мог подменить файл между вызовами
            GenIndexFile.Col c = f == null ? null : f.col(cx, cz);
            if (c != null) {
                int p = c.at(cy);
                if (p > 0) return f.resolved(p);
                if (p == GenIndexFile.Col.MIXED) return null;
            }
        }
        int u = rawUniform(cx, cy, cz);
        return u < 0 ? null : new int[]{u, 0};
    }

    @Override
    public Cube cube(int cx, int cy, int cz) {
        if (!hasBlocks(cx, cy, cz)) return new Cube(new Section(), new Int2ObjectOpenHashMap<>(0));
        int[] u = uniform(cx, cy, cz);
        if (u != null) return uniformCube(u[0], u[1]);
        return generated(cx, cy, cz);
    }

    /** Путь Minestom (GenerationUnit) — для совместимости: та же секция, переписанная в unit. */
    @Override
    public void generateCube(int cx, int cy, int cz, GenerationUnit unit) {
        Cube c = cube(cx, cy, cz);
        var m = unit.modifier();
        int x0 = cx << 4, y0 = cy << 4, z0 = cz << 4;
        var p = c.section().blockPalette();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int id = p.get(x, y, z);
            if (id != 0) m.setBlock(x0 + x, y0 + y, z0 + z, Block.fromStateId(id));
        }
        for (var e : c.specials().int2ObjectEntrySet()) {
            int k = e.getIntKey();
            m.setBlock(x0 + (net.minestom.server.coordinate.CoordConversion.chunkBlockIndexGetX(k) & 15),
                    y0 + net.minestom.server.coordinate.CoordConversion.chunkBlockIndexGetY(k),
                    z0 + (net.minestom.server.coordinate.CoordConversion.chunkBlockIndexGetZ(k) & 15), e.getValue());
        }
    }

    @Override
    public int highest(int x, int z, int yFrom, int yTo, HeightIndex.Kind kind) {
        // границы — по кубам (как inside): куб на краю size генерируется целиком, и высоты должны с ним сходиться
        if (x >= TallChunk.YARD_X0 || (x >> 4) * 16 >= TallChunk.YARD_X0 || !settings.bounds().cubeInside(x >> 4, minCy, z >> 4))
            return HeightIndex.NONE;
        int lo = Math.max(yFrom, minCy << 4), hi = Math.min(yTo, (maxCy << 4) + 15);
        if (lo > hi) return HeightIndex.NONE;
        int h = w.highest(x, z, lo, hi, HeightKind.values()[kind.ordinal()]);
        if (h != WorldGenerator.UNKNOWN) return h;
        GenIndex ix = index;
        if (ix != null) {
            GenIndexFile.Col c = ix.col(x >> 4, z >> 4);
            if (c != null && c.heights != null) {
                int t = c.heights[kind.ordinal()][(z & 15) << 4 | (x & 15)];
                if (t < lo) return HeightIndex.NONE;                          // верх колонки ниже диапазона
                if (t <= hi) return t;
            }
        }
        return scan(x, z, lo, hi, kind);
    }

    /** Верх колонки по кубам сверху вниз (кэш кубов). */
    int scan(int x, int z, int lo, int hi, HeightIndex.Kind kind) {
        int cx = x >> 4, cz = z >> 4;
        for (int cy = hi >> 4; cy >= lo >> 4; cy--) {
            if (!hasBlocks(cx, cy, cz)) continue;
            int y0 = cy << 4;
            int top = Math.min(hi, y0 + 15), bot = Math.max(lo, y0);
            int[] u = uniform(cx, cy, cz);
            if (u != null) {
                if (u[0] == 0) continue;
                int r = HeightIndex.contrib(HeightIndex.info(u[0]), kind, top);
                if (r != HeightIndex.NONE) return r;
                continue;
            }
            Section s = cached(cx, cy, cz);
            var p = s.blockPalette();
            if (p.count() == 0) continue;
            for (int y = top; y >= bot; y--) {
                int id = p.get(x & 15, y & 15, z & 15);
                if (id == 0) continue;
                int r = HeightIndex.contrib(HeightIndex.info(id), kind, y);
                if (r != HeightIndex.NONE) return r;
            }
        }
        return HeightIndex.NONE;
    }

    private final Map<Long, Section> lru = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Section> e) { return size() > 512; }
    };

    private Section cached(int cx, int cy, int cz) {
        long k = ((long) (cx & 0x3FFFFF) << 42) | ((long) (cz & 0x3FFFFF) << 20) | (cy & 0xFFFFF);
        synchronized (lru) {
            Section s = lru.get(k);
            if (s != null) return s;
        }
        Section s = cube(cx, cy, cz).section();
        synchronized (lru) { lru.put(k, s); }
        return s;
    }

    @Override
    public RegistryKey<Biome> biomeAt(int x, int z) {
        RegistryKey<Biome> b = w.columnBiome(x, z);
        return b != null ? b : MinecraftServer.getBiomeRegistry().getKey(0);
    }

    // ───────────── отпечаток ─────────────

    private volatile Integer fingerprint;

    /** Хэш 16 фиксированных кубов (без индекса): «генератор поменялся, а version — нет». */
    public int fingerprint() {
        Integer f = fingerprint;
        if (f != null) return f;
        int h = 1;
        int half = settings.bounds().size() > 0 ? Math.max(1, settings.bounds().size() / 32) : 32;
        for (int k = 0; k < 16; k++) {
            int cy = minCy + (int) ((long) (maxCy - minCy) * k / 15);
            int cx = Math.floorMod(k * 37, 2 * half) - half, cz = Math.floorMod(k * 61 + 7, 2 * half) - half;
            h = 31 * h + hash(rawCube(cx, cy, cz));
        }
        long oh = objects.hash();                                             // объекты сменились — индекс pregen другой
        if (oh != 0) h = 31 * h + (int) (oh ^ (oh >>> 32));
        fingerprint = h;
        return h;
    }

    /** Хэш куба: блоки, биомы, блоки с данными. */
    public static int hash(Cube c) {
        int h = 17;
        var p = c.section().blockPalette();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) h = 31 * h + p.get(x, y, z);
        var b = c.section().biomePalette();
        for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) h = 31 * h + b.get(x, y, z);
        int sp = 0;
        for (var e : c.specials().int2ObjectEntrySet()) sp += e.getIntKey() * 131 + e.getValue().state().hashCode() + java.util.Objects.hashCode(e.getValue().nbt());
        return 31 * h + sp;
    }
}
