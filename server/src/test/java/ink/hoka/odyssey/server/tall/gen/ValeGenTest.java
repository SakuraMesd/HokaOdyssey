package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.worldgen.GenSettings;
import ink.hoka.odyssey.gen.*;
import ink.hoka.odyssey.server.objects.ObjectLibrary;
import ink.hoka.odyssey.server.tall.CubeHash;
import ink.hoka.odyssey.server.tall.HeightIndex;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Генератор vale@2 (настоящий jar) + свои объекты {@link ValeDemoObjects}: объекты пишутся в demo-objects/ (выложить в
 * objects/ сервера), честность hasBlocks/uniform/highest по кубам (и прицельно — горы, разломы, тепуи), флаги блоков =
 * ядро, сервер = клиент, горы выше 1500, скорость, эталоны vale@2 и vale@2-objects.
 */
class ValeGenTest {

    static byte[] jar;
    static final long SEED = 20261126L;

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        jar = Files.readAllBytes(Path.of(System.getProperty("hoka.test.vale-jar")));
    }

    static PortableProvider provider() throws Exception {
        var l = PortableGens.load(jar);
        assertEquals("vale", l.manifest().id());
        assertTrue(l.manifest().objects());
        return new PortableProvider(l.provider(), l.sha256(), jar, l.manifest());
    }

    static GenObjects lib() { return GenObjects.of(ValeDemoObjects.all()); }

    @Test
    void writeDemoObjects() throws Exception {
        Path dir = Path.of(System.getProperty("hoka.test.demo-objects-dir"));
        ObjectLibrary l = new ObjectLibrary(dir);
        for (GenObject o : ValeDemoObjects.all()) {
            assertNull(GenObjects.checkLimits(o), o.id());
            l.save(o, true);
            // save пишет .json только если его нет ({"weight":1}) — метаданные (on, sink, вес) пишем сами, как writeMeta
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : new TreeMap<>(o.meta().raw()).entrySet()) {
                sb.append(first ? "\n" : ",\n").append("  ").append(GenJson.write(e.getKey())).append(": ").append(GenJson.write(e.getValue()));
                first = false;
            }
            sb.append(first ? "}" : "\n}").append('\n');
            Files.writeString(l.metaFile(o.id()), sb.toString(), StandardCharsets.UTF_8);
        }
        ObjectLibrary again = new ObjectLibrary(dir);
        assertTrue(again.errors().isEmpty(), again.errors().toString());
        assertEquals(new TreeSet<>(List.of("azure", "bellflowers", "birch", "bushes", "glowfingers", "glycine", "oak", "palm", "rafflesia",
                "rocks", "spruce")), new TreeSet<>(again.categories()));
        for (GenObject o : ValeDemoObjects.all()) {
            GenObject a = again.get(o.id());
            assertArrayEquals(GenObjectCodec.writeBody(o), GenObjectCodec.writeBody(a), o.id());
            // с диска — те же метаданные, что у эталонов/тестов (иначе на сервере деревья на песке, валуны не вкопаны)
            assertEquals(o.meta().on(), a.meta().on(), o.id());
            assertEquals(o.meta().sink(), a.meta().sink(), o.id());
            assertEquals(o.meta().weight(), a.meta().weight(), 1e-9, o.id());
            assertEquals(o.meta().underwater(), a.meta().underwater(), o.id());
            assertEquals(o.meta().replaceAll(), a.meta().replaceAll(), o.id());
        }
    }

    @Test
    void honestHints() throws Exception {
        PortableProvider pp = provider();
        var g = new ModuleCubeGen(pp, new GenSettings("vale", SEED, pp.defaultBounds(), Map.of()), 5L, lib());
        GenTest.Result res = GenTest.run(g, 200);
        assertTrue(res.ok(), res.first().toString());
        System.out.println("[vale] gentest: cubes " + res.cubes() + ", ms/cube " + res.msPerCube());
        // highest у поверхности, все виды: деревья, вода, растения
        Random r = new Random(3);
        int bad = 0, n = 0;
        List<String> first = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            int x = r.nextInt(3000) - 1500, z = r.nextInt(3000) - 1500;
            for (HeightIndex.Kind k : HeightIndex.Kind.values()) {
                int lo = -500, hi = 700;
                if (i % 3 == 1) { lo = 280 + r.nextInt(60); hi = lo + r.nextInt(40); }  // окна по Y
                int a = g.w.highest(x, z, lo, hi, ink.hoka.odyssey.api.worldgen.HeightKind.values()[k.ordinal()]);
                assertNotEquals(PlanetGenerator.UNKNOWN, a);
                int b = GenTest.scanDirect(g, x, z, lo, hi, k);
                n++;
                if (a != b) { bad++; if (first.size() < 8) first.add(k + " " + x + " " + z + " [" + lo + "," + hi + "]: " + a + " ≠ " + b); }
            }
        }
        assertEquals(0, bad, "highest расходится в " + bad + " из " + n + ": " + first);
    }

    @Test
    void serverEqualsClient() throws Exception {
        PortableProvider pp = provider();
        GenObjects lib = lib();
        var g = new ModuleCubeGen(pp, new GenSettings("vale", SEED, pp.defaultBounds(), Map.of()), 5L, lib);
        var cl = GenClassLoader.load(jar, getClass().getClassLoader());
        GenObjects clientLib = GenObjects.readPack(lib.pack());
        GenBounds b = new GenBounds(-500, 2000, 0);
        PlanetGenerator client = cl.provider().create(GenContext.of("vale", SEED, b, Map.of(), MinestomGenIds.get(), false, null, clientLib));
        Random r = new Random(17);
        int trees = 0;
        for (int k = 0; k < 300; k++) {
            int cx = r.nextInt(201) - 100, cz = r.nextInt(201) - 100;
            int h = client.highest(cx << 4, cz << 4, -500, 2000, HeightKind.SURFACE);
            int cy = (h >> 4) + r.nextInt(3) - 1;
            long server = g.visHash(cx, cy, cz);
            GenArrayCube c = GenFingerprint.visible(client, b, SEED, cx, cy, cz, new GenArrayCube(false));
            assertEquals(server, c.visHash(), "сервер = клиент " + cx + " " + cy + " " + cz);
            assertEquals(server, CubeHash.of(g.cube(cx, cy, cz).section()), "куб ядра = видимая часть");
            for (int v : c.blocks) if (v != 0 && isWood(v)) { trees++; break; }
        }
        assertTrue(trees > 5, "деревья есть: " + trees);
    }

    /** Сводка мира (печать): высоты, вода, зоны, спавн у берега рядом с 0,0. */
    @Test
    void worldSummary() throws Exception {
        PortableProvider pp = provider();
        PlanetGenerator g = pp.gen.create(GenContext.of("vale", SEED, pp.gen.defaultBounds(), Map.of(), MinestomGenIds.get(), false, null, lib()));
        int[] hist = new int[8];
        int water = 0, n = 0, max = Integer.MIN_VALUE, min = Integer.MAX_VALUE;
        Map<Integer, Integer> biomes = new TreeMap<>();
        for (int x = -4000; x <= 4000; x += 64) for (int z = -4000; z <= 4000; z += 64) {
            int h = g.highest(x, z, -500, 2000, HeightKind.MOTION);
            n++;
            if (h <= 300) water++;
            max = Math.max(max, h); min = Math.min(min, h);
            hist[Math.max(0, Math.min(7, (h - 250) / 50))]++;
            biomes.merge(g.columnBiome(x, z), 1, Integer::sum);
        }
        System.out.println("[vale] columns " + n + ", water " + (100 * water / n) + "%, heights " + min + ".." + max + ", per 50 from 250: " + Arrays.toString(hist));
        System.out.println("[vale] biomes (id=columns): " + biomes);
        // спавн: суша 302..308 ближе к 0,0, в 10 блоках — вода (блок 299 — вода)
        int waterState = net.minestom.server.instance.block.Block.WATER.stateId();
        GenBounds bb = pp.gen.defaultBounds();
        GenArrayCube buf = new GenArrayCube(false);
        int best = Integer.MAX_VALUE, sx = 0, sz = 0, sy = 0;
        for (int x = -200; x <= 200; x += 8) for (int z = -200; z <= 200; z += 8) {
            int d = x * x + z * z;
            if (d >= best) continue;
            int h = g.highest(x, z, -500, 2000, HeightKind.MOTION);
            if (h < 302 || h > 308) continue;
            boolean near = false;
            for (int[] o : new int[][]{{10, 0}, {-10, 0}, {0, 10}, {0, -10}, {7, 7}, {-7, 7}, {7, -7}, {-7, -7}}) {
                int wx = x + o[0], wz = z + o[1];
                if (g.highest(wx, wz, -500, 2000, HeightKind.MOTION) != 300) continue;
                GenArrayCube c = GenFingerprint.visible(g, bb, SEED, wx >> 4, 299 >> 4, wz >> 4, buf);
                if (c.blocks[GenArrayCube.index(wx & 15, 299 & 15, wz & 15)] == waterState) { near = true; break; }
            }
            if (!near) continue;
            best = d; sx = x; sz = z; sy = h;
        }
        System.out.println("[vale] spawn near shore: " + sx + " " + (sy + 1) + " " + sz + " zone biome " + g.columnBiome(sx, sz));
        assertTrue(sy > 0, "spawn found");
    }

    static boolean isWood(int state) {
        var b = net.minestom.server.instance.block.Block.fromStateId(state);
        if (b == null) return false;
        String k = b.key().value();
        return k.endsWith("_log") || k.endsWith("_wood") || k.endsWith("_stem");
    }

    /** Метод генератора из jar (пакетный, рефлексией). */
    static Object call(Object gen, String name, Object... args) throws Exception {
        for (var m : gen.getClass().getDeclaredMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            m.setAccessible(true);
            return m.invoke(gen, args);
        }
        throw new NoSuchMethodException(name);
    }

    static PlanetGenerator portable(PortableProvider pp, long seed) {
        return pp.gen.create(GenContext.of("vale", seed, pp.gen.defaultBounds(), Map.of(), MinestomGenIds.get(), false, null, lib()));
    }

    /** Флаги высот каждого состояния генератора и объектов (Flags.of) = предикаты ядра (HeightIndex.info). */
    @Test
    void flagsMatchCore() throws Exception {
        PlanetGenerator g = portable(provider(), SEED);
        @SuppressWarnings("unchecked") List<String> used = (List<String>) call(g, "statesUsed");
        assertTrue(used.size() > 150, "состояний " + used.size());
        GenIdTable ids = MinestomGenIds.get();
        List<String> bad = new ArrayList<>();
        for (String s : new TreeSet<>(used)) {
            int st = s.indexOf('[') < 0 ? ids.defaultState(s) : ids.state(s);
            if (st == 0) continue;
            int f = (int) call(g, "flagsOf", s);
            int inf = HeightIndex.info(st);
            int sky = HeightIndex.contrib(inf, HeightIndex.Kind.SKY, 10), mot = HeightIndex.contrib(inf, HeightIndex.Kind.MOTION, 10);
            int esky = (f & 1) != 0 ? 10 : (f & 2) != 0 ? 9 : HeightIndex.NONE, emot = (f & 4) != 0 ? 10 : HeightIndex.NONE;
            if (sky != esky || mot != emot) bad.add(s + " ядро sky=" + sky + " motion=" + mot + ", генератор флаги " + f);
        }
        assertTrue(bad.isEmpty(), bad.toString());
    }

    /**
     * Прицельно: колонки гор (пики, ледники), разломов, тепуи и великанов — highest всех видов в случайных окнах = скан
     * кубов; каждый куб колонки: hasBlocks=false → пуст, uniform → весь одного состояния.
     */
    @Test
    void honestHintsTargeted() throws Exception {
        PortableProvider pp = provider();
        PlanetGenerator g = portable(pp, SEED);
        GenBounds b = pp.gen.defaultBounds();
        Map<String, List<int[]>> found = new TreeMap<>();
        for (int x = -12000; x <= 12000; x += 97) for (int z = -12000; z <= 12000; z += 89) {
            String zn = (String) call(g, "zoneName", x, z);
            int h = (int) call(g, "surface", x, z);
            String key = switch (zn) {
                case "jagged", "stony", "frozen" -> h > 1500 ? "peak" : "mountain";
                case "rift" -> "rift";
                case "redwood", "jungle", "pale", "swamp", "glow", "cherry", "savanna", "mesa" -> zn;
                default -> h > 700 && !zn.equals("alpine") && !zn.equals("grove") && !zn.equals("taiga") ? "tepui" : null;
            };
            if (key == null) continue;
            List<int[]> l = found.computeIfAbsent(key, k -> new ArrayList<>());
            if (l.size() < 14) l.add(new int[]{x, z});
        }
        System.out.println("[vale] targeted: " + found.keySet());
        for (String k : List.of("peak", "mountain", "rift", "redwood", "jungle")) assertTrue(found.containsKey(k), "нет колонок " + k);
        Random r = new Random(11);
        GenArrayCube buf = new GenArrayCube(false);
        List<String> bad = new ArrayList<>();
        int checks = 0, cubes = 0;
        for (var en : found.entrySet()) for (int[] p : en.getValue()) for (int rep = 0; rep < 3; rep++) {
            int x = p[0] + r.nextInt(48) - 24, z = p[1] + r.nextInt(48) - 24;
            // столб кубов колонки: снизу (у поверхности) до верха объектов
            int surf = g.highest(x, z, b.minY(), b.maxY(), HeightKind.SURFACE);
            int[][] col = new int[(b.maxY() >> 4) - (b.minY() >> 4) + 1][];
            int cy0 = b.minY() >> 4;
            for (int cy = Math.max(cy0, (surf >> 4) - 40); cy <= Math.min(b.maxY() >> 4, (surf >> 4) + 18); cy++) {
                buf.reset(x >> 4, cy, z >> 4, SEED);
                boolean has = g.hasBlocks(x >> 4, cy, z >> 4);
                int u = g.uniform(x >> 4, cy, z >> 4);
                g.generate(buf);
                cubes++;
                int nonAir = 0;
                for (int v : buf.blocks) if (v != 0) nonAir++;
                if (!has && nonAir > 0) bad.add(en.getKey() + " hasBlocks=false, а куб не пуст " + (x >> 4) + " " + cy + " " + (z >> 4));
                if (u >= 0) for (int v : buf.blocks) if (v != u) { bad.add(en.getKey() + " uniform не сходится " + (x >> 4) + " " + cy + " " + (z >> 4)); break; }
                int[] ys = new int[16];
                for (int ly = 0; ly < 16; ly++) ys[ly] = buf.blocks[GenArrayCube.index(x & 15, ly, z & 15)];
                col[cy - cy0] = ys;
            }
            for (int w = 0; w < 6; w++) {
                int lo = w == 0 ? b.minY() : Math.max(b.minY(), surf - 40 + r.nextInt(60)), hi = w == 0 ? b.maxY() : Math.min(b.maxY(), lo + r.nextInt(200));
                for (HeightKind kind : HeightKind.values()) {
                    int a = g.highest(x, z, lo, hi, kind);
                    int want = HeightIndex.NONE;
                    scan:
                    for (int y = hi; y >= lo; y--) {
                        int[] ys = col[(y >> 4) - cy0];
                        if (ys == null) {
                            if (y > surf + 300 || g.hasBlocks(x >> 4, y >> 4, z >> 4)) {
                                if (g.hasBlocks(x >> 4, y >> 4, z >> 4)) {
                                    buf.reset(x >> 4, y >> 4, z >> 4, SEED);
                                    g.generate(buf);
                                    ys = new int[16];
                                    for (int ly = 0; ly < 16; ly++) ys[ly] = buf.blocks[GenArrayCube.index(x & 15, ly, z & 15)];
                                } else ys = new int[16];
                            } else {
                                int u = g.uniform(x >> 4, y >> 4, z >> 4);
                                if (u < 0) { buf.reset(x >> 4, y >> 4, z >> 4, SEED); g.generate(buf); ys = new int[16];
                                    for (int ly = 0; ly < 16; ly++) ys[ly] = buf.blocks[GenArrayCube.index(x & 15, ly, z & 15)]; }
                                else { ys = new int[16]; Arrays.fill(ys, u); }
                            }
                            col[(y >> 4) - cy0] = ys;
                        }
                        int v = ys[y & 15];
                        if (v == 0) continue;
                        int c = HeightIndex.contrib(HeightIndex.info(v), HeightIndex.Kind.values()[kind.ordinal()], y);
                        if (c != HeightIndex.NONE) { want = c; break scan; }
                    }
                    checks++;
                    if (a != want && bad.size() < 20) bad.add(en.getKey() + " highest " + kind + " " + x + " " + z + " [" + lo + "," + hi + "]: " + a + " ≠ " + want);
                }
            }
        }
        System.out.println("[vale] targeted: columns " + found.values().stream().mapToInt(List::size).sum() * 3 + ", cubes " + cubes + ", highest checks " + checks);
        assertTrue(bad.isEmpty(), bad.toString());
    }

    /**
     * Ванильная «живучесть» (как после апдейта соседа в MC): лианы, лозы, мох, вьюны, какао, пропагулы — с опорой; двойные
     * растения целые; кактус без твёрдых соседей; растения не висят; песок/гравий не над пустотой; вода и лава не стоят стеной
     * над воздухом; листва persistent. Участки — колонки чанков в зонах с великанами, грибами, разломами, мезами, топями, реками;
     * объекты соседних колонок в них уже вставлены, соседи для проверки берутся из соседних кубов.
     */
    @Test
    void vanillaSurvival() throws Exception {
        PortableProvider pp = provider();
        PlanetGenerator g = portable(pp, SEED);
        GenBounds b = pp.gen.defaultBounds();
        Set<String> want = Set.of("redwood", "jungle", "pale", "swamp", "glow", "cherry", "savanna", "mesa", "rift", "river", "lake", "taiga");
        Map<String, List<int[]>> found = new TreeMap<>();
        for (int x = -12000; x <= 12000; x += 131) for (int z = -12000; z <= 12000; z += 127) {
            String zn = (String) call(g, "zoneName", x, z);
            if (!want.contains(zn)) continue;
            List<int[]> l = found.computeIfAbsent(zn, k -> new ArrayList<>());
            if (l.size() < 5) l.add(new int[]{x, z});
        }
        Map<String, Integer> seen = new TreeMap<>();
        List<String> bad = new ArrayList<>();
        int checked = 0;
        for (var en : found.entrySet()) for (int[] p : en.getValue()) {
            int ccx = p[0] >> 4, ccz = p[1] >> 4;
            int surf = (int) call(g, "surface", p[0], p[1]);
            int ylo = Math.max(b.minY() + 1, surf - 80), yhi = Math.min(b.maxY() - 1, surf + 170);
            Map<Long, int[]> cubes = new HashMap<>();
            Blk w = (x, y, z) -> {
                int cx = x >> 4, cy = y >> 4, cz = z >> 4;
                long key = ((long) cx & 0x1FFFFF) << 42 | ((long) cy & 0x1FFFFF) << 21 | ((long) cz & 0x1FFFFF);
                int[] c = cubes.computeIfAbsent(key, k -> {
                    GenArrayCube buf = new GenArrayCube(false);
                    buf.reset(cx, cy, cz, SEED);
                    if (g.hasBlocks(cx, cy, cz)) g.generate(buf);
                    return buf.blocks.clone();
                });
                return c[GenArrayCube.index(x & 15, y & 15, z & 15)];
            };
            for (int x = ccx * 16; x < ccx * 16 + 16; x++) for (int z = ccz * 16; z < ccz * 16 + 16; z++)
                for (int y = ylo; y <= yhi; y++) {
                    int v = w.at(x, y, z);
                    if (v == 0) continue;
                    Block blk = Block.fromStateId(v);
                    String id = blk.key().value();
                    String why = survive(w, blk, id, x, y, z);
                    checked++;
                    if (why != null) {
                        seen.merge(id, 1, Integer::sum);
                        if (bad.size() < 25) bad.add(en.getKey() + " " + blk.name() + blk.properties() + " в " + x + " " + y + " " + z + ": " + why);
                    }
                }
        }
        System.out.println("[vale] survival: zones " + found.keySet() + ", blocks " + checked + ", bad " + seen);
        assertTrue(found.keySet().containsAll(Set.of("redwood", "jungle", "swamp", "glow", "rift", "mesa")), "зоны: " + found.keySet());
        assertTrue(bad.isEmpty(), bad.toString());
    }

    interface Blk { int at(int x, int y, int z); }

    static Block blk(int v) { return v == 0 ? Block.AIR : Block.fromStateId(v); }

    static boolean faceFull(int v, BlockFace f) {
        if (v == 0) return false;
        Block b = blk(v);
        return !b.isLiquid() && b.registry().collisionShape().isFaceFull(f);
    }

    /** Прочная опора (у листвы опорная форма пустая — ягодные/плакучие лозы и спороцвет на ней не держатся). */
    static boolean sturdy(int v, BlockFace f) { return faceFull(v, f) && !blk(v).key().value().endsWith("_leaves"); }

    static final Set<String> GROUND = Set.of("short_grass", "fern", "tall_grass", "large_fern", "dandelion", "poppy", "oxeye_daisy",
            "azure_bluet", "cornflower", "allium", "lily_of_the_valley", "blue_orchid", "orange_tulip", "pink_tulip", "red_tulip",
            "white_tulip", "sunflower", "peony", "rose_bush", "lilac", "bush", "firefly_bush", "short_dry_grass", "tall_dry_grass",
            "dead_bush", "red_mushroom", "brown_mushroom", "pink_petals", "leaf_litter", "wildflowers", "moss_carpet", "pale_moss_carpet",
            "closed_eyeblossom", "open_eyeblossom");

    static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Почему блок не выжил бы в ванилле при апдейте соседа (null — живёт). */
    static String survive(Blk w, Block b, String id, int x, int y, int z) {
        int up = w.at(x, y + 1, z), dn = w.at(x, y - 1, z);
        String upId = blk(up).key().value(), dnId = blk(dn).key().value();
        if (id.endsWith("_leaves")) return "true".equals(b.getProperty("persistent")) ? null : "листва не persistent";
        switch (id) {
            case "vine" -> {
                String[] f = {"east", "west", "south", "north"};
                BlockFace[] face = {BlockFace.WEST, BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH};
                for (int i = 0; i < 4; i++) {
                    if (!"true".equals(b.getProperty(f[i]))) continue;
                    if (faceFull(w.at(x + SIDES[i][0], y, z + SIDES[i][1]), face[i])) return null;
                    if (upId.equals("vine") && "true".equals(blk(up).getProperty(f[i]))) return null;
                }
                if ("true".equals(b.getProperty("up")) && faceFull(up, BlockFace.BOTTOM)) return null;
                return "лиана без опоры";
            }
            case "cave_vines", "cave_vines_plant" -> {
                return upId.startsWith("cave_vines") || sturdy(up, BlockFace.BOTTOM) ? null : "лоза без прочной опоры сверху (" + upId + ")";
            }
            case "weeping_vines", "weeping_vines_plant" -> {
                return upId.startsWith("weeping_vines") || sturdy(up, BlockFace.BOTTOM) ? null : "лоза без прочной опоры сверху (" + upId + ")";
            }
            case "spore_blossom", "hanging_roots" -> {
                return sturdy(up, BlockFace.BOTTOM) ? null : "без прочной опоры сверху (" + upId + ")";
            }
            case "pale_hanging_moss" -> {
                return upId.equals("pale_hanging_moss") || faceFull(up, BlockFace.BOTTOM) ? null : "мох без опоры (" + upId + ")";
            }
            case "twisting_vines", "twisting_vines_plant" -> {
                return dnId.startsWith("twisting_vines") || sturdy(dn, BlockFace.TOP) ? null : "вьюн без опоры снизу (" + dnId + ")";
            }
            case "mangrove_propagule" -> {
                if ("true".equals(b.getProperty("hanging"))) return upId.equals("mangrove_leaves") ? null : "пропагула не под мангровой листвой";
                return null;
            }
            case "cocoa" -> {
                String f = b.getProperty("facing");
                int dx = f.equals("east") ? 1 : f.equals("west") ? -1 : 0, dz = f.equals("south") ? 1 : f.equals("north") ? -1 : 0;
                String n = blk(w.at(x + dx, y, z + dz)).key().value();
                return n.contains("jungle_log") || n.contains("jungle_wood") ? null : "какао без джунглевого бревна (" + n + ")";
            }
            case "cactus" -> {
                for (int[] q : SIDES) {
                    Block nb = blk(w.at(x + q[0], y, z + q[1]));
                    if (nb.isSolid() || nb.isLiquid()) return "кактус: сосед " + nb.name();
                }
                return dnId.equals("sand") || dnId.equals("red_sand") || dnId.equals("cactus") ? null : "кактус не на песке (" + dnId + ")";
            }
            case "cactus_flower" -> { return dnId.equals("cactus") || sturdy(dn, BlockFace.TOP) ? null : "цветок кактуса без опоры"; }
            case "lily_pad" -> { return dnId.equals("water") ? null : "кувшинка не на воде"; }
            case "sand", "red_sand", "gravel" -> { return dn == 0 ? "сыпучий над пустотой" : null; }
            case "water", "lava" -> {
                for (int[] q : SIDES) if (w.at(x + q[0], y, z + q[1]) == 0) return id + " стоит стеной над воздухом";
                return dn == 0 ? id + " над пустотой" : null;
            }
            default -> {}
        }
        if (GROUND.contains(id)) {
            if ("upper".equals(b.getProperty("half"))) return dnId.equals(id) && "lower".equals(blk(dn).getProperty("half")) ? null : "верх без низа";
            if ("lower".equals(b.getProperty("half")) && !(upId.equals(id) && "upper".equals(blk(up).getProperty("half")))) return "низ без верха";
            if (id.endsWith("carpet")) return dn != 0 ? null : "ковёр висит";
            return sturdy(dn, BlockFace.TOP) || dnId.equals("mud") ? null : "растение висит (" + dnId + ")";   // грязь ниже блока, но почва
        }
        return null;
    }

    /** Горы: пик выше 1500 есть, гистограмма высот, доли зон. */
    @Test
    void mountainsAndZones() throws Exception {
        PlanetGenerator g = portable(provider(), SEED);
        int[] hist = new int[11];
        int max = Integer.MIN_VALUE, n = 0, over1500 = 0, mx = 0, mz = 0;
        Map<String, Integer> zones = new TreeMap<>();
        for (int x = -16000; x <= 16000; x += 128) for (int z = -16000; z <= 16000; z += 128) {
            int h = (int) call(g, "surface", x, z);
            n++;
            if (h > max) { max = h; mx = x; mz = z; }
            if (h > 1500) over1500++;
            hist[Math.max(0, Math.min(10, (h - 200) / 200))]++;
            zones.merge((String) call(g, "zoneName", x, z), 1, Integer::sum);
        }
        System.out.println("[vale] heights: max " + max + " at " + mx + " " + mz + ", >1500: " + (100.0 * over1500 / n) + "%, per 200 from 200: " + Arrays.toString(hist));
        StringBuilder sb = new StringBuilder();
        for (var e : zones.entrySet()) sb.append(e.getKey()).append(' ').append(String.format(Locale.ROOT, "%.1f", 100.0 * e.getValue() / n)).append("% ");
        System.out.println("[vale] zones: " + sb);
        assertTrue(max > 1500, "пик выше 1500: " + max);
        assertTrue(max <= 1960, "потолок: " + max);
    }

    /**
     * Скорость (генератор с объектами), печать в лог — сравнение версий:
     * <ul><li>«сплошь» — 8 участков 12×12 колонок чанков подряд (как при обходе мира): мс на куб и на колонку;</li>
     * <li>«вразброс» — 400 одиночных колонок (кэши холодные: рельеф соседей и объекты в охвате считаются ради одной);</li>
     * <li>доли (v2): рельеф колонки (Col.compute) и сбор объектов колонки.</li></ul>
     * Кубы — все с блоками (не пустые и не однородные). v1 (23ноя): сплошь 0.13 мс/куб, вразброс 0.54 мс/куб.
     */
    @Test
    void speed() throws Exception {
        PortableProvider pp = provider();
        GenBounds b = pp.gen.defaultBounds();
        boolean v2 = pp.gen.version() >= 2;
        for (int pass = 0; pass < 2; pass++) {                              // 0 — прогрев JIT, 1 — замер
            PlanetGenerator g = pp.gen.create(GenContext.of("vale", SEED + pass, b, Map.of(), MinestomGenIds.get(), false, null, lib()));
            GenArrayCube buf = new GenArrayCube(false);
            Random r = new Random(77 + pass);
            long ns = 0;
            int cubes = 0, cols = 0;
            for (int reg = 0; reg < 8; reg++) {
                int bx = r.nextInt(1000) - 500, bz = r.nextInt(1000) - 500;
                for (int cx = bx; cx < bx + 12; cx++) for (int cz = bz; cz < bz + 12; cz++) {
                    long t0 = System.nanoTime();
                    for (int cy = b.minY() >> 4; cy <= b.maxY() >> 4; cy++) {
                        if (!g.hasBlocks(cx, cy, cz) || g.uniform(cx, cy, cz) >= 0) continue;
                        buf.reset(cx, cy, cz, SEED);
                        g.generate(buf);
                        cubes++;
                    }
                    ns += System.nanoTime() - t0;
                    cols++;
                }
            }
            long ns2 = 0, nsObj = 0, nsCol = 0;
            int cubes2 = 0;
            for (int i = 0; i < 400; i++) {
                int cx = (r.nextInt(400) - 200) * 7 + 9000, cz = (r.nextInt(400) - 200) * 7;
                long t0 = System.nanoTime();
                if (v2) {
                    call(g, "surface", cx << 4, cz << 4);
                    long t1 = System.nanoTime();
                    nsCol += t1 - t0;
                    g.hasBlocks(cx, b.maxY() >> 4, cz);
                    nsObj += System.nanoTime() - t1;
                }
                for (int cy = b.minY() >> 4; cy <= b.maxY() >> 4; cy++) {
                    if (!g.hasBlocks(cx, cy, cz) || g.uniform(cx, cy, cz) >= 0) continue;
                    buf.reset(cx, cy, cz, SEED);
                    g.generate(buf);
                    cubes2++;
                }
                ns2 += System.nanoTime() - t0;
            }
            if (pass == 0) continue;
            System.out.printf(Locale.ROOT, "[vale] speed сплошь: ms/cube %.3f, ms/column %.2f, cubes/column %.1f%s%n", ns / 1e6 / cubes,
                    ns / 1e6 / cols, (double) cubes / cols, "");
            System.out.printf(Locale.ROOT, "[vale] speed вразброс: ms/cube %.3f, ms/column %.2f%s%n", ns2 / 1e6 / cubes2, ns2 / 1e6 / 400,
                    v2 ? String.format(Locale.ROOT, ", из них рельеф колонки (Col.compute) %.3f, объекты в охвате %.2f", nsCol / 1e6 / 400,
                            nsObj / 1e6 / 400) : "");
        }
    }

    // ───────────── эталоны ─────────────

    @Test
    void golden() throws Exception {
        PortableProvider pp = provider();
        GenPortableTest.golden(pp.gen);                                       // vale@2.txt — без объектов
        GenObjects lib = lib();
        GenIdTable ids = MinestomGenIds.get();
        GenBounds b = pp.gen.defaultBounds();
        long seed = 12345;
        PlanetGenerator g = pp.gen.create(GenContext.of("golden", seed, b, Map.of(), ids, false, null, lib));
        StringBuilder sb = new StringBuilder();
        sb.append("# эталон генератора vale@2 с объектами ValeDemoObjects (demo-objects/, сид 12345, options {})\n");
        sb.append("objectsHash ").append(lib.hash()).append('\n');
        sb.append("fingerprint ").append(GenFingerprint.of(g, b, seed, lib.hash())).append('\n');
        GenArrayCube buf = new GenArrayCube(false);
        Random r = new Random(5);
        for (int k = 0; k < 400; k++) {
            int cx = r.nextInt(401) - 200, cz = r.nextInt(401) - 200;
            int h = g.highest(cx << 4, cz << 4, b.minY(), b.maxY(), HeightKind.SURFACE);
            int cy = (h >> 4) + r.nextInt(3) - 1;
            sb.append("cube ").append(cx).append(' ').append(cy).append(' ').append(cz).append(' ')
                    .append(GenFingerprint.visible(g, b, seed, cx, cy, cz, buf).visHash()).append('\n');
        }
        Path f = Path.of(System.getProperty("hoka.test.golden-dir")).resolve("vale@2-objects.txt");
        String now = sb.toString();
        if (!Files.exists(f) || Boolean.getBoolean("hoka.golden.update")) { Files.writeString(f, now, StandardCharsets.UTF_8); return; }
        assertEquals(Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n"), now, "эталон vale@2-objects разошёлся");
    }
}
