package ink.hoka.odyssey.planet.vale;

import ink.hoka.odyssey.gen.GenArrayCube;
import ink.hoka.odyssey.gen.GenFingerprint;
import ink.hoka.odyssey.gen.PlanetGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Кэши рельефа и объектов (2-путевые, замки полос) не меняют результат: 8 потоков вразнобой = 1 поток подряд. */
class ValeCacheTest {

    @Test
    void threadsSameAsSerial() throws Exception {
        List<int[]> cubes = new ArrayList<>();
        for (int cx = -40; cx < -20; cx++) for (int cz = 10; cz < 30; cz++) for (int cy = 17; cy <= 26; cy++) cubes.add(new int[]{cx, cy, cz});
        PlanetGenerator a = ValeTestKit.gen();
        long[] want = new long[cubes.size()];
        GenArrayCube buf = new GenArrayCube(false);
        for (int i = 0; i < want.length; i++) {
            int[] c = cubes.get(i);
            want[i] = GenFingerprint.visible(a, ValeTestKit.BOUNDS, ValeTestKit.SEED, c[0], c[1], c[2], buf).visHash();
        }
        PlanetGenerator b = ValeTestKit.gen();
        long[] got = new long[cubes.size()];
        AtomicInteger next = new AtomicInteger();
        Thread[] ts = new Thread[8];
        for (int t = 0; t < ts.length; t++) {
            ts[t] = new Thread(() -> {
                GenArrayCube c = new GenArrayCube(false);
                int k;
                while ((k = next.getAndIncrement()) < got.length) {
                    int i = (int) (k * 7919L % got.length);                   // вразнобой (7919 взаимно просто с 4000), каждый ровно раз
                    int[] q = cubes.get(i);
                    got[i] = GenFingerprint.visible(b, ValeTestKit.BOUNDS, ValeTestKit.SEED, q[0], q[1], q[2], c).visHash();
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        assertArrayEquals(want, got);
    }
}
