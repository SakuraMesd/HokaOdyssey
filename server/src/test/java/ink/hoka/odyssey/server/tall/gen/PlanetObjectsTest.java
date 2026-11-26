package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.ModuleConfig;
import ink.hoka.odyssey.api.worldgen.GenSettings;
import ink.hoka.odyssey.gen.*;
import ink.hoka.odyssey.server.objects.ObjectLibrary;
import ink.hoka.odyssey.server.objects.ObjectPacks;
import ink.hoka.odyssey.server.tall.CubeHash;
import net.minestom.server.MinecraftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Генератор planet (ядро 0.60) и объекты: jar грузится GenClassLoader, серверный путь (ModuleCubeGen + пакет) = «клиентский»
 * (свой загрузчик тех же байт jar + разбор тех же байт пакета, server=false), эталоны planet@1 и planet@1-objects,
 * отпечатки, ключ map.yml objects:, библиотека objects/ на диске.
 */
class PlanetObjectsTest {

    static byte[] planetJar;

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        planetJar = Files.readAllBytes(Path.of(System.getProperty("hoka.test.planet-jar")));
    }

    @AfterEach
    void reset() { ObjectLibrary.setGlobal(null); }

    /** Тестовые объекты на настоящих состояниях Minecraft: дерево, сундук с лутом, камень. */
    static List<GenObject> testObjects() {
        List<GenObject> r = new ArrayList<>();
        GenObjectCodec.Builder t = new GenObjectCodec.Builder(false);
        for (int y = 0; y < 6; y++) t.add(0, y, 0, "minecraft:oak_log[axis=y]");
        for (int y = 4; y <= 7; y++) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            if ((x != 0 || z != 0 || y > 5) && Math.abs(x) + Math.abs(z) <= (y == 7 ? 1 : 3))
                t.add(x, y, z, "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]");
        t.anchor(0, 0, 0);
        r.add(t.build("trees/oak", Map.of("weight", 3, "on", List.of("minecraft:grass_block"))));
        GenObjectCodec.Builder c = new GenObjectCodec.Builder(false);
        c.add(0, 0, 0, "minecraft:cobblestone");
        c.add(1, 0, 0, "minecraft:cobblestone");
        c.add(0, 1, 0, "minecraft:chest[facing=north,type=single,waterlogged=false]");
        c.data(0, 1, 0, "{Items:[{Slot:0b,id:\"minecraft:apple\",count:3b}]}");
        c.add(1, 1, 0, "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=true]");
        c.anchor(0, 0, 0);
        r.add(c.build("trees/cache", Map.of("weight", 1)));
        return r;
    }

    static PortableProvider provider() throws Exception {
        var l = PortableGens.load(planetJar);
        assertEquals("planet", l.manifest().id());
        assertEquals(2, l.manifest().api());
        assertTrue(l.manifest().objects());
        return new PortableProvider(l.provider(), l.sha256(), planetJar, l.manifest());
    }

    // ───────────── 1. сервер = клиент ─────────────

    @Test
    void serverEqualsClientPath() throws Exception {
        PortableProvider pp = provider();
        GenObjects lib = GenObjects.of(testObjects());
        byte[] pack = lib.pack();
        Map<String, Object> opts = Map.of("features", List.of(Map.of("category", "trees", "cell", 9, "density", 0.8, "margin", 1)));
        var g = new ModuleCubeGen(pp, new GenSettings("p", 2024, pp.defaultBounds(), opts), 5L, lib);
        assertTrue(g.portable());
        assertEquals(lib.hash(), g.objects.hash());
        // «клиент»: свой загрузчик тех же байт, пакет из тех же байт, контекст как из op 20
        var cl = GenClassLoader.load(planetJar, getClass().getClassLoader());
        GenObjects clientLib = GenObjects.readPack(pack);
        assertEquals(lib.hash(), clientLib.hash());
        GenBounds b = new GenBounds(-500, 2000, 0);
        PlanetGenerator client = cl.provider().create(GenContext.of("p", 2024, b, GenJson.parseObject(GenJson.write(opts)),
                MinestomGenIds.get(), false, null, clientLib));
        Random r = new Random(11);
        int trees = 0;
        for (int k = 0; k < 256; k++) {
            int cx = r.nextInt(41) - 20, cz = r.nextInt(41) - 20;
            int cy = k < 200 ? 18 + r.nextInt(2) : -32 + r.nextInt(160);          // у поверхности (море 300 → кубы 18–19) и где угодно
            long server = g.visHash(cx, cy, cz);
            GenArrayCube c = GenFingerprint.visible(client, b, 2024, cx, cy, cz, new GenArrayCube(false));
            assertEquals(server, c.visHash(), "сервер = клиент " + cx + " " + cy + " " + cz);
            assertEquals(server, CubeHash.of(g.cube(cx, cy, cz).section()), "куб ядра = видимая часть");
            if (cy == 19) for (int v : c.blocks) if (v != 0) { trees++; break; }
        }
        assertTrue(trees > 20, "деревья есть над поверхностью: " + trees);
        assertEquals(GenFingerprint.of(client, b, 2024, lib.hash()), g.genFingerprint(), "отпечаток op 20 = отпечаток клиента");
        // сундук: на сервере — данные блок-сущности, у клиента их нет (op 22)
        boolean data = false;
        for (int cx = -6; cx <= 6 && !data; cx++) for (int cz = -6; cz <= 6 && !data; cz++)
            if (g.mayHaveSecrets(cx, 18, cz) && !g.visible(cx, 18, cz, true).dataList().isEmpty()) data = true;
        assertTrue(data, "сундук с лутом у сервера");
    }

    @Test
    void fingerprintsDiffer() throws Exception {
        PortableProvider pp = provider();
        GenSettings s = new GenSettings("p", 1, pp.defaultBounds(), Map.of());
        var empty = new ModuleCubeGen(pp, s, 5L, GenObjects.EMPTY);
        var full = new ModuleCubeGen(pp, s, 5L, GenObjects.of(testObjects()));
        assertNotEquals(empty.genFingerprint(), full.genFingerprint());
        assertNotEquals(empty.fingerprint(), full.fingerprint(), "индекс pregen — другой");
        List<GenObject> changed = new ArrayList<>(testObjects());
        changed.set(0, changed.get(0).withMeta(Map.of("weight", 4)));
        assertNotEquals(full.genFingerprint(), new ModuleCubeGen(pp, s, 5L, GenObjects.of(changed)).genFingerprint(), "вес объекта — другой отпечаток");
    }

    @Test
    void planetLayersAndHints() throws Exception {
        PortableProvider pp = provider();
        var g = new ModuleCubeGen(pp, new GenSettings("p", 1, pp.defaultBounds(), Map.of()), 5L, GenObjects.EMPTY);
        var blk = g.cube(3, 18, -4).section().blockPalette();                 // 288…303
        assertEquals(net.minestom.server.instance.block.Block.GRASS_BLOCK.stateId(), blk.get(0, 300 - 288, 0));
        assertEquals(net.minestom.server.instance.block.Block.DIRT.stateId(), blk.get(0, 299 - 288, 0));
        assertEquals(net.minestom.server.instance.block.Block.STONE.stateId(), blk.get(0, 296 - 288, 0));
        assertEquals(0, blk.get(0, 301 - 288, 0));
        assertEquals(net.minestom.server.instance.block.Block.BEDROCK.stateId(), g.cube(0, -32, 0).section().blockPalette().get(0, -500 + 512, 0));
        PlanetGenerator pg = g.pg;
        assertFalse(pg.hasBlocks(0, 19, 0), "без объектов над травой пусто");
        assertTrue(pg.uniform(0, 10, 0) >= 0, "камень однородный");
        assertEquals(300, pg.highest(5, 5, -500, 2000, HeightKind.SURFACE));
        var withSea = new ModuleCubeGen(pp, new GenSettings("p", 1, pp.defaultBounds(), Map.of("sea_level", 100)), 5L, GenObjects.EMPTY);
        assertEquals(100, withSea.pg.highest(0, 0, -500, 2000, HeightKind.SURFACE), "sea_level из options");
    }

    // ───────────── 2. эталоны ─────────────

    @Test
    void golden() throws Exception {
        PortableProvider pp = provider();
        GenPortableTest.golden(pp.gen);                                       // planet@1.txt — пустая библиотека
        // planet@1-objects.txt — тестовый пакет, кубы у поверхности
        GenObjects lib = GenObjects.of(testObjects());
        GenIdTable ids = MinestomGenIds.get();
        GenBounds b = pp.gen.defaultBounds();
        long seed = 12345;
        PlanetGenerator g = pp.gen.create(GenContext.of("golden", seed, b, Map.of(), ids, false, null, lib));
        StringBuilder sb = new StringBuilder();
        sb.append("# эталон генератора planet@1 с тестовым пакетом объектов (PlanetObjectsTest.testObjects, сид 12345, options {})\n");
        sb.append("objectsHash ").append(lib.hash()).append('\n');
        sb.append("fingerprint ").append(GenFingerprint.of(g, b, seed, lib.hash())).append('\n');
        GenArrayCube buf = new GenArrayCube(false);
        for (int cx = -6; cx <= 6; cx++) for (int cz = -6; cz <= 6; cz++) for (int cy = 18; cy <= 19; cy++)
            sb.append("cube ").append(cx).append(' ').append(cy).append(' ').append(cz).append(' ')
                    .append(GenFingerprint.visible(g, b, seed, cx, cy, cz, buf).visHash()).append('\n');
        Path f = Path.of(System.getProperty("hoka.test.golden-dir")).resolve("planet@1-objects.txt");
        String now = sb.toString();
        if (!Files.exists(f) || Boolean.getBoolean("hoka.golden.update")) { Files.writeString(f, now, StandardCharsets.UTF_8); return; }
        assertEquals(Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n"), now, "эталон planet@1-objects разошёлся");
    }

    // ───────────── 3. map.yml objects:, библиотека на диске ─────────────

    @Test
    void resolveWithObjectsKey(@TempDir Path tmp) throws Exception {
        ObjectLibrary lib = new ObjectLibrary(tmp.resolve("objects"));
        for (GenObject o : testObjects()) lib.save(o, false);
        ObjectLibrary.setGlobal(lib);
        if (GeneratorRegistry.get("planet") == null) PortableGens.register("test", planetJar);
        if (GeneratorRegistry.get("noise-test") == null) PortableGens.register("test", GenPortableTest.noiseJar != null
                ? GenPortableTest.noiseJar : Files.readAllBytes(Path.of(System.getProperty("hoka.test.noise-jar"))));

        CubeGens.Resolved all = resolve(tmp, "a", "generator: planet\n");
        assertNull(all.error(), all.error());
        assertEquals(2, all.gen().objects.size(), "нет ключа objects:, генератор просит — все категории");
        CubeGens.Resolved none = resolve(tmp, "b", "generator: planet\nobjects: []\n");
        assertTrue(none.gen().objects.isEmpty());
        assertNotEquals(all.gen().fingerprint(), none.gen().fingerprint(), "индекс pregen различается");
        CubeGens.Resolved other = resolve(tmp, "c", "generator: planet\nobjects: [rocks]\n");
        assertTrue(other.gen().objects.isEmpty(), "нет такой категории — пусто");

        CubeGens.Resolved noise = resolve(tmp, "d", "generator: noise-test\n");
        assertTrue(noise.gen().objects.isEmpty(), "noise-test объектов не просит");
        var plain = new ModuleCubeGen(noise.gen().provider, noise.gen().settings, 1L);
        assertEquals(plain.fingerprint(), noise.gen().fingerprint(), "отпечаток старых карт прежний");
        assertEquals(plain.genFingerprint(), noise.gen().genFingerprint());

        // раздача пакета: файл genobj/… пока карта держит пакет
        ObjectPacks.acquire("карта a", all.gen().objects);
        assertTrue(ObjectPacks.files().containsKey(ObjectPacks.path(all.gen().objects)));
        assertTrue(PortableGens.files().containsKey(ObjectPacks.path(all.gen().objects)));
        assertArrayEquals(all.gen().objects.pack(), ObjectPacks.files().get(ObjectPacks.path(all.gen().objects)));
        ObjectPacks.release("карта a");
        assertTrue(ObjectPacks.files().isEmpty());
    }

    static CubeGens.Resolved resolve(Path tmp, String name, String yml) throws Exception {
        Path d = tmp.resolve("maps").resolve(name);
        Files.createDirectories(d);
        Files.writeString(d.resolve("map.yml"), "tall: true\nstore: cubes\nseed: 7\n" + yml, StandardCharsets.UTF_8);
        return CubeGens.resolve(name, d, new ModuleConfig(d.resolve("map.yml")));
    }

    @Test
    void libraryOnDisk(@TempDir Path tmp) throws Exception {
        ObjectLibrary lib = new ObjectLibrary(tmp.resolve("objects"));
        GenObject t = testObjects().get(0).withMeta(Map.of());
        lib.save(t, false);
        assertTrue(Files.exists(tmp.resolve("objects/trees/oak.hobj")));
        assertEquals("{\n  \"weight\": 1\n}\n", Files.readString(tmp.resolve("objects/trees/oak.json")), ".json создан");
        assertThrows(IllegalStateException.class, () -> lib.save(t, false), "без force не перезаписывает");
        lib.save(t, true);
        lib.setMeta("trees/oak", "sink", "2");
        lib.setMeta("trees/oak", "on", "grass_block, dirt");
        assertThrows(IllegalArgumentException.class, () -> lib.setMeta("trees/oak", "sink", "99"));
        assertThrows(IllegalArgumentException.class, () -> lib.setMeta("trees/oak", "colour", "red"), "неизвестный ключ");
        lib.setMeta("trees/oak", "x.note", "дуб");
        ObjectLibrary again = new ObjectLibrary(tmp.resolve("objects"));
        GenObject r = again.get("trees/oak");
        assertEquals(2, r.meta().sink());
        assertEquals(Set.of("minecraft:grass_block", "minecraft:dirt"), r.meta().on());
        assertEquals("дуб", r.meta().string("x.note", ""));
        assertArrayEquals(GenObjectCodec.writeBody(t), GenObjectCodec.writeBody(r));
        GenObject moved = again.setAnchor("trees/oak", 0, 1, 0);
        assertEquals(1, moved.anchorY());
        Path trash = again.delete("trees/oak");
        assertTrue(Files.exists(trash) && trash.startsWith(tmp.resolve("objects/.trash/trees")), "в корзину");
        assertFalse(Files.exists(tmp.resolve("objects/trees/oak.hobj")));
        assertNull(again.get("trees/oak"));
        assertEquals(0, new ObjectLibrary(tmp.resolve("objects")).ids().size(), ".trash не сканируется");
        Files.write(tmp.resolve("objects/trees/bad.hobj"), new byte[]{1, 2, 3});
        ObjectLibrary withBad = new ObjectLibrary(tmp.resolve("objects"));
        assertTrue(withBad.errors().containsKey("trees/bad"), "битый файл — в ошибки, остальные грузятся");
    }
}
