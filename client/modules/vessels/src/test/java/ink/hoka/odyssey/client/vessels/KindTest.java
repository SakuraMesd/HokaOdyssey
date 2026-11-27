package ink.hoka.odyssey.client.vessels;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ink.hoka.odyssey.client.model.BbModel;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Нос corabll в моде (задача vessels-1b): с turn 180 из конфига сервера кабина — к +Z корабля, сопла — к −Z;
 * отрисовка модели (Kind.modelPose) и треугольники для луча и корпуса (Kind.shape) дают одни и те же точки мира.
 */
class KindTest {

    /** Вид как из пакета KINDS сервера: середина и половины коробки — по габариту модели (при 180° те же), turn и seat — из config.yml. */
    static Kind corabll(float[] t, float turn) {
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < t.length; i++) { lo[i % 3] = Math.min(lo[i % 3], t[i]); hi[i % 3] = Math.max(hi[i % 3], t[i]); }
        JsonObject o = new JsonObject();
        o.addProperty("model", "vessels/corabll.bbmodel");
        o.addProperty("turn", turn);
        JsonArray c = new JsonArray(), h = new JsonArray(), s = new JsonArray();
        for (int a = 0; a < 3; a++) { c.add((lo[a] + hi[a]) / 2); h.add((hi[a] - lo[a]) / 32); }
        s.add(0); s.add(1.04); s.add(-0.18);
        o.add("center", c);
        o.add("half", h);
        o.add("seat", s);
        Kind k = new Kind("corabll", o);
        k.shape(t);
        return k;
    }

    static float[] tris() throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(Path.of("../../../demo-objects/vessels/corabll.bbmodel"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        return BbModel.parse("vessels/corabll.bbmodel", root).restTriangles();
    }

    @Test
    void cabinForwardNozzlesAft() throws Exception {
        Kind k = corabll(tris(), 180);
        // точки модели (пиксели; кость уже повёрнута): лобовое стекло кабины и голубые сопла с пламенем
        Vector3f glass = ship(k, 0, 32, 2), nozzles = ship(k, 0, 25, 110);
        assertTrue(glass.z > 0.5f, "кабина — к носу (+Z): " + glass.z);
        assertTrue(nozzles.z < -4f, "сопла — в корме (−Z): " + nozzles.z);
        // место пилота — в коробке модели
        for (int a = 0; a < 3; a++) assertTrue(Math.abs(k.seat[a]) < k.half[a], "место пилота в коробке, ось " + a);
        // turn 0 (как было) — наоборот
        Kind wrong = corabll(tris(), 0);
        assertTrue(ship(wrong, 0, 32, 2).z < ship(wrong, 0, 25, 110).z);
    }

    @Test
    void renderMatchesShape() throws Exception {
        float[] t = tris();
        Kind k = corabll(t, 180);
        Vector3d at = new Vector3d(100.5, 64.25, -30);
        Quaternionf rot = new Quaternionf().rotationY(0.7f).rotateX(-0.3f).rotateZ(0.4f);   // поза корабля
        Vector3d origin = new Vector3d(at);
        Quaternionf full = new Quaternionf(rot);
        k.modelPose(origin, full);
        float s = k.scale / 16f;
        for (int i = 0; i < t.length; i += 3 * 97) {
            // как рисует ядро: начало модели + поворот модели · (вершина × масштаб)
            Vector3f r = full.transform(new Vector3f(t[i] * s, t[i + 1] * s, t[i + 2] * s));
            // как считают луч и корпус: середина корабля + поворот корабля · вершина в системе корабля
            Vector3f h = rot.transform(new Vector3f(k.tris[i], k.tris[i + 1], k.tris[i + 2]));
            assertEquals(origin.x + r.x, at.x + h.x, 1e-3);
            assertEquals(origin.y + r.y, at.y + h.y, 1e-3);
            assertEquals(origin.z + r.z, at.z + h.z, 1e-3);
        }
        // нос корабля по курсу: при повороте rot вперёд (+Z корабля) смотрит кабина
        Vector3f fwd = rot.transform(new Vector3f(0, 0, 1));
        Vector3f glass = rot.transform(ship(k, 0, 32, 2)), nozzles = rot.transform(ship(k, 0, 25, 110));
        assertTrue(glass.dot(fwd) > nozzles.dot(fwd));
    }

    /** Точка модели (пиксели) → система корабля, той же формулой, что Kind.shape. */
    private static Vector3f ship(Kind k, float x, float y, float z) {
        float s = k.scale / 16f;
        return k.modelTurn().transform(new Vector3f((x - k.center[0]) * s, (y - k.center[1]) * s, (z - k.center[2]) * s));
    }
}
