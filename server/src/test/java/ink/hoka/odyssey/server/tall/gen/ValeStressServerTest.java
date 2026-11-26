package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.worldgen.Bounds;
import ink.hoka.odyssey.api.worldgen.GenSettings;
import ink.hoka.odyssey.gen.*;
import ink.hoka.odyssey.server.objects.ObjectLibrary;
import ink.hoka.odyssey.server.objects.ObjectPacks;
import ink.hoka.odyssey.server.tall.CubeHash;
import net.minestom.server.MinecraftServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Карта hoka23 как в бою (лог 26ноя: «разошлась с сервером (хэш op 21) в кубе -4 19 -4»): сервер — ModuleCubeGen.cube в
 * 8 потоках вразнобой (как CubeStore), хэш op 21 — CubeHash секции; клиент — свой экземпляр из jar, объекты из пакета,
 * 16 потоков вразнобой (как GenPool). Всё сверяется с однопоточной видимой частью сервера.
 */
class ValeStressServerTest {

    static byte[] jar;
    static final long SEED = 20261126L;
    static final List<String> CATS = List.of("oak", "birch", "spruce", "bushes", "rocks", "glycine", "azure", "palm", "glowfingers",
            "bellflowers", "rafflesia");

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        jar = Files.readAllBytes(Path.of(System.getProperty("hoka.test.vale-jar")));
        ValeGenTest.jar = jar;
    }

    static void parallel(int n, int threads, long seed, IntConsumer f) throws Exception {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);
        Collections.shuffle(order, new Random(seed));
        AtomicInteger next = new AtomicInteger();
        Throwable[] err = new Throwable[1];
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = new Thread(() -> {
                int k;
                try { while ((k = next.getAndIncrement()) < n) f.accept(order.get(k)); }
                catch (Throwable e) { err[0] = e; }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        if (err[0] != null) throw new AssertionError(err[0]);
    }

    @Test
    void serverEqualsClientUnderThreads() throws Exception {
        Path dir = Path.of(System.getProperty("hoka.test.demo-objects-dir"));
        GenObjects objs = ObjectPacks.build(new ObjectLibrary(dir), CATS);
        assertTrue(objs.all().size() > 10, "объекты: " + objs.all().size());
        Bounds b = new Bounds(-500, 2000, 25000);
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("sea_level", 300);
        opts.put("tree_density", 1.0);
        GenBounds gb = new GenBounds(b.minY(), b.maxY(), b.size());
        List<int[]> cubes = new ArrayList<>();
        for (int cx = -6; cx <= 9; cx++) for (int cy = 18; cy <= 21; cy++) for (int cz = -6; cz <= 6; cz++) cubes.add(new int[]{cx, cy, cz});
        Random rr = new Random(11);
        for (int a = 0; a < 3; a++) {
            int bx = rr.nextInt(300) - 150, bz = rr.nextInt(300) - 150;
            for (int cx = bx; cx < bx + 8; cx++) for (int cz = bz; cz < bz + 8; cz++) for (int cy = 18; cy <= 22; cy++) cubes.add(new int[]{cx, cy, cz});
        }
        int n = cubes.size();
        PortableProvider pp0 = ValeGenTest.provider();
        long[] want = new long[n];
        {
            var g = new ModuleCubeGen(pp0, new GenSettings("hoka23", SEED, b, opts), 5L, objs);
            for (int i = 0; i < n; i++) { int[] c = cubes.get(i); want[i] = g.visHash(c[0], c[1], c[2]); }
        }
        int bad = 0, checked = 0;
        List<String> first = new ArrayList<>();
        for (int round = 0; round < 4; round++) {
            // сервер: свежий генератор, 8 потоков, куб в память и хэш секции (op 21)
            var sg = new ModuleCubeGen(ValeGenTest.provider(), new GenSettings("hoka23", SEED, b, opts), 5L, objs);
            long[] srv = new long[n];
            parallel(n, 8, round * 7L + 1, i -> { int[] c = cubes.get(i); var cb = sg.cube(c[0], c[1], c[2]);
                // как CubeRegionLoader.generate → install: пустой куб с биомами по умолчанию — общая пустая секция (был empty(): терял биомы)
                srv[i] = CubeHash.of(cb.blank() ? new net.minestom.server.instance.Section() : cb.section()); });
            // клиент: свой загрузчик, пакет объектов, 16 потоков
            var cl = GenClassLoader.load(jar, getClass().getClassLoader());
            GenObjects clientObjs = GenObjects.readPack(objs.pack());
            PlanetGenerator client = cl.provider().create(GenContext.of("vale", SEED, gb, opts, MinestomGenIds.get(), false, null, clientObjs));
            long[] cli = new long[n];
            ThreadLocal<GenArrayCube> buf = ThreadLocal.withInitial(() -> new GenArrayCube(false));
            parallel(n, 16, round * 7L + 2, i -> { int[] c = cubes.get(i); cli[i] = GenFingerprint.visible(client, gb, SEED, c[0], c[1], c[2], buf.get()).visHash(); });
            parallel(n, 12, round * 7L + 3, i -> { int[] c = cubes.get(i);                                   // тёплые кэши
                long h = GenFingerprint.visible(client, gb, SEED, c[0], c[1], c[2], buf.get()).visHash();
                if (h != cli[i]) cli[i] = h ^ 1; });
            for (int i = 0; i < n; i++) {
                checked++;
                int[] c = cubes.get(i);
                boolean uni = sg.uniform(c[0], c[1], c[2]) != null;               // однородные идут меткой без хэша
                if ((!uni && srv[i] != want[i]) || cli[i] != want[i]) {
                    bad++;
                    if (first.size() < 30) first.add("r" + round + " " + c[0] + " " + c[1] + " " + c[2] + (srv[i] != want[i] ? " сервер" : "")
                            + (cli[i] != want[i] ? " клиент" : ""));
                }
            }
        }
        System.out.println("[stress-srv] кубов " + checked + ", расхождений " + bad);
        for (String s : first) System.out.println("[stress-srv]   " + s);
        assertTrue(bad == 0, "расхождений " + bad + ": " + first);
    }
}
