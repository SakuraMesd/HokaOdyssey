package ink.hoka.odyssey.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Модель Blockbench (.bbmodel, формат 4/5 — как у ModelEngine): кости с пивотами, кубы, текстуры, анимации.
 * Координаты — пиксели Blockbench (16 = блок), оси как в мире Minecraft: x — восток, y — вверх, z — юг.
 * Вершины кубов заранее переведены в систему своей кости (поворот куба «запечён»), так что на кадр остаётся
 * только матрица кости. 0.78: и меши (элементы {@code type: "mesh"}) — грани из 3 и 4 вершин, как в Blockbench.
 */
public final class BbModel {

    /**
     * Грань: 4 вершины в системе кости (пиксели), uv 0..1, номер текстуры. Треугольник меша — квад с повторённой
     * последней вершиной (вершины 2 и 3 совпадают, {@link #triangle()}): конвейер игры рисует квадами.
     */
    public record Face(float[] xyz, float[] uv, int texture, boolean triangle) {
        public Face(float[] xyz, float[] uv, int texture) { this(xyz, uv, texture, false); }

        /** Сколько треугольников в грани (1 у треугольника меша, 2 у квада). */
        public int triangles() { return triangle ? 1 : 2; }
    }

    public static final class Bone {
        public final String name;
        public final String uuid;
        public final Vector3f origin;       // пивот, пиксели
        public final Vector3f rotation;     // эйлер, градусы (порядок ZYX, как в Blockbench)
        public final List<Bone> children = new ArrayList<>();
        public final List<Face> faces = new ArrayList<>();
        public int index;                   // номер кости в {@link #bones} (для массивов поз)

        Bone(String name, String uuid, Vector3f origin, Vector3f rotation) {
            this.name = name;
            this.uuid = uuid;
            this.origin = origin;
            this.rotation = rotation;
        }
    }

    public final String id;
    public final List<Bone> roots = new ArrayList<>();
    public final List<Bone> bones = new ArrayList<>();
    public final Map<String, BbAnimation> animations = new HashMap<>();
    public final List<byte[]> texturePngs = new ArrayList<>();
    public int textureCount;
    /** Габарит кости «hitbox» (или всей модели) в позе по умолчанию, пиксели: minX,minY,minZ,maxX,maxY,maxZ. */
    public final float[] hitbox = new float[6];

    private BbModel(String id) {
        this.id = id;
    }

    public Bone bone(String name) {
        for (Bone b : bones) if (b.name.equals(name)) return b;
        return null;
    }

    public static BbModel parse(String id, JsonObject root) {
        BbModel m = new BbModel(id);
        JsonObject res = root.has("resolution") ? root.getAsJsonObject("resolution") : null;
        float resW = res != null ? res.get("width").getAsFloat() : 16f;
        float resH = res != null ? res.get("height").getAsFloat() : 16f;

        // текстуры: размер uv у каждой свой (uv_width/uv_height), иначе общий resolution
        List<float[]> texUv = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("textures")) {
            JsonObject t = el.getAsJsonObject();
            String src = t.has("source") ? t.get("source").getAsString() : "";
            int comma = src.indexOf(',');
            m.texturePngs.add(comma > 0 ? java.util.Base64.getDecoder().decode(src.substring(comma + 1)) : new byte[0]);
            float uw = t.has("uv_width") ? t.get("uv_width").getAsFloat() : resW;
            float uh = t.has("uv_height") ? t.get("uv_height").getAsFloat() : resH;
            texUv.add(new float[]{uw, uh});
        }
        m.textureCount = texUv.size();

        // группы (формат 5: отдельный список; формат 4: прямо в outliner)
        Map<String, JsonObject> groups = new HashMap<>();
        if (root.has("groups")) {
            for (JsonElement el : root.getAsJsonArray("groups")) {
                JsonObject g = el.getAsJsonObject();
                groups.put(g.get("uuid").getAsString(), g);
            }
        }
        Map<String, JsonObject> elements = new HashMap<>();
        for (JsonElement el : root.getAsJsonArray("elements")) {
            JsonObject e = el.getAsJsonObject();
            elements.put(e.get("uuid").getAsString(), e);
        }

        Bone virtual = new Bone("", "", new Vector3f(), new Vector3f());   // корневые кубы без группы
        for (JsonElement el : root.getAsJsonArray("outliner")) {
            if (el.isJsonPrimitive()) addElement(virtual, elements.get(el.getAsString()), texUv);
            else m.roots.add(readBone(m, el.getAsJsonObject(), groups, elements, texUv));
        }
        if (!virtual.faces.isEmpty()) m.roots.add(0, virtual);
        for (Bone b : m.roots) index(m, b);

        if (root.has("animations")) {
            boolean legacy = legacyKeys(root);
            for (JsonElement el : root.getAsJsonArray("animations")) {
                BbAnimation a = BbAnimation.parse(el.getAsJsonObject(), m, legacy);
                m.animations.put(a.name, a);
            }
        }
        m.computeHitbox();
        return m;
    }

    /** Файлы до формата 5.0 хранят ключи поворота x/y и сдвига x с обратным знаком (Blockbench при открытии
     *  такого файла переворачивает их сам) — тогда переворачиваем и мы. */
    private static boolean legacyKeys(JsonObject root) {
        JsonObject meta = root.has("meta") ? root.getAsJsonObject("meta") : null;
        if (meta == null || !meta.has("format_version")) return true;
        try {
            return Integer.parseInt(meta.get("format_version").getAsString().split("\\.")[0]) < 5;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Bone readBone(BbModel m, JsonObject node, Map<String, JsonObject> groups,
                                 Map<String, JsonObject> elements, List<float[]> texUv) {
        String uuid = node.get("uuid").getAsString();
        JsonObject g = groups.getOrDefault(uuid, node);   // формат 4: всё в самом узле
        Bone b = new Bone(g.has("name") ? g.get("name").getAsString() : uuid, uuid, vec(g, "origin"), vec(g, "rotation"));
        for (JsonElement c : node.getAsJsonArray("children")) {
            if (c.isJsonPrimitive()) addElement(b, elements.get(c.getAsString()), texUv);
            else b.children.add(readBone(m, c.getAsJsonObject(), groups, elements, texUv));
        }
        return b;
    }

    private static void index(BbModel m, Bone b) {
        b.index = m.bones.size();
        m.bones.add(b);
        for (Bone c : b.children) index(m, c);
    }

    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};

    /** Элемент модели: куб или меш (остальное — локаторы, нули — не рисуется). */
    private static void addElement(Bone bone, JsonObject e, List<float[]> texUv) {
        if (e == null) return;
        String type = e.has("type") ? e.get("type").getAsString() : "cube";
        if ("cube".equals(type)) addCube(bone, e, texUv);
        else if ("mesh".equals(type)) addMesh(bone, e, texUv);
    }

    /**
     * Меш → грани в системе кости. Как в Blockbench: вершины — в системе элемента, мир = origin + R(rotation)·v
     * (эйлер ZYX); uv грани — по вершинам, в пикселях текстуры (uv_width/uv_height, как у кубов). Грань из 3 вершин —
     * треугольник (квад с повторённой вершиной), из 4 — квад в порядке {@link #sortedQuad} (Blockbench не хранит
     * порядок обхода). Больше 4 вершин в Blockbench не бывает; встретится — веером треугольников.
     */
    private static void addMesh(Bone bone, JsonObject e, List<float[]> texUv) {
        JsonObject verts = e.getAsJsonObject("vertices");
        JsonObject faces = e.getAsJsonObject("faces");
        if (verts == null || faces == null) return;
        Vector3f origin = vec(e, "origin");
        Vector3f rot = vec(e, "rotation");
        Matrix4f mat = new Matrix4f().translate(origin);
        rotateZYX(mat, rot);
        Map<String, float[]> local = new HashMap<>();
        for (Map.Entry<String, JsonElement> v : verts.entrySet()) {
            if (!v.getValue().isJsonArray()) continue;
            JsonArray a = v.getValue().getAsJsonArray();
            if (a.size() < 3) continue;
            local.put(v.getKey(), new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()});
        }
        Vector3f p = new Vector3f();
        for (Map.Entry<String, JsonElement> fe : faces.entrySet()) {
            if (!fe.getValue().isJsonObject()) continue;
            JsonObject face = fe.getValue().getAsJsonObject();
            int tex = textureIndex(face);
            if (tex < 0 || tex >= texUv.size()) continue;
            JsonArray vs = face.getAsJsonArray("vertices");
            JsonObject uvs = face.has("uv") && face.get("uv").isJsonObject() ? face.getAsJsonObject("uv") : new JsonObject();
            if (vs == null || vs.size() < 3) continue;
            List<String> keys = new ArrayList<>(vs.size());
            boolean ok = true;
            for (JsonElement k : vs) {
                String key = k.getAsString();
                if (!local.containsKey(key)) { ok = false; break; }
                keys.add(key);
            }
            if (!ok) continue;
            if (keys.size() == 4) keys = sortedQuad(keys, local);
            float[] tw = texUv.get(tex);
            // все точки грани: мир кости и uv 0..1
            int n = keys.size();
            float[][] wp = new float[n][];
            float[][] tp = new float[n][];
            for (int i = 0; i < n; i++) {
                float[] l = local.get(keys.get(i));
                mat.transformPosition(l[0], l[1], l[2], p);
                wp[i] = new float[]{p.x, p.y, p.z};
                float u = 0, v = 0;
                JsonElement uv = uvs.get(keys.get(i));
                if (uv != null && uv.isJsonArray() && uv.getAsJsonArray().size() >= 2) {
                    u = uv.getAsJsonArray().get(0).getAsFloat() / tw[0];
                    v = uv.getAsJsonArray().get(1).getAsFloat() / tw[1];
                }
                tp[i] = new float[]{u, v};
            }
            if (n == 4) bone.faces.add(meshFace(wp, tp, 0, 1, 2, 3, tex, false));
            else for (int i = 1; i + 1 < n; i++) bone.faces.add(meshFace(wp, tp, 0, i, i + 1, i + 1, tex, true));
        }
    }

    private static Face meshFace(float[][] wp, float[][] tp, int a, int b, int c, int d, int tex, boolean tri) {
        int[] idx = {a, b, c, d};
        float[] xyz = new float[12];
        float[] uv = new float[8];
        for (int i = 0; i < 4; i++) {
            System.arraycopy(wp[idx[i]], 0, xyz, i * 3, 3);
            System.arraycopy(tp[idx[i]], 0, uv, i * 2, 2);
        }
        return new Face(xyz, uv, tex, tri);
    }

    /** Номер текстуры грани: число (форматы 4/5); null/false/прочее — грань без текстуры (не рисуется). */
    private static int textureIndex(JsonObject face) {
        if (!face.has("texture")) return -1;
        JsonElement t = face.get("texture");
        if (!t.isJsonPrimitive() || !t.getAsJsonPrimitive().isNumber()) return -1;
        return t.getAsInt();
    }

    /**
     * Порядок обхода 4-вершинной грани — как MeshFace.getSortedVertices в Blockbench (mesh.js): если вершина 3
     * лежит по другую сторону прямой 1–2, чем вершина 0, — порядок [2, 0, 1, 3]; иначе если она по другую сторону
     * прямой 0–1, чем 2, — [0, 2, 1, 3]; иначе как в файле.
     */
    static List<String> sortedQuad(List<String> v, Map<String, float[]> pos) {
        float[] p0 = pos.get(v.get(0)), p1 = pos.get(v.get(1)), p2 = pos.get(v.get(2)), p3 = pos.get(v.get(3));
        if (otherSide(p1, p2, p0, p3)) return List.of(v.get(2), v.get(0), v.get(1), v.get(3));
        if (otherSide(p0, p1, p2, p3)) return List.of(v.get(0), v.get(2), v.get(1), v.get(3));
        return v;
    }

    /**
     * Blockbench: плоскость через base1–base2 с нормалью от top к ближайшей точке прямой base1–base2;
     * check по другую сторону прямой от top — расстояние до плоскости > 0.
     */
    static boolean otherSide(float[] base1, float[] base2, float[] top, float[] check) {
        double dx = base2[0] - base1[0], dy = base2[1] - base1[1], dz = base2[2] - base1[2];
        double len2 = dx * dx + dy * dy + dz * dz;
        double t = len2 == 0 ? 0 : ((top[0] - base1[0]) * dx + (top[1] - base1[1]) * dy + (top[2] - base1[2]) * dz) / len2;
        double nx = base1[0] + dx * t - top[0], ny = base1[1] + dy * t - top[1], nz = base1[2] + dz * t - top[2];
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl == 0) return false;                                       // top на прямой: three.js даёт нормаль 0 → расстояние 0
        nx /= nl; ny /= nl; nz /= nl;
        double dist = nx * (check[0] - base2[0]) + ny * (check[1] - base2[1]) + nz * (check[2] - base2[2]);
        return dist > 0;
    }

    /** Куб → 6 граней в системе кости (с учётом поворота куба вокруг его origin и inflate). */
    private static void addCube(Bone bone, JsonObject e, List<float[]> texUv) {
        // "visibility" — лишь глазик в редакторе Blockbench, ModelEngine его не учитывает (огурец весь «скрыт»)
        Vector3f from = vec(e, "from"), to = vec(e, "to");
        float inf = e.has("inflate") ? e.get("inflate").getAsFloat() : 0f;
        from.sub(inf, inf, inf);
        to.add(inf, inf, inf);
        Vector3f origin = vec(e, "origin");
        Vector3f rot = vec(e, "rotation");
        Matrix4f mat = new Matrix4f().translate(origin);
        rotateZYX(mat, rot);
        mat.translate(-origin.x, -origin.y, -origin.z);

        JsonObject faces = e.getAsJsonObject("faces");
        if (faces == null) return;
        float x0 = from.x, y0 = from.y, z0 = from.z, x1 = to.x, y1 = to.y, z1 = to.z;
        for (String f : FACES) {
            JsonObject face = faces.getAsJsonObject(f);
            if (face == null || !face.has("texture") || face.get("texture").isJsonNull()) continue;
            int tex = face.get("texture").getAsInt();
            if (tex < 0 || tex >= texUv.size()) continue;
            JsonArray uvA = face.getAsJsonArray("uv");
            float[] tw = texUv.get(tex);
            float u0 = uvA.get(0).getAsFloat() / tw[0], v0 = uvA.get(1).getAsFloat() / tw[1];
            float u1 = uvA.get(2).getAsFloat() / tw[0], v1 = uvA.get(3).getAsFloat() / tw[1];
            // углы грани по часовой, если смотреть на неё снаружи: верх-лево, низ-лево, низ-право, верх-право
            float[][] q = switch (f) {
                case "north" -> new float[][]{{x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}};
                case "south" -> new float[][]{{x0, y1, z1}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}};
                case "east" -> new float[][]{{x1, y1, z1}, {x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}};
                case "west" -> new float[][]{{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}};
                case "up" -> new float[][]{{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
                default -> new float[][]{{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};   // down
            };
            float[][] uv = {{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}};
            int r = face.has("rotation") ? (face.get("rotation").getAsInt() / 90) & 3 : 0;
            float[] xyz = new float[12];
            float[] uvs = new float[8];
            Vector3f p = new Vector3f();
            for (int i = 0; i < 4; i++) {
                mat.transformPosition(q[i][0], q[i][1], q[i][2], p);
                xyz[i * 3] = p.x;
                xyz[i * 3 + 1] = p.y;
                xyz[i * 3 + 2] = p.z;
                float[] t = uv[(i + 4 - r) & 3];   // поворот uv грани на 90°·r
                uvs[i * 2] = t[0];
                uvs[i * 2 + 1] = t[1];
            }
            bone.faces.add(new Face(xyz, uvs, tex));
        }
    }

    /** Эйлер Blockbench: R = Rz · Ry · Rx (градусы). */
    static void rotateZYX(Matrix4f m, Vector3f deg) {
        if (deg.z != 0) m.rotateZ((float) Math.toRadians(deg.z));
        if (deg.y != 0) m.rotateY((float) Math.toRadians(deg.y));
        if (deg.x != 0) m.rotateX((float) Math.toRadians(deg.x));
    }

    private static Vector3f vec(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return new Vector3f();
        JsonArray a = o.getAsJsonArray(key);
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }

    /** Сколько всего треугольников в гранях (квад — 2, треугольник меша — 1). */
    public int triangleCount() {
        int n = 0;
        for (Bone b : bones) for (Face f : b.faces) n += f.triangles();
        return n;
    }

    /**
     * 0.78: все треугольники модели в позе по умолчанию (пиксели модели, кости уже применены; кость «hitbox» не
     * входит — её не рисуют): по 9 чисел (x y z трёх вершин). Для попаданий лучом по форме модели.
     */
    public float[] restTriangles() {
        Matrix4f[] rest = new Matrix4f[bones.size()];
        Pose.compute(this, null, -1, rest);
        int n = 0;
        for (Bone b : bones) if (!"hitbox".equals(b.name)) for (Face f : b.faces) n += f.triangles();
        float[] out = new float[n * 9];
        Vector3f p = new Vector3f();
        int k = 0;
        int[][] tris = {{0, 1, 2}, {0, 2, 3}};
        for (Bone b : bones) {
            if ("hitbox".equals(b.name)) continue;
            Matrix4f m = rest[b.index] != null ? rest[b.index] : new Matrix4f();
            for (Face f : b.faces) {
                for (int t = 0; t < f.triangles(); t++) {
                    for (int v : tris[t]) {
                        m.transformPosition(f.xyz[v * 3], f.xyz[v * 3 + 1], f.xyz[v * 3 + 2], p);
                        out[k++] = p.x;
                        out[k++] = p.y;
                        out[k++] = p.z;
                    }
                }
            }
        }
        return out;
    }

    private void computeHitbox() {
        Bone hb = bone("hitbox");
        List<Bone> src = hb != null && !hb.faces.isEmpty() ? List.of(hb) : bones;
        float[] h = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Bone b : src) {
            for (Face f : b.faces) {
                for (int i = 0; i < 4; i++) {
                    for (int a = 0; a < 3; a++) {
                        h[a] = Math.min(h[a], f.xyz[i * 3 + a]);
                        h[a + 3] = Math.max(h[a + 3], f.xyz[i * 3 + a]);
                    }
                }
            }
        }
        if (h[0] > h[3]) h = new float[]{-6, 0, -6, 6, 16, 6};
        System.arraycopy(h, 0, hitbox, 0, 6);
    }
}
