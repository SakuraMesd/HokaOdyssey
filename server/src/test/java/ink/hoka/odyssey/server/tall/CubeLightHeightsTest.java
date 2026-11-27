package ink.hoka.odyssey.server.tall;

import ink.hoka.odyssey.server.tall.region.CubeCodec;
import ink.hoka.odyssey.server.tall.region.RegionFiles;
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Шаг 13: пересвет при смене skyTop и отметки высот кубов. Настоящие CubeColumn на ленивой кубовой карте с выгрузкой:
 * крыша над выгруженной пещерой → после загрузки темно; сняли крышу → светло (в том числе когда skyTop уточняется
 * только при загрузке куба далеко над землёй); куб без блоков-крыш на диске спуск высот пропускает.
 */
class CubeLightHeightsTest {

    static RegistryKey<DimensionType> key;

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tmp;

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        key = MinecraftServer.getDimensionTypeRegistry().register(Key.key("hoka:tall_marks_test"),
                TallWorld.tall(DimensionType.builder().build(), TallWorld.MIN_Y, TallWorld.HEIGHT, true, 0));
    }

    private static int idx(int y) { return (y >> 4) - (TallWorld.MIN_Y >> 4); }

    /** Часы для pickUnload: каждый вызов — «через 2 × IDLE» (keep отмечает интерес моментом вызова). */
    private long clock = System.currentTimeMillis();

    private record World(InstanceContainer inst, CubeRegionLoader loader, CubeLight eng) {}

    private World world(String name) {
        CubeRegionLoader l = new CubeRegionLoader(tmp.resolve(name), null);
        l.setLazy(true);
        l.setUnload(true);
        InstanceContainer inst = MinecraftServer.getInstanceManager().createInstanceContainer(key, l);
        inst.setChunkSupplier(CubeColumn::generated);
        TallWorld.markCubes(inst);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) inst.loadChunk(cx, cz).join();
        return new World(inst, l, CubeLight.of(inst));
    }

    private static CubeColumn col(World w, int cx, int cz) { return (CubeColumn) w.inst.getChunk(cx, cz); }

    private static void await(CubeColumn c, int y) throws Exception { c.whenReady(idx(y), 0).get(20, TimeUnit.SECONDS); }

    /** Земля 3×3 столбцов в памяти (кубы y 0…63). */
    private static void ground(World w) throws Exception {
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++)
            for (int y = 0; y < 64; y += 16) await(col(w, cx, cz), y);
    }

    /** Выгрузить куб с y столбца c (остальные — в keep). */
    private void unload(CubeColumn c, int y) {
        BitSet keep = new BitSet();
        keep.set(0, (TallWorld.HEIGHT >> 4));
        keep.clear(idx(y));
        clock += 2 * CubeStore.IDLE_MS;
        int[] is = c.pickUnload(keep, clock, CubeStore.IDLE_MS);
        assertArrayEquals(new int[]{idx(y)}, is, "выгружается только куб " + y);
        assertEquals(1, c.unloadCubes(is));
        assertFalse(c.inMemory(idx(y)));
    }

    private static void close(World w) {
        w.loader.close();
        MinecraftServer.getInstanceManager().unregisterInstance(w.inst);
        assertFalse(TallSections.poisoned());
    }

    @Test
    void markCodec() throws Exception {
        assertEquals(CubeMark.BELOW, CubeMark.rel(32, c -> 30));
        assertEquals(CubeMark.INSIDE, CubeMark.rel(32, c -> 31), "блок с нижней гранью на дне куба даёт y0−1 — внутри");
        assertEquals(CubeMark.ABOVE, CubeMark.rel(32, c -> 48));
        assertEquals(CubeMark.INSIDE, CubeMark.rel(32, c -> c == 7 ? 20 : 60), "вперемешку");
        assertEquals(7 << 2, CubeMark.clearBits(null));
        Palette p = Palette.blocks();
        p.set(1, 2, 3, Block.GLASS.stateId());
        int m = CubeMark.of(32, c -> 40, p);
        assertTrue(CubeMark.clear(m, HeightIndex.Kind.SKY), "стекло небо не задерживает");
        assertFalse(CubeMark.clear(m, HeightIndex.Kind.SURFACE));
        assertFalse(CubeMark.clear(0, HeightIndex.Kind.SKY), "старая запись без отметки — ничего не знаем");
        var d = CubeCodec.decode(CubeCodec.encode(p, Palette.biomes(), Map.of(), m));
        assertEquals(m, d.heightMark());
        BitSet[] b = {new BitSet(), new BitSet(), new BitSet()};
        b[0].set(5);
        b[0].set(1800);
        b[2].set(70);
        BitSet[] r = CubeMark.decodeTag(CubeMark.encodeTag(b), (TallWorld.HEIGHT >> 4));
        assertArrayEquals(b, r);
        assertNull(CubeMark.encodeTag(new BitSet[]{new BitSet(), new BitSet(), new BitSet()}));
        assertNull(CubeMark.decodeTag("zz;;", (TallWorld.HEIGHT >> 4)), "битый тег — как нет");
    }

    /** Яма в земле; её нижний куб выгружен; над ямой крыша → после загрузки в яме темно; сняли крышу → снова светло. */
    @Test
    void roofOverUnloadedCave() throws Exception {
        World w = world("cave");
        ground(w);
        CubeColumn c = col(w, 0, 0);
        for (int x = 4; x <= 11; x++) for (int z = 4; z <= 11; z++) for (int y = 20; y <= 40; y++) w.inst.setBlock(x, y, z, Block.AIR);
        assertEquals(15, w.eng.level(8, 21, 8, true), "открытая яма — небо до дна");

        unload(c, 21);                                                      // куб 16…31 — на диск (отметка «внутри»)
        for (int x = 2; x <= 13; x++) for (int z = 2; z <= 13; z++) w.inst.setBlock(x, 50, z, Block.STONE);
        assertEquals(50, c.height(HeightIndex.Kind.SKY, 8, 8));
        long stale0 = w.eng.markStale;
        await(c, 21);
        assertEquals(stale0 + 1, w.eng.markStale, "отметка с диска не совпала с высотами — куб устарел");
        assertEquals(0, w.eng.level(8, 21, 8, true), "под крышей в яме темно");
        assertEquals(0, w.eng.level(5, 25, 10, true));
        int side = w.eng.level(4, 40, 8, true);
        assertTrue(side > 0 && side < 15, "у края ямы — свет сбоку из-под крыши: " + side);

        unload(c, 21);                                                      // снова на диск (отметка «выше»)
        for (int x = 2; x <= 13; x++) for (int z = 2; z <= 13; z++) w.inst.setBlock(x, 50, z, Block.AIR);
        assertEquals(31, c.height(HeightIndex.Kind.SKY, 8, 8), "дно ямы в выгруженном кубе — верх с запасом");
        assertEquals(15, w.eng.level(8, 35, 8, true), "выше выгруженного куба уже светло");
        await(c, 21);
        assertEquals(19, c.height(HeightIndex.Kind.SKY, 8, 8), "куб загрузился — skyTop уточнился");
        assertEquals(15, w.eng.level(8, 21, 8, true), "крышу сняли — в яме светло");
        assertEquals(15, w.eng.level(5, 20, 10, true));
        close(w);
    }

    /**
     * Крыша на 12000 над землёй на 40, между ними выгруженный куб на 5000 с камнем в другой колонке: сняли крышу —
     * спуск высот остановился на этом кубе (с запасом), свет у земли — сбоку 14; куб загрузился — skyTop 40, светло
     * по всей шахте (пересвет полосы skyTop, а не только 3×3×3 вокруг куба). Потом в кубе только стекло: отметка
     * «нет крыш», и спуск идёт мимо невыгруженного куба сразу.
     */
    @Test
    void roofRemovedAboveUnloadedCube() throws Exception {
        World w = world("shaft");
        ground(w);
        CubeColumn c = col(w, 0, 0);
        await(c, 5000);
        await(c, 12000);
        w.inst.setBlock(3, 5000, 3, Block.STONE);
        w.inst.setBlock(8, 12000, 8, Block.STONE);
        assertEquals(14, w.eng.level(8, 41, 8, true), "под крышей на 12000 у земли — свет сбоку");
        unload(c, 5000);

        w.inst.setBlock(8, 12000, 8, Block.AIR);
        assertEquals(40, c.height(HeightIndex.Kind.SKY, 8, 8), "маска колонок неба: в колонке (8, 8) выгруженного куба камня нет — спуск мимо");
        assertEquals(15, w.eng.level(8, 41, 8, true), "свет до земли сразу, куб не загружая");
        assertFalse(c.inMemory(idx(5000)));
        await(c, 5000);
        assertEquals(40, c.height(HeightIndex.Kind.SKY, 8, 8));
        assertEquals(15, w.eng.level(8, 41, 8, true), "у земли светло");
        assertEquals(15, w.eng.level(8, 3000, 8, true), "по шахте светло");
        assertEquals(15, w.eng.level(8, 4999, 8, true));

        // в кубе 5000 вместо камня стекло — отметка «нет блоков, задерживающих небо»
        assertEquals(HeightIndex.NONE, HeightIndex.contrib(HeightIndex.info(Block.GLASS.stateId()), HeightIndex.Kind.SKY, 0));
        w.inst.setBlock(3, 5000, 3, Block.GLASS);
        w.inst.setBlock(8, 12000, 8, Block.STONE);
        unload(c, 5000);
        BitSet[] tag = CubeMark.decodeTag(w.loader.header(0, 0).tags.get(CubeMark.TAG), (TallWorld.HEIGHT >> 4));
        assertNotNull(tag, "сводка «нет вклада» в заголовке столбца");
        assertTrue(tag[HeightIndex.Kind.SKY.ordinal()].get(idx(5000)));
        assertFalse(tag[HeightIndex.Kind.SURFACE.ordinal()].get(idx(5000)), "стекло — поверхность");
        w.inst.setBlock(8, 12000, 8, Block.AIR);
        assertFalse(c.inMemory(idx(5000)));
        assertEquals(40, c.height(HeightIndex.Kind.SKY, 8, 8), "куб без крыш спуск пропускает, не загружая");
        assertEquals(15, w.eng.level(8, 41, 8, true));
        close(w);

        // после рестарта сводка из заголовка: спуск по невыгруженному кубу снова мимо
        CubeRegionLoader l2 = new CubeRegionLoader(tmp.resolve("shaft"), null);
        l2.setLazy(true);
        InstanceContainer i2 = MinecraftServer.getInstanceManager().createInstanceContainer(key, l2);
        i2.setChunkSupplier(CubeColumn::generated);
        i2.loadChunk(0, 0).join();
        CubeColumn e = (CubeColumn) i2.getChunk(0, 0);
        assertTrue(e.st().clear(HeightIndex.Kind.SKY, idx(5000)));
        assertFalse(e.inMemory(idx(5000)));
        await(e, 12000);
        e.lockWriteLock();
        try {
            e.setBlock(8, 12000, 8, Block.STONE);
            e.setBlock(8, 12000, 8, Block.AIR);
        } finally { e.unlockWriteLock(); }
        assertEquals(40, e.height(HeightIndex.Kind.SKY, 8, 8));
        l2.close();
        RegionFiles.closeUnder(tmp.resolve("shaft"));
        MinecraftServer.getInstanceManager().unregisterInstance(i2);
    }
}
