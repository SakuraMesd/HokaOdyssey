package ink.hoka.odyssey.vessels;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * vessels-4, п.4: поле расстояний (сверка с перебором), сила отталкивания, полёт на простых картах из клеток: вниз к
 * земле — не ближе clearance, на стену — огибает сверху, высота зависания над ровной землёй.
 */
class CushionTest {

    static final Cushion.Settings S = new Cushion.Settings(10, 6, 0.8, 16, 3);

    /** Корабль 4×2×8 клеток: середины клеток относительно середины корабля (по 3 числа). */
    static double[] offsets() {
        double[] o = new double[4 * 2 * 8 * 3];
        int i = 0;
        for (int x = -2; x <= 1; x++) for (int y = -1; y <= 0; y++) for (int z = -4; z <= 3; z++) {
            o[i++] = x + 0.5; o[i++] = y + 0.5; o[i++] = z + 0.5;
        }
        return o;
    }

    static double[] probes(double[] off, double x, double y, double z) {
        double[] p = new double[off.length];
        for (int i = 0; i < off.length; i += 3) { p[i] = x + off[i]; p[i + 1] = y + off[i + 1]; p[i + 2] = z + off[i + 2]; }
        return p;
    }

    static DistField.Blocked map(Predicate<int[]> solid) { return (x, y, z) -> solid.test(new int[]{x, y, z}); }

    @Test
    void distFieldMatchesBruteForce() {
        Random r = new Random(7);
        boolean[][][] b = new boolean[18][14][16];
        for (int n = 0; n < 25; n++) b[r.nextInt(18)][r.nextInt(14)][r.nextInt(16)] = true;
        DistField f = DistField.build(100, -20, 5, 18, 14, 16, (x, y, z) -> b[x - 100][y + 20][z - 5]);
        for (int i = 0; i < 18; i++) for (int j = 0; j < 14; j++) for (int k = 0; k < 16; k++) {
            double best = DistField.FAR;
            for (int a = 0; a < 18; a++) for (int c = 0; c < 14; c++) for (int d = 0; d < 16; d++)
                if (b[a][c][d]) best = Math.min(best, Math.sqrt((a - i) * (a - i) + (c - j) * (c - j) + (d - k) * (d - k)));
            assertEquals(best, f.cell(i, j, k), 1e-4, "клетка " + i + "," + j + "," + k);
        }
        // между серединами — трилинейно; у занятого блока — 0
        assertEquals(f.cell(3, 4, 5), f.at(100 + 3.5, -20 + 4.5, 5 + 5.5), 1e-6);
        DistField empty = DistField.build(0, 0, 0, 8, 8, 8, (x, y, z) -> false);
        assertEquals(DistField.FAR, empty.at(4, 4, 4), 1e-6, "блоков нет — далеко");
        // кусок 16³ «пусто целиком» и «занято целиком» — без опроса блоков
        DistField quick = DistField.build(0, 0, 0, 32, 16, 16, new DistField.Blocked() {
            @Override public boolean at(int x, int y, int z) { throw new AssertionError("не должен спрашивать"); }

            @Override public int section(int sx, int sy, int sz) { return sx == 0 ? 0 : 1; }
        });
        assertEquals(1.0, quick.at(15.5, 8, 8), 1e-6, "у границы занятого куска — до середины соседнего блока");
    }

    @Test
    void forceAndApproach() {
        assertEquals(0, Cushion.force(16.5, S), "дальше clearance + soft — 0");
        assertEquals(0, Cushion.force(100, S));
        double prev = 0;
        for (double c = 15.9; c >= 10; c -= 0.5) {
            double f = Cushion.force(c, S);
            assertTrue(f > prev, "растёт по мере приближения: " + c);
            prev = f;
        }
        assertEquals(1, Cushion.force(10, S), 1e-9, "у clearance — полная");
        assertEquals(1, Cushion.force(3, S));
        assertEquals(Double.MAX_VALUE, Cushion.approach(20, 2.5, S), "далеко — без ограничения");
        assertEquals(0, Cushion.approach(10, 2.5, S), 1e-12, "у clearance — к блокам нельзя");
        assertEquals(0, Cushion.approach(7, 2.5, S), 1e-12);
        assertTrue(Cushion.approach(13, 2.5, S) <= 0.5 * 3 + 1e-9, "не перескочить: шаг не больше половины остатка");
    }

    @Test
    void cannotDiveIntoGround() {
        Predicate<int[]> ground = b -> b[1] <= 64;
        double[] off = offsets();
        DistField.Cache cache = new DistField.Cache();
        double x = 0, y = 100, z = 0, minGap = Double.MAX_VALUE;
        double cap = 2.5;
        for (int t = 0; t < 200; t++) {                                         // пикирует вниз на форсаже
            double[] p = probes(off, x, y, z);
            DistField f = cache.get(p, S.reach(), map(ground));
            double[] v = Cushion.apply(f, p, 0, -cap, 0, cap, S);
            x += v[0]; y += v[1]; z += v[2];
            minGap = Math.min(minGap, Cushion.clearance(f, probes(off, x, y, z)));
        }
        assertTrue(minGap >= S.clearance() - 0.05, "не ближе clearance: " + minGap);
        double bottom = y - 1;                                                  // низ корпуса (клетки −1…0)
        assertEquals(65 + S.clearance(), bottom, 0.6, "висит в 10 блоках над землёй");
        assertTrue(cache.builds <= 4, "поле строится редко: " + cache.builds);
    }

    @Test
    void climbsOverWall() {
        // земля y ≤ 64, стена (гора) z 40…50 до y 90
        Predicate<int[]> world = b -> b[1] <= 64 || (b[2] >= 40 && b[2] <= 50 && b[1] <= 90);
        for (double cap : new double[]{1.5, 2.5}) climbsOverWall(world, cap);                // ход и форсаж
    }

    private static void climbsOverWall(Predicate<int[]> world, double cap) {
        double[] off = offsets();
        DistField.Cache cache = new DistField.Cache();
        double x = 0, y = 77, z = 0, minGap = Double.MAX_VALUE;
        double vx = 0, vy = 0, vz = 0;
        for (int t = 0; t < 400; t++) {                                         // W: вперёд по +Z; вертикаль гаснет (как у пилота)
            double[] p = probes(off, x, y, z);
            DistField f = cache.get(p, S.reach(), map(world));
            vz = cap;
            vy *= 0.89;
            double[] v = Cushion.apply(f, p, vx, vy, vz, cap, S);
            vx = v[0]; vy = v[1]; vz = v[2];
            x += vx; y += vy; z += vz;
            minGap = Math.min(minGap, Cushion.clearance(f, probes(off, x, y, z)));
        }
        assertTrue(minGap >= S.clearance() - 0.6, "к стене не ближе clearance: " + minGap + " (ход " + cap + ")");
        assertTrue(z > 60, "перелетел гору: z " + z);
        assertTrue(y - 1 >= 91 + S.clearance() - 0.6, "поднялся над горой: " + y);
    }

    @Test
    void levelFlightOverFlatGroundUntouched() {
        Predicate<int[]> ground = b -> b[1] <= 64;
        double[] off = offsets();
        double y = 65 + S.clearance() + 1 + 0.2;                                // низ корпуса чуть выше clearance
        DistField.Cache cache = new DistField.Cache();
        double[] p = probes(off, 0, y, 0);
        DistField f = cache.get(p, S.reach(), map(ground));
        double[] v = Cushion.apply(f, p, 0, 0, 1.5, 1.5, S);
        assertArrayEquals(new double[]{0, 0, 1.5}, v, 1e-9, "ровно над землёй на W — без рывков");
        double[] far = Cushion.apply(f, probes(off, 0, 120, 0), 0, -1, 0, 1.5, S);
        assertArrayEquals(new double[]{0, -1, 0}, far, 1e-9, "высоко — подушка не мешает");
    }

    @Test
    void hoverHeightOverFlatGround() {
        Predicate<int[]> ground = b -> b[1] <= 64;
        double[] off = offsets();
        DistField f = DistField.build(-40, 40, -40, 80, 90, 80, map(ground));
        double hy = Cushion.hoverY(f, off, 0, 0, 60, 120, 0.5, S);
        assertFalse(Double.isNaN(hy));
        assertEquals(65 + S.clearance(), hy - 1, 0.51, "низ корпуса — в 10 блоках над землёй");
        assertTrue(Cushion.clearance(f, probes(off, 0, hy, 0)) >= S.clearance() - 1e-6);
        assertTrue(Cushion.clearance(f, probes(off, 0, hy - 0.5, 0)) < S.clearance(), "ниже — уже ближе");
        // над столбом — выше
        Predicate<int[]> pillar = b -> b[1] <= 64 || (Math.abs(b[0]) <= 1 && Math.abs(b[2]) <= 1 && b[1] <= 80);
        DistField fp = DistField.build(-40, 40, -40, 80, 100, 80, map(pillar));
        assertEquals(81 + S.clearance(), Cushion.hoverY(fp, off, 0, 0, 60, 130, 0.5, S) - 1, 0.51);
        assertTrue(Double.isNaN(Cushion.hoverY(f, off, 0, 0, 60, 70, 0.5, S)), "некуда — NaN");
    }
}
