package ink.hoka.odyssey.server.tall;

import ink.hoka.odyssey.server.tall.region.RegionFiles;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.heightmap.Heightmap;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Шаг 9: CubeColumn — блоки, блоки с данными и обработчики, высоты, общая пустая секция, сохранение/загрузка регионами. */
class CubeColumnTest {

    static Instance inst;
    static RegistryKey<DimensionType> key;

    static final AtomicInteger PLACED = new AtomicInteger(), DESTROYED = new AtomicInteger(), TICKS = new AtomicInteger();

    /** Обработчик печки: считает onPlace/onDestroy/tick (по имени блока — его же вернёт загрузка из региона). */
    static final BlockHandler FURNACE = new BlockHandler() {
        @Override public Key getKey() { return Key.key("minecraft:furnace"); }
        @Override public void onPlace(Placement p) { PLACED.incrementAndGet(); }
        @Override public void onDestroy(Destroy d) { DESTROYED.incrementAndGet(); }
        @Override public boolean isTickable() { return true; }
        @Override public void tick(Tick t) { TICKS.incrementAndGet(); }
    };

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tmp;

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        key = MinecraftServer.getDimensionTypeRegistry().register(Key.key("hoka:tall_column_test"),
                TallWorld.tall(DimensionType.builder().build(), TallWorld.MIN_Y, TallWorld.HEIGHT, true, 0));
        inst = MinecraftServer.getInstanceManager().createInstanceContainer(key);
        MinecraftServer.getBlockManager().registerHandler("minecraft:furnace", () -> FURNACE);
    }

    private static final Block CHEST = Block.CHEST.withProperty("facing", "north")
            .withNbt(CompoundBinaryTag.builder().putString("CustomName", "\"Ящик\"").build());

    private static void set(TallColumn c, int x, int y, int z, Block b) {
        c.lockWriteLock();
        try { c.setBlock(x, y, z, b); } finally { c.unlockWriteLock(); }
    }

    private static Block get(TallColumn c, int x, int y, int z) {
        c.lockReadLock();
        try { return c.chunk().getBlock(x, y, z); } finally { c.unlockReadLock(); }
    }

    private static int idx(int y) { return (y >> 4) - (TallWorld.MIN_Y >> 4); }

    @Test
    void setGetAndSparse() {
        CubeColumn c = CubeColumn.generated(inst, 3, 4);
        int real = c.realSections();
        assertEquals(Block.GRASS_BLOCK, get(c, 48, 40, 64), "генератор: трава на 40");
        assertTrue(get(c, 48, 9000, 64).isAir());
        assertFalse(c.isModified(), "сгенерирован — не изменён");

        set(c, 50, 9000, 66, Block.GOLD_BLOCK);
        assertEquals(Block.GOLD_BLOCK, get(c, 50, 9000, 66));
        assertEquals(real + 1, c.realSections(), "куб 9000 заведён");
        assertTrue(c.isModified());
        assertEquals(9000, c.height(HeightIndex.Kind.SURFACE, 50, 66));
        assertEquals(9000, c.worldSurfaceHeightmap().getHeight(2, 2), "карта высот — из HeightIndex");
        assertEquals(9000, c.motionBlockingHeightmap().getHeight(2, 2));

        set(c, 50, 9000, 66, Block.AIR);                                  // куб опустел — память назад, высота вниз
        assertTrue(get(c, 50, 9000, 66).isAir());
        assertEquals(real, c.realSections());
        assertEquals(40, c.height(HeightIndex.Kind.SURFACE, 50, 66));
        assertEquals(40, c.worldSurfaceHeightmap().getHeight(2, 2));

        set(c, 50, 7000, 66, Block.AIR);                                  // воздух в пустой куб — ничего не заводим
        assertEquals(real, c.realSections());

        // getSection не бросает: вне мира и пустые — общая EMPTY, не испорчена
        assertSame(TallSections.EMPTY, c.getSection(5000));
        assertSame(TallSections.EMPTY, c.getSection(-5000));
        assertSame(TallSections.EMPTY, c.getSection(7000 >> 4));
        assertTrue(get(c, 0, TallWorld.MIN_Y - 5, 0).isAir(), "вне мира — воздух");
        assertThrows(UnsupportedOperationException.class, () -> c.getSections().set(0, new net.minestom.server.instance.Section()));
        assertFalse(TallSections.poisoned());
        assertEquals(TallWorld.HEIGHT >> 4, c.getSections().size());
    }

    @Test
    void dataBlocksAndHandlers() {
        CubeColumn c = CubeColumn.generated(inst, -2, 9);
        int x = -2 * 16 + 4, z = 9 * 16 + 5;
        set(c, x, 41, z, CHEST);
        Block got = get(c, x, 41, z);
        assertEquals("north", got.getProperty("facing"));
        assertEquals(CHEST.nbt(), got.nbt(), "NBT — из entries");
        assertEquals(1, c.dataBlocks(32, 47).size());
        assertEquals(0, c.dataBlocks(48, 63).size());

        int p0 = PLACED.get(), d0 = DESTROYED.get();
        Block furnace = Block.FURNACE.withHandler(FURNACE);
        set(c, x, -12000, z, furnace);
        assertEquals(p0 + 1, PLACED.get(), "onPlace");
        int t0 = TICKS.get();
        c.tick(0);
        assertEquals(t0 + 1, TICKS.get(), "тикающий блок");
        set(c, x, -12000, z, Block.STONE);
        assertEquals(d0 + 1, DESTROYED.get(), "onDestroy");
        c.tick(0);
        assertEquals(t0 + 1, TICKS.get(), "больше не тикает");
        assertNull(c.dataBlocks(-12000, -12000).get(net.minestom.server.coordinate.CoordConversion.chunkBlockIndex(x, -12000, z)));
    }

    @Test
    void biomesAndSkeleton() {
        CubeColumn c = CubeColumn.generated(inst, 0, 0);
        var reg = MinecraftServer.getBiomeRegistry();
        RegistryKey<net.minestom.server.world.biome.Biome> desert = reg.getKey(Key.key("minecraft:desert"));
        int real = c.realSections();
        c.lockWriteLock();
        try { c.setBiome(1, 8000, 1, desert); } finally { c.unlockWriteLock(); }
        c.lockReadLock();
        try { assertEquals(desert, c.getBiome(1, 8000, 1)); } finally { c.unlockReadLock(); }
        assertEquals(real + 1, c.realSections(), "свой биом — свой куб");
        assertTrue(c.st().takeDirty().get(idx(8000)));
        // скелет: пустые секции, высоты motion/surface (не в кубовом мире — полный столбец; кэш скелета строится сам)
        var full = c.getFullDataPacket();
        assertInstanceOf(ChunkDataPacket.class, full);
        assertTrue(((ChunkDataPacket) full).chunkData().heightmaps().containsKey(Heightmap.Type.MOTION_BLOCKING));
    }

    @Test
    void resetDoesNotEraseAndCopy() {
        CubeColumn c = CubeColumn.generated(inst, 1, 1);
        set(c, 20, 5000, 20, Block.DIAMOND_BLOCK);
        set(c, 21, 41, 21, CHEST);
        c.lockWriteLock();
        try { c.reset(); } finally { c.unlockWriteLock(); }
        assertEquals(Block.DIAMOND_BLOCK, get(c, 20, 5000, 20), "reset не стирает");
        c.lockReadLock();
        CubeColumn d;
        try { d = (CubeColumn) c.copy(inst, 1, 1); } finally { c.unlockReadLock(); }
        assertNull(CubeConvert.compare(c, d));
        set(d, 20, 5000, 20, Block.AIR);
        assertEquals(Block.DIAMOND_BLOCK, get(c, 20, 5000, 20), "копия — свои секции");
        assertTrue(d.isModified());
        net.minestom.server.snapshot.ChunkSnapshot snap = net.minestom.server.snapshot.SnapshotUpdater.update(c);
        assertEquals(Block.DIAMOND_BLOCK, snap.getBlock(20, 5000, 20));
        assertEquals(CHEST.nbt(), snap.getBlock(21, 41, 21).nbt());
        assertTrue(snap.getBlock(0, 9000, 0).isAir());
        assertFalse(TallSections.poisoned());
    }

    @Test
    void saveLoadThroughRegions() throws Exception {
        Path map = tmp.resolve("cc");
        CubeRegionLoader l = new CubeRegionLoader(map, null);
        CubeColumn c = CubeColumn.generated(inst, 5, -6);
        int x = 5 * 16 + 3, z = -6 * 16 + 7;
        set(c, x, 41, z, CHEST);
        set(c, x, 12000, z, Block.GOLD_BLOCK);
        set(c, x, -10000, z, Block.FURNACE.withHandler(FURNACE));
        set(c, x, 20, z, Block.AIR);
        assertEquals(4, l.save(c), "кубы 32…47, 12000, −10000, 16…31");
        assertFalse(c.isModified());
        l.close();

        int p0 = PLACED.get();
        var d = (CubeColumn) new CubeRegionLoader(map, null).loadChunk(inst, 5, -6);
        assertNotNull(d);
        assertNull(CubeConvert.compare(c, d));
        assertEquals(Block.GOLD_BLOCK, get(d, x, 12000, z));
        assertEquals(CHEST.nbt(), get(d, x, 41, z).nbt());
        assertSame(FURNACE, get(d, x, -10000, z).handler(), "обработчик — по имени блока");
        assertEquals(p0 + 1, PLACED.get(), "при загрузке блок с обработчиком ставится setBlock-ом");
        assertTrue(get(d, x, 20, z).isAir());
        assertEquals(12000, d.height(HeightIndex.Kind.SURFACE, x, z));
        assertFalse(d.isModified());
        assertTrue(d.st().diskCubes.get(idx(12000)));

        // правка одного куба — пишется только он
        set(d, x, 12001, z, Block.STONE);
        assertEquals(1, new CubeRegionLoader(map, null).save(d));
        RegionFiles.closeUnder(map);
        var e = (CubeColumn) new CubeRegionLoader(map, null).loadChunk(inst, 5, -6);
        assertEquals(Block.STONE, get(e, x, 12001, z));
        assertEquals(Block.GOLD_BLOCK, get(e, x, 12000, z));
        RegionFiles.closeUnder(map);
    }

    @Test
    void skeletonCacheInvalidates() {
        CubeColumn c = CubeColumn.generated(inst, 8, 8);
        TallWorld.markCubes(inst);                                         // этот мир — кубовый: скелет
        try {
            var sk = c.getFullDataPacket();
            assertInstanceOf(CachedPacket.class, sk);
            var p1 = (ChunkDataPacket) ((CachedPacket) sk).packet(net.minestom.server.network.ConnectionState.PLAY);
            set(c, 8 * 16, 3000, 8 * 16, Block.STONE);                     // выше всего — высоты сменились
            var p2 = (ChunkDataPacket) ((CachedPacket) c.getFullDataPacket()).packet(net.minestom.server.network.ConnectionState.PLAY);
            assertFalse(java.util.Arrays.equals(p1.chunkData().heightmaps().get(Heightmap.Type.WORLD_SURFACE),
                    p2.chunkData().heightmaps().get(Heightmap.Type.WORLD_SURFACE)), "кэш скелета сброшен");
            assertTrue(p2.chunkData().blockEntities().isEmpty());
        } finally {
            unmarkCubes();
        }
    }

    /** Снять отметку «кубовый мир» с тестового мира (поле закрытое — через рефлексию, только в тесте). */
    private static void unmarkCubes() {
        try {
            var f = TallWorld.class.getDeclaredField("CUBE_INSTANCES");
            f.setAccessible(true);
            ((java.util.Set<?>) f.get(null)).remove(inst);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
