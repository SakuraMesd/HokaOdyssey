package ink.hoka.odyssey.vessels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Куда смотрит нос corabll (задача vessels-1b: корабль летел задом наперёд). Части модели узнаём по текстуре:
 * стекло кабины — большие тёмные почти горизонтальные грани посередине (|x| < 1 блока), сопла — голубые грани. Вид берётся из настоящего
 * config.yml модуля, так что тест ловит и неверный turn/seat в конфиге.
 */
class NoseTest {

    /** Грань модели в системе корабля: середина (блоки), нормаль, площадь (пиксели²), цвет текстуры. */
    record Face(double[] c, double[] n, double area, int r, int g, int b, double[][] v) {
        boolean dark() { return Math.max(r, Math.max(g, b)) < 60; }
        boolean blue() { return b > 150 && b > r + 40 && g > 100; }
    }

    static VesselKind kind;
    static List<Face> faces;

    @BeforeAll
    static void load() throws Exception {
        Path p = Path.of(System.getProperty("vessels.demo", "../../demo-objects/vessels"), "corabll.bbmodel");
        JsonObject root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<?, ?> cfg;
        try (InputStream in = NoseTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml модуля");
            Map<?, ?> all = new Yaml().load(in);
            cfg = (Map<?, ?>) ((Map<?, ?>) all.get("kinds")).get("corabll");
        }
        kind = new VesselKind("corabll", cfg, ModelShape.parse(root));
        faces = faces(root, kind);
    }

    @Test
    void cabinIsTheNose() {
        assertEquals(180, kind.turn, 1e-9, "нос corabll в осях модели — −Z");
        double[] glass = centroid(glass()), nozzles = centroid(faces.stream().filter(Face::blue).toList());
        assertTrue(glass[2] > nozzles[2] + 3, "кабина ближе к носу (+Z), чем сопла: стекло z=" + glass[2] + ", сопла z=" + nozzles[2]);
        assertTrue(nozzles[2] < -3, "сопла — в корме: z=" + nozzles[2]);
        // лобовое стекло опускается к носу: у наклонных стёкол ниже та вершина, что ближе к +Z
        int slopes = 0;
        for (Face f : glass()) {
            if (Math.abs(f.n[1]) > 0.99) continue;
            double[] lo = f.v[0], hi = f.v[0];
            for (double[] v : f.v) { if (v[1] < lo[1]) lo = v; if (v[1] > hi[1]) hi = v; }
            if (hi[1] - lo[1] < 0.1) continue;
            assertTrue(lo[2] > hi[2], "лобовое стекло наклонено к носу");
            slopes++;
        }
        assertTrue(slopes >= 2, "наклонное лобовое стекло найдено");
    }

    @Test
    void seatIsInTheCabin() {
        double[] s = kind.seat;
        for (int a = 0; a < 3; a++) assertTrue(Math.abs(s[a]) < kind.half[a], "место пилота внутри коробки модели, ось " + a);
        // глаза — на уровне стекла кабины (не ниже его низа и не выше верха больше чем на 0.25 блока), над ним по z
        double yLo = Double.MAX_VALUE, yHi = -Double.MAX_VALUE, zLo = Double.MAX_VALUE, zHi = -Double.MAX_VALUE, xMax = 0;
        for (Face f : glass()) for (double[] v : f.v) {
            yLo = Math.min(yLo, v[1]); yHi = Math.max(yHi, v[1]);
            zLo = Math.min(zLo, v[2]); zHi = Math.max(zHi, v[2]);
            xMax = Math.max(xMax, Math.abs(v[0]));
        }
        assertTrue(s[1] >= yLo && s[1] <= yHi + 0.25, "глаза на уровне стекла: y=" + s[1] + " ∈ [" + yLo + ", " + yHi + "]");
        assertTrue(s[2] > zLo && s[2] < zHi, "глаза над кабиной по длине: z=" + s[2] + " ∈ (" + zLo + ", " + zHi + ")");
        assertTrue(Math.abs(s[0]) < xMax, "глаза между бортами кабины");
        double nozzles = centroid(faces.stream().filter(Face::blue).toList())[2];
        assertTrue(s[2] > nozzles + 3, "пилот ближе к носу, чем сопла");
        // на месте глаз корабль никуда не смотрит «в стену»: впереди по курсу (+Z) на 3 блока нет граней прямо у глаз
        for (Face f : faces) {
            double dz = f.c[2] - s[2];
            if (dz > 0.2 && dz < 3 && Math.abs(f.c[0] - s[0]) < 0.3 && Math.abs(f.c[1] - s[1]) < 0.15)
                fail("перед глазами пилота грань: " + java.util.Arrays.toString(f.c));
        }
    }

    @Test
    void toShipTurnsAroundVertical() {
        // точка модели на оси −Z (нос) после turn 180 — в +Z корабля; x меняет знак, y — нет
        double[] nose = kind.toShip(kind.centerPx[0] + 16, kind.centerPx[1] + 16, kind.centerPx[2] - 32);
        assertArrayEquals(new double[]{-1, 1, 2}, nose, 1e-9);
    }

    private static List<Face> glass() {
        List<Face> out = faces.stream().filter(f -> f.dark() && Math.abs(f.n[1]) > 0.9 && f.area > 200 && Math.abs(f.c[0]) < 1).toList();
        assertFalse(out.isEmpty(), "стекло кабины найдено");
        return out;
    }

    private static double[] centroid(List<Face> fs) {
        assertFalse(fs.isEmpty());
        double[] c = new double[3];
        double s = 0;
        for (Face f : fs) {
            for (int a = 0; a < 3; a++) c[a] += f.c[a] * f.area;
            s += f.area;
        }
        for (int a = 0; a < 3; a++) c[a] /= s;
        return c;
    }

    // ───────────────────────── грани модели с цветом (как ModelShape, только меши) ─────────────────────────

    static List<Face> faces(JsonObject root, VesselKind k) throws Exception {
        JsonObject tex = root.getAsJsonArray("textures").get(0).getAsJsonObject();
        String src = tex.get("source").getAsString();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(src.substring(src.indexOf(',') + 1))));
        double uw = tex.get("uv_width").getAsDouble(), uh = tex.get("uv_height").getAsDouble();
        Map<String, JsonObject> groups = new HashMap<>(), elements = new HashMap<>();
        for (JsonElement el : root.getAsJsonArray("groups")) groups.put(el.getAsJsonObject().get("uuid").getAsString(), el.getAsJsonObject());
        for (JsonElement el : root.getAsJsonArray("elements")) elements.put(el.getAsJsonObject().get("uuid").getAsString(), el.getAsJsonObject());
        List<Face> out = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("outliner")) walk(el, ModelShape.Mat.identity(), groups, elements, img, uw, uh, k, out);
        return out;
    }

    private static void walk(JsonElement node, double[] parent, Map<String, JsonObject> groups, Map<String, JsonObject> elements,
                             BufferedImage img, double uw, double uh, VesselKind k, List<Face> out) {
        if (node.isJsonPrimitive()) {
            JsonObject e = elements.get(node.getAsString());
            if (e != null && "mesh".equals(e.get("type").getAsString())) mesh(e, parent, img, uw, uh, k, out);
            return;
        }
        JsonObject n = node.getAsJsonObject();
        JsonObject g = groups.getOrDefault(n.get("uuid").getAsString(), n);
        double[] o = vec(g, "origin");
        double[] m = ModelShape.Mat.mul(parent, ModelShape.Mat.translate(o[0], o[1], o[2]));
        m = ModelShape.Mat.mul(m, ModelShape.Mat.rotZYX(vec(g, "rotation")));
        m = ModelShape.Mat.mul(m, ModelShape.Mat.translate(-o[0], -o[1], -o[2]));
        if (n.has("children")) for (JsonElement c : n.getAsJsonArray("children")) walk(c, m, groups, elements, img, uw, uh, k, out);
    }

    private static void mesh(JsonObject e, double[] bone, BufferedImage img, double uw, double uh, VesselKind k, List<Face> out) {
        double[] origin = vec(e, "origin");
        double[] m = ModelShape.Mat.mul(bone, ModelShape.Mat.translate(origin[0], origin[1], origin[2]));
        m = ModelShape.Mat.mul(m, ModelShape.Mat.rotZYX(vec(e, "rotation")));
        JsonObject verts = e.getAsJsonObject("vertices");
        for (var fe : e.getAsJsonObject("faces").entrySet()) {
            JsonObject f = fe.getValue().getAsJsonObject();
            JsonArray vs = f.getAsJsonArray("vertices");
            if (vs.size() < 3) continue;
            double[][] w = new double[vs.size()][];
            double u = 0, v = 0;
            for (int i = 0; i < w.length; i++) {
                String id = vs.get(i).getAsString();
                JsonArray a = verts.getAsJsonArray(id);
                double[] mp = ModelShape.Mat.apply(m, a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
                w[i] = k.toShip(mp[0], mp[1], mp[2]);
                JsonArray uv = f.getAsJsonObject("uv").getAsJsonArray(id);
                u += uv.get(0).getAsDouble() / w.length;
                v += uv.get(1).getAsDouble() / w.length;
            }
            double[] c = new double[3];
            for (double[] p : w) for (int a = 0; a < 3; a++) c[a] += p[a] / w.length;
            double[] ab = sub(w[1], w[0]), ac = sub(w[2], w[0]);
            double[] n = {ab[1] * ac[2] - ab[2] * ac[1], ab[2] * ac[0] - ab[0] * ac[2], ab[0] * ac[1] - ab[1] * ac[0]};
            double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            if (len < 1e-12) continue;
            double area = len / 2 * 256 / (k.scale * k.scale) * (w.length == 4 ? 2 : 1);   // блоки² → пиксели²
            int x = (int) Math.min(img.getWidth() - 1, Math.max(0, u / uw * img.getWidth()));
            int y = (int) Math.min(img.getHeight() - 1, Math.max(0, v / uh * img.getHeight()));
            int rgb = img.getRGB(x, y);
            out.add(new Face(c, new double[]{n[0] / len, n[1] / len, n[2] / len}, area, (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, w));
        }
    }

    private static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }

    private static double[] vec(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return new double[3];
        JsonArray a = o.getAsJsonArray(key);
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }
}
