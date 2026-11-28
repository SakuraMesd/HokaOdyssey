package ink.hoka.odyssey.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Этап 6: кольцо ViewArea по Y (ViewRing, ViewAreaRingMixin). */
class ViewRingTest {

    static final int MIN = -2047, MAX = 2046;                 // космос: 4094 секции

    @Test
    void radius() {
        assertEquals(0, ViewRing.radiusY(true, 4094, 12, 10), "с Sodium — ваниль");
        assertEquals(0, ViewRing.radiusY(false, 24, 12, 10), "обычный мир — ваниль");
        assertEquals(11, ViewRing.radiusY(false, 4094, 12, 10));
        assertEquals(8, ViewRing.radiusY(false, 4094, 8, 10), "не больше дальности прорисовки");
        assertEquals(2, ViewRing.radiusY(false, 4094, 2, 10));
        assertEquals(0, ViewRing.radiusY(false, 66, 40, 32), "кольцо не меньше мира — ваниль");
    }

    @Test
    void baseClamped() {
        assertEquals(-11, ViewRing.base(0, 11, MIN, MAX));
        assertEquals(MIN, ViewRing.base(MIN, 11, MIN, MAX));
        assertEquals(MIN, ViewRing.base(MIN - 50, 11, MIN, MAX), "камера под миром");
        assertEquals(MAX - 22, ViewRing.base(MAX, 11, MIN, MAX));
        assertEquals(MAX - 22, ViewRing.base(MAX + 99, 11, MIN, MAX), "камера над миром");
    }

    /** Каждая секция окна лежит ровно в своём слое, слои — без повторов, при любом положении камеры. */
    @Test
    void layersMatchSlots() {
        int r = 11, size = 2 * r + 1;
        for (int cam = MIN - 30; cam <= MAX + 30; cam += 7) {
            int base = ViewRing.base(cam, r, MIN, MAX);
            boolean[] seen = new boolean[size];
            for (int j = 0; j < size; j++) {
                int y = ViewRing.layerY(j, base, size);
                assertTrue(y >= base && y < base + size, "слой вне окна");
                assertTrue(y >= MIN && y <= MAX, "секция вне мира");
                assertEquals(j, ViewRing.slot(y, size), "секция не в своём слое");
                assertFalse(seen[j]);
                seen[j] = true;
            }
            if (cam >= MIN && cam <= MAX) assertTrue(cam >= base && cam < base + size, "камера вне окна");
        }
    }
}
