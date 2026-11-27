package ink.hoka.odyssey.planet.space;

import ink.hoka.odyssey.gen.GenArrayCube;
import ink.hoka.odyssey.gen.GenBounds;
import ink.hoka.odyssey.gen.GenContext;
import ink.hoka.odyssey.gen.GenIds;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Плотность тел, доля руд, скорость; видимая часть не зависит от секретов (сервер = клиент). */
class SpaceGenTest {

    static final Map<String, Integer> IDS = new HashMap<>();

    static GenIds ids() {
        return new GenIds() {
            @Override public synchronized int state(String c) { return IDS.computeIfAbsent(c, k -> IDS.size() + 1); }
            @Override public int defaultState(String b) { return state(b); }
            @Override public int biome(String k) { return 0; }
        };
    }

    static SpaceGen gen(boolean server) {
        Map<String, Object> o = new HashMap<>();
        o.put("exclude", "0,0,0,2000");
        o.put("spawn_rock", "2600,0,2600,14");
        return (SpaceGen) new SpaceProvider().create(GenContext.of("t", 42, new GenBounds(-12000, 12000, 50000), o, ids(), server, server ? 777L : null));
    }

    @Test
    void stats() {
        SpaceGen s = gen(true), c = gen(false);
        int R = 125;                                                             // куб 4000 блоков со стороной
        long t0 = System.nanoTime();
        int filled = 0, total = 0;
        java.util.List<int[]> sample = new java.util.ArrayList<>();
        for (int cx = 200; cx < 200 + R; cx++) for (int cy = -R / 2; cy < R / 2; cy++) for (int cz = 200; cz < 200 + R; cz++) {
            total++;
            if (s.hasBlocks(cx, cy, cz)) { filled++; if (sample.size() < 3000) sample.add(new int[]{cx, cy, cz}); }
        }
        long t1 = System.nanoTime();
        System.out.printf("кубов %d, с телами %d (%.4f%%), hasBlocks %.1f нс/куб%n", total, filled, filled * 100.0 / total, (t1 - t0) / (double) total);
        int asteroids = 0, metes = 0;
        for (int i = 200 * 16 / SpaceGen.A; i < (200 + R) * 16 / SpaceGen.A; i++)
            for (int j = -R * 8 / SpaceGen.A; j < R * 8 / SpaceGen.A; j++)
                for (int k = 200 * 16 / SpaceGen.A; k < (200 + R) * 16 / SpaceGen.A; k++) if (s.asteroid(i, j, k) != null) asteroids++;
        for (int i = 200 * 16 / SpaceGen.B; i < (200 + R) * 16 / SpaceGen.B; i++)
            for (int j = -R * 8 / SpaceGen.B; j < R * 8 / SpaceGen.B; j++)
                for (int k = 200 * 16 / SpaceGen.B; k < (200 + R) * 16 / SpaceGen.B; k++) if (s.meteorite(i, j, k) != null) metes++;
        System.out.printf("в 4000³: астероидов %d, метеоритов %d%n", asteroids, metes);
        long solid = 0, secret = 0, chests = 0;
        long g0 = System.nanoTime();
        for (int[] q : sample) {
            GenArrayCube a = new GenArrayCube(true).reset(q[0], q[1], q[2], 42);
            s.generate(a);
            GenArrayCube b = new GenArrayCube(false).reset(q[0], q[1], q[2], 42);
            c.generate(b);
            assertArrayEquals(a.blocks, b.blocks, "видимая часть сервер ≠ клиент");
            for (int v : a.blocks) if (v != 0) solid++;
            secret += a.secretList().size();
            chests += a.dataList().size();
        }
        long g1 = System.nanoTime();
        System.out.printf("генерация %.3f мс/куб, блоков %d, секретов %d (%.1f%%), сундуков %d%n",
                (g1 - g0) / 1e6 / Math.max(1, sample.size()), solid, secret, secret * 100.0 / Math.max(1, solid), chests);
        assertTrue(filled > 0);
        // точка входа: астероид на месте
        assertTrue(s.hasBlocks(2600 >> 4, 0, 2600 >> 4));
        // зона исключения пуста
        for (int cx = -40; cx < 40; cx += 3) for (int cz = -40; cz < 40; cz += 3) assertTrue(!s.hasBlocks(cx, 0, cz));
        // highest = верх по generate (несколько столбцов с телами)
        long h0 = System.nanoTime(); int checked = 0;
        for (int[] q : sample.subList(0, Math.min(40, sample.size()))) {
            GenArrayCube a = new GenArrayCube(false).reset(q[0], q[1], q[2], 42);
            c.generate(a);
            for (int lx = 0; lx < 16; lx += 5) for (int lz = 0; lz < 16; lz += 5) {
                int x = (q[0] << 4) + lx, z = (q[2] << 4) + lz;
                int h = c.highest(x, z, (q[1] << 4), (q[1] << 4) + 15, null);
                int want = ink.hoka.odyssey.gen.PlanetGenerator.NONE;
                for (int ly = 15; ly >= 0; ly--) if (a.get(lx, ly, lz) != 0) { want = (q[1] << 4) + ly; break; }
                org.junit.jupiter.api.Assertions.assertEquals(want, h, "highest " + x + "," + z);
                checked++;
            }
        }
        long hc = System.nanoTime();
        for (int i = 0; i < 256; i++) c.highest(3200 + i % 16, 3200 + i / 16, -12000, 12000, null);
        System.out.printf("highest сверен в %d точках; столбец целиком (256 ячеек, −12000…12000): %.1f мс%n", checked, (System.nanoTime() - hc) / 1e6);
        System.out.println("пример лута: " + Loot.chest(new ink.hoka.odyssey.gen.GenRandom(5)));
    }
}
