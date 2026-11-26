package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.worldgen.GenSettings;
import ink.hoka.odyssey.gen.*;
import ink.hoka.odyssey.server.tall.CubeGen;
import ink.hoka.odyssey.server.tall.CubeHash;
import ink.hoka.odyssey.server.tall.FlatTallGen;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.network.NetworkBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Переносимые генераторы (gen-api, ядро 0.59): общий хэш, детерминизм jar noise-test в двух загрузчиках и потоках,
 * честность uniform/hasBlocks/highest, путь ядра = видимая часть клиента, flat как прежде, эталон для клиента.
 */
class GenPortableTest {

    static byte[] noiseJar;

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        noiseJar = Files.readAllBytes(Path.of(System.getProperty("hoka.test.noise-jar")));
    }

    // ───────────── 1. хэш: один алгоритм ─────────────

    @Test
    void genHashMatchesCubeHash() {
        Random r = new Random(5);
        int[] pick = {0, Block.STONE.stateId(), Block.DIRT.stateId(), Block.OAK_LOG.withProperty("axis", "x").stateId(), Block.WATER.stateId()};
        var reg = MinecraftServer.getBiomeRegistry();
        for (int t = 0; t < 20; t++) {
            Section s = new Section();
            int[] b = new int[4096], m = new int[64];
            int kinds = 1 + r.nextInt(pick.length);
            for (int i = 0; i < 4096; i++) {
                int v = pick[r.nextInt(kinds)];
                b[i] = v;
                s.blockPalette().set(i & 15, i >> 8, (i >> 4) & 15, v);
            }
            for (int i = 0; i < 64; i++) {
                int v = r.nextInt(Math.min(5, reg.size()));
                m[i] = v;
                s.biomePalette().set(i & 3, i >> 4, (i >> 2) & 3, v);
            }
            long g = GenHash.ids(b, m);
            assertEquals(g, CubeHash.of(s), "GenHash = CubeHash.of");
            byte[] w = NetworkBuffer.makeArray(buf -> {
                buf.write(NetworkBuffer.SHORT, (short) s.blockPalette().count());
                buf.write(Palette.BLOCK_SERIALIZER, s.blockPalette());
                buf.write(Palette.biomeSerializer(reg.size()), s.biomePalette());
            });
            assertEquals(g, CubeHash.wire(w, new int[]{0}), "GenHash = CubeHash.wire (как у клиента)");
        }
        assertEquals(GenHash.ids(filled(Block.STONE.stateId()), new int[64]), GenHash.uniform(Block.STONE.stateId(), 0));
    }

    static int[] filled(int v) { int[] a = new int[4096]; Arrays.fill(a, v); return a; }

    // ───────────── 2. детерминизм jar ─────────────

    static PlanetGenerator noise(ClassLoader parent, long seed) throws Exception {
        var l = GenClassLoader.load(noiseJar, parent);
        assertEquals("noise-test", l.manifest().id());
        assertEquals(2, l.provider().version());
        return l.provider().create(GenContext.of("t", seed, l.provider().defaultBounds(), Map.of(), MinestomGenIds.get(), false, null));
    }

    static List<int[]> cubes(int n, long seed) {
        Random r = new Random(seed);
        List<int[]> out = new ArrayList<>();
        for (int k = 0; k < n; k++) out.add(new int[]{r.nextInt(2001) - 1000, -5 + r.nextInt(23), r.nextInt(2001) - 1000});
        return out;
    }

    @Test
    void noiseDeterministicAcrossLoadersAndThreads() throws Exception {
        PlanetGenerator a = noise(getClass().getClassLoader(), 777), b = noise(getClass().getClassLoader(), 777);
        assertNotSame(a.getClass(), b.getClass(), "два независимых загрузчика");
        GenBounds bounds = new GenBounds(-64, 255, 0);
        List<int[]> cs = cubes(2000, 1);
        long[] one = new long[cs.size()];
        GenArrayCube buf = new GenArrayCube(false);
        for (int k = 0; k < cs.size(); k++) { int[] c = cs.get(k); one[k] = GenFingerprint.visible(a, bounds, 777, c[0], c[1], c[2], buf).visHash(); }
        long[] par = new long[cs.size()];
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < cs.size(); k++) order.add(k);
        Collections.shuffle(order, new Random(3));
        ExecutorService ex = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int k : order) fs.add(ex.submit(() -> {
                int[] c = cs.get(k);
                par[k] = GenFingerprint.visible(b, bounds, 777, c[0], c[1], c[2], new GenArrayCube(false)).visHash();
            }));
            for (var f : fs) f.get(60, TimeUnit.SECONDS);
        } finally { ex.shutdownNow(); }
        assertArrayEquals(one, par, "один поток = 8 потоков в случайном порядке, другой загрузчик");
        assertEquals(GenFingerprint.of(a, bounds, 777), GenFingerprint.of(b, bounds, 777));
        assertNotEquals(GenFingerprint.of(a, bounds, 777), GenFingerprint.of(noise(getClass().getClassLoader(), 40777), bounds, 40777), "другой сид — другой отпечаток");
    }

    @Test
    void noiseHintsHonest() throws Exception {
        PlanetGenerator g = noise(getClass().getClassLoader(), 42);
        GenArrayCube buf = new GenArrayCube(false);
        for (int[] c : cubes(600, 2)) {
            buf.reset(c[0], c[1], c[2], 42);
            g.generate(buf);
            boolean empty = true;
            for (int v : buf.blocks) if (v != 0) { empty = false; break; }
            if (!g.hasBlocks(c[0], c[1], c[2])) assertTrue(empty, "hasBlocks=false, а куб не пуст " + Arrays.toString(c));
            int u = g.uniform(c[0], c[1], c[2]);
            if (u >= 0) for (int v : buf.blocks) assertEquals(u, v, "uniform честный " + Arrays.toString(c));
        }
        for (int k = 0; k < 300; k++) {
            int x = k * 37 - 5000, z = k * 91 + 17;
            int h = g.highest(x, z, -64, 255, HeightKind.SURFACE);
            buf.reset(x >> 4, h >> 4, z >> 4, 42);
            g.generate(buf);
            assertNotEquals(0, buf.blocks[GenArrayCube.index(x & 15, h & 15, z & 15)], "на высоте highest — блок");
            if ((h + 1) >> 4 == h >> 4) assertEquals(0, buf.blocks[GenArrayCube.index(x & 15, (h + 1) & 15, z & 15)], "над highest — воздух");
        }
    }

    // ───────────── 3. ядро: регистрация jar, путь ModuleCubeGen = видимая часть клиента ─────────────

    @Test
    void registeredJarGeneratesLikeClient() throws Exception {
        var l = PortableGens.load(noiseJar);
        var pp = new PortableProvider(l.provider(), l.sha256(), noiseJar);
        assertTrue(pp.filePath.startsWith("gen/noise-test@2-") && pp.filePath.endsWith(".jar"), pp.filePath);
        var g = new ModuleCubeGen(pp, new GenSettings("t", 99, pp.defaultBounds(), Map.of("amp", 30)), 5L);
        assertTrue(g.portable());
        // клиент: свой загрузчик, контекст из op 20 (options строкой JSON, границы карты)
        PlanetGenerator client = l.provider().create(GenContext.of("t", 99, new GenBounds(-64, 255, 0),
                GenJson.parseObject(GenJson.write(Map.of("amp", 30))), MinestomGenIds.get(), false, null));
        GenBounds b = new GenBounds(-64, 255, 0);
        for (int[] c : cubes(300, 4)) {
            CubeGen.Cube cube = g.cube(c[0], c[1], c[2]);
            long server = CubeHash.of(cube.section());
            long cl = GenFingerprint.visible(client, b, 99, c[0], c[1], c[2], new GenArrayCube(false)).visHash();
            assertEquals(server, cl, "куб сервера = видимая часть клиента " + Arrays.toString(c));
            assertEquals(server, g.visHash(c[0], c[1], c[2]), "visHash сервера");
        }
        assertEquals(GenFingerprint.of(client, b, 99), g.genFingerprint(), "отпечаток op 20 = отпечаток клиента");
    }

    @Test
    void flatBuiltinSameAsBefore() {
        var flat = GeneratorRegistry.get("flat");
        assertInstanceOf(PortableProvider.class, flat.provider(), "flat — переносимый встроенный");
        assertEquals("flat@1", flat.tag(), "тег прежний — generator_seen и индексы pregen не меняются");
        var g = new ModuleCubeGen(flat.provider(), new GenSettings("f", 0, flat.provider().defaultBounds(), Map.of()));
        var old = new ModuleCubeGen(FlatGenerator.PROVIDER, new GenSettings("f", 0, flat.provider().defaultBounds(), Map.of()));
        assertEquals(old.fingerprint(), g.fingerprint(), "отпечаток индекса pregen тот же");
        for (int[] c : new int[][]{{0, 0, 0}, {0, 1, 0}, {0, 2, 0}, {-1, 2, 5}, {0, 375, 0}, {-1, -125, -1}, {3, 375, 3}, {7, -1, 7}}) {
            assertEquals(ModuleCubeGen.hash(FlatTallGen.INSTANCE.cube(c[0], c[1], c[2])), ModuleCubeGen.hash(g.cube(c[0], c[1], c[2])), Arrays.toString(c));
        }
    }

    @Test
    void secretsOnlyOnServer() {
        GenProvider withOre = new GenProvider() {
            @Override public String id() { return "t-ore"; }
            @Override public int version() { return 1; }
            @Override public PlanetGenerator create(GenContext ctx) {
                int stone = ctx.ids().defaultState("stone"), ore = ctx.ids().defaultState("diamond_ore");
                long ss = ctx.server() ? ctx.secretSeed() : 0;
                return new PlanetGenerator() {
                    @Override public void generate(GenCube out) {
                        out.fill(stone);
                        if (out.secrets()) out.secret((int) (ss & 15), 3, 4, ore);
                    }
                    @Override public boolean mayHaveSecrets(int cx, int cy, int cz) { return true; }
                };
            }
        };
        var pp = PortableProvider.builtin(withOre);
        var g = new ModuleCubeGen(pp, new GenSettings("t", 1, pp.defaultBounds(), Map.of()), 7L);
        var cube = g.cube(0, 0, 0);
        assertEquals(Block.DIAMOND_ORE.stateId(), cube.section().blockPalette().get(7, 3, 4), "у сервера руда на месте");
        long vis = g.visHash(0, 0, 0);
        assertEquals(GenHash.uniform(Block.STONE.stateId(), 0), vis, "видимая часть — без руды");
        assertNotEquals(CubeHash.of(cube.section()), vis);
        var a = g.visible(0, 0, 0, true);
        assertEquals(Map.of(GenArrayCube.index(7, 3, 4), Block.DIAMOND_ORE.stateId()), a.secretList(), "op 22: секрет");
        assertThrows(IllegalStateException.class, () -> GenContext.of("t", 1, new GenBounds(0, 1, 0), Map.of(), MinestomGenIds.get(), false, null).secretSeed());
    }

    // ───────────── 4. эталон для сверки сервер ↔ клиент ─────────────

    /**
     * gen-golden/&lt;id&gt;@&lt;v&gt;.txt: statesCount, statesDigest, biomesDigest, fingerprint, хэши 512 кубов (сид 12345,
     * options {}). Клиентский тест (client/core, настоящие реестры MC) сверяет те же числа. Нет файла — пишется
     * (закоммитить); -Dhoka.golden.update=true — переписать.
     */
    @Test
    void goldenForClient() throws Exception {
        var l = PortableGens.load(noiseJar);
        golden(l.provider());
        golden(BuiltinGens.FLAT);
    }

    static void golden(GenProvider p) throws Exception {
        GenIdTable ids = MinestomGenIds.get();
        GenBounds b = p.defaultBounds();
        long seed = 12345;
        PlanetGenerator g = p.create(GenContext.of("golden", seed, b, Map.of(), ids, false, null));
        StringBuilder sb = new StringBuilder();
        sb.append("# эталон генератора ").append(p.id()).append('@').append(p.version())
                .append(" (сид 12345, options {}): сервер пишет, клиент сверяет — GenPortableTest.goldenForClient\n");
        sb.append("statesCount ").append(ids.statesCount()).append('\n');
        sb.append("statesDigest ").append(ids.statesDigest()).append('\n');
        sb.append("biomesDigest ").append(ids.biomesDigest()).append('\n');
        sb.append("fingerprint ").append(GenFingerprint.of(g, b, seed)).append('\n');
        Random r = new Random(9);
        GenArrayCube buf = new GenArrayCube(false);
        int minCy = b.minY() >> 4, maxCy = b.maxY() >> 4;
        for (int k = 0; k < 512; k++) {
            int cx = r.nextInt(4001) - 2000, cz = r.nextInt(4001) - 2000;
            int cy;
            if (k < 128) cy = minCy + r.nextInt(maxCy - minCy + 1);           // где угодно в границах
            else {                                                            // у поверхности (рельеф, а не камень/воздух)
                int h = g.highest(cx << 4, cz << 4, b.minY(), b.maxY(), HeightKind.SURFACE);
                cy = (h == PlanetGenerator.NONE || h == PlanetGenerator.UNKNOWN ? 0 : h >> 4) + r.nextInt(3) - 1;
            }
            sb.append("cube ").append(cx).append(' ').append(cy).append(' ').append(cz).append(' ')
                    .append(GenFingerprint.visible(g, b, seed, cx, cy, cz, buf).visHash()).append('\n');
        }
        Path f = Path.of(System.getProperty("hoka.test.golden-dir")).resolve(p.id() + "@" + p.version() + ".txt");
        String now = sb.toString();
        if (!Files.exists(f) || Boolean.getBoolean("hoka.golden.update")) {
            Files.createDirectories(f.getParent());
            Files.writeString(f, now, StandardCharsets.UTF_8);
            return;
        }
        String was = Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(was, now, "эталон " + f.getFileName() + " разошёлся: генератор/номера сменились — поднимите version (или -Dhoka.golden.update=true)");
    }
}
