package ink.hoka.odyssey.server.tall;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.data.LightData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Шаг 12: CubeLight — свет кубами с обменом краями. Проверки на поддельном мире (столбцы из TallSections + HeightIndex):
 * факел через границы кубов по X/Z/Y без ступенек, тень у края чанка, совпадение с ColumnLight на простых случаях,
 * пересчёт по правкам/загрузкам против эталонной заливки всей коробки, замер времени пересчёта на правку.
 */
class CubeLightTest {

    static final int MIN = CubeLight.MIN, CUBES = CubeLight.CUBES;

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    // ───────────── поддельный мир ─────────────

    static final class FakeCol implements CubeLight.Column {
        final int cx, cz;
        final TallSections secs = new TallSections(CUBES);
        final HeightIndex h = new HeightIndex();
        final BitSet notReady = new BitSet(CUBES);                       // сразу на все кубы: читает поток света
        final CubeLight.Store st = new CubeLight.Store(this, null);

        FakeCol(int cx, int cz) { this.cx = cx; this.cz = cz; }

        @Override public int cx() { return cx; }
        @Override public int cz() { return cz; }
        @Override public boolean ready(int i) { return !notReady.get(i); }
        @Override public boolean maybeBlocks(int i) { return notReady.get(i) || secs.get(i).blockPalette().count() > 0; }
        @Override public int skyTop(int c) { return h.skyTop(c); }

        /** Под замком столбца (как CubeColumn под замком чтения): set может идти в другом потоке (CubeLightAsyncTest). */
        @Override public synchronized boolean ids(int i, int[] out) {
            var p = secs.get(i).blockPalette();
            if (p.count() == 0) return false;
            p.getAll((x, y, z, v) -> out[y << 8 | z << 4 | x] = v);
            return true;
        }

        @Override public synchronized int id(int i, int x, int y, int z) { return secs.get(i).blockPalette().get(x, y, z); }
        @Override public CubeLight.Store store() { return st; }
        @Override public boolean mayEmit(int i) { return CubeLight.mayEmit(secs.get(i).blockPalette()); }   // быстрый путь без неба — как на сервере

        synchronized void set(int x, int y, int z, Block b) {
            int i = (y >> 4) - MIN;
            Section s = b.isAir() ? secs.get(i) : secs.materialize(i);
            if (s != TallSections.EMPTY) s.blockPalette().set(x & 15, y & 15, z & 15, b.stateId());
            h.update(secs, MIN, x, y, z, b.stateId());
        }

        int get(int x, int y, int z) { return secs.get((y >> 4) - MIN).blockPalette().get(x & 15, y & 15, z & 15); }
    }

    static final class FakeWorld implements CubeLight.World {
        final boolean sky;
        final Map<Long, FakeCol> cols = new java.util.concurrent.ConcurrentHashMap<>();   // поток света читает
        final Map<Long, FakeCol> hidden = new HashMap<>();             // «не загружен»: есть, но миру не виден
        /** Генератор незагруженных: пол (y 0…floorTop) или ничего. */
        final int floorTop;
        final CubeLight eng;

        FakeWorld(boolean sky, int floorTop) { this(sky, floorTop, false); }

        /** async — движок со своим потоком света (CubeLightAsyncTest). */
        FakeWorld(boolean sky, int floorTop, boolean async) { this.sky = sky; this.floorTop = floorTop; eng = new CubeLight(this, async); }

        @Override public boolean sky() { return sky; }
        @Override public CubeLight.Column column(int cx, int cz) { return cols.get(CubeLight.colKey(cx, cz)); }
        @Override public int genTop(int x, int z) { return floorTop; }
        @Override public boolean genBlocks(int cx, int cy, int cz) { return floorTop >= 0 && cy >= 0 && cy <= floorTop >> 4; }

        FakeCol col(int cx, int cz) {
            return cols.computeIfAbsent(CubeLight.colKey(cx, cz), k -> {
                FakeCol c = new FakeCol(cx, cz);
                for (int y = 0; y <= floorTop; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                    c.set(cx * 16 + x, y, cz * 16 + z, y == 0 ? Block.BEDROCK : Block.STONE);
                return c;
            });
        }

        /** Загрузить столбцы [x0, x1]×[z0, z1] и посчитать. */
        void load(int x0, int x1, int z0, int z1) {
            for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) { col(cx, cz); eng.columnLoadedAt(cx, cz); }
            eng.process();
        }

        /** Поставить блок (как CubeColumn.setBlock → CubeLight.edit). */
        void set(int x, int y, int z, Block b) {
            FakeCol c = cols.get(CubeLight.colKey(x >> 4, z >> 4));
            int t0 = c.h.get(HeightIndex.Kind.SKY, x, z);
            c.set(x, y, z, b);
            eng.editAt(x, y, z, t0, c.h.get(HeightIndex.Kind.SKY, x, z));
        }

        int top(int x, int z) {
            FakeCol c = cols.get(CubeLight.colKey(x >> 4, z >> 4));
            return c == null ? floorTop : c.h.get(HeightIndex.Kind.SKY, x, z);
        }

        int inf(int x, int y, int z) {
            FakeCol c = cols.get(CubeLight.colKey(x >> 4, z >> 4));
            int i = (y >> 4) - MIN;
            if (c == null || i < 0 || i >= CUBES || !c.ready(i)) return 0;
            return CubeLight.inf(c.get(x, y, z));
        }
    }

    // ───────────── эталон: заливка всей коробки (независимо от кубов) ─────────────

    /**
     * Свет в коробке простой заливкой по клеткам: источники неба y &gt; skyTop, блоков — свечение; снаружи коробки —
     * неподвижная рамка по F (y &gt; skyTop ? 15 : 0, блоки 0), незагруженный столбец — блок неизвестен (0).
     */
    static byte[] reference(FakeWorld w, int x0, int y0, int z0, int sx, int sy, int sz, boolean sky) {
        int n = sx * sy * sz;
        byte[] l = new byte[n];
        int[] inf = new int[n];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
            int k = (y * sz + z) * sx + x, wx = x0 + x, wy = y0 + y, wz = z0 + z;
            inf[k] = w.inf(wx, wy, wz);
            int v = sky ? (wy > w.top(wx, wz) ? 15 : 0) : (inf[k] >> 10) & 15;
            if (sky && !w.sky) v = 0;
            l[k] = (byte) v;
            if (v > 0) q.add(k);
        }
        int[][] dir = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}, {0, 1, 0}};
        // рамка: снаружи → внутрь
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
            if (x > 0 && x < sx - 1 && y > 0 && y < sy - 1 && z > 0 && z < sz - 1) continue;
            int k = (y * sz + z) * sx + x;
            for (int d = 0; d < 6; d++) {
                int ox = x + dir[d][0], oy = y + dir[d][1], oz = z + dir[d][2];
                if (ox >= 0 && ox < sx && oy >= 0 && oy < sy && oz >= 0 && oz < sz) continue;
                int wx = x0 + ox, wy = y0 + oy, wz = z0 + oz;
                int fv = sky ? (w.sky && wy > w.top(wx, wz) ? 15 : 0) : 0;
                int fi = w.inf(wx, wy, wz);
                // свет идёт из o в k по направлению d^1
                int lb = inf[k] & 15;
                if (lb >= 15 || ((fi >> (4 + (d ^ 1))) & 1) != 0 || ((inf[k] >> (4 + d)) & 1) != 0) continue;
                int v = fv - Math.max(1, lb);
                if (v > l[k]) { l[k] = (byte) v; q.add(k); }
            }
        }
        while (!q.isEmpty()) {
            int k = q.poll(), v = l[k];
            if (v <= 1) continue;
            int x = k % sx, z = (k / sx) % sz, y = k / (sx * sz);
            for (int d = 0; d < 6; d++) {
                int nx = x + dir[d][0], ny = y + dir[d][1], nz = z + dir[d][2];
                if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) continue;
                int m = (ny * sz + nz) * sx + nx, lb = inf[m] & 15;
                if (lb >= 15 || ((inf[k] >> (4 + d)) & 1) != 0 || ((inf[m] >> (4 + (d ^ 1))) & 1) != 0) continue;
                int nl = v - Math.max(1, lb);
                if (nl > l[m]) { l[m] = (byte) nl; q.add(m); }
            }
        }
        return l;
    }

    static void assertBox(FakeWorld w, int x0, int y0, int z0, int sx, int sy, int sz, String what) {
        for (boolean sky : new boolean[]{true, false}) {
            byte[] ref = reference(w, x0, y0, z0, sx, sy, sz, sky);
            byte[] got = w.eng.box(x0, y0, z0, sx, sy, sz, sky);
            for (int k = 0; k < ref.length; k++)
                if (ref[k] != got[k]) {
                    int x = k % sx, z = (k / sx) % sz, y = k / (sx * sz);
                    fail(what + ": " + (sky ? "небо" : "блоки") + " в " + (x0 + x) + " " + (y0 + y) + " " + (z0 + z)
                            + " — " + got[k] + ", эталон " + ref[k]);
                }
        }
    }

    // ───────────── тесты ─────────────

    /** Факел у границы куба по X, Z и Y: свет за границей убывает ровно по 1, без ступенек. */
    @Test
    void torchAcrossCubeBorders() {
        FakeWorld w = new FakeWorld(false, -1);                         // без неба и без земли
        w.load(-1, 1, -1, 1);
        int[][] at = {{15, 8, 8}, {8, 8, 15}, {8, 15, 8}};
        int[][] step = {{1, 0, 0}, {0, 0, 1}, {0, 1, 0}};
        for (int t = 0; t < 3; t++) {
            int[] p = at[t], s = step[t];
            w.set(p[0], p[1], p[2], Block.TORCH);
            for (int d = 0; d <= 14; d++) {
                int fwd = w.eng.level(p[0] + s[0] * d, p[1] + s[1] * d, p[2] + s[2] * d, false);
                int back = w.eng.level(p[0] - s[0] * d, p[1] - s[1] * d, p[2] - s[2] * d, false);
                assertEquals(Math.max(0, 14 - d), fwd, "ось " + t + ": +" + d + " за границей куба");
                assertEquals(fwd, back, "ось " + t + ": симметрия на " + d + " (ступенька на границе)");
            }
            assertBox(w, -16, -16, -16, 48, 48, 48, "факел " + t);
            w.set(p[0], p[1], p[2], Block.AIR);
            assertEquals(0, w.eng.level(p[0] + s[0], p[1] + s[1], p[2] + s[2], false), "убрали факел — темно");
        }
    }

    /** Без неба: кубы без светящихся в 3×3×3 считаются сразу «всё 0» — свет совпадает с эталонной заливкой. */
    @Test
    void noSkyFastPath() {
        FakeWorld w = new FakeWorld(false, -1);
        w.load(-2, 2, -2, 2);
        for (int x = -32; x < 48; x += 5) for (int z = -32; z < 48; z += 7) for (int y = -20; y < 40; y += 9) w.set(x, y, z, Block.STONE);
        long before = w.eng.cubesTrivial;
        w.set(3, 5, 3, Block.GLOWSTONE);
        w.set(40, 30, -30, Block.TORCH);
        assertEquals(14, w.eng.level(4, 5, 3, false), "рядом со светокамнем");
        assertBox(w, -32, -32, -32, 80, 80, 80, "без неба, два источника");
        assertTrue(w.eng.cubesTrivial > before, "быстрый путь сработал");
        w.set(3, 5, 3, Block.AIR);
        assertEquals(0, w.eng.level(4, 5, 3, false), "убрали — темно");
        assertBox(w, -32, -32, -32, 80, 80, 80, "без неба, один источник");
    }

    /** Тень от крыши, лежащей на двух столбцах: под крышей у границы столбцов свет одинаков с обеих сторон. */
    @Test
    void shadowAtChunkEdge() {
        FakeWorld w = new FakeWorld(true, 0);                           // пол на y 0
        w.load(-2, 1, -2, 1);
        for (int x = -8; x <= 7; x++) for (int z = -8; z <= 7; z++) w.set(x, 30, z, Block.STONE);
        for (int z = -8; z <= 7; z++)
            for (int y = 1; y < 30; y++) assertEquals(w.eng.level(-1, y, z, true), w.eng.level(0, y, z, true), "граница x=0, y " + y + " z " + z);
        assertTrue(w.eng.level(-1, 20, -1, true) < 15, "под крышей не 15");
        assertEquals(15 - 8, w.eng.level(-1, 20, -1, true), "свет сбоку: до открытой колонки 8 шагов");
        assertBox(w, -32, -16, -32, 64, 96, 64, "крыша");
        for (int x = -8; x <= 7; x++) for (int z = -8; z <= 7; z++) w.set(x, 30, z, Block.AIR);
        assertEquals(15, w.eng.level(-1, 20, -1, true), "крышу сняли — светло");
        assertBox(w, -32, -16, -32, 64, 96, 64, "крыша снята");
    }

    /** Простые случаи — как старый ColumnLight: ровная земля и факел посреди куба. */
    @Test
    void matchesOldColumnLight() {
        FakeWorld w = new FakeWorld(true, 40);
        w.load(-1, 1, -1, 1);
        w.set(8, 42, 8, Block.TORCH);
        FakeCol c = w.col(0, 0);
        LightData old = ColumnLight.compute(c.secs, c.h, true);
        int iTorch = (42 >> 4) - MIN;
        // свет блоков куба с факелом
        int n = 0, k = -1;
        for (int b = old.blockMask().nextSetBit(0); b >= 0; b = old.blockMask().nextSetBit(b + 1), n++) if (b == iTorch + 1) k = n;
        assertTrue(k >= 0, "у старого есть свет блоков");
        byte[] ob = old.blockLight().get(k);
        for (int q = 0; q < 4096; q++)
            assertEquals(CubeLight.nib(ob, q), w.eng.level(q & 15, (2 << 4) + (q >> 8), q >> 4 & 15, false), "блоки, клетка " + q);
        // небо непустых кубов 0…2
        n = 0;
        for (int b = old.skyMask().nextSetBit(0); b >= 0; b = old.skyMask().nextSetBit(b + 1), n++) {
            int cy = b - 1 + MIN;
            byte[] os = old.skyLight().get(n);
            for (int q = 0; q < 4096; q++)
                assertEquals(CubeLight.nib(os, q), w.eng.level(q & 15, (cy << 4) + (q >> 8), q >> 4 & 15, true), "небо, куб " + cy + " клетка " + q);
        }
        // выдача игроку: непустой куб со светом, пустой «всё 15» не шлётся, явный режим — шлётся массивом
        LightData d = w.eng.data(c.st, java.util.List.of(2 - MIN, 5 - MIN), false);
        assertTrue(d.skyMask().get(2 - MIN + 1) && !d.skyMask().get(5 - MIN + 1));
        LightData e = w.eng.data(c.st, java.util.List.of(5 - MIN), true);
        assertTrue(e.skyMask().get(5 - MIN + 1) && e.emptyBlockMask().get(5 - MIN + 1));
    }

    /** Случайные правки, загрузка куба и столбца — против эталонной заливки всей коробки. */
    @Test
    void randomEditsMatchReference() {
        FakeWorld w = new FakeWorld(true, 0);
        Random r = new Random(12);
        // столбец (2, 2) ещё «не загружен»: в нём башня и факел, генератор про них не знает
        FakeCol late = new FakeCol(2, 2);
        for (int y = 0; y <= 0; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) late.set(32 + x, y, 32 + z, Block.BEDROCK);
        for (int y = 1; y < 50; y++) late.set(40, y, 40, Block.STONE);
        late.set(33, 20, 33, Block.GLOWSTONE);
        late.set(47, 60, 47, Block.OAK_LEAVES);
        for (int cx = 0; cx < 5; cx++) for (int cz = 0; cz < 5; cz++) if (cx != 2 || cz != 2) w.col(cx, cz);
        w.load(0, 4, 0, 4);
        Block[] kinds = {Block.STONE, Block.STONE, Block.GLASS, Block.OAK_LEAVES, Block.GLOWSTONE, Block.TORCH, Block.SMOOTH_STONE_SLAB,
                Block.SMOOTH_STONE_SLAB.withProperty("type", "top"), Block.AIR, Block.AIR, Block.AIR, Block.SEA_LANTERN, Block.WATER};
        int X0 = 0, Y0 = -48, Z0 = 0, SX = 80, SY = 192, SZ = 80;
        for (int batch = 0; batch < 25; batch++) {
            int m = 1 + r.nextInt(6);
            for (int j = 0; j < m; j++) {
                int x = 16 + r.nextInt(48), z = 16 + r.nextInt(48), y = 1 + r.nextInt(75);
                if (x >> 4 == 2 && z >> 4 == 2 && !w.cols.containsKey(CubeLight.colKey(2, 2))) continue;
                Block b = kinds[r.nextInt(kinds.length)];
                if (r.nextInt(4) == 0) {                                    // плита 3×3 — тени и проёмы
                    for (int a = -1; a <= 1; a++) for (int c = -1; c <= 1; c++) w.set(x + a, y, z + c, b);
                } else w.set(x, y, z, b);
            }
            if (batch == 8) {                                               // загрузился столбец (2, 2)
                w.cols.put(CubeLight.colKey(2, 2), late);
                w.eng.columnLoadedAt(2, 2);
            }
            if (batch == 14) {                                              // куб выгрузили…
                FakeCol c = w.cols.get(CubeLight.colKey(1, 1));
                c.notReady.set(2 - MIN);
                w.eng.cubeUnloadedAt(1, 2, 1);
                w.eng.process();
                c.notReady.clear(2 - MIN);                                  // …и загрузили обратно
                w.eng.cubeLoadedAt(1, 2, 1);
            }
            assertBox(w, X0, Y0, Z0, SX, SY, SZ, "партия " + batch);
        }
    }

    /** На настоящих CubeColumn кубовой карты: факел у границы столбцов светит в соседний столбец, земля по генератору. */
    @Test
    void realCubeColumns() {
        var key = MinecraftServer.getDimensionTypeRegistry().register(net.kyori.adventure.key.Key.key("hoka:tall_light_test"),
                rethrow(() -> TallWorld.tall(net.minestom.server.world.DimensionType.builder().build(), TallWorld.MIN_Y, TallWorld.HEIGHT, true, 0)));
        var inst = MinecraftServer.getInstanceManager().createInstanceContainer(key);
        inst.setChunkLoader(net.minestom.server.instance.ChunkLoader.noop());
        inst.setChunkSupplier(CubeColumn::generated);
        TallWorld.markCubes(inst);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) inst.loadChunk(cx, cz).join();
        CubeColumn c = (CubeColumn) inst.getChunk(0, 0);
        assertTrue(CubeLight.active(c));
        CubeLight eng = CubeLight.of(inst);
        assertEquals(15, eng.level(5, 41, 5, true), "над травой — небо");
        assertEquals(0, eng.level(5, 30, 5, true), "в земле темно");
        inst.setBlock(15, 41, 8, Block.TORCH);
        assertEquals(14, eng.level(15, 41, 8, false));
        assertEquals(13, eng.level(16, 41, 8, false), "соседний столбец");
        assertEquals(eng.level(14, 41, 8, false), eng.level(16, 41, 8, false), "без ступеньки");
        LightData d = CubeLight.data(c, java.util.List.of(2 - MIN), true);
        assertTrue(d.blockMask().get(2 - MIN + 1), "игроку уходит свет блоков куба с факелом");
        assertTrue(c.lightStore().changed.get(2 - MIN), "куб помечен к рассылке");
        inst.setBlock(15, 41, 8, Block.AIR);
        assertEquals(0, eng.level(16, 41, 8, false));
        LightData e = CubeLight.data((CubeColumn) inst.getChunk(1, 0), java.util.List.of(2 - MIN), true);
        assertTrue(e.emptyBlockMask().get(2 - MIN + 1), "свет блоков пропал — бит «пусто»");
    }

    interface Th<T> { T get() throws Exception; }
    static <T> T rethrow(Th<T> f) { try { return f.get(); } catch (Exception ex) { throw new RuntimeException(ex); } }

    /** Замер: время пересчёта на одну правку (ровная земля 7×7 столбцов) и на «шахту» высотой 12000. */
    @Test
    void editTiming() {
        FakeWorld w = new FakeWorld(true, 40);
        long t0 = System.nanoTime();
        w.load(-3, 3, -3, 3);
        long load = System.nanoTime() - t0;
        Random r = new Random(3);
        for (int k = 0; k < 6; k++) w.set(r.nextInt(48) - 24, 41, r.nextInt(48) - 24, Block.TORCH);   // немного факелов вокруг
        long[] plain = timeEdits(w, r, new Block[]{Block.STONE, Block.GLASS, Block.AIR, Block.AIR, Block.OAK_PLANKS}, 1000);
        long oc = System.nanoTime();
        for (int k = 0; k < 50; k++) ColumnLight.compute(w.col(0, 0).secs, w.col(0, 0).h, true);
        long oldCol = (System.nanoTime() - oc) / 50;                        // старый расчёт: весь столбец на правку
        long[] dense = timeEdits(w, r, new Block[]{Block.STONE, Block.GLASS, Block.TORCH, Block.AIR, Block.GLOWSTONE}, 1000);
        long a = System.nanoTime();
        w.set(0, 12000, 0, Block.STONE);                                    // блок над землёй на 12000: skyTop 40 → 12000
        w.eng.process();
        long shaft = System.nanoTime() - a;
        assertEquals(14, w.eng.level(0, 5000, 0, true), "шахта под блоком: свет сбоку 14");
        assertEquals(15, w.eng.level(1, 5000, 0, true));
        a = System.nanoTime();
        w.set(0, 12000, 0, Block.AIR);
        w.eng.process();
        long shaftBack = System.nanoTime() - a;
        assertEquals(15, w.eng.level(0, 5000, 0, true), "блок сняли — шахта снова 15");
        System.out.printf("CubeLight: загрузка 49 столбцов %.1f мс; правка у земли (стройка, 6 факелов рядом): в среднем %.3f мс, " +
                        "максимум %.3f мс; правка среди сотен факелов/светокамня: в среднем %.3f мс, максимум %.3f мс; " +
                        "блок на 12000 над землёй %.2f мс, снять его %.2f мс; старый ColumnLight на столбец той же стройки %.3f мс%n",
                load / 1e6, plain[0] / 1e6, plain[1] / 1e6, dense[0] / 1e6, dense[1] / 1e6, shaft / 1e6, shaftBack / 1e6, oldCol / 1e6);
    }

    /** n правок блоками kinds у земли (после 300 на разогрев): [среднее, максимум] нс на пересчёт. */
    private static long[] timeEdits(FakeWorld w, Random r, Block[] kinds, int n) {
        for (int k = 0; k < 300; k++) { w.set(r.nextInt(32) - 16, 41 + r.nextInt(20), r.nextInt(32) - 16, kinds[r.nextInt(kinds.length)]); w.eng.process(); }
        long sum = 0, max = 0;
        for (int k = 0; k < n; k++) {
            w.set(r.nextInt(32) - 16, 41 + r.nextInt(20), r.nextInt(32) - 16, kinds[r.nextInt(kinds.length)]);
            long a = System.nanoTime();
            w.eng.process();
            long dt = System.nanoTime() - a;
            sum += dt;
            max = Math.max(max, dt);
        }
        return new long[]{sum / n, max};
    }
}
