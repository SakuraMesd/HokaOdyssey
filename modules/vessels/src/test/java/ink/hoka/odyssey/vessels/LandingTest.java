package ink.hoka.odyssey.vessels;

import net.minestom.server.MinecraftServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * vessels-6: посадка на планету с кораблём и взлёт — точка появления в круге (все внутри, равномерно по площади),
 * высоты по карте (урезание), высота конца спуска над рельефом, плавность кривых (без скачка скорости в конце), точка
 * выхода в космос (грань, расстояние, без повторного входа, нос от планеты), корабль после смены карты в vessels.json.
 */
class LandingTest {

    static final LandingMath.Planet VERDIS = new LandingMath.Planet("verdis", "cosmos", 0, 3200, 2600, 2048, 256, 50, "hoka23", false,
            2000, 0, 0, 2400, 2000, 6, 2600);

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    @Test
    void scatterInsideCircleAndUniform() {
        Random r = new Random(42);
        int n = 40000, inner = 0;
        int[] quad = new int[4];
        double cx = 100, cz = -300, R = 2000;
        for (int i = 0; i < n; i++) {
            double[] p = LandingMath.scatter(r, cx, cz, R);
            double dx = p[0] - cx, dz = p[1] - cz, d = Math.hypot(dx, dz);
            assertTrue(d <= R + 1e-9, "точка вне круга: " + d);
            if (d <= R / 2) inner++;
            quad[(dx >= 0 ? 0 : 1) + (dz >= 0 ? 0 : 2)]++;
        }
        assertEquals(0.25, inner / (double) n, 0.01, "равномерно по площади: в половине радиуса — четверть точек");
        for (int q : quad) assertEquals(0.25, q / (double) n, 0.01, "все стороны поровну");
        double[] same = LandingMath.scatter(r, 5, 7, 0);
        assertArrayEquals(new double[]{5, 7}, same, 1e-12, "разброс 0 — ровно в центр");
        double[] in = LandingMath.insideBorder(5000, -5000, 6000, 0, 0, 20);
        assertArrayEquals(new double[]{2980, -2980}, in, 1e-9, "точка — внутрь границы карты с запасом");
    }

    @Test
    void heightsClampedByMap() {
        LandingMath.Heights h = LandingMath.heights(-32752, 32752, 12, 2400, 2000, 2600);
        assertEquals(2400, h.entry());
        assertEquals(2000, h.rest());
        assertEquals(2600, h.exit());
        assertTrue(h.notes().isEmpty());

        LandingMath.Heights low = LandingMath.heights(-64, 2048, 12, 2400, 2000, 2600);   // карта ниже взлёта
        assertEquals(2048 - 12 - 2, low.exit(), 1e-9, "взлёт — не выше верха карты");
        assertTrue(low.entry() <= low.exit() - LandingMath.GAP + 1e-9, "появление ниже взлёта на GAP");
        assertTrue(low.rest() <= low.entry(), "конец спуска не выше появления");
        assertFalse(low.notes().isEmpty(), "урезанное — в лог");

        LandingMath.Heights tiny = LandingMath.heights(0, 256, 12, 2400, 2000, 2600);
        assertTrue(tiny.rest() >= 0 + 12 + 2 && tiny.rest() <= tiny.entry() && tiny.entry() < tiny.exit(), tiny.toString());
    }

    @Test
    void restHeightAboveTerrain() {
        double[] off = CushionTest.offsets();
        Cushion.Settings s = CushionTest.S;
        double calm = s.clearance() + s.soft() + 1;
        // ровная земля далеко внизу (y < 1900) — ровно ship-y
        DistField far = DistField.build(-30, 1960, -30, 61, 120, 61, (x, y, z) -> y < 1900);
        assertEquals(2000, LandingMath.restY(far, off, 0.5, 0.5, 2000, 2075, s), 1e-9);
        // гора до y = 2005 — выше: весь корпус дальше clearance + soft от блоков (подушка в конце не толкает)
        DistField hill = DistField.build(-30, 1960, -30, 61, 120, 61, (x, y, z) -> y <= 2005);
        double y = LandingMath.restY(hill, off, 0.5, 0.5, 2000, 2075, s);
        assertFalse(Double.isNaN(y));
        assertTrue(y > 2005, "над рельефом");
        assertTrue(Cushion.clearance(hill, CushionTest.probes(off, 0.5, y, 0.5)) >= calm - 1e-6, "зазор не меньше подушки + мягкой зоны");
        assertTrue(Cushion.clearance(hill, CushionTest.probes(off, 0.5, y - 1, 0.5)) < calm, "и это наименьшая такая высота");
        // гора выше верха поиска — места нет
        DistField wall = DistField.build(-30, 1960, -30, 61, 120, 61, (x, y2, z) -> y2 <= 2070);
        assertTrue(Double.isNaN(LandingMath.restY(wall, off, 0.5, 0.5, 2000, 2060, s)));
    }

    @Test
    void descentIsSmooth() {
        Glide g = new Glide(Glide.DESCENT, new double[]{10, 2400, -5}, new double[]{10, 2000, -5}, 120);
        assertArrayEquals(new double[]{10, 2400, -5}, g.at(0), 1e-9);
        assertArrayEquals(new double[]{10, 2000, -5}, g.at(120), 1e-9);
        assertArrayEquals(new double[]{10, 2000, -5}, g.at(500), 1e-9, "после конца — стоит");
        double prevY = 2400, prevV = Double.NaN, maxDv = 0;
        for (int t = 1; t <= 120; t++) {
            double y = g.at(t)[1], v = y - prevY;
            assertTrue(v <= 1e-9, "только вниз");
            if (!Double.isNaN(prevV)) maxDv = Math.max(maxDv, Math.abs(v - prevV));
            prevY = y;
            prevV = v;
        }
        double start = -400.0 / 120 * 2;
        assertEquals(start, g.velocity(0)[1], 1e-9, "входит со скоростью 2·L/T");
        assertEquals(0, g.velocity(120)[1], 1e-12, "в конце скорость ноль");
        assertTrue(Math.abs(prevV) < 0.01, "последний тик почти не двигается: " + prevV);
        assertTrue(maxDv < Math.abs(start) * 0.06, "скорость меняется плавно (без скачков): " + maxDv);
        double a1 = g.velocity(119)[1] - g.velocity(118)[1], a0 = g.velocity(120)[1] - g.velocity(119)[1];
        assertTrue(Math.abs(a0) <= Math.abs(a1) + 1e-12 && Math.abs(a0) < Math.abs(start) * 1e-3, "торможение гаснет к концу (ускорение → 0)");
        for (double u = 0; u <= 1.0001; u += 0.01) assertTrue(Glide.rate(Glide.DESCENT, u) >= -1e-12, "не уходит назад");
    }

    @Test
    void exitAccelerates() {
        int T = 50;
        double vEnd = 30 / 20.0, len = Glide.exitLength(vEnd, T);
        Glide g = new Glide(Glide.EXIT, new double[]{0, 0, 0}, new double[]{len, 0, 0}, T);
        assertEquals(vEnd, g.velocity(T)[0], 1e-9, "в конце — заданная скорость");
        assertEquals(vEnd * Glide.EXIT_START, g.velocity(0)[0], 1e-9);
        double prev = 0;
        for (int t = 1; t <= T; t++) { double v = g.velocity(t)[0]; assertTrue(v >= prev - 1e-12, "ускоряется"); prev = v; }
        assertEquals(len, g.at(T)[0], 1e-9);
    }

    @Test
    void exitFaceByFlight() {
        assertArrayEquals(new int[]{0, 1, 0}, LandingMath.exitFace(0.1, 2, 0.2, null), "вверх — над верхней гранью");
        assertArrayEquals(new int[]{1, 0, 0}, LandingMath.exitFace(1.5, 1, 0.3, null), "на восток — грань +X");
        assertArrayEquals(new int[]{0, 0, -1}, LandingMath.exitFace(0.2, 0.5, -1.4, null), "на север — грань −Z");
        assertArrayEquals(new int[]{-1, 0, 0}, LandingMath.exitFace(0, 0, 0, new double[]{-1, 0, 0.2}), "стоит — по носу");
    }

    @Test
    void exitPointNearPlanetWithoutReentry() {
        double r = 12, margin = 30;
        for (int[] n : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}}) {
            for (double[] at : new double[][]{{0, 0}, {1500, -900}, {-2000, 2000}}) {
                double[] p = LandingMath.exitPoint(VERDIS, n, at[0], at[1], VERDIS.approach() + margin + r);
                double d = VERDIS.distance(p[0], p[1], p[2]);
                assertEquals(VERDIS.approach() + margin + r, d, 1e-6, "середина корабля — в approach + 30 + радиус от поверхности");
                assertTrue(d - r > VERDIS.approach() + margin - 1e-6, "корпус — за зоной входа на 30: сразу не войдёт");
                double[] rel = {p[0] - VERDIS.x(), p[1] - VERDIS.y(), p[2] - VERDIS.z()};
                int axis = n[0] != 0 ? 0 : n[1] != 0 ? 1 : 2;
                int sign = n[axis];
                assertTrue(rel[axis] * sign > 0 && Math.abs(rel[axis]) >= Math.abs(rel[(axis + 1) % 3]) && Math.abs(rel[axis]) >= Math.abs(rel[(axis + 2) % 3]),
                        "над нужной гранью: " + java.util.Arrays.toString(rel));
                Quat q = LandingMath.noseAlong(n);
                double[] nose = q.rotate(0, 0, 1);
                assertEquals(1, nose[0] * n[0] + nose[1] * n[1] + nose[2] * n[2], 1e-9, "нос — от планеты");
                // разгон по нормали только удаляет от планеты
                double[] far = {p[0] + n[0] * 40, p[1] + n[1] * 40, p[2] + n[2] * 40};
                assertTrue(VERDIS.distance(far[0], far[1], far[2]) > d);
            }
        }
    }

    @Test
    void sameDistanceAsPlanetsModule() {
        Random r = new Random(3);
        for (int i = 0; i < 200; i++) {
            double x = (r.nextDouble() - 0.5) * 4000, y = 3200 + (r.nextDouble() - 0.5) * 4000, z = 2600 + (r.nextDouble() - 0.5) * 4000;
            assertEquals(ink.hoka.odyssey.planets.PlanetsApi.roundedBox(x - VERDIS.x(), y - VERDIS.y(), z - VERDIS.z(), VERDIS.size(), VERDIS.round()),
                    VERDIS.distance(x, y, z), 1e-9);
        }
    }

    @Test
    void vesselSavedAfterMapChange(@TempDir Path dir) throws Exception {
        Vessel v = new Vessel(7, "corabll", "cosmos", 10, 3300, 3700, Quat.yaw(30));
        v.cargo.storage[0] = net.minestom.server.item.ItemStack.of(net.minestom.server.item.Material.DIAMOND, 5);
        v.hp = 120;
        v.zoneDmg[2] = 15;
        VesselStore store = new VesselStore(dir.resolve("vessels.json"));
        store.write(VesselStore.json(List.of(v)));
        // переход: карта и место меняются одной записью — корабль всегда на одной из карт
        v.map = "hoka23";
        v.x = 812.5; v.y = 2400; v.z = -1290;
        v.rot = Quat.yaw(v.rot.yawDeg());
        store.write(VesselStore.json(List.of(v)));
        List<Vessel> back = store.load();
        assertFalse(store.broken());
        assertEquals(1, back.size());
        Vessel b = back.get(0);
        assertEquals(7, b.id, "тот же корабль");
        assertEquals("hoka23", b.map);
        assertEquals(2400, b.y, 1e-9);
        assertEquals(120, b.hp, 1e-9, "прочность");
        assertEquals(15, b.zoneDmg[2], 1e-9, "урон зон");
        assertEquals(5, b.cargo.storage[0].amount(), "груз");
        assertEquals(30, b.rot.yawDeg(), 1e-6, "курс");
        assertTrue(Files.exists(dir.resolve("vessels.json")));
    }
}
