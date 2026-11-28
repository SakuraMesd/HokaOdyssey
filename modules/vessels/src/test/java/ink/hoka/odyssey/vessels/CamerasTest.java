package ink.hoka.odyssey.vessels;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** vessels-5, п.3: камеры кораблей — корабль поверх вида, правка, перестановка, файл, перевод точки мир ↔ корабль. */
class CamerasTest {

    static final CameraBook.Scope KIND = CameraBook.Scope.kind("corabll"), SHIP = CameraBook.Scope.ship(7);

    static CameraBook.Cam cam(double x) { return CameraBook.Cam.of(x, 1, -10, 0, 10, 0); }

    @Test
    void shipOverKind() {
        CameraBook b = CameraBook.EMPTY;
        assertTrue(b.effective("corabll", 7).isEmpty(), "нет камер — по умолчанию");
        b = b.withFirst(KIND, cam(1)).withF5(KIND, 1, cam(2)).withF5(KIND, 2, cam(3));
        CameraBook.Set e = b.effective("corabll", 7);
        assertEquals(cam(1), e.first());
        assertEquals(List.of(cam(2), cam(3)), e.f5());
        // у корабля — своё первое лицо: главнее вида, F5 — от вида
        b = b.withFirst(SHIP, cam(9));
        e = b.effective("corabll", 7);
        assertEquals(cam(9), e.first());
        assertEquals(List.of(cam(2), cam(3)), e.f5());
        assertEquals(cam(1), b.effective("corabll", 8).first(), "другой корабль того же вида — камеры вида");
        // свой список F5 у корабля — вместо списка вида
        b = b.withF5(SHIP, 1, cam(5));
        assertEquals(List.of(cam(5)), b.effective("corabll", 7).f5());
        assertTrue(b.effective("other", 7).f5().contains(cam(5)), "переопределение — по id корабля");
        // корабль удалён — его камеры прочь
        assertEquals(List.of(cam(2), cam(3)), b.forgetShip(7).effective("corabll", 7).f5());
    }

    @Test
    void editingReplacesNotAdds() {
        CameraBook b = CameraBook.EMPTY.withF5(KIND, 1, cam(1)).withF5(KIND, 2, cam(2)).withF5(KIND, 3, cam(3));
        CameraBook c = b.withF5(KIND, 2, cam(20));                           // правка существующей камеры
        assertEquals(List.of(cam(1), cam(20), cam(3)), c.kind("corabll").f5());
        c = c.withFirst(KIND, cam(4)).withFirst(KIND, cam(40));
        assertEquals(cam(40), c.kind("corabll").first());
        assertEquals(3, c.kind("corabll").f5().size());
        c = c.withF5(KIND, 4, cam(5));                                       // n = число + 1 — новый в конец
        assertEquals(4, c.kind("corabll").f5().size());
        CameraBook d = c;
        assertThrows(IllegalArgumentException.class, () -> d.withF5(KIND, 6, cam(6)), "дыр в списке нет");
        assertThrows(IllegalArgumentException.class, () -> d.withF5(KIND, 0, cam(6)));
        CameraBook full = d.withF5(KIND, 5, cam(6));
        assertThrows(IllegalArgumentException.class, () -> full.withF5(KIND, 6, cam(7)), "не больше 5 видов");
        assertEquals(List.of(cam(1), cam(20), cam(5), cam(6)), full.withoutF5(KIND, 3).kind("corabll").f5());
        assertNull(full.withFirst(KIND, null).kind("corabll").first());
        assertTrue(full.reset(KIND).kind("corabll").isEmpty());
        assertFalse(full.reset(KIND).kinds().containsKey("corabll"), "пустое — не хранится");
    }

    @Test
    void reorder() {
        CameraBook b = CameraBook.EMPTY.withF5(KIND, 1, cam(1)).withF5(KIND, 2, cam(2)).withF5(KIND, 3, cam(3));
        assertEquals(List.of(cam(3), cam(1), cam(2)), b.moveF5(KIND, 3, 1).kind("corabll").f5());
        assertEquals(List.of(cam(2), cam(3), cam(1)), b.moveF5(KIND, 1, 3).kind("corabll").f5());
        assertEquals(List.of(cam(1), cam(3), cam(2)), b.moveF5(KIND, 2, 3).kind("corabll").f5());
        assertEquals(b.kind("corabll"), b.moveF5(KIND, 2, 2).kind("corabll"));
        assertThrows(IllegalArgumentException.class, () -> b.moveF5(KIND, 1, 4));
    }

    @Test
    void copyShipToKind() {
        CameraBook b = CameraBook.EMPTY.withF5(KIND, 1, cam(1)).withFirst(SHIP, cam(9));
        CameraBook c = b.copyToKind(7, "corabll", "frigate");
        assertEquals(cam(9), c.kind("frigate").first());
        assertEquals(List.of(cam(1)), c.kind("frigate").f5(), "в деле — с учётом вида");
    }

    @Test
    void camLimits() {
        CameraBook.Cam c = new CameraBook.Cam(0, 0, 0, 270, 120, 500, new double[]{1, 2}, 5);
        assertEquals(-90, c.yaw(), 1e-9);
        assertEquals(90, c.pitch(), 1e-9);
        assertEquals(130, c.fov(), 1e-9);
        assertNull(c.look());
        assertEquals(1, c.smooth(), 1e-9);
        assertEquals(0, CameraBook.Cam.of(0, 0, 0, 0, 0, -3).fov(), 1e-9);
        assertEquals(180, CameraBook.wrap(-180), 1e-9);
        assertEquals(10, CameraBook.wrap(370), 1e-9);
        // не числа (лояльный JSON пропускает NaN) — камера не принимается, файл не портится
        assertThrows(IllegalStateException.class, () -> CameraBook.camFrom(com.google.gson.JsonParser.parseString(
                "{\"pos\":[0,0,0],\"look\":[NaN,0,0]}").getAsJsonObject()));
        assertThrows(IllegalStateException.class, () -> CameraBook.camFrom(com.google.gson.JsonParser.parseString(
                "{\"pos\":[0,Infinity,0]}").getAsJsonObject()));
    }

    @Test
    void fileRoundTrip(@TempDir Path dir) throws Exception {
        CameraBook b = CameraBook.EMPTY.withFirst(KIND, CameraBook.Cam.of(0.5, 1.25, 2.5, 3, -4, 80))
                .withF5(KIND, 1, new CameraBook.Cam(0, 4, -14, 0, 12, 0, null, 0.15))
                .withF5(KIND, 2, new CameraBook.Cam(9, 3, 0, 0, 0, 70, new double[]{0, 0, 0}, 1))
                .withF5(SHIP, 1, cam(3));
        CameraBook.Store st = new CameraBook.Store(dir.resolve("cameras.json"));
        assertTrue(st.load().kinds().isEmpty());
        assertTrue(st.write(b.toJson()));
        CameraBook l = new CameraBook.Store(dir.resolve("cameras.json")).load();
        assertEquals(b.kinds(), l.kinds());
        assertEquals(b.ships(), l.ships());
        assertEquals(b.effective("corabll", 7), l.effective("corabll", 7));
        // битый файл — не перезаписывается
        Files.writeString(dir.resolve("cameras.json"), "{\"kinds\":{\"corabll\":{\"first\":{\"pos\":[1,2]}}}}");
        CameraBook.Store bad = new CameraBook.Store(dir.resolve("cameras.json"));
        assertTrue(bad.load().kinds().isEmpty());
        assertTrue(bad.broken());
        assertFalse(bad.write(b.toJson()));
        assertTrue(Files.exists(dir.resolve("cameras.json.broken")));
    }

    @Test
    void worldToShipAndBack() {
        // корабль повёрнут: курс 90°, нос задран на 20°, крен 15°
        Quat rot = Quat.yaw(90).mul(new Quat(Math.sin(Math.toRadians(-10)), 0, 0, Math.cos(Math.toRadians(-10))))
                .mul(new Quat(0, 0, Math.sin(Math.toRadians(7.5)), Math.cos(Math.toRadians(7.5)))).normalized();
        double[] c = {1000.5, 64, -300.25};
        double[] local = {1.5, 3, -12};                                       // F5: сзади и сверху
        double[] w = VesselMath.toWorld(local[0], local[1], local[2], c, rot);
        double[] back = VesselMath.toLocal(w[0], w[1], w[2], c, rot);
        for (int i = 0; i < 3; i++) assertEquals(local[i], back[i], 1e-9);
        double dist = Math.sqrt(Math.pow(w[0] - c[0], 2) + Math.pow(w[1] - c[1], 2) + Math.pow(w[2] - c[2], 2));
        assertEquals(Math.sqrt(1.5 * 1.5 + 9 + 144), dist, 1e-9, "поворот не меняет расстояния");
        // без поворота: нос +Z — точка сзади (−Z) в мире тоже сзади
        double[] w0 = VesselMath.toWorld(0, 0, -10, c, Quat.IDENTITY);
        assertEquals(c[2] - 10, w0[2], 1e-9);
        // курс 90° (нос на запад, −X у Minecraft): точка сзади — на востоке (+X)
        double[] w90 = VesselMath.toWorld(0, 0, -10, c, Quat.yaw(90));
        assertEquals(c[0] + 10, w90[0], 1e-6);
    }
}
