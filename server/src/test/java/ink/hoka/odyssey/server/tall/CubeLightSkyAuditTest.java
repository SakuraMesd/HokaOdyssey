package ink.hoka.odyssey.server.tall;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.BitSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Аудит света неба (sun:true) на кубах: остров, пещера под толщей, башня, крыша над шахтой, замеры. */
class CubeLightSkyAuditTest {

    static final int MIN = (TallWorld.MIN_Y >> 4), CUBES = (TallWorld.HEIGHT >> 4);
    static RegistryKey<DimensionType> key;

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tmp;

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        key = MinecraftServer.getDimensionTypeRegistry().register(Key.key("hoka:tall_sky_audit"),
                TallWorld.tall(DimensionType.builder().build(), TallWorld.MIN_Y, TallWorld.HEIGHT, true, 0));
    }

    static int idx(int y) { return (y >> 4) - MIN; }

    static void log(String f, Object... a) { System.out.printf("[АУДИТ] " + f + "%n", a); }

    /** Плита камня [x0, x1]×[z0, z1] на высотах [y0, y1], process — раз на слой (как тик). Ответ — нс всего. */
    static long slab(CubeLightTest.FakeWorld w, int x0, int x1, int z0, int z1, int y0, int y1, Block b) {
        long t = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) w.set(x, y, z, b);
            long a = System.nanoTime();
            w.eng.process();
            t += System.nanoTime() - a;
        }
        return t;
    }

    // ───────────── 1. остров над землёй ─────────────

    @Test
    void islandLow() {
        CubeLightTest.FakeWorld w = new CubeLightTest.FakeWorld(true, 40);
        w.load(-2, 4, -2, 4);
        long t = slab(w, 0, 47, 0, 47, 340, 342, Block.STONE);
        log("1а остров 48×48×3 на +300: пересчёт %.1f мс (3 слоя)", t / 1e6);
        assertEquals(0, w.eng.level(24, 41, 24, true), "центр под островом — тень");
        assertEquals(14, w.eng.level(0, 41, 24, true), "край под островом — 14");
        assertEquals(15, w.eng.level(-1, 41, 24, true));
        CubeLightTest.assertBox(w, -16, 32, -16, 80, 320, 80, "остров +300");
    }

    @Test
    void islandHighUnloaded() {
        CubeLightTest.FakeWorld w = new CubeLightTest.FakeWorld(true, 40);
        w.load(-2, 4, -2, 4);
        long t = slab(w, 0, 47, 0, 47, 5000, 5002, Block.STONE);
        log("1б остров на +5000: пересчёт %.1f мс", t / 1e6);
        // кубы острова уходят из памяти (вне окна игрока)
        for (int cx = 0; cx <= 2; cx++) for (int cz = 0; cz <= 2; cz++) {
            w.cols.get(CubeLight.colKey(cx, cz)).notReady.set(idx(5000));
            w.eng.cubeUnloadedAt(cx, 5000 >> 4, cz);
        }
        w.eng.process();
        assertEquals(0, w.eng.level(24, 41, 24, true), "центр под островом +5000 — тень, куб острова не в памяти");
        assertEquals(14, w.eng.level(0, 41, 24, true));
        assertEquals(0, w.eng.level(24, 3000, 24, true), "середина между землёй и островом — тень");
        CubeLightTest.assertBox(w, -16, 32, -16, 80, 96, 80, "остров +5000, у земли");
        // правка у земли под островом
        long a = System.nanoTime();
        w.set(24, 41, 24, Block.TORCH);
        w.eng.process();
        log("1б правка у земли под островом: %.3f мс", (System.nanoTime() - a) / 1e6);
        assertEquals(0, w.eng.level(25, 41, 24, true));
        // данные игроку для куба земли и куба острова (не READY — по F)
        var c = w.cols.get(CubeLight.colKey(1, 1));
        var d = w.eng.data(c.st, java.util.List.of(idx(41), idx(5000), idx(3000)), true);
        assertEquals(3, d.skyMask().cardinality(), "небо всем трём");
    }

    // ───────────── 2. пещера под толщей ─────────────

    /** Столбец, залитый камнем по кубам [0, top] (палитра fill), высоты — recompute. */
    static CubeLightTest.FakeCol deep(CubeLightTest.FakeWorld w, int cx, int cz, int top) {
        CubeLightTest.FakeCol c = new CubeLightTest.FakeCol(cx, cz);
        for (int cy = 0; cy <= top >> 4; cy++) c.secs.materialize(cy - MIN).blockPalette().fill(Block.STONE.stateId());
        c.h.recompute(c.secs, MIN);
        w.cols.put(CubeLight.colKey(cx, cz), c);
        return c;
    }

    @Test
    void deepCave() {
        int top = 2047;
        CubeLightTest.FakeWorld w = new CubeLightTest.FakeWorld(true, top);
        for (int cx = -2; cx <= 2; cx++) for (int cz = -2; cz <= 2; cz++) {
            var c = deep(w, cx, cz, top);
            for (int y = 256; y <= top; y += 16) c.notReady.set(idx(y));   // всё выше 256 — не в памяти
        }
        long a = System.nanoTime();
        w.load(-2, 2, -2, 2);
        log("2 загрузка 25 столбцов толщи (кубы 0…255 в памяти): %.1f мс, кубов посчитано %d", (System.nanoTime() - a) / 1e6, w.eng.cubesComputed);
        a = System.nanoTime();
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) for (int y = 100; y <= 110; y++) w.set(x, y, z, Block.AIR);
        w.eng.process();
        log("2 вырыть пещеру 32×32×11: %.1f мс", (System.nanoTime() - a) / 1e6);
        assertEquals(0, w.eng.level(16, 105, 16, true), "пещера под 2000 блоков камня — темно");
        assertEquals(0, w.eng.level(0, 100, 0, true));
        var c = w.cols.get(CubeLight.colKey(0, 0));
        var d = w.eng.data(c.st, java.util.List.of(idx(105), idx(1000)), true);
        for (byte[] s : d.skyLight()) for (byte b : s) assertEquals(0, b, "в толще всё 0");
        java.util.Random r = new java.util.Random(9);
        long s2 = 0, m2 = 0, c0 = 0;
        for (int k = 0; k < 400; k++) {
            if (k == 100) c0 = w.eng.cubesComputed;
            w.set(r.nextInt(32), 100 + r.nextInt(11), r.nextInt(32), r.nextBoolean() ? Block.STONE : Block.AIR);
            long q = System.nanoTime();
            w.eng.process();
            long dt = System.nanoTime() - q;
            if (k >= 100) { s2 += dt; m2 = Math.max(m2, dt); }
        }
        log("2 прогрето: правка в пещере под толщей (небо всё 0) — в среднем %.3f мс, максимум %.3f мс, кубов на правку %.1f",
                s2 / 300 / 1e6, m2 / 1e6, (w.eng.cubesComputed - c0) / 300.0);
    }

    // ───────────── 3. башня 500 блоков ─────────────

    @Test
    void tower() {
        CubeLightTest.FakeWorld w = new CubeLightTest.FakeWorld(true, 40);
        w.load(-2, 2, -2, 2);
        long sum = 0, max = 0;
        for (int y = 41; y <= 540; y++) {
            for (int x = 6; x <= 9; x++) for (int z = 6; z <= 9; z++) w.set(x, y, z, Block.STONE);
            long a = System.nanoTime();
            w.eng.process();
            long dt = System.nanoTime() - a;
            sum += dt; max = Math.max(max, dt);
        }
        log("3 башня 4×4×500 слоями (тик на слой): в среднем %.3f мс, максимум %.3f мс, всего %.0f мс", sum / 500 / 1e6, max / 1e6, sum / 1e6);
        assertEquals(15, w.eng.level(5, 41, 8, true), "у подножия сбоку — небо");
        long t = slab(w, -8, 23, -8, 23, 541, 541, Block.STONE);              // площадка 32×32 на верху
        log("3 площадка 32×32 на 541: %.1f мс", t / 1e6);
        assertEquals(15 - 9, w.eng.level(0, 41, 0, true), "под площадкой у земли: до открытой колонки 9");
        assertEquals(15 - 9, w.eng.level(0, 300, 0, true), "под площадкой на полпути");
        CubeLightTest.assertBox(w, -24, 32, -24, 64, 528, 64, "башня с площадкой");
        long a = System.nanoTime();
        w.set(7, 541, 7, Block.AIR);                                        // дыра в площадке над башней
        w.set(-8, 541, -8, Block.AIR);                                      // дыра в углу — свет до земли
        w.eng.process();
        log("3 две дыры в площадке: %.3f мс", (System.nanoTime() - a) / 1e6);
        assertEquals(15, w.eng.level(-8, 41, -8, true));
        CubeLightTest.assertBox(w, -24, 32, -24, 64, 528, 64, "дыры");
    }

    // ───────────── 4. крыша над шахтой ─────────────

    @Test
    void roofOverShaft() {
        CubeLightTest.FakeWorld w = new CubeLightTest.FakeWorld(true, 40);
        w.load(-2, 2, -2, 2);
        long t = slab(w, -16, 31, -16, 31, 2040, 2040, Block.STONE);
        log("4 крыша 48×48 на 2040: %.1f мс", t / 1e6);
        assertEquals(0, w.eng.level(8, 41, 8, true));
        // кубы 1000…1100 у 3×3 столбцов — не в памяти (вне окна)
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++)
            for (int y = 1008; y < 1104; y += 16) { w.cols.get(CubeLight.colKey(cx, cz)).notReady.set(idx(y)); w.eng.cubeUnloadedAt(cx, y >> 4, cz); }
        w.eng.process();
        long a = System.nanoTime();
        long r0 = w.eng.cubesComputed;
        w.set(8, 2040, 8, Block.AIR);
        w.eng.process();
        log("4 сломать блок крыши на 2040 (шахта до 40): %.2f мс, кубов посчитано %d", (System.nanoTime() - a) / 1e6, w.eng.cubesComputed - r0);
        assertEquals(15, w.eng.level(8, 41, 8, true), "свет до дна");
        assertEquals(15, w.eng.level(8, 1500, 8, true));
        assertEquals(14, w.eng.level(9, 41, 8, true));
        int changed = w.cols.get(CubeLight.colKey(0, 0)).st.changed.cardinality();
        log("4 кубов столбца (0,0) к рассылке: %d", changed);
        CubeLightTest.assertBox(w, -24, 32, -24, 64, 160, 64, "шахта у земли");
        // кубы вернулись в память
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++)
            for (int y = 1008; y < 1104; y += 16) { w.cols.get(CubeLight.colKey(cx, cz)).notReady.clear(idx(y)); w.eng.cubeLoadedAt(cx, y >> 4, cz); }
        assertEquals(15, w.eng.level(8, 1050, 8, true));
        assertEquals(14, w.eng.level(9, 1050, 8, true));
        a = System.nanoTime();
        w.set(8, 2040, 8, Block.STONE);
        w.eng.process();
        log("4 поставить блок обратно: %.2f мс", (System.nanoTime() - a) / 1e6);
        assertEquals(0, w.eng.level(8, 41, 8, true), "крышу закрыли — тень");
        CubeLightTest.assertBox(w, -24, 1000, -24, 64, 112, 64, "шахта в середине");
        // прогретый замер: 40 раз сломать/поставить блок крыши в разных местах
        long br = 0, pl = 0, bmax = 0, pmax = 0;
        int n = 40;
        for (int k = 0; k < n; k++) {
            int x = -14 + (k * 7) % 44, z = -14 + (k * 11) % 44;
            long q = System.nanoTime();
            w.set(x, 2040, z, Block.AIR);
            w.eng.process();
            long d1 = System.nanoTime() - q;
            q = System.nanoTime();
            w.set(x, 2040, z, Block.STONE);
            w.eng.process();
            long d2 = System.nanoTime() - q;
            if (k >= 10) { br += d1; pl += d2; bmax = Math.max(bmax, d1); pmax = Math.max(pmax, d2); }
        }
        log("4 прогрето (30 раз): сломать блок крыши над шахтой 2000 — в среднем %.2f мс (макс %.2f), поставить — %.2f мс (макс %.2f)",
                br / 30 / 1e6, bmax / 1e6, pl / 30 / 1e6, pmax / 1e6);
        // прогретая правка у земли под крышей
        java.util.Random r = new java.util.Random(5);
        long s2 = 0, m2 = 0;
        for (int k = 0; k < 400; k++) {
            w.set(r.nextInt(40) - 12, 41 + r.nextInt(6), r.nextInt(40) - 12, r.nextBoolean() ? Block.STONE : Block.AIR);
            long q = System.nanoTime();
            w.eng.process();
            long d = System.nanoTime() - q;
            if (k >= 100) { s2 += d; m2 = Math.max(m2, d); }
        }
        log("4 прогрето: правка у земли в тени крыши — в среднем %.3f мс, максимум %.3f мс", s2 / 300 / 1e6, m2 / 1e6);
    }

    // ───────────── 4б. настоящий ленивый столбец: камень с дырой на диске ─────────────

    private long clock = System.currentTimeMillis();

    private void unload(CubeColumn c, int... ys) {
        BitSet keep = new BitSet();
        keep.set(0, CUBES);
        for (int y : ys) keep.clear(idx(y));
        clock += 2 * CubeStore.IDLE_MS;
        int[] is = c.pickUnload(keep, clock, CubeStore.IDLE_MS);
        assertEquals(ys.length, c.unloadCubes(is));
    }

    /** Ошибка аудита: спуск skyTop останавливается на выгруженном кубе с диска («верх куба» с запасом). Включить после правки. */

    @Test
    void lazyRoofOverUnloadedRock() throws Exception {
        CubeRegionLoader l = new CubeRegionLoader(tmp.resolve("rock"), null);
        l.setLazy(true);
        l.setUnload(true);
        InstanceContainer inst = MinecraftServer.getInstanceManager().createInstanceContainer(key, l);
        inst.setChunkSupplier(CubeColumn::generated);
        TallWorld.markCubes(inst);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) inst.loadChunk(cx, cz).join();
        CubeLight eng = CubeLight.of(inst);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++)
            for (int y = 0; y < 64; y += 16) ((CubeColumn) inst.getChunk(cx, cz)).whenReady(idx(y), 0).get(20, TimeUnit.SECONDS);
        CubeColumn c = (CubeColumn) inst.getChunk(0, 0);
        for (int y : new int[]{1008, 1024, 1200}) c.whenReady(idx(y), 0).get(20, TimeUnit.SECONDS);
        // скала 16×16 на 1008…1039 с дырой (8, 8), над дырой крыша на 1200
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 1008; y < 1040; y++)
            if (x != 8 || z != 8) inst.setBlock(x, y, z, Block.STONE);
        inst.setBlock(8, 1200, 8, Block.STONE);
        assertEquals(1200, c.height(HeightIndex.Kind.SKY, 8, 8));
        unload(c, 1008, 1024);
        inst.setBlock(8, 1200, 8, Block.AIR);
        int h = c.height(HeightIndex.Kind.SKY, 8, 8);
        int lv = eng.level(8, 41, 8, true);
        log("4б ленивый столбец: крышу над дырой в выгруженной скале сняли → skyTop(8,8) = %d (верно 40), свет у земли под дырой %d (верно 15)", h, lv);
        assertEquals(40, h, "skyTop не спустился сквозь выгруженную скалу");
        assertEquals(15, lv);
        l.close();
        MinecraftServer.getInstanceManager().unregisterInstance(inst);
    }
}
