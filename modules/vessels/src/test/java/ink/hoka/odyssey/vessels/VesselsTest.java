package ink.hoka.odyssey.vessels;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Форма модели, коробка корабля, проверка полёта, vessels.json. */
class VesselsTest {

    static ModelShape corabll() throws Exception {
        Path p = Path.of(System.getProperty("vessels.demo", "../../demo-objects/vessels"), "corabll.bbmodel");
        return ModelShape.parse(JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject());
    }

    static VesselKind kind(ModelShape s) {
        return new VesselKind("corabll", Map.of("speed", 30, "boost-speed", 50, "turn-speed", 90, "roll-speed", 120, "seat", List.of(0, 1, 3)), s);
    }

    @Test
    void corabllShape() throws Exception {
        ModelShape s = corabll();
        assertEquals(3328, s.triangleCount());
        // кость повёрнута на 90° по X: y' = −z, z' = y — то же, что у ядра мода (BbModelTest)
        assertArrayEquals(new double[]{-56.35371, 3.83261, -84.75073, 56.35977, 44.91533, 126.8644}, s.box, 1e-3);
        VesselKind k = kind(s);
        assertArrayEquals(new double[]{(56.35977 - 56.35371) / 2, (3.83261 + 44.91533) / 2, (126.8644 - 84.75073) / 2}, k.centerPx, 1e-3);
        assertArrayEquals(new double[]{(56.35977 + 56.35371) / 32, (44.91533 - 3.83261) / 32, (126.8644 + 84.75073) / 32}, k.half, 1e-3);
        assertFalse(k.hull.isEmpty());
        for (int[] c : k.hull) for (int a = 0; a < 3; a++) {                  // клетки корпуса — в коробке
            assertTrue(c[a] >= Math.floor(-k.half[a]) - 1e-9 && c[a] <= Math.ceil(k.half[a]), "клетка вне коробки");
        }
    }

    @Test
    void turnAndScale() throws Exception {
        VesselKind k = new VesselKind("t", Map.of("scale", 2, "turn", 90), corabll());
        double hx = (56.35977 + 56.35371) / 16, hz = (126.8644 + 84.75073) / 16;  // ×2, поворот на 90° меняет x и z
        assertEquals(hz, k.half[0], 1e-3);
        assertEquals(hx, k.half[2], 1e-3);
    }

    @Test
    void quat() {
        Quat west = Quat.yaw(90);                                               // курс 90 — нос на запад (−X)
        double[] f = west.rotate(0, 0, 1);
        assertArrayEquals(new double[]{-1, 0, 0}, f, 1e-9);
        assertEquals(90, west.yawDeg(), 1e-9);
        assertEquals(90, Quat.IDENTITY.angleTo(west), 1e-9);
        double[] back = west.conjugate().rotate(f[0], f[1], f[2]);
        assertArrayEquals(new double[]{0, 0, 1}, back, 1e-9);
        Quat m = Quat.yaw(30).mul(Quat.yaw(60));
        assertEquals(90, m.yawDeg(), 1e-9);
    }

    @Test
    void orientedBox() {
        double[] c = {100, 64, -50}, half = {1, 2, 5};
        Quat q = Quat.yaw(90);                                                  // длинная ось (z корабля) — вдоль X мира
        assertEquals(0, VesselMath.distanceToBox(100, 64, -50, c, q, half), 1e-9);
        assertEquals(0, VesselMath.distanceToBox(95.5, 64, -50, c, q, half), 1e-9);   // у носа: внутри
        assertEquals(1, VesselMath.distanceToBox(94, 64, -50, c, q, half), 1e-9);      // 1 блок за носом
        assertEquals(3, VesselMath.distanceToBox(100, 64, -46, c, q, half), 1e-9);     // сбоку: ширина 1 → 4 − 1
        assertEquals(Math.sqrt(2), VesselMath.distanceToBox(106, 67, -50, c, q, half), 1e-9);
        // по центру мира — была бы ошибка: точка (100, 64, −46) в 4 блоках от центра, но в 3 от коробки
        double[] o = {90, 64, -50}, east = {1, 0, 0};
        assertEquals(5, VesselMath.rayBox(o, east, 64, c, q, half), 1e-9);
        assertEquals(-1, VesselMath.rayBox(o, east, 4, c, q, half), 1e-9);  // не дотянуться
        assertEquals(-1, VesselMath.rayBox(new double[]{90, 70, -50}, east, 64, c, q, half), 1e-9);
        assertEquals(0, VesselMath.rayBox(new double[]{100, 64, -50}, east, 64, c, q, half), 1e-9);
    }

    @Test
    void flightCheck() throws Exception {
        VesselKind k = kind(corabll());
        VesselMath.Limits lim = VesselMath.Limits.DEFAULT;
        double[] p0 = {0, 100, 0};
        Quat q0 = Quat.IDENTITY;
        // форсаж 50 блоков/с = 2.5 за тик; запас ×1.3 + 1.5 → за тик можно до 4.75
        assertNull(VesselMath.check(p0, q0, new double[]{0, 100, 4.5}, q0, 1, k, lim));
        assertEquals("слишком быстро", VesselMath.check(p0, q0, new double[]{0, 100, 5}, q0, 1, k, lim));
        assertNull(VesselMath.check(p0, q0, new double[]{0, 100, 30}, q0, 10, k, lim));     // 10 тиков — в 10 раз дальше
        assertEquals("слишком быстро", VesselMath.check(p0, q0, new double[]{0, 100, 1000}, q0, 1000, k, lim));   // пауза — не дольше 40 тиков
        // поворот: √(2·90² + 120²) ≈ 174 °/с → 8.7°/тик × 1.5 + 10 ≈ 23°
        assertNull(VesselMath.check(p0, q0, p0, Quat.yaw(20), 1, k, lim));
        assertEquals("слишком быстрый поворот", VesselMath.check(p0, q0, p0, Quat.yaw(30), 1, k, lim));
        assertNull(VesselMath.check(p0, q0, p0, Quat.yaw(90), 10, k, lim));
        assertEquals("плохие числа", VesselMath.check(p0, q0, new double[]{Double.NaN, 0, 0}, q0, 1, k, lim));
        assertEquals("плохой поворот", VesselMath.check(p0, q0, p0, new Quat(0, 0, 0, 0), 1, k, lim));
        assertEquals("плохой поворот", VesselMath.check(p0, q0, p0, new Quat(Double.NaN, 0, 0, 1), 1, k, lim));
    }

    @Test
    void storeRoundTrip(@TempDir Path dir) throws Exception {
        VesselStore st = new VesselStore(dir.resolve("vessels.json"));
        assertTrue(st.load().isEmpty());
        assertFalse(st.broken());
        Vessel a = new Vessel(1, "corabll", "world", 10.5, 70.25, -3, Quat.yaw(45));
        Vessel b = new Vessel(7, "corabll", "space", -1e6, 0, 2e6, Quat.IDENTITY);
        assertTrue(st.write(VesselStore.json(List.of(a, b))));
        assertFalse(Files.exists(dir.resolve("vessels.json.tmp")), "временный файл переименован");
        List<Vessel> back = new VesselStore(dir.resolve("vessels.json")).load();
        assertEquals(2, back.size());
        Vessel a2 = back.get(0);
        assertEquals(1, a2.id);
        assertEquals("corabll", a2.kind);
        assertEquals("world", a2.map);
        assertArrayEquals(new double[]{10.5, 70.25, -3}, a2.pos(), 1e-12);
        assertEquals(0, a2.rot.angleTo(Quat.yaw(45)), 1e-6);
        assertEquals(7, back.get(1).id);
        assertEquals(-1e6, back.get(1).x, 1e-9);
    }

    @Test
    void brokenFileIsKept(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("vessels.json");
        String bad = "{\"version\":1,\"vessels\":[{\"id\":1,\"kind\":\"corabll\",";    // оборван посреди записи
        Files.writeString(f, bad);
        VesselStore st = new VesselStore(f);
        assertTrue(st.load().isEmpty());
        assertTrue(st.broken());
        assertNotNull(st.brokenWhy());
        assertEquals(bad, Files.readString(dir.resolve("vessels.json.broken")));
        assertFalse(st.write(VesselStore.json(List.of())));                  // пустой список старое не затирает
        assertEquals(bad, Files.readString(f));
        // и не JSON вовсе, и без списка — тоже битый
        Files.writeString(f, "[]");
        assertTrue(new VesselStore(f).load().isEmpty());
        VesselStore st2 = new VesselStore(f);
        st2.load();
        assertTrue(st2.broken());
    }
}
