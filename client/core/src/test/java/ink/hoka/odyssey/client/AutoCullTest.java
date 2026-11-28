package ink.hoka.odyssey.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.90 Выбор обхода секций: проба граф → дерево, решение по числу секций и времени. */
class AutoCullTest {

    @Test
    void decide() {
        assertTrue(AutoCull.decide(800, 3_000_000, 900, 300_000), "космос: дерево рисует почти столько же и в 10 раз быстрее");
        assertFalse(AutoCull.decide(300, 1_000_000, 2_000, 400_000), "внутри станции: стены скрывают много — граф");
        assertFalse(AutoCull.decide(800, 500_000, 820, 480_000), "дерево не быстрее — граф");
    }

    @Test
    void probeSequence() {
        AutoCull.reset();
        long s = 1_000_000_000L;
        assertFalse(AutoCull.wantTree(0, 0, 0, 2 * s), "проба: сначала граф");
        AutoCull.result(false, 800, 3_000_000);
        assertTrue(AutoCull.wantTree(0, 0, 0, 2 * s + 1), "проба: потом дерево");
        AutoCull.result(true, 850, 300_000);
        assertTrue(AutoCull.usingTree());
        assertTrue(AutoCull.wantTree(0, 0, 0, 2 * s + 2), "до следующей пробы — дерево");
        AutoCull.result(true, 850, 300_000);
        assertFalse(AutoCull.wantTree(3, 0, 0, 2 * s + 3), "сдвиг камеры на 3 секции — новая проба графом");
        AutoCull.result(false, 300, 1_000_000);
        assertTrue(AutoCull.wantTree(3, 0, 0, 2 * s + 4));
        AutoCull.result(true, 2000, 400_000);
        assertFalse(AutoCull.usingTree(), "вошли в станцию — граф");
        assertFalse(AutoCull.wantTree(3, 0, 0, 2 * s + 5));
    }
}
