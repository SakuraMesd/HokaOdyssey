package ink.hoka.odyssey.client.stations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Стоимость плёнки: у большой стены строится только то, что в кадре. */
class FilmCostTest {

    private static int quads(double dist, BorderMesh.Visible vis) {
        int[] n = {0};
        var st = new BorderMesh.Style();
        st.visible = vis;
        BorderMesh.box(-200, -100, 0, 200, 100, 300, 0x8FD8FF, 1, true, false, 0, 0, -dist, 0, 0, -dist, 0, st,
                (x0, y0, z0, c0, x1, y1, z1, c1, x2, y2, z2, c2, x3, y3, z3, c3) -> n[0]++);
        return n[0];
    }

    @Test void frustumCutsWork() {
        // «кадр»: окно 30×18 блоков напротив игрока у стены (обзор ~70° в 10 блоках)
        BorderMesh.Visible screen = (ax, ay, az, bx, by, bz) -> bx > -15 && ax < 15 && by > -9 && ay < 9;
        int all = quads(10, null), seen = quads(10, screen);
        System.out.println("квадов у стены (10 бл): весь круг " + all + ", в кадре " + seen + "; издалека (45 бл): " + quads(45, null));
        assertTrue(seen > 100, "в кадре сетка есть: " + seen);
        assertTrue(seen * 4 < all, "отсечение режет большую часть: " + seen + " из " + all);
    }
}
