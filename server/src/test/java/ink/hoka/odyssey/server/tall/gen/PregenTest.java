package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.ModuleConfig;
import net.minestom.server.MinecraftServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** /hocubes pregen: детерминированный — только индекс; обрыв + возобновление = непрерывный прогон; скорость flat. */
class PregenTest {

    @BeforeAll
    static void init() {
        MinecraftServer.init();
        TestGens.reg(TestGens.sine("t-pregen", false));
    }

    @TempDir
    Path tmp;

    private Pregen.Env env(Path maps) {
        return new Pregen.Env() {
            @Override public Path dir(String map) { return maps.resolve(map); }
            @Override public ModuleConfig meta(String map) { return new ModuleConfig(maps.resolve(map).resolve("map.yml")); }
            @Override public boolean lock(String map) { return true; }
            @Override public void unlock(String map) { }
            @Override public ModuleCubeGen liveGen(String map) { return null; }
            @Override public SortedSet<String> names() { return new TreeSet<>(java.util.List.of("p")); }
        };
    }

    private void map(Path maps, String gen, long seed) throws Exception {
        Path d = maps.resolve("p");
        Files.createDirectories(d);
        ModuleConfig m = new ModuleConfig(d.resolve("map.yml"));
        m.set("tall", true); m.set("store", "cubes"); m.set("generator", gen); m.set("seed", seed);
        m.save();
    }

    private static PregenProgress area(int x1, int z1, int x2, int z2, int threads) {
        PregenProgress p = new PregenProgress();
        p.x1 = x1; p.z1 = z1; p.x2 = x2; p.z2 = z2; p.threads = threads;
        return p;
    }

    private static Pregen run(String map, Pregen.Env env, PregenProgress p) throws Exception {
        String e = Pregen.start(map, env, p, null);
        assertNull(e, e);
        Pregen pg = Pregen.get(map);
        pg.join();
        for (int i = 0; i < 100 && Pregen.get(map) != null; i++) Thread.sleep(50);   // отчётный поток подводит итог
        return pg;
    }

    @Test
    void resumeEqualsContinuous() throws Exception {
        Path a = tmp.resolve("a"), b = tmp.resolve("b");
        map(a, "t-pregen", 5);
        map(b, "t-pregen", 5);
        Pregen full = run("p", env(a), area(-40, -20, 40, 30, 3));
        assertEquals(Pregen.State.DONE, full.state());
        assertFalse(Files.exists(a.resolve("p/pregen.yml")), "готово — прогресс удалён");
        assertFalse(Files.exists(a.resolve("p/cols")), "детерминированный — кубы не пишутся");
        int regions = full.regionsTotal();
        // «обрыв»: прогон в b, потом удалить индекс регионов спирали с K-го, урезать K−1-й до половины, прогресс — K−1
        run("p", env(b), area(-40, -20, 40, 30, 3));
        long[] units = Spiral.order(-2, -1, 1, 0, 0, 0);
        assertEquals(regions, units.length);
        int k = 3;
        for (int i = k; i < units.length; i++) Files.deleteIfExists(GenIndexFile.path(b.resolve("p/genidx"), Spiral.x(units[i]), Spiral.z(units[i])));
        Path half = GenIndexFile.path(b.resolve("p/genidx"), Spiral.x(units[k - 1]), Spiral.z(units[k - 1]));
        GenIndexFile hf = GenIndexFile.read(half);
        for (int i = 0; i < GenIndexFile.COLS; i += 2) hf.cols[i] = null;
        hf.write(half);
        PregenProgress p = area(-40, -20, 40, 30, 2);
        for (int i = 0; i < k - 1; i++) p.markDone(i);
        p.gen = "t-pregen@1"; p.seed = 5;
        p.save(b.resolve("p/pregen.yml"));
        Pregen.resumeAll(env(b));
        Pregen pg = Pregen.get("p");
        assertNotNull(pg, "state running — продолжился сам");
        pg.join();
        for (int i = 0; i < 100 && Pregen.get("p") != null; i++) Thread.sleep(50);
        for (long u : units) {
            GenIndexFile x = GenIndexFile.read(GenIndexFile.path(a.resolve("p/genidx"), Spiral.x(u), Spiral.z(u)));
            GenIndexFile y = GenIndexFile.read(GenIndexFile.path(b.resolve("p/genidx"), Spiral.x(u), Spiral.z(u)));
            assertEquals(x.done(), y.done(), "регион " + Spiral.x(u) + " " + Spiral.z(u));
            for (int c = 0; c < GenIndexFile.COLS; c++) {
                assertEquals(x.cols[c], y.cols[c]);
                if (x.cols[c] == null) continue;
                for (int cy = -10; cy < 20; cy++) {
                    int pa = x.cols[c].at(cy), pb = y.cols[c].at(cy);
                    if (pa > 0) assertArrayEquals(x.resolved(pa), y.resolved(pb)); else assertEquals(pa, pb);
                }
            }
        }
    }

    /** Цифры для отчёта: оценка диска и скорости (печать, без строгих порогов). */
    @Test
    void numbers() throws Exception {
        var flat = new ModuleCubeGen(FlatGenerator.PROVIDER, new ink.hoka.odyssey.api.worldgen.GenSettings("f", 0,
                FlatGenerator.PROVIDER.defaultBounds(), java.util.Map.of()));
        for (String l : DiskEstimate.estimate(flat, 100_000)) System.out.println("flat: " + l);
        var sine = TestGens.gen(TestGens.sine("t-num", false), 9);
        for (String l : DiskEstimate.estimate(sine, 100_000)) System.out.println("sine: " + l);
        var r = GenTest.run(sine, 500);
        System.out.printf("sine: %.3f мс/куб (%d кубов)%n", r.msPerCube(), r.cubes());
        assertTrue(r.ok());
    }

    @Test
    void flatSpeed() throws Exception {
        Path a = tmp.resolve("f");
        map(a, "flat", 0);
        long t0 = System.nanoTime();
        Pregen pg = run("p", env(a), area(0, 0, 127, 127, 4));                 // 16 регионов, 16384 столбца
        double ms = (System.nanoTime() - t0) / 1e6;
        long bytes = DiskEstimate.dirBytes(a.resolve("p/genidx"));
        System.out.printf("pregen flat: 16384 столбцов за %.0f мс (%.3f мс/столбец), индекс %d Б (%.2f Б/столбец)%n",
                ms, ms / 16384, bytes, bytes / 16384.0);
        assertEquals(Pregen.State.DONE, pg.state());
        assertTrue(bytes / 16384.0 < 8, "flat < 8 Б/столбец");
    }

    /** Код генератора поменяли без смены version: продолжение по pregen.yml запрещено (готовые регионы — от старого). */
    @Test
    void resumeRefusedOnFingerprintChange() throws Exception {
        Path a = tmp.resolve("fp");
        map(a, "t-pregen", 5);
        PregenProgress p = area(0, 0, 31, 31, 1);
        p.gen = "t-pregen@1"; p.seed = 5; p.fp = 12345;
        p.save(a.resolve("p/pregen.yml"));
        String e = Pregen.resume("p", env(a), null);
        assertNotNull(e);
        assertTrue(e.contains("отпечаток"), e);
        assertNull(Pregen.get("p"));
        assertNull(GeneratorRegistry.guard(java.util.List.of("test")), "отказ — генератор отпущен");
        // свой отпечаток сохраняется и принимается
        PregenProgress q = PregenProgress.decode(area(0, 0, 1, 1, 1).encode());
        q.fp = 7;
        assertEquals(7, PregenProgress.decode(q.encode()).fp);
    }

    /** Остановка сервера (pauseAll) не превращает паузу руками в «продолжить при старте». */
    @Test
    void pauseAllKeepsManualPause() throws Exception {
        Path a = tmp.resolve("pz");
        map(a, "t-pregen", 3);
        assertNull(Pregen.start("p", env(a), area(-4000, -4000, 4000, 4000, 1), null));
        Pregen pg = Pregen.get("p");
        pg.haltNow();                                                        // пауза руками (синхронно)
        Pregen.pauseAll();                                                   // остановка сервера, pregen ещё в ACTIVE
        for (int i = 0; i < 100 && Pregen.get("p") != null; i++) Thread.sleep(50);
        PregenProgress saved = PregenProgress.load(a.resolve("p/pregen.yml"));
        assertEquals("paused", saved.state);
        assertNotNull(saved.fp, "отпечаток в pregen.yml");
        assertNull(GeneratorRegistry.guard(java.util.List.of("test")));
    }

    /** compact: пустой заголовок при .htw (tall/) — оставить, иначе миграция вернёт старые блоки; без .htw — убрать. */
    @Test
    void compactKeepsEmptyHeaderWithHtw() throws Exception {
        for (boolean htw : new boolean[]{true, false}) {
            Path d = tmp.resolve("c" + htw);
            Path f = d.resolve("cols/r.0.0.hcl");
            try (var r = ink.hoka.odyssey.server.tall.region.RegionFile.open(f, 1024, true)) {
                r.write(5, new ink.hoka.odyssey.server.tall.region.ColumnHeader(ink.hoka.odyssey.server.tall.TallWorld.HEIGHT >> 4).encode());
            }
            if (htw) { Files.createDirectories(d.resolve("tall")); Files.writeString(d.resolve("tall/x.htw"), "x"); }
            ink.hoka.odyssey.server.tall.CubeConvert.compact(d, m -> { });
            if (htw) {
                try (var r = ink.hoka.odyssey.server.tall.region.RegionFile.open(f, 1024, false)) { assertTrue(r.has(5)); }
            } else assertFalse(Files.exists(f));
        }
    }
}
