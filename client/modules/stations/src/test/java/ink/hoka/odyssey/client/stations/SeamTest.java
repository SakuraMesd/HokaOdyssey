package ink.hoka.odyssey.client.stations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Стыки коробок одной станции: закрытая грань и рёбра-швы не рисуются, наружные края — рисуются. */
class SeamTest {

    /** Вторая коробка — ровно над первой (y 10..20), того же сечения. */
    private static final BorderMesh.Inside ABOVE = (x, y, z) -> x > 0 && x < 10 && y > 10 && y < 20 && z > 0 && z < 10;
    private static final double[] LO = {0, 0, 0}, HI = {10, 10, 10};

    @Test void sharedFaceHidden() {
        assertTrue(BorderMesh.covered(ABOVE, 1, 10, 1, LO, HI));                 // верх первой = низ второй
        assertFalse(BorderMesh.covered(ABOVE, 1, 0, -1, LO, HI));                // низ — край станции
        assertFalse(BorderMesh.covered(ABOVE, 0, 10, 1, LO, HI));                // бок — край
        assertFalse(BorderMesh.covered(null, 1, 10, 1, LO, HI));
    }

    @Test void seamEdgesHiddenOuterKept() {
        // ребро вдоль X на верхней кромке (y = 10, z = 10): стена z = 10 продолжается вверх — шов
        assertTrue(BorderMesh.seam(ABOVE, LO, HI, new double[]{5, 10, 10}, 1, 1, 2, 1));
        // ребро вдоль X на нижней кромке (y = 0, z = 10) — настоящий край
        assertFalse(BorderMesh.seam(ABOVE, LO, HI, new double[]{5, 0, 10}, 1, 0, 2, 1));
        // меньшая коробка сверху (x 0..5): над ребром x = 10 пусто — край остаётся
        BorderMesh.Inside half = (x, y, z) -> x > 0 && x < 5 && y > 10 && y < 20 && z > 0 && z < 10;
        assertFalse(BorderMesh.seam(half, LO, HI, new double[]{10, 10, 5}, 0, 1, 1, 1));
        assertFalse(BorderMesh.covered(half, 1, 10, 1, LO, HI));                 // грань закрыта не целиком — рисуется
    }
}
