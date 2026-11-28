package ink.hoka.odyssey.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.90 «Плавность»: выбор ступени потолка, гистерезис, точность ожидания до срока. */
class SmoothTest {

    @Test
    void pickStep() {
        assertEquals(500, Smooth.pick(530, Integer.MAX_VALUE), "без потолка игрока — ступень не выше возможного");
        assertEquals(240, Smooth.pick(260, Integer.MAX_VALUE));
        assertEquals(144, Smooth.pick(500, 144), "потолок игрока не превышается");
        assertEquals(30, Smooth.pick(12, Integer.MAX_VALUE), "совсем мало — нижняя ступень");
        assertEquals(60, Smooth.pick(65, Integer.MAX_VALUE), "мало FPS — ровные 60");
        assertEquals(500, Smooth.pick(900, Smooth.cap(Smooth.CAP_500)), "«до 500»: 900 возможно — ровно 500");
        assertEquals(450, Smooth.pick(480, Smooth.cap(Smooth.CAP_500)), "«до 500»: не тянет — ступенью ниже");
        assertEquals(Integer.MAX_VALUE, Smooth.cap(Smooth.AUTO));
    }

    @Test
    void calmSteps() {
        Smooth.resetForTest();
        long s = 1_000_000_000L, ms = 1_000_000L;
        assertEquals(500, Smooth.next(0, 500, 0), "первый выбор — сразу");
        assertEquals(500, Smooth.next(500, 120, s), "разовая тяжесть — потолок держим");
        assertEquals(500, Smooth.next(500, 500, s + 500 * ms), "прошло — сброс");
        assertEquals(500, Smooth.next(500, 120, 2 * s));
        assertEquals(400, Smooth.next(500, 120, 3 * s + 1), "тяжело секунду подряд — вниз, но не больше 2 ступеней (500→450→400)");
        assertEquals(400, Smooth.next(400, 500, 4 * s), "вверх — не сразу");
        assertEquals(500, Smooth.next(400, 500, 6 * s + 1), "запас держится 2 с — вверх");
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
        // макс не проверяем: на занятой машине (игра рядом) поток вытесняют на единицы мс — от нас не зависит
    }
}
