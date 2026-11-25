package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.worldgen.Bounds;
import ink.hoka.odyssey.api.worldgen.GenSettings;
import ink.hoka.odyssey.server.tall.CubeGen;
import ink.hoka.odyssey.server.tall.FlatTallGen;
import ink.hoka.odyssey.server.tall.HeightIndex;
import ink.hoka.odyssey.server.tall.TallChunk;
import ink.hoka.odyssey.server.tall.TallWorld;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Генераторы планет: flat через API = FlatTallGen; границы/uniform/индекс в ModuleCubeGen; детерминизм (gentest);
 * формат индекса (туда-обратно, CRC, чужой заголовок, частичный done, объём); защита от выгрузки; оценка диска.
 */
class WorldGenTest {

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    @TempDir
    Path tmp;

    private static ModuleCubeGen flat() {
        return new ModuleCubeGen(FlatGenerator.PROVIDER, new GenSettings("t", 0, FlatGenerator.PROVIDER.defaultBounds(), Map.of()));
    }

    private static void sameCube(CubeGen.Cube a, CubeGen.Cube b, String where) {
        assertEquals(a.empty(), b.empty(), where);
        assertEquals(ModuleCubeGen.hash(a), ModuleCubeGen.hash(b), where);
    }

    @Test
    void flatSameAsFlatTallGen() {
        ModuleCubeGen g = flat();
        FlatTallGen f = FlatTallGen.INSTANCE;
        int[] cys = {-938, -1, 0, 1, 2, 3, -15000 >> 4, -2000 >> 4, 2000 >> 4, 15000 >> 4, 937};
        for (int cx : new int[]{-2, -1, 0, 1, 5, TallChunk.YARD_X0 >> 4})
            for (int cz : new int[]{-1, 0, 3})
                for (int cy : cys) {
                    String w = cx + " " + cy + " " + cz;
                    assertEquals(f.hasBlocks(cx, cy, cz), g.hasBlocks(cx, cy, cz), "hasBlocks " + w);
                    sameCube(CubeGen.generate(f, cx, cy, cz), g.cube(cx, cy, cz), "куб " + w);
                }
        for (int[] c : new int[][]{{0, 0}, {-4, 4}, {5, 0}, {100, -7}, {TallChunk.YARD_X0, 0}})
            for (HeightIndex.Kind k : HeightIndex.Kind.values())
                for (int[] r : new int[][]{{TallWorld.MIN_Y, TallWorld.MIN_Y + TallWorld.HEIGHT - 1}, {10, 20}, {41, 3000}, {-100, -1}, {2000, 2000}})
                    assertEquals(f.highest(c[0], c[1], r[0], r[1], k), g.highest(c[0], c[1], r[0], r[1], k), "highest " + c[0] + " " + c[1] + " " + r[0] + "…" + r[1]);
        assertNotNull(g.uniform(3, 1, 3), "cy 1 — сплошной камень, без вызова generate");
        assertEquals(Block.STONE.stateId(), g.uniform(3, 1, 3)[0]);
        assertEquals("flat@1", g.tag());
    }

    @Test
    void boundsAndUniformSkipModule() {
        var p = TestGens.sine("t-bounds", true);
        ModuleCubeGen g = new ModuleCubeGen(p, new GenSettings("t", 7, new Bounds(-64, 255, 512), Map.of()));
        int before = TestGens.GENERATE_CALLS.get();
        assertTrue(g.cube(0, 100, 0).empty(), "выше границ — пусто");
        assertTrue(g.cube(40, 3, 0).empty(), "за size — пусто");
        assertFalse(g.hasBlocks(-17, 3, 0));
        assertTrue(g.hasBlocks(-16, 3, 0));
        CubeGen.Cube u = g.cube(2, 0, 2);                                       // y 0…15 — uniform камень
        assertEquals(before, TestGens.GENERATE_CALLS.get(), "вне границ и однородное — модуль не зовут");
        assertEquals(4096, u.section().blockPalette().count());
        assertEquals(-1, GenIndexBuilder.classify(g.cube(2, 4, 2)), "поверхность — смешанный");
        assertTrue(TestGens.GENERATE_CALLS.get() > before);
        assertEquals(HeightIndex.NONE, g.highest(300, 0, -1000, 1000, HeightIndex.Kind.SKY), "за size — ничего");
    }

    @Test
    void determinismCaught() throws Exception {
        var ok = TestGens.gen(TestGens.sine("t-det", true), 3);
        var r = GenTest.run(ok, 200);
        assertTrue(r.ok(), () -> "sine: " + r.first());
        assertTrue(r.cubes() > 50);
        var bad = TestGens.gen(TestGens.bad("t-bad"), 3);
        var rb = GenTest.run(bad, 200);
        assertTrue(rb.mismatches() > 0, "счётчик в генераторе должен ловиться");
        GenIndexBuilder b = new GenIndexBuilder(bad, false);
        b.checkEvery = 1;
        GenIndexFile f = new GenIndexFile(new GenIndexFile.Header("t-bad", 1, 3, 0, bad.minCy, bad.maxCy, false));
        assertThrows(GenIndexBuilder.NotDeterministic.class, () -> { for (int i = 0; i < 4; i++) b.column(f, i, 0); });
    }

    @Test
    void indexRoundTripAndHeightsFromIndex() throws Exception {
        var p = TestGens.sine("t-idx", false);                                 // highest = UNKNOWN → индекс full
        TestGens.reg(p);
        ModuleCubeGen g = TestGens.gen(p, 11);
        assertTrue(GenIndexBuilder.needHeights(g));
        GenIndexBuilder b = new GenIndexBuilder(g, true);
        var h = new GenIndexFile.Header("t-idx", 1, 11, g.fingerprint(), g.minCy, g.maxCy, true);
        GenIndexFile f = new GenIndexFile(h);
        for (int x = 0; x < 32; x++) for (int z = 0; z < 20; z++) f.put(x, z, b.column(f, x, z));   // частичный регион
        Path dir = tmp.resolve("genidx");
        f.write(GenIndexFile.path(dir, 0, 0));
        GenIndexFile r = GenIndexFile.read(GenIndexFile.path(dir, 0, 0));
        assertEquals(32 * 20, r.done());
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) assertEquals(f.col(x, z), r.col(x, z), x + " " + z);
        // высоты из индекса = скан кубов
        ModuleCubeGen gi = TestGens.gen(p, 11).withIndex(dir);
        int before = TestGens.GENERATE_CALLS.get();
        for (int x = 0; x < 64; x += 7) for (int z = 0; z < 64; z += 5)
            for (HeightIndex.Kind k : HeightIndex.Kind.values()) {
                int lo = g.minCy << 4, hi = (g.maxCy << 4) + 15;
                assertEquals(GenTest.scanDirect(g, x, z, lo, hi, k), gi.highest(x, z, lo, hi, k), "высота " + k + " " + x + " " + z);
            }
        assertNotNull(gi.index().col(5, 5));
        assertNull(gi.index().col(5, 25), "не посчитан — нет в индексе");
        // кубы через индекс = без индекса; пустые/однородные — без вызова модуля
        for (int cy = g.minCy; cy <= g.maxCy; cy++) sameCube(g.cube(3, cy, 3), gi.cube(3, cy, 3), "куб 3 " + cy + " 3");
        assertTrue(TestGens.GENERATE_CALLS.get() > before);
        // чужой генератор (другой сид) — индекс не используется
        ModuleCubeGen other = TestGens.gen(p, 12).withIndex(dir);
        assertNull(other.index().col(5, 5));
        // битый CRC
        byte[] bytes = Files.readAllBytes(GenIndexFile.path(dir, 0, 0));
        bytes[bytes.length - 3] ^= 0x55;
        assertThrows(java.io.IOException.class, () -> GenIndexFile.decode(bytes));
    }

    @Test
    void flatIndexIsTiny() throws Exception {
        ModuleCubeGen g = flat();
        GenIndexBuilder b = new GenIndexBuilder(g, false);
        GenIndexFile f = new GenIndexFile(new GenIndexFile.Header("flat", 1, 0, g.fingerprint(), g.minCy, g.maxCy, false));
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) f.put(x + 32, z + 32, b.column(f, x + 32, z + 32));
        int bytes = f.encode().length;
        assertTrue(bytes / 1024.0 < 8, "плоская карта: " + bytes / 1024.0 + " Б/столбец");
        GenIndexFile.Col c = f.col(40, 40);
        assertEquals(0, c.lo);
        assertEquals(3, c.count, "кубы 0…2");
        assertEquals(GenIndexFile.Col.OUT, c.at(5));
        assertEquals(GenIndexFile.Col.MIXED, c.at(0));
        assertTrue(c.at(1) > 0, "cy 1 — однородный камень");
    }

    @Test
    void spiralCoversOnceFromCenter() {
        long[] o = Spiral.order(-3, -2, 4, 5, 0, 0);
        assertEquals(8 * 8, o.length);
        assertEquals(Spiral.pack(0, 0), o[0]);
        assertEquals(o.length, java.util.Arrays.stream(o).distinct().count());
        long[] c = Spiral.order(10, 10, 12, 11, 0, 0);                         // центр вне — с ближайшей клетки
        assertEquals(6, c.length);
        assertEquals(Spiral.pack(10, 10), c[0]);
        int prevRing = 0;
        for (long k : o) {
            int ring = Math.max(Math.abs(Spiral.x(k)), Math.abs(Spiral.z(k)));
            assertTrue(ring >= prevRing, "кольца по возрастанию");
            prevRing = ring;
        }
    }

    @Test
    void progressWatermarkAndText() {
        PregenProgress p = new PregenProgress();
        p.x1 = -5; p.z1 = -5; p.x2 = 70; p.z2 = 40; p.threads = 3; p.ymin = -10; p.ymax = 300; p.gen = "x@2"; p.seed = -7;
        p.markDone(0); p.markDone(2); p.markDone(3); p.markDone(5);
        assertEquals(1, p.watermark());
        assertTrue(p.isDone(3));
        assertFalse(p.isDone(1));
        PregenProgress q = PregenProgress.decode(p.encode());
        assertEquals(p.encode(), q.encode());
        q.markDone(1);
        assertEquals(4, q.watermark());
    }

    @Test
    void unloadGuard() {
        var p = TestGens.sine("t-guard", true);
        GeneratorRegistry.register("planetmod", p);
        try {
            assertNull(GeneratorRegistry.guard(Set.of("planetmod")));
            GeneratorRegistry.use("загружена карта mars", "t-guard");
            String r = GeneratorRegistry.guard(List.of("other", "planetmod"));
            assertNotNull(r);
            assertTrue(r.contains("mars") && r.contains("t-guard"), r);
            assertNull(GeneratorRegistry.guard(Set.of("other")), "чужой модуль не держим");
            GeneratorRegistry.release("загружена карта mars");
            assertNull(GeneratorRegistry.guard(Set.of("planetmod")));
            assertThrows(IllegalStateException.class, () -> GeneratorRegistry.register("x", p), "id занят");
        } finally { GeneratorRegistry.unregisterOwner("planetmod"); }
        assertNull(GeneratorRegistry.get("t-guard"));
        assertNotNull(GeneratorRegistry.get("flat"));
    }

    @Test
    void diskEstimateArithmetic() {
        assertEquals(6250L * 6250, DiskEstimate.columns(100_000));
        assertEquals(256, DiskEstimate.recordBytes(60, 256));
        assertEquals(512, DiskEstimate.recordBytes(249, 256));
        assertEquals(4096, DiskEstimate.recordBytes(60, 4096));
        long idx = DiskEstimate.indexBytes(6, 100_000);
        assertTrue(idx > 234_000_000L && idx < 250_000_000L, "lite ~6 Б/стб на 100k ≈ 0,24 ГБ: " + idx);
        assertEquals((long) (6250L * 6250 * 0.01 * (4096 + 3 * 4096)), DiskEstimate.editsBytes(100_000, 0.01, 3, 4096, 4096));
    }
}
