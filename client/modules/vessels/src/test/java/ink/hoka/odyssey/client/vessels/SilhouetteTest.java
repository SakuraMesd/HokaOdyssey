package ink.hoka.odyssey.client.vessels;

import ink.hoka.odyssey.client.vessels.hud.Canvas;
import ink.hoka.odyssey.client.vessels.hud.Silhouette;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Силуэт корабля сверху из модели: не пустой, в своей рамке (с отступом), нос — вправо. */
class SilhouetteTest {

    @Test
    void corabllSilhouette() throws Exception {
        float[] tris = KindTest.corabll(KindTest.tris(), 180).tris;
        for (float gs : new float[]{1, 2, 3, 4}) {
            int w = Math.round(92 * gs), h = Math.round(28 * gs);
            Canvas c = Silhouette.draw(tris, w, h, gs);
            int on = 0, minX = w, maxX = -1, minY = h, maxY = -1;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (c.alpha(x, y) > 0.1f) {
                on++;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
            assertTrue(on > w * h / 20, "силуэт не пустой (×" + gs + "): " + on);
            int pad = (int) Math.floor(gs);
            assertTrue(minX >= pad - 1 && minY >= pad - 1 && maxX <= w - pad && maxY <= h - pad + 1, "в рамке с отступом (×" + gs + ")");
            assertTrue(maxY - minY > h * 0.6f || maxX - minX > w * 0.6f, "вписан крупно");
        }
        // нос (+Z) — справа: самая дальняя точка носа даёт заливку правее середины
        float[] arrow = {0, 0, 2, -1, 0, -2, 1, 0, -2};                         // треугольник носом вперёд
        Canvas c = Silhouette.draw(arrow, 60, 30, 1);
        float right = 0, left = 0;
        for (int y = 0; y < 30; y++) { right += c.alpha(50, y); left += c.alpha(10, y); }
        assertTrue(left > right, "широкая корма слева, острый нос справа");
        assertEquals(0, Silhouette.draw(new float[0], 10, 10, 1).alpha(5, 5), "нет модели — пусто");
        assertEquals(0, Silhouette.draw(null, 10, 10, 1).alpha(5, 5));
    }
}
