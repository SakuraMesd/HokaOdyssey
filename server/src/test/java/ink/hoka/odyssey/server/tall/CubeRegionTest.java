package ink.hoka.odyssey.server.tall;

import ink.hoka.odyssey.server.tall.region.CubeCodec;
import ink.hoka.odyssey.server.tall.region.RegionFile;
import ink.hoka.odyssey.server.tall.region.RegionFiles;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Шаг 7: кодек куба, загрузчик регионов (пишет только отличия от генератора), ленивая миграция .htw, конвертер+сверка. */
class CubeRegionTest {

    static Instance inst;
    static RegistryKey<DimensionType> key;

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tmp;

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        key = MinecraftServer.getDimensionTypeRegistry().register(Key.key("hoka:tall_region_test"),
                TallWorld.tall(DimensionType.builder().build(), TallWorld.MIN_Y, TallWorld.HEIGHT, true, 0));
        inst = MinecraftServer.getInstanceManager().createInstanceContainer(key);
    }

    private static final Block CHEST = Block.CHEST.withProperty("facing", "east")
            .withNbt(CompoundBinaryTag.builder().putString("CustomName", "\"Сундук\"").build());

    // ───────────── кодек ─────────────

    @Test
    void codecRoundTrip() throws Exception {
        Palette b = Palette.blocks();
        Random r = new Random(3);
        Block[] pick = {Block.AIR, Block.STONE, Block.OAK_STAIRS.withProperty("facing", "west"), Block.WATER, Block.GLASS};
        for (int i = 0; i < 4096; i++) b.set(i & 15, i >> 8, (i >> 4) & 15, pick[r.nextInt(pick.length)].stateId());
        Palette bi = Palette.biomes();
        var reg = MinecraftServer.getBiomeRegistry();
        int desert = reg.getId(reg.getKey(Key.key("minecraft:desert")));
        bi.set(1, 2, 3, desert);
        Map<Integer, Block> data = Map.of(CubeCodec.index(3, 4, 5), CHEST);
        var d = CubeCodec.decode(CubeCodec.encode(b, bi, data, 0));
        assertTrue(CubeRegionLoader.samePalette(b, d.blocks(), 16));
        assertTrue(CubeRegionLoader.samePalette(bi, d.biomes(), 4));
        assertEquals(desert, d.biomes().get(1, 2, 3));
        assertTrue(CubeRegionLoader.sameData(data, d.data()));
        assertEquals(0, d.unknown());
        assertEquals(0, d.heightMark());
    }

    @Test
    void codecManyStatesAndEmpty() throws Exception {
        Palette b = Palette.blocks();                                       // > 256 разных состояний — индексы short
        for (int i = 0; i < 4096; i++) b.set(i & 15, i >> 8, (i >> 4) & 15, 1 + (i % 600));
        var d = CubeCodec.decode(CubeCodec.encode(b, Palette.biomes(), Map.of(), 0));
        assertTrue(CubeRegionLoader.samePalette(b, d.blocks(), 16));
        var e = CubeCodec.decode(CubeCodec.encode(Palette.blocks(), Palette.biomes(), Map.of(), 0));
        assertEquals(0, e.blocks().count());
    }

    @Test
    void codecCorrupt() {
        byte[] ok = CubeCodec.encode(Palette.blocks(), Palette.biomes(), Map.of(), 0);
        byte[] v = ok.clone(); v[0] = 7;
        assertThrows(RegionFile.Corrupt.class, () -> CubeCodec.decode(v));
        byte[] cut = java.util.Arrays.copyOf(ok, ok.length - 3);
        assertThrows(RegionFile.Corrupt.class, () -> CubeCodec.decode(cut));
    }

    // ───────────── загрузчик ─────────────

    /** Правки: земля (y 0…40), высоко, глубоко, сундук; блок воздуха в земле (куб опустел частично). */
    private static void edit(TallColumn c, int x, int z) {
        set(c, x * 16 + 1, 41, z * 16 + 1, Block.OAK_PLANKS);
        set(c, x * 16 + 2, 20, z * 16 + 2, Block.AIR);
        set(c, x * 16 + 3, 12000, z * 16 + 3, Block.GOLD_BLOCK);
        set(c, x * 16 + 4, -10000, z * 16 + 4, Block.DIAMOND_BLOCK);
        set(c, x * 16 + 5, 15, z * 16 + 5, CHEST);                      // граница куба y 15/16
        set(c, x * 16 + 5, 16, z * 16 + 5, Block.TORCH);
    }

    /** Запись в столбец под его замком (как Instance.setBlock). */
    private static void set(TallColumn c, int x, int y, int z, Block b) {
        c.lockWriteLock();
        try { c.setBlock(x, y, z, b); } finally { c.unlockWriteLock(); }
    }

    private static Block get(TallColumn c, int x, int y, int z) {
        c.lockReadLock();
        try { return c.chunk().getBlock(x, y, z); } finally { c.unlockReadLock(); }
    }

    private static long files(Path d) throws Exception {
        if (!Files.isDirectory(d)) return 0;
        try (Stream<Path> s = Files.list(d)) { return s.count(); }
    }

    @Test
    void saveLoadOnlyDiffs() throws Exception {
        Path map = tmp.resolve("m1");
        CubeRegionLoader l = new CubeRegionLoader(map, null);
        assertNull(l.loadChunk(inst, 7, -3), "нет в регионе и нет .htw — сгенерировать");
        TallChunk c = TallChunk.generated(inst, 7, -3);
        l.saveChunk(c);
        assertEquals(0, files(map.resolve("cols")), "не менялся — не пишем");
        edit(c, 7, -3);
        int written = l.save(c);
        assertEquals(5, written, "кубы: y 0…15 (дыра+сундук), 16…31 (факел), 32…47 (доски), 12000, −10000");
        assertFalse(c.isModified());
        l.close();

        CubeRegionLoader l2 = new CubeRegionLoader(map, null);
        TallColumn d = (TallColumn) l2.loadChunk(inst, 7, -3);
        assertNotNull(d);
        assertNull(CubeConvert.compare(c, d));
        assertEquals(Block.GOLD_BLOCK, get(d, 7 * 16 + 3, 12000, -3 * 16 + 3));
        Block ch = get(d, 7 * 16 + 5, 15, -3 * 16 + 5);
        assertEquals("east", ch.getProperty("facing"));
        assertEquals(CHEST.nbt(), ch.nbt());
        assertEquals(c.height(HeightIndex.Kind.SURFACE, 1, 1), d.height(HeightIndex.Kind.SURFACE, 1, 1));

        // вернули блок как у генератора — куб уходит из региона
        set(d, 7 * 16 + 3, 12000, -3 * 16 + 3, Block.AIR);
        l2.save(d);
        assertFalse(d.st().diskCubes.get((12000 >> 4) - CubeRegionLoader.MIN_SECTION));
        assertNull(l2.cube(7, (12000 >> 4) - CubeRegionLoader.MIN_SECTION, -3));
        l2.close();
        TallColumn e = (TallColumn) new CubeRegionLoader(map, null).loadChunk(inst, 7, -3);
        assertTrue(get(e, 7 * 16 + 3, 12000, -3 * 16 + 3).isAir());
        assertEquals(Block.DIAMOND_BLOCK, get(e, 7 * 16 + 4, -10000, -3 * 16 + 4));
        RegionFiles.closeUnder(map);
    }

    @Test
    void yardWritesAllNonEmpty() throws Exception {
        Path map = tmp.resolve("yard");
        CubeRegionLoader l = new CubeRegionLoader(map, null);
        int x = TallChunk.YARD_X0 >> 4;
        TallChunk c = TallChunk.generated(inst, x, 0);
        set(c, x * 16, 100, 0, Block.STONE);
        set(c, x * 16, 5000, 0, Block.OAK_PLANKS);
        assertEquals(2, l.save(c));
        l.close();
        TallColumn d = (TallColumn) new CubeRegionLoader(map, null).loadChunk(inst, x, 0);
        assertNull(CubeConvert.compare(c, d));
        RegionFiles.closeUnder(map);
    }

    @Test
    void corruptCubeFallsBackToGen() throws Exception {
        Path map = tmp.resolve("bad");
        CubeRegionLoader l = new CubeRegionLoader(map, null);
        TallChunk c = TallChunk.generated(inst, 0, 0);
        set(c, 3, 2000 + 5, 3, Block.GOLD_BLOCK);                         // куб площадки y 2000
        set(c, 3, 41, 3, Block.OAK_PLANKS);
        l.save(c);
        l.close();
        Path f;
        try (Stream<Path> s = Files.list(map.resolve("cubes"))) {
            f = s.filter(p -> p.getFileName().toString().equals("r.0." + ((2000 >> 4) >> 4) + ".0.hcr")).findFirst().orElseThrow();
        }
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {   // испортить данные единственной записи
            long pos = raf.length() - RegionFile.SECTOR_V2 + 30;          // новые файлы — v2 (сектор 256)
            raf.seek(pos); int b = raf.read(); raf.seek(pos); raf.write(b ^ 0xFF);
        }
        CubeRegionLoader l2 = new CubeRegionLoader(map, null);
        TallColumn d = (TallColumn) l2.loadChunk(inst, 0, 0);
        assertEquals(Block.OAK_PLANKS, get(d, 3, 41, 3), "целые кубы прочитаны");
        assertEquals(Block.GLOWSTONE, get(d, 0, 2000, 0), "битый куб — как генератор (площадка)");
        assertTrue(get(d, 3, 2005, 3).isAir());
        int bad = (2000 >> 4) - CubeRegionLoader.MIN_SECTION;
        assertFalse(d.isModified(), "битая запись остаётся в регионе, пока куб не поменяли");
        assertTrue(d.st().diskCubes.get(bad));
        set(d, 3, 41, 3, Block.STONE_BRICKS);                              // правка другого куба — битый не трогаем
        l2.save(d);
        assertTrue(d.st().diskCubes.get(bad), "битый куб по-прежнему в заголовке");
        set(d, 1, 2001, 1, Block.DIAMOND_BLOCK);                           // правка самого битого куба — перезапишется целым
        l2.save(d);
        TallColumn e = (TallColumn) l2.loadChunk(inst, 0, 0);
        assertEquals(Block.DIAMOND_BLOCK, get(e, 1, 2001, 1));
        assertEquals(Block.GLOWSTONE, get(e, 0, 2000, 0));
        assertEquals(Block.STONE_BRICKS, get(e, 3, 41, 3));
        RegionFiles.closeUnder(map);
    }

    @Test
    void lazyMigrationAndConvert() throws Exception {
        Path map = tmp.resolve("conv"), tall = map.resolve("tall");
        TallLoader htw = new TallLoader(tall);
        Map<Long, TallChunk> src = new HashMap<>();
        for (int[] xz : new int[][]{{0, 0}, {-1, 5}, {100, -100}, {TallChunk.YARD_X0 >> 4, 2}}) {
            TallChunk c = TallChunk.generated(inst, xz[0], xz[1]);
            edit(c, xz[0], xz[1]);
            htw.saveChunk(c);
            src.put((long) xz[0] << 32 | (xz[1] & 0xFFFFFFFFL), c);
        }
        // ленивая миграция: в регионе нет — читается .htw, помечен для переноса
        CubeRegionLoader lazy = new CubeRegionLoader(map, tall);
        TallColumn a = (TallColumn) lazy.loadChunk(inst, -1, 5);
        assertNull(CubeConvert.compare(src.get((long) -1 << 32 | 5L), a));
        assertTrue(a.isModified());
        lazy.save(a);
        lazy.close();
        TallColumn a2 = (TallColumn) new CubeRegionLoader(map, null).loadChunk(inst, -1, 5);
        assertNull(CubeConvert.compare(a, a2), "после сохранения — из регионов, без .htw");
        RegionFiles.closeUnder(map);

        var r = CubeConvert.convert(map, tall, key, m -> {});
        assertEquals(4, r.columns());
        assertEquals(0, r.mismatches());
        var v = CubeConvert.verify(map, tall, key, m -> {});
        assertEquals(4, v.columns());
        assertEquals(0, v.mismatches(), String.valueOf(v.first()));

        // расхождение ловится: правим регион мимо .htw
        CubeRegionLoader l = new CubeRegionLoader(map, null);
        TallColumn b = (TallColumn) l.loadChunk(inst, 100, -100);
        set(b, 100 * 16, 7000, -100 * 16, Block.STONE);
        l.save(b);
        l.close();
        var v2 = CubeConvert.verify(map, tall, key, m -> {});
        assertEquals(1, v2.mismatches());
        RegionFiles.closeUnder(map);
    }
}
