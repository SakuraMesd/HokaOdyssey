package ink.hoka.odyssey.client.vessels;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ink.hoka.odyssey.client.model.BbModel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Луч против коробки и треугольников; тестовая модель corabll — попадания в корпус и промахи мимо. */
class MeshRayTest {

    private static final float[] TRI = {-1, -1, 2, 1, -1, 2, 0, 1, 2};   // треугольник в плоскости z = 2

    @Test
    void triangleHitAndMiss() {
        assertEquals(2, MeshRay.triangle(new double[]{0, 0, 0}, new double[]{0, 0, 1}, TRI, 0), 1e-9);
        assertEquals(2, MeshRay.triangle(new double[]{0, 0, 4}, new double[]{0, 0, -1}, TRI, 0), 1e-9);   // изнанка тоже
        assertEquals(-1, MeshRay.triangle(new double[]{0, 0, 0}, new double[]{0, 0, -1}, TRI, 0));         // позади
        assertEquals(-1, MeshRay.triangle(new double[]{3, 0, 0}, new double[]{0, 0, 1}, TRI, 0));          // мимо
        assertEquals(-1, MeshRay.triangle(new double[]{0, 0, 0}, new double[]{1, 0, 0}, TRI, 0));          // вдоль плоскости
        assertEquals(-1, MeshRay.triangles(new double[]{0, 0, 0}, new double[]{0, 0, 1}, 1.5, TRI));       // дальше max
    }

    @Test
    void box() {
        double[] half = {1, 1, 1};
        assertEquals(4, MeshRay.box(new double[]{0, 0, -5}, new double[]{0, 0, 1}, 6, half), 1e-9);
        assertEquals(0, MeshRay.box(new double[]{0, 0, 0}, new double[]{0, 0, 1}, 6, half), 1e-9);
        assertEquals(-1, MeshRay.box(new double[]{0, 2, -5}, new double[]{0, 0, 1}, 6, half));
        assertEquals(-1, MeshRay.box(new double[]{0, 0, -9}, new double[]{0, 0, 1}, 6, half));
        // коробка есть, треугольников ещё нет — по коробке
        assertEquals(4, MeshRay.hit(new double[]{0, 0, -5}, new double[]{0, 0, 1}, 6, half, null), 1e-9);
    }

    @Test
    void corabll() throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(Path.of("../../../demo-objects/vessels/corabll.bbmodel"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        BbModel m = BbModel.parse("vessels/corabll.bbmodel", root);
        float[] t = m.restTriangles();
        assertEquals(3328 * 9, t.length);
        // габарит треугольников → вид корабля с серединой в центре габарита
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < t.length; i++) { lo[i % 3] = Math.min(lo[i % 3], t[i]); hi[i % 3] = Math.max(hi[i % 3], t[i]); }
        JsonObject o = new JsonObject();
        o.addProperty("model", "vessels/corabll.bbmodel");
        com.google.gson.JsonArray c = new com.google.gson.JsonArray(), h = new com.google.gson.JsonArray();
        for (int a = 0; a < 3; a++) { c.add((lo[a] + hi[a]) / 2); h.add((hi[a] - lo[a]) / 32 + 0.01); }
        o.add("center", c);
        o.add("half", h);
        Kind k = new Kind("corabll", o);
        k.shape(t);
        assertTrue(k.hull.length > 0);
        // все вершины — внутри коробки half
        for (int i = 0; i < k.tris.length; i++) assertTrue(Math.abs(k.tris[i]) <= k.half[i % 3] + 1e-3);
        // луч снаружи в середину — попадает в корпус (не дальше, чем до середины); тот же луч вбок — мимо
        for (int a = 0; a < 3; a++) {
            double[] org = new double[3], dir = new double[3];
            org[a] = -(k.half[a] + 3);
            dir[a] = 1;
            double hit = MeshRay.hit(org, dir, k.half[a] * 2 + 6, k.half, k.tris);
            assertTrue(hit >= 3 - 1e-6 && hit <= k.half[a] + 3, "ось " + a + ": " + hit);
            double[] side = org.clone();
            side[(a + 1) % 3] = k.half[(a + 1) % 3] + 1;
            assertEquals(-1, MeshRay.hit(side, dir, 100, k.half, k.tris));
        }
        // слишком далеко — промах
        assertEquals(-1, MeshRay.hit(new double[]{0, 0, -(k.half[2] + 10)}, new double[]{0, 0, 1}, 6, k.half, k.tris));
    }
}
