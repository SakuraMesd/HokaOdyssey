package ink.hoka.odyssey.planet.vale;

import ink.hoka.odyssey.gen.GenArrayCube;
import ink.hoka.odyssey.gen.GenFingerprint;
import ink.hoka.odyssey.gen.PlanetGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Стресс детерминизма (лог 26ноя: «разошлась с сервером (хэш op 21) в кубе -4 19 -4» и т.п.): кубы у спавна hoka23 и
 * случайные области в 8–16 потоках вразнобой, холодные и тёплые кэши, многократно — хэш видимой части (как op 21)
 * должен совпасть с однопоточной генерацией свежим генератором (тот же порядок, что у эталонов).
 */
class ValeStressTest {

    private static List<int[]> spawnArea() {
        List<int[]> cubes = new ArrayList<>();
        for (int cx = -6; cx <= 9; cx++) for (int cy = 18; cy <= 21; cy++) for (int cz = -6; cz <= 6; cz++) cubes.add(new int[]{cx, cy, cz});
        return cubes;
    }

    private static List<int[]> randomArea(Random r) {
        int bx = r.nextInt(400) - 200, bz = r.nextInt(400) - 200;
        List<int[]> cubes = new ArrayList<>();
        for (int cx = bx; cx < bx + 10; cx++) for (int cz = bz; cz < bz + 10; cz++) for (int cy = 17; cy <= 24; cy++) cubes.add(new int[]{cx, cy, cz});
        return cubes;
    }

    /** Эталон: свежий генератор, один поток, кубы по одному со своим свежим генератором (не зависит от соседей). */
    private static long[] serial(List<int[]> cubes) throws Exception {
        PlanetGenerator g = ValeTestKit.gen();
        long[] h = new long[cubes.size()];
        GenArrayCube buf = new GenArrayCube(false);
        for (int i = 0; i < h.length; i++) {
            int[] c = cubes.get(i);
            h[i] = GenFingerprint.visible(g, ValeTestKit.BOUNDS, ValeTestKit.SEED, c[0], c[1], c[2], buf).visHash();
        }
        return h;
    }

    private static long[] parallel(PlanetGenerator g, List<int[]> cubes, int threads, long seed) throws Exception {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < cubes.size(); i++) order.add(i);
        Collections.shuffle(order, new Random(seed));
        long[] got = new long[cubes.size()];
        AtomicInteger next = new AtomicInteger();
        Throwable[] err = new Throwable[1];
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = new Thread(() -> {
                GenArrayCube c = new GenArrayCube(false);
                int k;
                try {
                    while ((k = next.getAndIncrement()) < got.length) {
                        int i = order.get(k);
                        int[] q = cubes.get(i);
                        got[i] = GenFingerprint.visible(g, ValeTestKit.BOUNDS, ValeTestKit.SEED, q[0], q[1], q[2], c).visHash();
                    }
                } catch (Throwable e) { err[0] = e; }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        if (err[0] != null) throw new AssertionError(err[0]);
        return got;
    }

    private static int diff(List<int[]> cubes, long[] want, long[] got, String tag, List<String> out) {
        int n = 0;
        for (int i = 0; i < want.length; i++) if (want[i] != got[i]) {
            n++;
            int[] c = cubes.get(i);
            if (out.size() < 40) out.add(tag + ": " + c[0] + " " + c[1] + " " + c[2]);
        }
        return n;
    }

    @Test
    void parallelSameAsSerial() throws Exception {
        int rounds = Integer.getInteger("hoka.stress.rounds", 4);
        List<String> bad = new ArrayList<>();
        int total = 0, checked = 0;
        List<int[]> spawn = spawnArea();
        long[] want = serial(spawn);
        // однопоточный обход в другом порядке тоже обязан совпасть (зависимость от того, кто первым вызвал)
        Random rr = new Random(7);
        for (int round = 0; round < rounds; round++) {
            int threads = 8 + round % 9;
            PlanetGenerator cold = ValeTestKit.gen();                         // холодные кэши
            long[] got = parallel(cold, spawn, threads, round * 31L + 1);
            total += diff(spawn, want, got, "холодный r" + round, bad);
            checked += spawn.size();
            got = parallel(cold, spawn, threads, round * 31L + 2);           // тёплые
            total += diff(spawn, want, got, "тёплый r" + round, bad);
            checked += spawn.size();
            PlanetGenerator one = ValeTestKit.gen();                          // 1 поток, случайный порядок
            got = parallel(one, spawn, 1, round * 31L + 3);
            total += diff(spawn, want, got, "1 поток r" + round, bad);
            checked += spawn.size();
            List<int[]> area = randomArea(rr);
            long[] aw = serial(area);
            PlanetGenerator g2 = ValeTestKit.gen();
            got = parallel(g2, area, 16, round * 31L + 4);
            total += diff(area, aw, got, "область r" + round, bad);
            checked += area.size();
        }
        // вытеснение: окно 65×65 + полоса — кэши (8192 колонок) переполнены, кубы спавна вперемешку с дальними
        {
            List<int[]> mix = new ArrayList<>(spawn);
            Random mr = new Random(99);
            for (int k = 0; k < 6000; k++) mix.add(new int[]{mr.nextInt(260) - 130, 17 + mr.nextInt(6), mr.nextInt(260) - 130});
            long[] mw = new long[mix.size()];
            System.arraycopy(want, 0, mw, 0, want.length);
            long[] tail = serial(mix.subList(spawn.size(), mix.size()));
            System.arraycopy(tail, 0, mw, want.length, tail.length);
            PlanetGenerator g3 = ValeTestKit.gen();
            for (int rep = 0; rep < 1; rep++) {
                long[] got = parallel(g3, mix, 16, 500 + rep);
                total += diff(mix, mw, got, "вытеснение " + rep, bad);
                checked += mix.size();
            }
        }
        System.out.println("[stress] кубов сверено " + checked + ", расхождений " + total);
        for (String s : bad) System.out.println("[stress]   " + s);
        assertTrue(total == 0, "расхождений " + total + ": " + bad);
    }
}
