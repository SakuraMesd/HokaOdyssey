package ink.hoka.odyssey.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Разбор .bbmodel: меши (тестовый корабль corabll), кубы — как раньше, порядок вершин 4-угольных граней. */
class BbModelTest {

    /** Тестовая модель лежит в репозитории: demo-objects/vessels (тесты идут из client/core). */
    static JsonObject corabll() throws Exception {
        Path p = Path.of("../../demo-objects/vessels/corabll.bbmodel");
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void corabllMeshes() throws Exception {
        JsonObject root = corabll();
        assertEquals(280, root.getAsJsonArray("elements").size());
        BbModel m = BbModel.parse("vessels/corabll.bbmodel", root);
        int faces = 0, tris = 0;
        for (BbModel.Bone b : m.bones) {
            faces += b.faces.size();
            for (BbModel.Face f : b.faces) {
                tris += f.triangles();
                assertTrue(f.triangle(), "в corabll только треугольники");
                for (int i = 0; i < 4; i++) {                                    // uv 0..1 (в файле есть −0.0005 пикселя)
                    assertTrue(f.uv()[i * 2] >= -1e-3f && f.uv()[i * 2] <= 1 + 1e-3f, "u " + f.uv()[i * 2]);
                    assertTrue(f.uv()[i * 2 + 1] >= -1e-3f && f.uv()[i * 2 + 1] <= 1 + 1e-3f, "v " + f.uv()[i * 2 + 1]);
                }
                assertArrayEquals(new float[]{f.xyz()[6], f.xyz()[7], f.xyz()[8]}, new float[]{f.xyz()[9], f.xyz()[10], f.xyz()[11]},
                        "треугольник — квад с повторённой вершиной");
            }
        }
        assertEquals(3328, faces);
        assertEquals(3328, tris);
        assertEquals(3328, m.triangleCount());
        assertEquals(1, m.textureCount);
        assertTrue(m.texturePngs.get(0).length > 1000, "текстура вшита в файл");
        // кость «Fighter Spaceship» повёрнута на 90° по X: y' = −z, z' = y (габарит — по python из вершин файла)
        float[] t = m.restTriangles();
        assertEquals(3328 * 9, t.length);
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < t.length; i += 3) for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], t[i + a]); hi[a] = Math.max(hi[a], t[i + a]); }
        assertArrayEquals(new float[]{-56.35371f, 3.83261f, -84.75073f}, lo, 1e-3f);
        assertArrayEquals(new float[]{56.35977f, 44.91533f, 126.8644f}, hi, 1e-3f);
        // hitbox — как раньше: по вершинам в системе кости (без её поворота)
        assertArrayEquals(new float[]{-56.35371f, -84.75073f, -44.91533f, 56.35977f, 126.8644f, -3.83261f}, m.hitbox, 1e-3f);
    }

    /** Кубовая модель (формат 4: группы прямо в outliner) — грани и координаты как до мешей. */
    @Test
    void cubesAsBefore() {
        String json = """
                {"meta":{"format_version":"4.5"},"resolution":{"width":32,"height":32},
                 "textures":[{"name":"t","source":""}],
                 "elements":[
                   {"uuid":"c1","type":"cube","from":[0,0,0],"to":[16,8,4],"origin":[0,0,0],
                    "faces":{"north":{"uv":[0,0,16,8],"texture":0},"south":{"uv":[0,0,16,8],"texture":0},
                             "east":{"uv":[0,0,4,8],"texture":0},"west":{"uv":[0,0,4,8],"texture":0},
                             "up":{"uv":[0,0,16,4],"texture":0},"down":{"uv":[0,0,16,4],"texture":null}}},
                   {"uuid":"c2","from":[-2,0,-2],"to":[2,4,2],"origin":[0,0,0],"rotation":[0,45,0],
                    "faces":{"north":{"uv":[0,0,4,4],"texture":0,"rotation":90},"up":{"uv":[0,0,4,4],"texture":0}}},
                   {"uuid":"l1","type":"locator","position":[0,0,0]}
                 ],
                 "outliner":[{"uuid":"g1","name":"body","origin":[8,0,0],"rotation":[0,0,0],"children":["c1",
                   {"uuid":"g2","name":"head","origin":[0,8,0],"children":["c2","l1"]}]}]}
                """;
        BbModel m = BbModel.parse("cube", JsonParser.parseString(json).getAsJsonObject());
        assertEquals(2, m.bones.size());
        assertEquals(5, m.bone("body").faces.size());                            // down без текстуры
        assertEquals(2, m.bone("head").faces.size());
        assertEquals(14, m.triangleCount());
        BbModel.Face north = m.bone("body").faces.get(0);
        assertFalse(north.triangle());
        assertArrayEquals(new float[]{16, 8, 0, 16, 0, 0, 0, 0, 0, 0, 8, 0}, north.xyz(), 1e-6f);
        assertArrayEquals(new float[]{0, 0, 0, 0.25f, 0.5f, 0.25f, 0.5f, 0}, north.uv(), 1e-6f);
        BbModel.Face rotated = m.bone("head").faces.get(0);                     // uv повёрнут на 90°
        assertArrayEquals(new float[]{0.125f, 0, 0, 0, 0, 0.125f, 0.125f, 0.125f}, rotated.uv(), 1e-6f);
        float s = (float) Math.sqrt(8);                                         // куб 4×4 повёрнут на 45°: угол (2,4,−2) → (0,4,−√8)
        float[] p = rotated.xyz();
        assertEquals(0, p[0], 1e-5f);
        assertEquals(4, p[1], 1e-5f);
        assertEquals(-s, p[2], 1e-5f);
    }

    /** Меш с элементом вне начала координат, поворотом и 4-угольной гранью в «перекрёстном» порядке. */
    @Test
    void meshQuadOrderAndTransform() {
        String json = """
                {"meta":{"format_version":"5.0"},"resolution":{"width":16,"height":16},
                 "textures":[{"name":"t","uv_width":16,"uv_height":16,"source":""}],
                 "elements":[{"uuid":"m1","type":"mesh","origin":[10,0,0],"rotation":[0,90,0],
                   "vertices":{"a":[0,0,0],"b":[4,0,4],"c":[4,0,0],"d":[0,0,4],"e":[0,4,0]},
                   "faces":{"q":{"vertices":["a","b","c","d"],"uv":{"a":[0,0],"b":[16,16],"c":[16,0],"d":[0,16]},"texture":0},
                            "t":{"vertices":["a","c","e"],"uv":{"a":[0,0],"c":[16,0],"e":[0,16]},"texture":0},
                            "x":{"vertices":["a","c","e"],"uv":{},"texture":null}}}],
                 "outliner":["m1"]}
                """;
        BbModel m = BbModel.parse("mesh", JsonParser.parseString(json).getAsJsonObject());
        assertEquals(1, m.bones.size());                                        // корневые элементы — «пустая» кость
        List<BbModel.Face> f = m.bones.get(0).faces;
        assertEquals(2, f.size());
        assertEquals(3, m.triangleCount());
        BbModel.Face q = f.get(0);
        assertFalse(q.triangle());
        // a b c d — диагональ a–b; Blockbench: d по другую сторону b–c от a? нет; по другую сторону a–b от c — да → a c b d
        // проверяем, что квад обходится по периметру: соседние вершины — стороны квадрата (длина 4), не диагонали
        float[] p = q.xyz();
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            double d = Math.sqrt(Math.pow(p[i * 3] - p[j * 3], 2) + Math.pow(p[i * 3 + 1] - p[j * 3 + 1], 2) + Math.pow(p[i * 3 + 2] - p[j * 3 + 2], 2));
            assertEquals(4, d, 1e-4, "ребро " + i + "–" + j);
        }
        // поворот на 90° по Y вокруг начала элемента, потом сдвиг на origin: (4,0,0) → (10, 0, −4)
        boolean found = false;
        for (int i = 0; i < 4; i++) found |= Math.abs(p[i * 3] - 10) < 1e-4 && Math.abs(p[i * 3 + 2] + 4) < 1e-4;
        assertTrue(found);
        // uv — пиксели / uv_width
        for (int i = 0; i < 8; i++) assertTrue(q.uv()[i] == 0 || q.uv()[i] == 1);
    }

    @Test
    void sortedQuadLikeBlockbench() {
        Map<String, float[]> pos = Map.of("a", new float[]{0, 0, 0}, "b", new float[]{1, 0, 0}, "c", new float[]{1, 1, 0}, "d", new float[]{0, 1, 0});
        assertEquals(List.of("a", "b", "c", "d"), BbModel.sortedQuad(List.of("a", "b", "c", "d"), pos));   // уже по периметру
        assertEquals(List.of("d", "a", "b", "c"), BbModel.sortedQuad(List.of("a", "b", "d", "c"), pos));   // диагональ 1–2
        assertEquals(List.of("a", "b", "c", "d"), BbModel.sortedQuad(List.of("a", "c", "b", "d"), pos));   // диагональ 0–1
    }
}
