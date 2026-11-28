package ink.hoka.odyssey.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.90 «Плавность»: выбор ступени потолка, гистерезис, точность ожидания до срока. */
class SmoothTest {

    @Test
    void pickStep() {
        assertEquals(500, Smooth.pick(530, 260), "без потолка игрока — ступень не выше возможного");
        assertEquals(240, Smooth.pick(260, 260));
        assertEquals(144, Smooth.pick(500, 144), "потолок игрока не превышается");
        assertEquals(30, Smooth.pick(12, 260), "совсем мало — нижняя ступень");
        assertEquals(60, Smooth.pick(65, 260), "мало FPS — ровные 60");
    }

    @Test
    void downAtOnceUpAfterHold() {
        Smooth.resetForTest();
        long s = 1_000_000_000L;
        assertEquals(400, Smooth.next(0, 400, 0));
        assertEquals(300, Smooth.next(400, 300, s), "вниз — сразу");
        assertEquals(300, Smooth.next(300, 500, 2 * s), "вверх — не сразу");
        assertEquals(300, Smooth.next(300, 500, 4 * s));
        assertEquals(500, Smooth.next(300, 500, 5 * s + 1), "запас держится 3 с — вверх");
        assertEquals(500, Smooth.next(500, 560, 6 * s));
        assertEquals(240, Smooth.next(500, 240, 6 * s + 1), "провал — сразу вниз, ожидание подъёма сброшено");
        assertEquals(240, Smooth.next(240, 560, 7 * s));
    }

    @Test
    void waitIsPrecise() {
        long[] lates = new long[270];
        for (int i = 0; i < 300; i++) {
            long d = System.nanoTime() + (i % 3 == 0 ? 16_000_000L : 2_000_000L);   // 60 и 500 FPS
            Smooth.waitUntil(d);
            long late = System.nanoTime() - d;
            assertTrue(late >= 0, "раньше срока");
            if (i >= 30) lates[i - 30] = late;                                   // первые — подстройка под таймер
        }
        java.util.Arrays.sort(lates);
        long p95 = lates[(int) (lates.length * 0.95)], max = lates[lates.length - 1];
        System.out.printf("ожидание до срока: p95 %d мкс, макс %d мкс%n", p95 / 1000, max / 1000);
        assertTrue(p95 < 200_000L, "опоздание p95 " + p95 / 1000 + " мкс");      // одиночные — вытеснение потока системой
        assertTrue(max < 3_000_000L, "опоздание до " + max / 1000 + " мкс");
    }
}
