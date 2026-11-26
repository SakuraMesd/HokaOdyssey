package ink.hoka.odyssey.planet.vale;

import ink.hoka.odyssey.gen.LodTile;
import ink.hoka.odyssey.gen.PlanetGenerator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Дальняя прорисовка: верх ячеек совпадает с настоящим рельефом, плитка считается быстро, результат не зависит от потока. */
class ValeLodTest {

    @Test
    void matchesTerrainAndIsFast() throws Exception {
        PlanetGenerator g = ValeTestKit.gen();
        ValeGen v = (ValeGen) g;
        int n = 32;
        int[] diffs = new int[n * n];
        int cnt = 0;
        LodTile t = new LodTile(1000, -3000, 4, n);
        assertTrue(g.lod(t));
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            int k = j * n + i;
            int real = v.surface(t.cx(i), t.cz(j));
            if (t.top[k] > real + 3) continue;                                 // крона
            diffs[cnt++] = Math.abs(t.top[k] - real);
        }
        Arrays.sort(diffs, 0, cnt);
        int med = diffs[cnt / 2], p90 = diffs[cnt * 9 / 10];
        System.out.println("LOD: ячеек без крон " + cnt + ", медиана |Δh| " + med + ", 90% " + p90);
        assertTrue(cnt > n * n / 4, "почти всё под кронами?");
        assertTrue(med <= 2 && p90 <= 6, "верх LOD далёк от рельефа");

        // скорость: крупные плитки
        long t0 = System.nanoTime();
        int tiles = 0;
        for (int step : new int[]{2, 4, 8, 16, 32, 64}) for (int q = 0; q < 8; q++) {
            LodTile x = new LodTile(q * n * step - 5000, 7000 - q * 333, step, n);
            g.lod(x);
            tiles++;
        }
        double ms = (System.nanoTime() - t0) / 1e6 / tiles;
        System.out.printf("LOD: %.2f мс на плитку %d×%d%n", ms, n, n);
        assertTrue(ms < 40, "плитка LOD считается " + ms + " мс");

        // детерминизм: два генератора (холодные кэши) — одно и то же
        LodTile a = new LodTile(-777, 1234, 16, n), b = new LodTile(-777, 1234, 16, n);
        g.lod(a);
        ValeTestKit.gen().lod(b);
        assertArrayEquals(a.top, b.top);
        assertArrayEquals(a.state, b.state);
        assertArrayEquals(a.water, b.water);
    }
}
