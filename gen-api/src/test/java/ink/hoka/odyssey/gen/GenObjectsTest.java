package ink.hoka.odyssey.gen;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Объекты для генерации: формат .hobj, повороты состояний, пакет и его хэш, FeatureGrid (детерминизм и стыки кубов). */
class GenObjectsTest {

    // ───────────── таблица номеров для тестов ─────────────

    static final List<String> STATES = new ArrayList<>();
    static {
        STATES.add("minecraft:air");
        STATES.add("minecraft:stone");
        STATES.add("minecraft:dirt");
        STATES.add("minecraft:grass_block[snowy=false]");
        STATES.add("minecraft:bedrock");
        for (String a : new String[]{"x", "y", "z"}) STATES.add("minecraft:oak_log[axis=" + a + "]");
        STATES.add("minecraft:oak_leaves");
        for (String f : new String[]{"north", "east", "south", "west"}) STATES.add("minecraft:chest[facing=" + f + ",type=single,waterlogged=false]");
        STATES.add("minecraft:glowstone");
    }

    static GenIdTable ids() {
        Map<String, Integer> def = new HashMap<>();
        for (int i = 0; i < STATES.size(); i++) {
            String s = STATES.get(i);
            int b = s.indexOf('[');
            def.putIfAbsent(b < 0 ? s : s.substring(0, b), i);
        }
        return new GenIdTable(STATES, List.of("minecraft:plains", "minecraft:forest"), def);
    }

    /** Дерево: ствол 5, крона 3×3×2 + макушка; рядом в рамке воздух и structure_void. */
    static GenObject tree(String id, Map<String, Object> meta) {
        GenObjectCodec.Builder b = new GenObjectCodec.Builder(false);
        for (int y = 0; y < 5; y++) b.add(100, 64 + y, 200, "oak_log[axis=y]");
        for (int y = 4; y <= 5; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            if (x != 0 || z != 0) b.add(100 + x, 64 + y, 200 + z, "minecraft:oak_leaves");
        b.add(100, 70, 200, "minecraft:oak_leaves");
        b.add(103, 64, 200, "minecraft:air");
        b.add(103, 65, 200, "minecraft:structure_void");
        b.add(101, 64, 200, "minecraft:cave_air");
        b.anchor(100, 64, 200);
        return b.build(id, meta);
    }

    /** Г-образный камень с сундуком (несимметричный — для поворотов). */
    static GenObject rock(String id, Map<String, Object> meta) {
        GenObjectCodec.Builder b = new GenObjectCodec.Builder(false);
        b.add(0, 0, 0, "stone");
        b.add(1, 0, 0, "stone");
        b.add(2, 0, 0, "stone");
        b.add(2, 0, 1, "stone");
        b.add(2, 1, 1, "minecraft:chest[waterlogged=false,facing=east,type=single]");
        b.data(2, 1, 1, "{Items:[{id:\"minecraft:apple\",count:1b,Slot:0b}]}");
        b.anchor(0, 0, 0);
        return b.build(id, meta);
    }

    // ───────────── 1. формат .hobj ─────────────

    @Test
    void codecRoundTrip() throws IOException {
        GenObject t = tree("trees/oak", Map.of("weight", 2));
        assertEquals(5 + 16 + 1, t.count(), "воздух и structure_void не пишутся");
        assertEquals(List.of("minecraft:oak_leaves", "minecraft:oak_log[axis=y]"), t.palette(), "палитра каноническая, по алфавиту");
        assertEquals(3, t.sizeX());
        assertEquals(7, t.sizeY());
        assertEquals(1, t.anchorX());
        assertEquals(0, t.anchorY());
        assertEquals(1, t.reach());
        assertEquals(6, t.up());
        assertEquals(0, t.down());
        byte[] f = GenObjectCodec.writeFile(t);
        GenObject r = GenObjectCodec.readFile("trees/oak", f, Map.of("weight", 2));
        assertArrayEquals(GenObjectCodec.writeBody(t), GenObjectCodec.writeBody(r), "туда-обратно");
        assertEquals(2.0, r.meta().weight());
        for (int i = 0; i < t.count(); i++) {
            assertEquals(t.dx(i), r.dx(i));
            assertEquals(t.dy(i), r.dy(i));
            assertEquals(t.dz(i), r.dz(i));
        }
    }

    @Test
    void stableBytes() {
        long h1 = GenObjects.hash(GenObjectCodec.writeBody(tree("trees/oak", null)));
        long h2 = GenObjects.hash(GenObjectCodec.writeBody(tree("trees/other-name", Map.of("weight", 5))));
        assertEquals(h1, h2, "тело не зависит от id и метаданных");
        assertEquals(0xea65fe53ecc25ddfL, h1, "байты формата .hobj v1 не меняются (иначе — новая версия формата)");
    }

    @Test
    void keepAirAndData() throws IOException {
        GenObjectCodec.Builder b = new GenObjectCodec.Builder(true);
        b.add(0, 0, 0, "stone");
        b.add(1, 0, 0, "minecraft:cave_air");
        b.add(2, 0, 0, "minecraft:structure_void");
        GenObject o = b.build("caves/tunnel", null);
        assertTrue(o.keepAir());
        assertEquals(List.of("minecraft:air", "minecraft:stone"), o.palette(), "keep-air пишет air (cave_air → air), не structure_void");
        assertEquals(2, o.count());
        assertTrue(o.meta().replaceAll(), "keep-air — replace all по умолчанию");

        GenObject rk = rock("rocks/l", null);
        assertTrue(rk.hasData());
        int k = rk.data().firstKey();
        assertEquals("minecraft:chest[facing=east,type=single,waterlogged=false]", rk.palette().get(rk.paletteIndex(k)), "данные — на своём блоке");
        GenObject r = GenObjectCodec.readFile("rocks/l", GenObjectCodec.writeFile(rk), null);
        assertEquals(rk.data(), r.data());
    }

    @Test
    void badInput() throws IOException {
        byte[] good = GenObjectCodec.writeBody(tree("trees/oak", null));
        assertThrows(IOException.class, () -> GenObjectCodec.readBody("a/b", Arrays.copyOf(good, good.length - 3), null), "обрезано");
        byte[] bad = good.clone();
        bad[0] = 'X';
        assertThrows(IOException.class, () -> GenObjectCodec.readBody("a/b", bad, null), "не HOBJ");
        byte[] more = Arrays.copyOf(good, good.length + 1);
        assertThrows(IOException.class, () -> GenObjectCodec.readBody("a/b", more, null), "лишние байты");
        assertThrows(IOException.class, () -> GenObjectCodec.readFile("a/b", new byte[]{1, 2, 3}, null), "не gzip");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(o)) { z.write(new byte[9 << 20]); }
        assertThrows(IOException.class, () -> GenObjectCodec.readFile("a/b", o.toByteArray(), null), "zip-бомба");
        Random r = new Random(1);
        for (int t = 0; t < 300; t++) {                                       // случайная порча — только IOException
            byte[] c = good.clone();
            c[4 + r.nextInt(c.length - 4)] ^= (byte) (1 + r.nextInt(255));
            try { GenObjectCodec.readBody("a/b", c, null); } catch (IOException ignored) {}
        }
    }

    // ───────────── 2. повороты ─────────────

    @Test
    void statesRotate() {
        assertEquals("minecraft:chest[facing=east,type=single]", GenStates.rotate("minecraft:chest[facing=north,type=single]", 1, false));
        assertEquals("minecraft:chest[facing=west,type=right]", GenStates.rotate("minecraft:chest[facing=east,type=left]", 0, true));
        assertEquals("minecraft:hopper[facing=down]", GenStates.rotate("minecraft:hopper[facing=down]", 1, true));
        assertEquals("minecraft:oak_log[axis=z]", GenStates.rotate("minecraft:oak_log[axis=x]", 1, false));
        assertEquals("minecraft:oak_log[axis=x]", GenStates.rotate("minecraft:oak_log[axis=x]", 2, true));
        assertEquals("minecraft:oak_sign[rotation=4]", GenStates.rotate("minecraft:oak_sign[rotation=0]", 1, false));
        assertEquals("minecraft:oak_sign[rotation=12]", GenStates.rotate("minecraft:oak_sign[rotation=4]", 0, true));
        assertEquals("minecraft:oak_fence[east=true,north=false,south=false,west=false]",
                GenStates.rotate("minecraft:oak_fence[east=false,north=true,south=false,west=false]", 1, false));
        assertEquals("minecraft:rail[shape=east_west]", GenStates.rotate("minecraft:rail[shape=north_south]", 1, false));
        assertEquals("minecraft:rail[shape=south_west]", GenStates.rotate("minecraft:rail[shape=north_west]", 3, false));
        assertEquals("minecraft:rail[shape=ascending_south]", GenStates.rotate("minecraft:rail[shape=ascending_east]", 1, false));
        assertEquals("minecraft:oak_stairs[facing=west,half=bottom,shape=inner_right]",
                GenStates.rotate("minecraft:oak_stairs[facing=east,half=bottom,shape=inner_left]", 0, true));
        assertEquals("minecraft:oak_door[facing=south,hinge=right]", GenStates.rotate("minecraft:oak_door[facing=north,hinge=left]", 2, true));
        assertEquals("minecraft:jigsaw[orientation=east_up]", GenStates.rotate("minecraft:jigsaw[orientation=north_up]", 1, false));
        assertEquals("minecraft:stone", GenStates.rotate("minecraft:stone", 1, true));
        assertEquals("minecraft:foo[bar=north_x,level=3]", GenStates.rotate("minecraft:foo[bar=north_x,level=3]", 1, true), "чужие свойства не трогаются");
        String[] all = {"minecraft:chest[facing=north,type=left]", "minecraft:oak_log[axis=x]", "minecraft:oak_sign[rotation=3]",
                "minecraft:oak_fence[east=true,north=true,south=false,west=false]", "minecraft:rail[shape=south_east]",
                "minecraft:rail[shape=ascending_north]", "minecraft:oak_stairs[facing=south,half=top,shape=outer_left]",
                "minecraft:redstone_wire[east=side,north=up,power=0,south=none,west=none]"};
        for (String s : all) {
            String t = s;
            for (int k = 0; k < 4; k++) t = GenStates.rotate(t, 1, false);
            assertEquals(s, t, "4 поворота = тождество: " + s);
            assertEquals(s, GenStates.rotate(GenStates.rotate(s, 0, true), 0, true), "два отражения = тождество: " + s);
            assertEquals(GenStates.rotate(GenStates.rotate(s, 1, false), 2, false), GenStates.rotate(s, 3, false), s);
        }
    }

    @Test
    void objectRotation() {
        GenObject r = rock("rocks/l", null);
        // блок (2, 0, 1) от якоря: поворот на 90° по часовой (dx, dz) → (−dz, dx)
        assertEquals(-1, GenObject.rx(2, 1, 1, false));
        assertEquals(2, GenObject.rz(2, 1, 1, false));
        assertEquals(-2, GenObject.rx(2, 1, 0, true));
        for (int rot = 0; rot < 4; rot++) for (boolean m : new boolean[]{false, true}) {
            int reach = 0;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < r.count(); i++) {
                int x = GenObject.rx(r.dx(i), r.dz(i), rot, m), z = GenObject.rz(r.dx(i), r.dz(i), rot, m);
                reach = Math.max(reach, Math.max(Math.abs(x), Math.abs(z)));
                assertTrue(seen.add(x + "," + r.dy(i) + "," + z), "вариант без наложений");
            }
            assertEquals(r.reach(), reach, "reach не меняется при повороте");
        }
        GenPlacer pl = GenPlacer.of(ids(), List.of(r));
        int k = r.data().firstKey();
        assertEquals(ids().state("minecraft:chest[facing=south,type=single,waterlogged=false]"), pl.state(r, k, 1, false), "сундук повёрнут");
        assertEquals(ids().state("minecraft:chest[facing=west,type=single,waterlogged=false]"), pl.state(r, k, 0, true), "сундук отражён");
    }

    // ───────────── 3. пакет ─────────────

    @Test
    void packRoundTripAndHash() throws IOException {
        List<GenObject> l = List.of(tree("trees/oak", Map.of("weight", 2, "on", List.of("minecraft:grass_block"))), rock("rocks/l", null));
        GenObjects a = GenObjects.of(l);
        assertNotEquals(0, a.hash());
        GenObjects b = GenObjects.of(List.of(l.get(1), l.get(0)));
        assertEquals(a.hash(), b.hash(), "порядок входа не важен");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("on", List.of("minecraft:grass_block"));
        m.put("weight", 2);
        assertEquals(a.hash(), GenObjects.of(List.of(tree("trees/oak", m), rock("rocks/l", null))).hash(), "порядок ключей .json не важен");
        assertEquals(a.hash(), GenObjects.of(List.of(tree("trees/oak", GenJson.parseObject(" {\n \"weight\" : 2, \"on\":[ \"minecraft:grass_block\" ] }")),
                rock("rocks/l", null))).hash(), "пробелы .json не важны");
        GenObjects r = GenObjects.readPack(a.pack());
        assertEquals(a.hash(), r.hash());
        assertEquals(Set.of("rocks", "trees"), r.categories());
        assertEquals(Set.of("minecraft:grass_block"), r.get("trees/oak").meta().on());
        assertArrayEquals(GenObjectCodec.writeBody(a.get("rocks/l")), GenObjectCodec.writeBody(r.get("rocks/l")));
        // меняется от одного ключа метаданных и от одного блока
        assertNotEquals(a.hash(), GenObjects.of(List.of(tree("trees/oak", Map.of("weight", 3, "on", List.of("minecraft:grass_block"))), rock("rocks/l", null))).hash());
        GenObjectCodec.Builder bb = new GenObjectCodec.Builder(false);
        bb.add(0, 0, 0, "stone"); bb.add(1, 0, 0, "stone"); bb.add(2, 0, 0, "stone"); bb.add(2, 0, 1, "dirt");
        bb.add(2, 1, 1, "minecraft:chest[waterlogged=false,facing=east,type=single]");
        bb.anchor(0, 0, 0);
        assertNotEquals(a.hash(), GenObjects.of(List.of(l.get(0), bb.build("rocks/l", null))).hash());
        assertSame(GenObjects.EMPTY, GenObjects.of(List.of()));
        assertEquals(0, GenObjects.EMPTY.hash());
        assertSame(GenObjects.EMPTY, GenObjects.readPack(null));
        assertThrows(IOException.class, () -> GenObjects.readPack(new byte[]{31, -117, 8, 0}));
    }

    // ───────────── 4. FeatureGrid ─────────────

    static final int SURF = 64;

    static GenSurface flat() {
        return new GenSurface() {
            @Override public int height(int x, int z) { return Math.floorMod(x * 7 + z * 3, 23) == 0 ? NONE : SURF + Math.floorMod(x + z, 3); }
            @Override public String block(int x, int z) { return "minecraft:grass_block"; }
        };
    }

    record Setup(GenContext ctx, GenPlacer placer, FeatureGrid grid) {}

    static Setup setup(long seed, double density) {
        GenObjects lib = GenObjects.of(List.of(tree("trees/oak", Map.of("weight", 3)), tree("trees/tall", Map.of("sink", 1)), rock("trees/rock", Map.of("replace", "all"))));
        GenContext ctx = GenContext.of("t", seed, new GenBounds(-64, 320, 0), Map.of(), ids(), true, 1L, lib);
        GenPlacer pl = GenPlacer.of(ctx);
        return new Setup(ctx, pl, FeatureGrid.of(ctx, pl, "trees").cell(5).density(density).margin(0).build());
    }

    /** Куб: земля до высоты поверхности (камень), потом объекты сетки. */
    static GenArrayCube cube(Setup s, int cx, int cy, int cz) {
        GenArrayCube c = new GenArrayCube(true).reset(cx, cy, cz, s.ctx().seed());
        GenSurface f = flat();
        int stone = s.ctx().ids().defaultState("stone");
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int h = f.height(c.x0() + x, c.z0() + z);
            for (int y = 0; y < 16; y++) if (h != GenSurface.NONE && c.y0() + y <= h) c.set(x, y, z, stone);
        }
        s.grid().place(c, f);
        return c;
    }

    @Test
    void gridDeterministicAnyOrder() {
        Setup a = setup(42, 0.6);
        List<int[]> cs = new ArrayList<>();
        for (int cx = -2; cx <= 2; cx++) for (int cy = 3; cy <= 5; cy++) for (int cz = -2; cz <= 2; cz++) cs.add(new int[]{cx, cy, cz});
        Map<String, long[]> first = new HashMap<>();
        for (int[] c : cs) first.put(Arrays.toString(c), hashOf(cube(a, c[0], c[1], c[2])));
        List<int[]> sh = new ArrayList<>(cs);
        Collections.shuffle(sh, new Random(7));
        Setup b = setup(42, 0.6);                                              // новый экземпляр
        for (int[] c : sh) assertArrayEquals(first.get(Arrays.toString(c)), hashOf(cube(b, c[0], c[1], c[2])), Arrays.toString(c));
        int nonEmpty = 0;
        for (int[] c : cs) if (!a.grid().placements(c[0] * 16, c[2] * 16, c[0] * 16 + 15, c[2] * 16 + 15, flat()).isEmpty()) nonEmpty++;
        assertTrue(nonEmpty > 10, "объекты есть");
    }

    static long[] hashOf(GenArrayCube c) {
        long d = 0;
        for (var e : c.dataList().entrySet()) d = GenHash.round(d, e.getKey() * 31L + e.getValue().hashCode());
        return new long[]{c.visHash(), d};
    }

    /** Сшитые кубы = «большой холст»: все объекты целиком в глобальном порядке ячеек (gz↑, gx↑). */
    @Test
    void stitchedEqualsCanvas() {
        Setup s = setup(1234, 0.7);
        int X0 = -48, Z0 = -48, Y0 = 48, N = 96, H = 48;
        int[][][] canvas = new int[N][H][N];
        GenSurface f = flat();
        int stone = s.ctx().ids().defaultState("stone");
        for (int x = 0; x < N; x++) for (int z = 0; z < N; z++) {
            int h = f.height(X0 + x, Z0 + z);
            for (int y = 0; y < H; y++) if (h != GenSurface.NONE && Y0 + y <= h) canvas[x][y][z] = stone;
        }
        GenPlacer pl = s.placer();
        int cell = 5;
        for (int gz = Math.floorDiv(Z0 - 10, cell); gz <= Math.floorDiv(Z0 + N + 10, cell); gz++)
            for (int gx = Math.floorDiv(X0 - 10, cell); gx <= Math.floorDiv(X0 + N + 10, cell); gx++) {
                var p = s.grid().at(gx, gz, f);
                if (p == null) continue;
                GenObject o = p.object();
                for (int i = 0; i < o.count(); i++) {
                    int x = p.x() + GenObject.rx(o.dx(i), o.dz(i), p.rot(), p.mirror()) - X0, y = p.ay() + o.dy(i) - Y0,
                            z = p.z() + GenObject.rz(o.dx(i), o.dz(i), p.rot(), p.mirror()) - Z0;
                    if (x < 0 || y < 0 || z < 0 || x >= N || y >= H || z >= N) continue;
                    if (!o.meta().replaceAll() && canvas[x][y][z] != 0) continue;
                    canvas[x][y][z] = pl.state(o, i, p.rot(), p.mirror());
                }
            }
        for (int cx = X0 >> 4; cx < (X0 + N) >> 4; cx++) for (int cy = Y0 >> 4; cy < (Y0 + H) >> 4; cy++) for (int cz = Z0 >> 4; cz < (Z0 + N) >> 4; cz++) {
            GenArrayCube c = cube(s, cx, cy, cz);
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++)
                assertEquals(canvas[(cx << 4) + x - X0][(cy << 4) + y - Y0][(cz << 4) + z - Z0], c.get(x, y, z),
                        "куб " + cx + " " + cy + " " + cz + " блок " + x + " " + y + " " + z);
        }
    }

    @Test
    void densityZeroAndMayTouch() {
        Setup z = setup(5, 0);
        assertTrue(z.grid().isEmpty());
        GenArrayCube e = cube(z, 0, 4, 0), plain = new GenArrayCube(true).reset(0, 4, 0, 5);
        GenSurface f = flat();
        for (int x = 0; x < 16; x++) for (int zz = 0; zz < 16; zz++) {
            int h = f.height(x, zz);
            for (int y = 0; y < 16; y++) if (h != GenSurface.NONE && 64 + y <= h) plain.set(x, y, zz, z.ctx().ids().defaultState("stone"));
        }
        assertEquals(plain.visHash(), e.visHash(), "density 0 — объектов нет");

        Setup s = setup(77, 1.0);
        int checked = 0;
        for (int cx = -3; cx <= 3; cx++) for (int cy = -2; cy <= 8; cy++) for (int cz = -3; cz <= 3; cz++) {
            GenArrayCube only = new GenArrayCube(false).reset(cx, cy, cz, 77);
            s.grid().place(only, f);                                         // только объекты, без земли
            boolean any = false;
            for (int v : only.blocks) if (v != 0) { any = true; break; }
            if (any) { checked++; assertTrue(s.grid().mayTouch(cx, cy, cz, SURF, SURF + 2), "mayTouch консервативен " + cx + " " + cy + " " + cz); }
        }
        assertTrue(checked > 0);
        assertFalse(s.grid().mayTouch(0, 10, 0, SURF, SURF + 2));
        assertFalse(s.grid().mayTouch(0, 2, 0, SURF, SURF + 2));
    }

    @Test
    void dataOnlyWithSecrets() {
        Setup s = setup(3, 1.0);
        boolean seen = false;
        for (int cx = -4; cx <= 4 && !seen; cx++) for (int cz = -4; cz <= 4 && !seen; cz++) {
            GenArrayCube server = cube(s, cx, 4, cz);
            GenArrayCube client = new GenArrayCube(false).reset(cx, 4, cz, 3);
            GenSurface f = flat();
            int stone = s.ctx().ids().defaultState("stone");
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int h = f.height(client.x0() + x, client.z0() + z);
                for (int y = 0; y < 16; y++) if (h != GenSurface.NONE && client.y0() + y <= h) client.set(x, y, z, stone);
            }
            s.grid().place(client, f);
            assertEquals(server.visHash(), client.visHash(), "видимая часть одна");
            if (!server.dataList().isEmpty()) seen = true;
            assertTrue(client.dataList().isEmpty());
        }
        assertTrue(seen, "сундук с данными нашёлся на сервере");
        assertTrue(s.grid().hasData());
    }

    // ───────────── 5. отпечаток ─────────────

    @Test
    void fingerprintWithObjects() {
        PlanetGenerator g = BuiltinGens.FLAT.create(GenContext.of("f", 1, BuiltinGens.FLAT.defaultBounds(), Map.of(), ids(), false, null));
        GenBounds b = BuiltinGens.FLAT.defaultBounds();
        assertEquals(GenFingerprint.of(g, b, 9), GenFingerprint.of(g, b, 9, 0), "hash 0 — как раньше");
        assertNotEquals(GenFingerprint.of(g, b, 9), GenFingerprint.of(g, b, 9, 0x1234));
        assertNotEquals(GenFingerprint.of(g, b, 9, 0x1234), GenFingerprint.of(g, b, 9, 0x1235));
    }

    @Test
    void manifestApi() {
        GenManifest m = GenManifest.parse("{\"id\":\"planet\",\"version\":1,\"provider\":\"a.B\",\"api\":2,\"objects\":true}".getBytes());
        assertEquals(2, m.api());
        assertTrue(m.objects());
        GenManifest old = GenManifest.parse("{\"id\":\"x\",\"version\":1,\"provider\":\"a.B\"}".getBytes());
        assertEquals(1, old.api());
        assertFalse(old.objects());
    }

    @Test
    void defTailCompatible() {
        var old = new GenWire.Def(3, "planet", 1, "gen/planet@1-abc.jar", "abc", 5, "{}", -500, 2000, 0, 10, 1, 2, 3, 1);
        byte[] noTail = GenWire.encodeDef(old);
        assertEquals(old, GenWire.decodeDef(noTail), "старый Def без хвоста");
        var ext = new GenWire.Def(3, "planet", 1, "gen/planet@1-abc.jar", "abc", 5, "{}", -500, 2000, 0, 10, 1, 2, 3, 1,
                "genobj/0123456789abcdef.hopk", "ff".repeat(32), 0x55L);
        byte[] withTail = GenWire.encodeDef(ext);
        assertTrue(withTail.length > noTail.length);
        assertEquals(ext, GenWire.decodeDef(withTail));
        assertEquals(old, GenWire.decodeDef(GenWire.encodeDef(ext, false)), "ext=false — без хвоста");
    }
}
