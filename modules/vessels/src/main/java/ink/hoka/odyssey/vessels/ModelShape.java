package ink.hoka.odyssey.vessels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Форма модели .bbmodel для сервера: треугольники в позе по умолчанию (пиксели Blockbench), габарит. Правила — те
 * же, что у ядра мода (client/core BbModel + Pose): кости — пивот и эйлер ZYX, кубы — 8 углов с inflate и поворотом
 * вокруг origin, меши — origin + R·v, 4-угольные грани — в порядке Blockbench. Текстуры серверу не нужны.
 */
final class ModelShape {

    /** Треугольники: по 9 чисел (x y z трёх вершин), пиксели модели. */
    final float[] triangles;
    /** Габарит: minX minY minZ maxX maxY maxZ (пиксели). */
    final double[] box;

    private ModelShape(float[] triangles) {
        this.triangles = triangles;
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i < triangles.length; i += 3) {
            for (int a = 0; a < 3; a++) {
                b[a] = Math.min(b[a], triangles[i + a]);
                b[a + 3] = Math.max(b[a + 3], triangles[i + a]);
            }
        }
        if (triangles.length == 0) b = new double[]{-8, 0, -8, 8, 16, 8};
        this.box = b;
    }

    int triangleCount() { return triangles.length / 9; }

    static ModelShape parse(JsonObject root) {
        Map<String, JsonObject> groups = new HashMap<>();
        if (root.has("groups")) for (JsonElement el : root.getAsJsonArray("groups")) {
            JsonObject g = el.getAsJsonObject();
            groups.put(g.get("uuid").getAsString(), g);
        }
        Map<String, JsonObject> elements = new HashMap<>();
        if (root.has("elements")) for (JsonElement el : root.getAsJsonArray("elements")) {
            JsonObject e = el.getAsJsonObject();
            elements.put(e.get("uuid").getAsString(), e);
        }
        List<float[]> out = new ArrayList<>();
        double[] identity = Mat.identity();
        if (root.has("outliner")) for (JsonElement el : root.getAsJsonArray("outliner")) walk(el, identity, groups, elements, out);
        float[] tris = new float[out.size() * 9];
        for (int i = 0; i < out.size(); i++) System.arraycopy(out.get(i), 0, tris, i * 9, 9);
        return new ModelShape(tris);
    }

    private static void walk(JsonElement node, double[] parent, Map<String, JsonObject> groups, Map<String, JsonObject> elements, List<float[]> out) {
        if (node.isJsonPrimitive()) {
            JsonObject e = elements.get(node.getAsString());
            if (e != null) element(e, parent, out);
            return;
        }
        JsonObject n = node.getAsJsonObject();
        JsonObject g = groups.getOrDefault(n.has("uuid") ? n.get("uuid").getAsString() : "", n);   // формат 4: всё в узле
        if (n.has("name") && "hitbox".equals(n.get("name").getAsString())) return;
        if (g.has("name") && "hitbox".equals(g.get("name").getAsString())) return;   // кость hitbox не рисуется — и не форма
        double[] o = vec(g, "origin"), r = vec(g, "rotation");
        double[] m = Mat.mul(parent, Mat.translate(o[0], o[1], o[2]));
        m = Mat.mul(m, Mat.rotZYX(r));
        m = Mat.mul(m, Mat.translate(-o[0], -o[1], -o[2]));
        if (n.has("children")) for (JsonElement c : n.getAsJsonArray("children")) walk(c, m, groups, elements, out);
    }

    private static void element(JsonObject e, double[] bone, List<float[]> out) {
        String type = e.has("type") ? e.get("type").getAsString() : "cube";
        double[] origin = vec(e, "origin"), rot = vec(e, "rotation");
        if ("cube".equals(type)) {
            double[] f = vec(e, "from"), t = vec(e, "to");
            double inf = e.has("inflate") ? e.get("inflate").getAsDouble() : 0;
            double[] m = Mat.mul(bone, Mat.translate(origin[0], origin[1], origin[2]));
            m = Mat.mul(m, Mat.rotZYX(rot));
            m = Mat.mul(m, Mat.translate(-origin[0], -origin[1], -origin[2]));
            double x0 = f[0] - inf, y0 = f[1] - inf, z0 = f[2] - inf, x1 = t[0] + inf, y1 = t[1] + inf, z1 = t[2] + inf;
            double[][] c = new double[8][];
            for (int i = 0; i < 8; i++) c[i] = Mat.apply(m, (i & 1) != 0 ? x1 : x0, (i & 2) != 0 ? y1 : y0, (i & 4) != 0 ? z1 : z0);
            int[][] quads = {{0, 1, 3, 2}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
            for (int[] q : quads) {
                out.add(tri(c[q[0]], c[q[1]], c[q[2]]));
                out.add(tri(c[q[0]], c[q[2]], c[q[3]]));
            }
        } else if ("mesh".equals(type)) {
            JsonObject verts = e.getAsJsonObject("vertices"), faces = e.getAsJsonObject("faces");
            if (verts == null || faces == null) return;
            double[] m = Mat.mul(bone, Mat.translate(origin[0], origin[1], origin[2]));
            m = Mat.mul(m, Mat.rotZYX(rot));
            Map<String, double[]> local = new HashMap<>();
            for (var v : verts.entrySet()) {
                if (!v.getValue().isJsonArray() || v.getValue().getAsJsonArray().size() < 3) continue;
                JsonArray a = v.getValue().getAsJsonArray();
                local.put(v.getKey(), new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()});
            }
            for (var fe : faces.entrySet()) {
                if (!fe.getValue().isJsonObject()) continue;
                JsonArray vs = fe.getValue().getAsJsonObject().getAsJsonArray("vertices");
                if (vs == null || vs.size() < 3) continue;
                List<double[]> p = new ArrayList<>();
                for (JsonElement k : vs) {
                    double[] l = local.get(k.getAsString());
                    if (l == null) { p = null; break; }
                    p.add(l);
                }
                if (p == null) continue;
                if (p.size() == 4) p = sortedQuad(p);
                double[][] w = new double[p.size()][];
                for (int i = 0; i < w.length; i++) w[i] = Mat.apply(m, p.get(i)[0], p.get(i)[1], p.get(i)[2]);
                for (int i = 1; i + 1 < w.length; i++) out.add(tri(w[0], w[i], w[i + 1]));
            }
        }
    }

    /** Порядок обхода 4-угольной грани — как getSortedVertices в Blockbench (и BbModel ядра мода). */
    static List<double[]> sortedQuad(List<double[]> v) {
        if (otherSide(v.get(1), v.get(2), v.get(0), v.get(3))) return List.of(v.get(2), v.get(0), v.get(1), v.get(3));
        if (otherSide(v.get(0), v.get(1), v.get(2), v.get(3))) return List.of(v.get(0), v.get(2), v.get(1), v.get(3));
        return v;
    }

    private static boolean otherSide(double[] b1, double[] b2, double[] top, double[] check) {
        double dx = b2[0] - b1[0], dy = b2[1] - b1[1], dz = b2[2] - b1[2];
        double len2 = dx * dx + dy * dy + dz * dz;
        double t = len2 == 0 ? 0 : ((top[0] - b1[0]) * dx + (top[1] - b1[1]) * dy + (top[2] - b1[2]) * dz) / len2;
        double nx = b1[0] + dx * t - top[0], ny = b1[1] + dy * t - top[1], nz = b1[2] + dz * t - top[2];
        return nx * (check[0] - b2[0]) + ny * (check[1] - b2[1]) + nz * (check[2] - b2[2]) > 0;
    }

    private static float[] tri(double[] a, double[] b, double[] c) {
        return new float[]{(float) a[0], (float) a[1], (float) a[2], (float) b[0], (float) b[1], (float) b[2], (float) c[0], (float) c[1], (float) c[2]};
    }

    private static double[] vec(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return new double[3];
        JsonArray a = o.getAsJsonArray(key);
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    /** Матрицы 4×4 по столбцам (как JOML), только то, что нужно для поз. */
    static final class Mat {
        private Mat() {}

        static double[] identity() {
            return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        }

        static double[] translate(double x, double y, double z) {
            double[] m = identity();
            m[12] = x; m[13] = y; m[14] = z;
            return m;
        }

        /** Эйлер Blockbench: R = Rz · Ry · Rx (градусы). */
        static double[] rotZYX(double[] deg) {
            double[] m = identity();
            if (deg[2] != 0) m = mul(m, rot(2, deg[2]));
            if (deg[1] != 0) m = mul(m, rot(1, deg[1]));
            if (deg[0] != 0) m = mul(m, rot(0, deg[0]));
            return m;
        }

        private static double[] rot(int axis, double deg) {
            double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
            double[] m = identity();
            switch (axis) {
                case 0 -> { m[5] = c; m[6] = s; m[9] = -s; m[10] = c; }
                case 1 -> { m[0] = c; m[2] = -s; m[8] = s; m[10] = c; }
                default -> { m[0] = c; m[1] = s; m[4] = -s; m[5] = c; }
            }
            return m;
        }

        static double[] mul(double[] a, double[] b) {
            double[] r = new double[16];
            for (int col = 0; col < 4; col++)
                for (int row = 0; row < 4; row++) {
                    double s = 0;
                    for (int k = 0; k < 4; k++) s += a[k * 4 + row] * b[col * 4 + k];
                    r[col * 4 + row] = s;
                }
            return r;
        }

        static double[] apply(double[] m, double x, double y, double z) {
            return new double[]{m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14]};
        }
    }
}
