package ink.hoka.odyssey.client.vessels;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Твёрдый корпус (vessels-2): мелкие клетки по полблока у corabll — на крыше и гондолах можно стоять, сквозь борт не
 * пройти (клетки сплошные по поверхности, без щелей, в которые пролезет игрок 0.6), область клеток для столкновений.
 */
class HullTest {

    @Test
    void fineCellsCoverHull() throws Exception {
        Kind k = KindTest.corabll(KindTest.tris(), 180);
        assertFalse(k.fine.isEmpty());
        // каждая клетка корпуса (1 блок) содержит хотя бы одну мелкую — те же поверхности
        float[] h = k.hull;
        for (int i = 0; i < h.length; i += 3) {
            assertTrue(k.fineIn(h[i] - 0.5, h[i + 1] - 0.5, h[i + 2] - 0.5, h[i] + 0.499, h[i + 1] + 0.499, h[i + 2] + 0.499),
                    "клетка без мелких: " + h[i] + " " + h[i + 1] + " " + h[i + 2]);
        }
    }

    @Test
    void standOnRoofNotInAir() throws Exception {
        Kind k = KindTest.corabll(KindTest.tris(), 180);
        List<double[]> tops = new ArrayList<>();
        for (int ix = -8; ix < 8; ix++) for (int iz = -14; iz < 14; iz++) {
            int top = Integer.MIN_VALUE;
            for (int iy = -6; iy < 6; iy++) if (k.fine.contains(Kind.fineKey(ix, iy, iz))) top = iy;
            if (top != Integer.MIN_VALUE) tops.add(new double[]{(ix + 0.5) / 2, (top + 1) / 2.0, (iz + 0.5) / 2});
        }
        assertTrue(tops.size() > 50);
        for (double[] t : tops) {
            assertTrue(k.standsOn(t[0], t[1], t[2], 0.35), "на корпусе");
            assertFalse(k.standsOn(t[0], t[1] + 3, t[2], 0.35), "в воздухе");
        }
        // над серединой крыши (кабина) — стоит
        assertTrue(k.standsOn(0, k.half[1] + 0.01, 0, 2.6) || k.standsOn(0, k.half[1], 1, 2.6));
    }

    @Test
    void noGapsOnSurface() throws Exception {
        // Каждая точка поверхности модели (гуще, чем при построении — через 1/12 блока) лежит в мелкой клетке корпуса:
        // щелей между клетками там, где у модели поверхность, нет. Дыра в корпусе — только где дыра в самой модели;
        // клетка 0.5 уже коробки игрока 0.6, так что в «шахматку» клеток игрок не пролезет.
        Kind k = KindTest.corabll(KindTest.tris(), 180);
        float[] t = k.tris;
        int miss = 0, all = 0;
        for (int i = 0; i + 8 < t.length; i += 9) {
            double len = 0;
            for (int a = 0; a < 3; a++) for (int b = a + 1; b < 3; b++)
                len = Math.max(len, Math.sqrt(Math.pow(t[i + a * 3] - t[i + b * 3], 2) + Math.pow(t[i + a * 3 + 1] - t[i + b * 3 + 1], 2) + Math.pow(t[i + a * 3 + 2] - t[i + b * 3 + 2], 2)));
            int n = Math.max(2, (int) Math.ceil(len * 12));
            for (int u = 0; u <= n; u++) for (int w = 0; u + w <= n; w++) {
                double fu = (double) u / n, fv = (double) w / n, fw = 1 - fu - fv;
                double x = t[i] * fw + t[i + 3] * fu + t[i + 6] * fv, y = t[i + 1] * fw + t[i + 4] * fu + t[i + 7] * fv, z = t[i + 2] * fw + t[i + 5] * fu + t[i + 8] * fv;
                all++;
                if (!k.fineAt(x, y, z) && !k.fineIn(x - 0.02, y - 0.02, z - 0.02, x + 0.02, y + 0.02, z + 0.02)) miss++;
            }
        }
        assertEquals(0, miss, "точек поверхности вне клеток: " + miss + " из " + all);
    }
}
