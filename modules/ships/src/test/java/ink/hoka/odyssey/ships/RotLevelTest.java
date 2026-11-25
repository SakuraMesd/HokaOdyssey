package ink.hoka.odyssey.ships;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Углы из кватерниона и «ровный» поворот (автовыравнивание). */
class RotLevelTest {

    @Test
    void eulerRoundTrip() {
        Random r = new Random(7);
        for (int i = 0; i < 2000; i++) {
            double yaw = r.nextDouble() * 360 - 180, pitch = r.nextDouble() * 178 - 89, roll = r.nextDouble() * 360 - 180;
            float[] q = Rot.quat(yaw, pitch, roll, 0);
            double[] e = Rot.euler(q);
            float[] q2 = Rot.quat(e[0], e[1], e[2], 0);
            assertTrue(Rot.angle(q, q2) < 5e-3, "поворот не восстановился: " + yaw + " " + pitch + " " + roll + " → " + e[0] + " " + e[1] + " " + e[2]);
        }
    }

    @Test
    void levelKeepsHeading() {
        Random r = new Random(11);
        for (int i = 0; i < 2000; i++) {
            double yaw = r.nextDouble() * 360 - 180, pitch = r.nextDouble() * 160 - 80, roll = r.nextDouble() * 360 - 180;
            double[] e = Rot.euler(Rot.level(Rot.quat(yaw, pitch, roll, 0)));
            assertEquals(0, e[1], 1e-3, "наклон");
            assertEquals(0, e[2], 1e-3, "крен");
            assertEquals(0, Rot.wrap(e[0] - yaw), 1e-2, "курс");
        }
    }

    @Test
    void slerpStepsToTarget() {
        float[] a = Rot.quat(30, 40, 170, 0), b = Rot.level(a);
        double full = Rot.angle(a, b);
        float[] m = Rot.slerp(a, b, 0.25);
        assertEquals(full * 0.75, Rot.angle(m, b), 5e-3);
    }
}
