package ink.hoka.odyssey.server.tall;

import ink.hoka.odyssey.server.tall.region.RegionFiles;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Шаг 10: ленивая загрузка кубов — не-READY куб не пишется и не сохраняется, грузится по запросу с диска/генератором. */
class CubeStoreTest {

    static Instance inst;
    static RegistryKey<DimensionType> key;

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tmp;

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        key = MinecraftServer.getDimensionTypeRegistry().register(Key.key("hoka:tall_store_test"),
                TallWorld.tall(DimensionType.builder().build(), TallWorld.MIN_Y, TallWorld.HEIGHT, true, 0));
        inst = MinecraftServer.getInstanceManager().createInstanceContainer(key);
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

    private static void await(CubeColumn c, int y) throws Exception { c.whenReady(idx(y), 0).get(20, TimeUnit.SECONDS); }

    @Test
    void lazyColumnFromRegion() throws Exception {
        Path map = tmp.resolve("lazy");
        int x = 2 * 16 + 5, z = 3 * 16 + 9;
        CubeColumn c = CubeColumn.generated(inst, 2, 3);                    // мир без CubeRegionLoader — все кубы сразу
        assertFalse(c.isLazy());
        set(c, x, 41, z, CHEST);
        set(c, x, 12000, z, Block.GOLD_BLOCK);
        set(c, x, -10000, z, Block.STONE);
        CubeRegionLoader l = new CubeRegionLoader(map, null);
        assertEquals(3, l.save(c));
        RegionFiles.closeUnder(map);

        CubeRegionLoader lazy = new CubeRegionLoader(map, null);
        lazy.setLazy(true);
        var d = (CubeColumn) lazy.loadChunk(inst, 2, 3);
        assertTrue(d.isLazy());
        assertFalse(d.ready(idx(12000)), "куб с диска — по запросу");
        assertFalse(d.ready(idx(40)), "земля генератора — по запросу");
        assertTrue(d.ready(idx(5000)), "пустой у генератора и не на диске — сразу READY");
        assertTrue(get(d, x, 12000, z).isAir(), "не в памяти — воздух");
        assertEquals(12000, d.height(HeightIndex.Kind.SURFACE, x, z), "высоты — из заголовка");
        assertEquals(0, d.realSections(), "в памяти ни одного своего куба");

        // §4.2: запись в незагруженный куб — исключение, данные не тронуты
        long v0 = CubeStore.violations.get();
        assertThrows(IllegalStateException.class, () -> set(d, x, 12001, z, Block.DIAMOND_BLOCK));
        assertEquals(v0 + 1, CubeStore.violations.get());
        d.lockReadLock();
        try { assertThrows(IllegalStateException.class, () -> d.copy(inst, 2, 3), "копия без незагруженных кубов — нельзя"); }
        finally { d.unlockReadLock(); }

        // правка READY-куба: сохраняется только он, кубы не в памяти на диске остаются
        set(d, x, 5000, z, Block.EMERALD_BLOCK);
        assertEquals(1, lazy.save(d));
        assertTrue(d.st().diskCubes.get(idx(12000)));
        assertTrue(d.st().diskCubes.get(idx(41)));

        // загрузка по запросу
        await(d, 12000);
        assertEquals(Block.GOLD_BLOCK, get(d, x, 12000, z));
        await(d, 41);
        assertEquals(CHEST.nbt(), get(d, x, 41, z).nbt());
        assertEquals(Block.GRASS_BLOCK, get(d, x + 1, 40, z), "нетронутое — от генератора");
        assertFalse(d.isModified(), "загрузка куба — не правка");
        set(d, x, 12001, z, Block.DIAMOND_BLOCK);                          // теперь можно
        assertEquals(1, lazy.save(d));
        RegionFiles.closeUnder(map);

        // всё на месте при обычной загрузке
        var e = (CubeColumn) new CubeRegionLoader(map, null).loadChunk(inst, 2, 3);
        assertFalse(e.isLazy());
        assertEquals(Block.GOLD_BLOCK, get(e, x, 12000, z));
        assertEquals(Block.DIAMOND_BLOCK, get(e, x, 12001, z));
        assertEquals(Block.EMERALD_BLOCK, get(e, x, 5000, z));
        assertEquals(Block.STONE, get(e, x, -10000, z));
        assertEquals(CHEST.nbt(), get(e, x, 41, z).nbt());
        RegionFiles.closeUnder(map);
        assertFalse(TallSections.poisoned());
    }

    @Test
    void lazyGeneratedAndHeights() throws Exception {
        Path map = tmp.resolve("gen");
        CubeRegionLoader l = new CubeRegionLoader(map, null);
        l.setLazy(true);
        Instance li = MinecraftServer.getInstanceManager().createInstanceContainer(key, l);
        CubeColumn c = CubeColumn.generated(li, 4, 4);
        assertTrue(c.isLazy());
        int x = 4 * 16 + 1, z = 4 * 16 + 2;
        assertEquals(40, c.height(HeightIndex.Kind.SURFACE, x, z), "высоты — генератор аналитически");
        assertFalse(c.ready(idx(40)));
        assertEquals(0, l.save(c), "ничего не менялось — не пишем");
        await(c, 40);
        assertEquals(Block.GRASS_BLOCK, get(c, x, 40, z));
        assertEquals(40, c.height(HeightIndex.Kind.SURFACE, x, z));

        // выкопали колонку 32…40: ниже куб 16…31 ещё не в памяти — верх по генератору (31), а не «пусто»
        assertFalse(c.ready(idx(31)));
        for (int y = 40; y >= 32; y--) set(c, x, y, z, Block.AIR);
        assertEquals(31, c.height(HeightIndex.Kind.SURFACE, x, z));
        assertEquals(31, c.height(HeightIndex.Kind.SKY, x, z));

        // сохранение: изменённый куб пишется, незагруженная земля — нет (её воссоздаст генератор)
        assertEquals(1, l.save(c));
        assertFalse(c.st().diskCubes.get(idx(31)));
        l.close();
        var d = (CubeColumn) new CubeRegionLoader(map, null).loadChunk(inst, 4, 4);
        assertTrue(get(d, x, 40, z).isAir());
        assertTrue(get(d, x, 32, z).isAir());
        assertEquals(Block.STONE, get(d, x, 31, z), "незагруженный куб — как у генератора");
        assertEquals(Block.DIRT, get(d, x + 1, 39, z));
        assertEquals(31, d.height(HeightIndex.Kind.SURFACE, x, z));
        RegionFiles.closeUnder(map);
        MinecraftServer.getInstanceManager().unregisterInstance(li);
    }

    // ───────────── шаг 11: выгрузка кубов ─────────────

    private static final long LATER = CubeStore.IDLE_MS + 1_000;

    @Test
    void unloadSavesAndReloads() throws Exception {
        Path map = tmp.resolve("unload");
        int x = 6 * 16 + 3, z = 7 * 16 + 4;
        CubeRegionLoader l = new CubeRegionLoader(map, null);
        l.setLazy(true);
        l.setUnload(true);
        Instance li = MinecraftServer.getInstanceManager().createInstanceContainer(key, l);
        CubeColumn c = CubeColumn.generated(li, 6, 7);
        assertTrue(c.isLazy());
        await(c, 12000);
        await(c, 40);
        set(c, x, 12000, z, Block.GOLD_BLOCK);
        set(c, x, 41, z, CHEST);
        assertTrue(c.st().isDirty(idx(12000)));
        long now = System.currentTimeMillis();

        // интерес: в keep — не выгружать; без интереса меньше 30 с — тоже
        java.util.BitSet keep = new java.util.BitSet();
        keep.set(idx(41));
        assertEquals(0, c.pickUnload(keep, now, CubeStore.IDLE_MS).length, "только что писали — интерес свежий");
        int[] is = c.pickUnload(keep, now + LATER, CubeStore.IDLE_MS);
        assertTrue(contains(is, idx(12000)), "без интереса 30 с — выгружать");
        assertFalse(contains(is, idx(41)), "в keep — не трогать");
        assertFalse(c.ready(idx(12000)));
        assertTrue(c.inMemory(idx(12000)), "выгружается — данные ещё в памяти");
        assertFalse(c.noCubes());

        // запись в выгружаемый — исключение (§4.2), данные не тронуты
        long v0 = CubeStore.violations.get();
        assertThrows(IllegalStateException.class, () -> set(c, x, 12001, z, Block.DIAMOND_BLOCK));
        assertEquals(v0 + 1, CubeStore.violations.get());

        assertEquals(is.length, c.unloadCubes(is));
        assertFalse(c.ready(idx(12000)));
        assertFalse(c.inMemory(idx(12000)));
        assertTrue(get(c, x, 12000, z).isAir(), "не в памяти — воздух");
        assertFalse(c.st().isDirty(idx(12000)));
        assertFalse(c.isModified(), "всё на диске");
        assertTrue(c.ready(idx(41)) && get(c, x, 41, z).nbt() != null, "куб в keep на месте");

        // снова по запросу — с диска
        await(c, 12000);
        assertEquals(Block.GOLD_BLOCK, get(c, x, 12000, z));

        // земля генератора с сундуком: выгрузили — сундук на диске, вернулся с NBT
        int[] is2 = c.pickUnload(new java.util.BitSet(), System.currentTimeMillis() + 3 * LATER, CubeStore.IDLE_MS);   // keep выше отметил интерес «в будущем»
        assertTrue(contains(is2, idx(41)));
        assertEquals(is2.length, c.unloadCubes(is2));
        assertTrue(c.noCubes(), "все загруженные кубы выгружены — столбец можно выгружать");
        c.lockReadLock();
        try { assertNull(c.getBlock(x, 41, z, Block.Getter.Condition.CACHED), "блоки с данными куба — тоже из памяти"); }
        finally { c.unlockReadLock(); }
        await(c, 41);
        assertEquals(CHEST.nbt(), get(c, x, 41, z).nbt());
        assertEquals(Block.GRASS_BLOCK, get(c, x + 1, 40, z));
        l.close();

        // после рестарта всё на месте
        var e = (CubeColumn) new CubeRegionLoader(map, null).loadChunk(inst, 6, 7);
        assertEquals(Block.GOLD_BLOCK, get(e, x, 12000, z));
        assertEquals(CHEST.nbt(), get(e, x, 41, z).nbt());
        RegionFiles.closeUnder(map);
        MinecraftServer.getInstanceManager().unregisterInstance(li);
        assertFalse(TallSections.poisoned());
    }

    @Test
    void unloadAbortedWhenRequested() throws Exception {
        Path map = tmp.resolve("abort");
        int x = 8 * 16 + 1, z = 8 * 16 + 1;
        CubeRegionLoader l = new CubeRegionLoader(map, null);
        l.setLazy(true);
        l.setUnload(true);
        Instance li = MinecraftServer.getInstanceManager().createInstanceContainer(key, l);
        CubeColumn c = CubeColumn.generated(li, 8, 8);
        await(c, 3000);
        set(c, x, 3000, z, Block.EMERALD_BLOCK);
        int[] is = c.pickUnload(new java.util.BitSet(), System.currentTimeMillis() + LATER, CubeStore.IDLE_MS);
        assertTrue(contains(is, idx(3000)));
        var f = c.whenReady(idx(3000), 0);                                 // попросили, пока выгружается
        assertFalse(f.isDone());
        assertEquals(0, c.unloadCubes(is), "попросили — остаётся в памяти");
        f.get(5, TimeUnit.SECONDS);
        assertTrue(c.ready(idx(3000)));
        assertEquals(Block.EMERALD_BLOCK, get(c, x, 3000, z));
        set(c, x, 3001, z, Block.EMERALD_BLOCK);                           // писать снова можно
        assertEquals(1, l.save(c));
        l.close();
        MinecraftServer.getInstanceManager().unregisterInstance(li);
    }

    private static boolean contains(int[] a, int v) { for (int x : a) if (x == v) return true; return false; }

    @Test
    void sectionPosMatchesVanilla() {
        // net.minecraft.core.SectionPos.asLong: X 22 бита << 42, Z 22 бита << 20, Y 20 бит
        assertEquals(((long) (-5 & 0x3FFFFF) << 42) | ((long) (7 & 0x3FFFFF) << 20) | (-938 & 0xFFFFF), CubeStreamer.pos(-5, -938, 7));
        assertEquals(0L, CubeStreamer.pos(0, 0, 0));
        assertEquals((1L << 42) | (1L << 20) | 1L, CubeStreamer.pos(1, 1, 1));
    }
}
