package ink.hoka.odyssey.vessels;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** vessels-4, п.3: прилёт корабля — направления, путь без блоков на картах из клеток, обход, сверху на планете. */
class ArrivalTest {

    /** Корабль-коробка 3×2×7 клеток (середины клеток — от −1.5…, как VesselKind.hull: floor-индексы). */
    static final List<int[]> HULL = new ArrayList<>();
    static {
        for (int x = -2; x <= 1; x++) for (int y = -1; y <= 0; y++) for (int z = -4; z <= 3; z++) HULL.add(new int[]{x, y, z});
    }

    /** Free по карте занятых блоков. */
    static ArrivalPlan.Free free(Predicate<int[]> solid) {
        return (x, y, z, yaw) -> {
            Quat q = Quat.yaw(yaw);
            double[] c = {x, y, z};
            for (int[] h : HULL) {
                double[] w = VesselMath.toWorld(h[0] + 0.5, h[1] + 0.5, h[2] + 0.5, c, q);
                if (solid.test(new int[]{(int) Math.floor(w[0]), (int) Math.floor(w[1]), (int) Math.floor(w[2])})) return false;
            }
            return true;
        };
    }

    @Test
    void directionsStartAtLookAndAlternate() {
        List<double[]> d = ArrivalPlan.directions(0, 8);
        assertEquals(8, d.size());
        assertArrayEquals(new double[]{0, 0, 1}, d.get(0), 1e-9, "курс 0 — на юг (+Z)");
        assertEquals(45, Math.abs(ArrivalPlan.yawOf(d.get(1)[0], d.get(1)[2])), 1e-6);
        assertEquals(-ArrivalPlan.yawOf(d.get(1)[0], d.get(1)[2]), ArrivalPlan.yawOf(d.get(2)[0], d.get(2)[2]), 1e-6, "вправо-влево по очереди");
        for (int i = 0; i < d.size(); i++) for (int j = i + 1; j < d.size(); j++)
            assertTrue(ArrivalPlan.dist(d.get(i), d.get(j)) > 0.5, "направления разные");
        assertEquals(90, ArrivalPlan.yawOf(-1, 0), 1e-9, "запад — 90, как у Minecraft");
    }

    @Test
    void spaceChoosesFreeDirection() {
        double[] t = {0, 100, 0};
        // стена 20×20 на юге (+Z) в 60 блоках: прямо с юга нельзя — берётся следующее направление
        Predicate<int[]> wall = b -> b[2] >= 60 && b[2] <= 62 && Math.abs(b[0]) <= 10 && Math.abs(b[1] - 100) <= 10;
        ArrivalPlan.Path p = ArrivalPlan.space(t, ArrivalPlan.directions(0, 8), 120, 40, free(wall));
        assertNotNull(p);
        assertEquals(2, p.points().size(), "прямой путь");
        double[] s = p.points().get(0);
        assertTrue(Math.abs(s[2] - 120) > 1 || Math.abs(s[0]) > 1, "не с юга");
        assertTrue(ArrivalPlan.pathFree(p.points(), 0.25, free(wall)), "путь без блоков (проверка мельче)");
        assertArrayEquals(t, p.points().get(p.points().size() - 1), 1e-9, "кончается у цели");
        // пусто — прямо с первого направления (откуда смотрит игрок)
        ArrivalPlan.Path open = ArrivalPlan.space(t, ArrivalPlan.directions(0, 8), 120, 40, free(b -> false));
        assertArrayEquals(new double[]{0, 100, 120}, open.points().get(0), 1e-9);
        assertEquals(180, Math.abs(open.endYaw()), 1e-6, "летит на север — носом на север");
    }

    @Test
    void spaceAllBlockedGivesNull() {
        double[] t = {0, 100, 0};
        // всё вокруг занято, кроме шара радиуса 8 у цели
        Predicate<int[]> box = b -> (b[0] * b[0] + (b[1] - 100) * (b[1] - 100) + b[2] * b[2]) > 64;
        assertNull(ArrivalPlan.space(t, ArrivalPlan.directions(0, 8), 120, 40, free(box)), "пути нет — варп");
    }

    @Test
    void spaceDetourOverWall() {
        double[] t = {0, 100, 0};
        // кольцо-стена вокруг цели на расстоянии 50…53 по горизонтали, высотой 100…125: прямо — нельзя, сверху (+40) — можно
        Predicate<int[]> ring = b -> {
            double r = Math.sqrt(b[0] * (double) b[0] + b[2] * (double) b[2]);
            return r >= 50 && r <= 53 && b[1] >= 70 && b[1] <= 125;
        };
        ArrivalPlan.Path p = ArrivalPlan.space(t, ArrivalPlan.directions(0, 8), 120, 40, free(ring));
        assertNotNull(p, "обход найден");
        assertEquals(3, p.points().size(), "через точку обхода");
        assertTrue(p.points().get(1)[1] > 125, "обход сверху");
        assertTrue(ArrivalPlan.pathFree(p.points(), 0.25, free(ring)), "обход без блоков");
    }

    @Test
    void planetFromAbove() {
        // ровная земля: блоки y ≤ 64; гора 30×30 на юге до y 140
        Predicate<int[]> ground = b -> b[1] <= 64 || (b[2] >= 40 && b[2] <= 70 && Math.abs(b[0]) <= 15 && b[1] <= 140);
        double[] t = {0, 77, 0};
        ArrivalPlan.Path p = ArrivalPlan.planet(t, ArrivalPlan.directions(0, 8), 250, 120, 300, 12, free(ground));
        assertNotNull(p);
        double[] s = p.points().get(0);
        assertTrue(s[1] >= t[1] + 100, "появился высоко: " + s[1]);
        assertTrue(Math.abs(s[2] - 150) > 1, "не через гору на юге");
        assertTrue(ArrivalPlan.pathFree(p.points(), 0.25, free(ground)));
        assertArrayEquals(t, p.points().get(p.points().size() - 1), 1e-9);
        double[] above = p.points().get(1);
        assertEquals(t[1] + 12, above[1], 1e-9, "в конце — опускается к точке зависания");
        // потолок мира низкий — не выше него
        ArrivalPlan.Path low = ArrivalPlan.planet(t, ArrivalPlan.directions(0, 8), 250, 120, 150, 12, free(ground));
        assertNotNull(low);
        for (double[] q : low.points()) assertTrue(q[1] <= 150 + 1e-9);
    }

    @Test
    void spotNearPlayer() {
        double[] player = {0, 101, 0};
        // занято всё на юге (+Z > 0) — место берётся в другой стороне; не на игроке
        Predicate<int[]> south = b -> b[2] >= 0 && b[1] >= 90 && b[1] <= 110;
        double r = 4.5;
        double[] s = ArrivalPlan.spotNear(player, ArrivalPlan.directions(0, 8), new double[]{r + 2, r + 6}, new double[]{0, 3},
                180, free(south), c -> ArrivalPlan.dist(c, player) >= r + 1.5);
        assertNotNull(s);
        assertTrue(ArrivalPlan.dist(s, player) >= r + 1.5);
        assertTrue(free(south).free(s[0], s[1], s[2], 180));
        assertNull(ArrivalPlan.spotNear(player, ArrivalPlan.directions(0, 8), new double[]{r + 2}, new double[]{0},
                180, free(b -> true), c -> true), "всё занято — места нет");
    }

    @Test
    void flightTiming() {
        assertEquals(0, ArrivalPlan.ease(0), 1e-12);
        assertEquals(1, ArrivalPlan.ease(1), 1e-12);
        double prev = 0, prevStep = Double.MAX_VALUE;
        for (int i = 1; i <= 100; i++) {
            double e = ArrivalPlan.ease(i / 100.0);
            assertTrue(e > prev, "вперёд");
            assertTrue(e - prev <= prevStep + 1e-12, "тормозит к концу");
            prevStep = e - prev;
            prev = e;
        }
        assertEquals(160, ArrivalPlan.duration(50, 25, 160, 240), "не быстрее 8 с");
        assertEquals(240, ArrivalPlan.duration(5000, 25, 160, 240), "не дольше 12 с");
        assertEquals(200, ArrivalPlan.duration(250, 25, 160, 240), "250 блоков на 25 бл/с — 10 с");
        List<double[]> pts = List.of(new double[]{0, 0, 0}, new double[]{10, 0, 0}, new double[]{10, 0, 10});
        double[] a = ArrivalPlan.at(pts, 15);
        assertArrayEquals(new double[]{10, 0, 5, 0, 0, 1}, a, 1e-9);
        assertArrayEquals(new double[]{10, 0, 10}, java.util.Arrays.copyOf(ArrivalPlan.at(pts, 999), 3), 1e-9);
        // поворот по направлению полёта: курс по ходу, корпус ровный (как проверялся путь); отвесно — запасной курс
        double[] nose = Arrivals.rot(0, 1, 1, 90).rotate(0, 0, 1);
        assertEquals(0, nose[1], 1e-9);
        assertEquals(1, nose[2], 1e-9);
        double[] west = Arrivals.rot(-1, 0, 0, 0).rotate(0, 0, 1);
        assertEquals(-1, west[0], 1e-9);
        double[] down = Arrivals.rot(0, -1, 0, 90).rotate(0, 0, 1);
        assertEquals(-1, down[0], 1e-9, "отвесно вниз — с курсом конца пути");
    }
}
