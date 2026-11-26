package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.worldgen.GenSettings;
import ink.hoka.odyssey.gen.*;
import ink.hoka.odyssey.server.objects.ObjectLibrary;
import ink.hoka.odyssey.server.tall.CubeHash;
import ink.hoka.odyssey.server.tall.HeightIndex;
import net.minestom.server.MinecraftServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Генератор vale@1 (настоящий jar) + свои объекты {@link ValeDemoObjects}: объекты пишутся в demo-objects/ (выложить в
 * objects/ сервера), честность hasBlocks/uniform/highest по кубам, сервер = клиент, эталоны vale@1 и vale@1-objects.
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
        assertEquals(Set.of("birch", "bushes", "oak", "rocks", "spruce"), new TreeSet<>(again.categories()));
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
            for (int v : c.blocks) if (v == net.minestom.server.instance.block.Block.OAK_LOG.stateId()) { trees++; break; }
        }
        assertTrue(trees > 5, "дубы есть: " + trees);
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

    // ───────────── эталоны ─────────────

    @Test
    void golden() throws Exception {
        PortableProvider pp = provider();
        GenPortableTest.golden(pp.gen);                                       // vale@1.txt — без объектов
        GenObjects lib = lib();
        GenIdTable ids = MinestomGenIds.get();
        GenBounds b = pp.gen.defaultBounds();
        long seed = 12345;
        PlanetGenerator g = pp.gen.create(GenContext.of("golden", seed, b, Map.of(), ids, false, null, lib));
        StringBuilder sb = new StringBuilder();
        sb.append("# эталон генератора vale@1 с объектами ValeDemoObjects (demo-objects/, сид 12345, options {})\n");
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
        Path f = Path.of(System.getProperty("hoka.test.golden-dir")).resolve("vale@1-objects.txt");
        String now = sb.toString();
        if (!Files.exists(f) || Boolean.getBoolean("hoka.golden.update")) { Files.writeString(f, now, StandardCharsets.UTF_8); return; }
        assertEquals(Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n"), now, "эталон vale@1-objects разошёлся");
    }
}
