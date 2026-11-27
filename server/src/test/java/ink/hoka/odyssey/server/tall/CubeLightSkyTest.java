package ink.hoka.odyssey.server.tall;

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
import java.util.HashSet;
import java.util.Set;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Свет неба (sun:true) на кубах — постоянные сценарии: правка считает только кубы в пределах 15 блоков, «всё 0» под
 * толщей, бюджет тика, маска колонок неба кубов на диске (остров +5000 и крыша над выгруженной скалой — без загрузки).
 */
class CubeLightSkyTest {

    static final int MIN = (TallWorld.MIN_Y >> 4), CUBES = (TallWorld.HEIGHT >> 4);
    static RegistryKey<DimensionType> key;

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tmp;

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        key = MinecraftServer.getDimensionTypeRegistry().register(Key.key("hoka:tall_sky_perm"),
                TallWorld.tall(DimensionType.builder().build(), TallWorld.MIN_Y, TallWorld.HEIGHT, true, 0));
    }

    static int idx(int y) { return (y >> 4) - MIN; }

    static Set<Long> set(long[] a) { Set<Long> s = new HashSet<>(); for (long v : a) s.add(v); return s; }

    static void clearChanged(CubeLightTest.FakeWorld w) { for (var c : w.cols.values()) c.st.changed.clear(); }

    // ───────────── правка: какие кубы считаются ─────────────

    @Test
    void addNearPicksOnlyReachableCubes() {
        long[][] r = CubeLight.nearKeys(8, 72, 8);                             // середина куба (0, 4, 0)
        assertEquals(7, r[0].length, "середина куба: свой и 6 по граням");
        assertEquals(20, r[1].length);
        assertEquals(8, CubeLight.nearKeys(0, 64, 0)[0].length, "угол: 8 кубов у угла");
        Set<Long> k = set(CubeLight.nearKeys(1, 70, 8)[0]);                    // у грани −x
        assertTrue(k.contains(CubeLight.key(-1, 4, 0)) && !k.contains(CubeLight.key(1, 4, 0)));
    }

    /** Случайные правки (факелы, камень, воздух) у границ кубов — свет совпадает с эталоном, кубов на правку < 27. */
    @Test
    void editsNearMatchReference() {
        CubeLightTest.FakeWorld w = new CubeLightTest.FakeWorld(true, 40);
        w.load(-2, 2, -2, 2);
        Random r = new Random(17);
        Block[] bs = {Block.TORCH, Block.STONE, Block.AIR, Block.GLOWSTONE, Block.OAK_LEAVES, Block.STONE_SLAB};
        long c0 = w.eng.cubesComputed;
        int n = 0;
        for (int round = 0; round < 6; round++) {
            for (int j = 0; j < 40; j++) {
                int x = -8 + r.nextInt(32), z = -8 + r.nextInt(32);
                int y = 30 + r.nextInt(40);
                if (r.nextInt(3) == 0) { x = (x & ~15) | (r.nextBoolean() ? 0 : 15); }   // у границ кубов чаще
                w.set(x, y, z, bs[r.nextInt(bs.length)]);
                w.eng.process();
                n++;
            }
            CubeLightTest.assertBox(w, -16, 16, -16, 48, 80, 48, "раунд " + round);
        }
        double per = (w.eng.cubesComputed - c0) / (double) n;
        System.out.printf("[НЕБО] правка у земли: кубов на правку %.1f%n", per);
        assertTrue(per < 27, "правка считает меньше 27 кубов: " + per);
    }

    /** Факел поставили и убрали в середине куба: к рассылке — только кубы, куда достаёт его свет. */
    @Test
    void torchMarksOnlyReachableCubes() {
        CubeLightTest.FakeWorld w = new CubeLightTest.FakeWorld(true, 40);
        w.load(-2, 2, -2, 2);
        w.set(8, 72, 8, Block.STONE);                                        // куб (0, 4, 0) не пустой — посчитанный
        w.eng.process();
        clearChanged(w);
        w.set(8, 70, 8, Block.TORCH);
        w.eng.process();
        Set<Long> near = set(CubeLight.nearKeys(8, 70, 8)[0]);
        int marked = 0;
        for (var c : w.cols.values())
            for (int i = c.st.changed.nextSetBit(0); i >= 0; i = c.st.changed.nextSetBit(i + 1)) {
                marked++;
                assertTrue(near.contains(CubeLight.key(c.cx, i + MIN, c.cz)), "к рассылке куб вне досягаемости факела: " + c.cx + " " + (i + MIN) + " " + c.cz);
            }
        assertTrue(marked >= 1 && marked <= 7, "помечено " + marked);
        assertEquals(13, w.eng.level(9, 70, 8, false), "свет факела 14 → сосед 13");
        clearChanged(w);
        w.set(8, 70, 8, Block.AIR);
        w.eng.process();
        assertEquals(0, w.eng.level(9, 70, 8, false));
        CubeLightTest.assertBox(w, -16, 32, -16, 48, 64, 48, "факел убран");
    }

    // ───────────── «всё 0» под толщей ─────────────

    @Test
    void buriedCubesTrivialAndExact() {
        int top = 1023;
        CubeLightTest.FakeWorld w = new CubeLightTest.FakeWorld(true, top);
        for (int cx = -2; cx <= 2; cx++) for (int cz = -2; cz <= 2; cz++) {
            var c = CubeLightSkyAuditTest.deep(w, cx, cz, top);
            for (int y = 256; y <= top; y += 16) c.notReady.set(idx(y));       // выше 256 — не в памяти
        }
        w.load(-2, 2, -2, 2);
        long t0 = w.eng.cubesTrivial;
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) for (int y = 100; y <= 110; y++) w.set(x, y, z, Block.AIR);
        w.set(10, 105, 10, Block.TORCH);
        w.eng.process();
        assertTrue(w.eng.cubesTrivial > t0, "кубы под толщей — «всё 0» без заливки");
        assertEquals(0, w.eng.level(16, 105, 16, true), "пещера под 900 блоков камня — неба нет");
        assertTrue(w.eng.level(11, 105, 10, false) > 0, "факел светит");
        CubeLightTest.assertBox(w, -8, 80, -8, 48, 48, 48, "пещера с факелом");
        var c = w.cols.get(CubeLight.colKey(0, 0));
        var d = w.eng.data(c.st, java.util.List.of(idx(200), idx(600)), true);
        for (byte[] s : d.skyLight()) for (byte b : s) assertEquals(0, b, "в толще (и вне памяти) небо 0");
    }

    /** Толстая плита над землёй: кубы под ней стали «всё 0» быстрым путём — и ушли в рассылку (было светло). */
    @Test
    void thickRoofMarksBuriedCubes() {
        CubeLightTest.FakeWorld w = new CubeLightTest.FakeWorld(true, 40);
        w.load(-3, 3, -3, 3);
        w.set(8, 45, 8, Block.STONE);                                        // куб у земли — посчитанный
        w.eng.process();
        assertEquals(15, w.eng.level(8, 48, 8, true));
        clearChanged(w);
        long t0 = w.eng.cubesTrivial;
        for (int y = 100; y <= 150; y++) {
            for (int x = -32; x < 48; x++) for (int z = -32; z < 48; z++) w.set(x, y, z, Block.STONE);
        }
        w.eng.process();
        assertTrue(w.eng.cubesTrivial > t0);
        assertEquals(0, w.eng.level(8, 48, 8, true), "под плитой 80×80 — тень");
        assertTrue(w.cols.get(CubeLight.colKey(0, 0)).st.changed.get(idx(48)), "куб у земли — к рассылке");
        CubeLightTest.assertBox(w, -40, 32, -40, 96, 64, 96, "под толстой плитой");
        // дыра насквозь — свет до земли
        for (int y = 100; y <= 150; y++) w.set(8, y, 8, Block.AIR);
        w.eng.process();
        assertEquals(15, w.eng.level(8, 60, 8, true));
        CubeLightTest.assertBox(w, -40, 32, -40, 96, 128, 96, "дыра в плите");
    }

    // ───────────── бюджет тика ─────────────

    @Test
    void budgetSplitsAcrossTicksAndConverges() {
        CubeLightTest.FakeWorld w = new CubeLightTest.FakeWorld(true, 40);
        w.load(-3, 3, -3, 3);
        Random r = new Random(3);
        for (int j = 0; j < 400; j++) {                                      // разбросанные правки + плита
            int x = -40 + r.nextInt(96), z = -40 + r.nextInt(96);
            w.set(x, 41 + r.nextInt(30), z, r.nextInt(4) == 0 ? Block.TORCH : Block.STONE);
        }
        for (int x = -16; x < 32; x++) for (int z = -16; z < 32; z++) w.set(x, 200, z, Block.STONE);
        int ticks = 0;
        while (w.eng.pending()) { w.eng.process(0); ticks++; assertTrue(ticks < 10_000); }
        assertTrue(ticks > 1, "бюджет 0 — по партии за вызов: " + ticks);
        CubeLightTest.assertBox(w, -40, 32, -40, 96, 64, 96, "после партий");
        CubeLightTest.assertBox(w, -24, 180, -24, 64, 40, 64, "у плиты");
    }

    // ───────────── маска колонок неба ─────────────

    @Test
    void skyColsCodec() {
        Palette p = Palette.blocks();
        p.set(3, 5, 7, Block.STONE.stateId());
        p.set(4, 0, 4, Block.GLASS.stateId());                               // стекло небо не задерживает
        p.set(9, 9, 9, Block.WATER.stateId());
        long[] m = CubeMark.skyCols(p);
        BitSet b = BitSet.valueOf(m);
        assertEquals(2, b.cardinality());
        assertTrue(b.get(7 << 4 | 3) && b.get(9 << 4 | 9));
        Palette full = Palette.blocks();
        full.fill(Block.STONE.stateId());
        assertTrue(CubeMark.full(CubeMark.skyCols(full)));
        assertNull(CubeMark.skyCols(Palette.blocks()));
        java.util.Map<Integer, long[]> map = new java.util.HashMap<>();
        map.put(5, m);
        map.put(9, CubeMark.skyCols(full));
        map.put(1000, new long[]{1, 0, -1L, 0x8000000000000000L});
        BitSet on = new BitSet();
        on.set(5); on.set(9); on.set(1000);
        String tag = CubeMark.encodeCols(map, on);
        var back = CubeMark.decodeCols(tag, CUBES);
        assertEquals(2, back.size(), "полная маска не пишется");
        assertArrayEquals(m, back.get(5));
        assertArrayEquals(map.get(1000), back.get(1000));
        assertTrue(CubeMark.decodeCols("zz:1", CUBES).isEmpty(), "битый тег — пусто");
    }

    // ───────────── настоящие ленивые столбцы ─────────────

    private long clock = System.currentTimeMillis();

    private void unload(CubeColumn c, int... ys) {
        BitSet keep = new BitSet();
        keep.set(0, CUBES);
        for (int y : ys) keep.clear(idx(y));
        clock += 2 * CubeStore.IDLE_MS;
        int[] is = c.pickUnload(keep, clock, CubeStore.IDLE_MS);
        assertEquals(ys.length, c.unloadCubes(is));
    }

    /** Остров 16×16 на +5000 над дырой в выгруженной скале: кубы острова и скалы на диске — земля в тени; сняли блок острова — свет до земли без загрузки. */
    @Test
    void lazyIslandOverUnloadedRock() throws Exception {
        CubeRegionLoader l = new CubeRegionLoader(tmp.resolve("island"), null);
        l.setLazy(true);
        l.setUnload(true);
        InstanceContainer inst = MinecraftServer.getInstanceManager().createInstanceContainer(key, l);
        inst.setChunkSupplier(CubeColumn::generated);
        TallWorld.markCubes(inst);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) inst.loadChunk(cx, cz).join();
        CubeLight eng = CubeLight.of(inst);
        eng.budgetNs = Long.MAX_VALUE;
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++)
            for (int y = 0; y < 64; y += 16) ((CubeColumn) inst.getChunk(cx, cz)).whenReady(idx(y), 0).get(20, TimeUnit.SECONDS);
        CubeColumn c = (CubeColumn) inst.getChunk(0, 0);
        int ground = c.height(HeightIndex.Kind.SKY, 8, 8);
        for (int y : new int[]{1008, 5040}) c.whenReady(idx(y), 0).get(20, TimeUnit.SECONDS);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            if (x != 8 || z != 8) inst.setBlock(x, 1010, z, Block.STONE);     // скала с дырой (8, 8)
            inst.setBlock(x, 5040, z, Block.STONE);                           // остров целиком
        }
        assertEquals(5040, c.height(HeightIndex.Kind.SKY, 8, 8));
        unload(c, 1008, 5040);
        assertFalse(c.inMemory(idx(5040)));
        assertEquals(5040, c.height(HeightIndex.Kind.SKY, 8, 8), "остров на диске — верх колонки на нём");
        // остров 16×16 — свет только сбоку: до открытой колонки 8 блоков
        assertEquals(7, eng.level(8, ground + 1, 8, true), "под островом +5000 (на диске) — тень");
        assertEquals(7, eng.level(8, 3000, 8, true), "между скалой и островом — тень");
        // сняли блок острова над дырой (куб острова грузится под запись — как у игрока), сразу выгрузили снова
        c.whenReady(idx(5040), 0).get(20, TimeUnit.SECONDS);
        inst.setBlock(8, 5040, 8, Block.AIR);
        unload(c, 5040);
        assertFalse(c.inMemory(idx(1008)), "скалу не грузили");
        assertEquals(ground, c.height(HeightIndex.Kind.SKY, 8, 8), "спуск сквозь выгруженную скалу по маске");
        assertEquals(15, eng.level(8, ground + 1, 8, true), "свет до земли");
        assertEquals(5040, c.height(HeightIndex.Kind.SKY, 9, 8), "рядом — остров");
        // заголовок: маска есть, после перезагрузки столбца спуск тот же
        var tag = CubeMark.decodeCols(l.header(0, 0).tags.get(CubeMark.COLS_TAG), CUBES);
        assertTrue(tag.containsKey(idx(1008)), "маска скалы в заголовке");
        assertEquals(0, (tag.get(idx(1008))[(8 << 4 | 8) >> 6] >>> (8 << 4 | 8)) & 1);
        l.close();
        MinecraftServer.getInstanceManager().unregisterInstance(inst);
    }
}
