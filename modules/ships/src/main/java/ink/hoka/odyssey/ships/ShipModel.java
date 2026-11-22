package ink.hoka.odyssey.ships;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minestom.server.instance.block.Block;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * Модель всего корабля одним предметом для ресурспака: все видимые блоки чертежа с их ванильной формой и текстурами
 * (blockstate → модель → родители, повороты варианта x/y), грани, закрытые соседним полным блоком корабля, выброшены.
 *
 * <p>Координаты — относительно точки пилота (центр клетки места пилота, ноги на seatH над блоком) при повороте
 * чертежа 0. Формат модели допускает координаты только от −16 до 32 (3 блока), поэтому модель уменьшена в
 * {@link #scale} раз, а сущность увеличивает её обратно — в полёте нужна одна сущность и только поворот.
 */
final class ShipModel {

    /** JSON модели (assets/hoka_ships/models/ship/&lt;имя&gt;.json). */
    final String modelJson;
    /** JSON определения предмета (assets/hoka_ships/items/&lt;имя&gt;.json). */
    final String itemJson;
    /** Во сколько раз модель уменьшена (масштаб сущности). */
    final float scale;
    final int elements;

    private ShipModel(String modelJson, String itemJson, float scale, int elements) {
        this.modelJson = modelJson; this.itemJson = itemJson; this.scale = scale; this.elements = elements;
    }

    static final String NS = "hoka_ships";

    static String modelPath(String name) { return "assets/" + NS + "/models/ship/" + name + ".json"; }
    static String itemPath(String name) { return "assets/" + NS + "/items/" + name + ".json"; }
    static String itemModel(String name) { return NS + ":" + name; }

    // ─────────────────────────────────────────────────────────────────────────────

    /** Элемент модели в координатах «блок 0..16». */
    private record Elem(double[] from, double[] to, JsonObject rotation, Map<String, JsonObject> faces) {}

    /**
     * Собрать модель. vanilla — чтение файлов ванильного клиента ("assets/minecraft/…") или null, если нет.
     */
    static ShipModel build(ShipTemplate t, double seatH, Function<String, byte[]> vanilla) {
        Resolver res = new Resolver(vanilla);
        Map<Long, Block> cells = new HashMap<>();
        for (int y = 0; y < t.sy; y++) for (int z = 0; z < t.sz; z++) for (int x = 0; x < t.sx; x++) {
            Block b = t.block(x, y, z);
            if (!b.isAir()) cells.put(key(x, y, z), b);
        }
        // сначала все элементы в «мировых» единицах относительно пилота (1 блок = 1.0), потом масштаб
        List<double[]> boxes = new ArrayList<>();                               // для границ
        List<JsonObject> out = new ArrayList<>();
        Map<String, String> texKeys = new LinkedHashMap<>();
        int sx = t.seat[0], sy = t.seat[1], sz = t.seat[2];
        List<Object[]> pending = new ArrayList<>();                             // [Elem, dx, dy, dz]
        for (var c : cells.entrySet()) {
            int[] p = unkey(c.getKey());
            Block b = c.getValue();
            for (Elem e : res.elements(b)) {
                Map<String, JsonObject> faces = new LinkedHashMap<>();
                for (var f : e.faces.entrySet()) {
                    JsonObject face = f.getValue();
                    if (face.has("cullface")) {
                        int[] d = dir(face.get("cullface").getAsString());
                        Block n = d == null ? null : cells.get(key(p[0] + d[0], p[1] + d[1], p[2] + d[2]));
                        if (n != null && n.registry().occludes()) continue;        // закрыта соседом
                    }
                    faces.put(f.getKey(), face);
                }
                if (faces.isEmpty()) continue;
                pending.add(new Object[]{new Elem(e.from, e.to, e.rotation, faces), p[0] - sx, p[1] - sy, p[2] - sz});
            }
        }
        // границы → масштаб
        double maxAbs = 0.5;
        for (Object[] o : pending) {
            Elem e = (Elem) o[0];
            int dx = (int) o[1], dy = (int) o[2], dz = (int) o[3];
            for (double[] v : new double[][]{e.from, e.to}) {
                maxAbs = Math.max(maxAbs, Math.abs(dx + v[0] / 16 - 0.5));
                maxAbs = Math.max(maxAbs, Math.abs(dy + v[1] / 16 - seatH));
                maxAbs = Math.max(maxAbs, Math.abs(dz + v[2] / 16 - 0.5));
            }
        }
        float scale = (float) Math.max(1.0, Math.ceil(maxAbs * 16 / 23.5 * 100) / 100);  // ±23.5 вокруг 8 — в пределах −16..32
        double k = 16 / scale;
        for (Object[] o : pending) {
            Elem e = (Elem) o[0];
            int dx = (int) o[1], dy = (int) o[2], dz = (int) o[3];
            JsonObject el = new JsonObject();
            el.add("from", vec(8 + (dx + e.from[0] / 16 - 0.5) * k, 8 + (dy + e.from[1] / 16 - seatH) * k, 8 + (dz + e.from[2] / 16 - 0.5) * k));
            el.add("to", vec(8 + (dx + e.to[0] / 16 - 0.5) * k, 8 + (dy + e.to[1] / 16 - seatH) * k, 8 + (dz + e.to[2] / 16 - 0.5) * k));
            if (e.rotation != null) {
                JsonObject r = e.rotation.deepCopy();
                JsonArray or = r.getAsJsonArray("origin");
                r.add("origin", vec(8 + (dx + or.get(0).getAsDouble() / 16 - 0.5) * k, 8 + (dy + or.get(1).getAsDouble() / 16 - seatH) * k,
                        8 + (dz + or.get(2).getAsDouble() / 16 - 0.5) * k));
                el.add("rotation", r);
            }
            JsonObject faces = new JsonObject();
            for (var f : e.faces.entrySet()) {
                JsonObject face = f.getValue().deepCopy();
                face.remove("cullface");                                         // в предмете cullface не нужен
                String tex = face.get("texture").getAsString();
                String key = texKeys.computeIfAbsent(tex, x -> "t" + texKeys.size());
                face.addProperty("texture", "#" + key);
                faces.add(f.getKey(), face);
            }
            el.add("faces", faces);
            el.addProperty("shade", true);
            out.add(el);
        }
        JsonObject model = new JsonObject();
        JsonObject textures = new JsonObject();
        for (var e : texKeys.entrySet()) textures.addProperty(e.getValue(), e.getKey());
        if (!texKeys.isEmpty()) textures.addProperty("particle", texKeys.keySet().iterator().next());
        model.add("textures", textures);
        JsonArray els = new JsonArray();
        for (JsonObject o : out) els.add(o);
        model.add("elements", els);

        JsonObject item = new JsonObject();
        JsonObject m = new JsonObject();
        m.addProperty("type", "minecraft:model");
        m.addProperty("model", NS + ":ship/" + t.name);
        JsonArray tints = new JsonArray();                                       // трава/листва: постоянный зелёный
        for (int i = 0; i < 3; i++) {
            JsonObject tint = new JsonObject();
            tint.addProperty("type", "minecraft:constant");
            tint.addProperty("value", 0x79C05A);
            tints.add(tint);
        }
        m.add("tints", tints);
        item.add("model", m);
        return new ShipModel(model.toString(), item.toString(), scale, out.size());
    }

    private static JsonArray vec(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(round(x)); a.add(round(y)); a.add(round(z));
        return a;
    }

    private static double round(double v) { return Math.round(v * 10000) / 10000.0; }

    private static long key(int x, int y, int z) { return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF); }

    private static int[] unkey(long k) {
        return new int[]{(int) (k >> 42) & 0x1FFFFF, (int) (k >> 21) & 0x1FFFFF, (int) k & 0x1FFFFF};
    }

    private static int[] dir(String d) {
        return switch (d) {
            case "north" -> new int[]{0, 0, -1}; case "south" -> new int[]{0, 0, 1};
            case "west" -> new int[]{-1, 0, 0}; case "east" -> new int[]{1, 0, 0};
            case "up" -> new int[]{0, 1, 0}; case "down" -> new int[]{0, -1, 0};
            default -> null;
        };
    }

    // ───────────────────────────── ванильные модели ─────────────────────────────

    /** blockstate → варианты → модели (с родителями) → элементы с применённым поворотом x/y. С кэшем. */
    static final class Resolver {
        private final Function<String, byte[]> vanilla;
        private final Map<String, JsonObject> json = new HashMap<>();
        private final Map<String, List<Elem>> byState = new HashMap<>();

        Resolver(Function<String, byte[]> vanilla) { this.vanilla = vanilla; }

        private JsonObject read(String path) {
            return json.computeIfAbsent(path, p -> {
                byte[] b = vanilla == null ? null : vanilla.apply(p);
                return b == null ? null : JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
            });
        }

        List<Elem> elements(Block b) {
            return byState.computeIfAbsent(b.state(), s -> resolve(b));
        }

        private List<Elem> resolve(Block b) {
            String name = b.key().value();
            JsonObject bs = read("assets/minecraft/blockstates/" + name + ".json");
            List<JsonObject> applies = new ArrayList<>();
            if (bs != null && bs.has("variants")) {
                for (var v : bs.getAsJsonObject("variants").entrySet()) {
                    if (matchesVariant(v.getKey(), b)) { applies.add(first(v.getValue())); break; }
                }
            } else if (bs != null && bs.has("multipart")) {
                for (JsonElement part : bs.getAsJsonArray("multipart")) {
                    JsonObject po = part.getAsJsonObject();
                    if (!po.has("when") || matchesWhen(po.getAsJsonObject("when"), b)) applies.add(first(po.get("apply")));
                }
            }
            List<Elem> out = new ArrayList<>();
            for (JsonObject a : applies) {
                Model m = model(a.get("model").getAsString());
                int rx = a.has("x") ? a.get("x").getAsInt() : 0, ry = a.has("y") ? a.get("y").getAsInt() : 0;
                boolean uvlock = a.has("uvlock") && a.get("uvlock").getAsBoolean();
                List<Elem> els = m.elements;
                if (els.isEmpty()) els = List.of(cube(m.particle()));            // сундук, табличка… — кубом с их текстурой
                for (Elem e : els) out.add(rotate(e, rx, ry, uvlock));
            }
            if (out.isEmpty() && !b.registry().isReplaceable())                  // нет описания — серый куб
                out.add(cube("minecraft:block/stone"));
            return out;
        }

        private static JsonObject first(JsonElement e) { return e.isJsonArray() ? e.getAsJsonArray().get(0).getAsJsonObject() : e.getAsJsonObject(); }

        private static boolean matchesVariant(String key, Block b) {
            if (key.isEmpty()) return true;
            for (String kv : key.split(",")) {
                String[] p = kv.split("=", 2);
                if (p.length != 2 || !p[1].equals(b.getProperty(p[0]))) return false;
            }
            return true;
        }

        private static boolean matchesWhen(JsonObject when, Block b) {
            if (when.has("OR")) { for (JsonElement o : when.getAsJsonArray("OR")) if (matchesWhen(o.getAsJsonObject(), b)) return true; return false; }
            if (when.has("AND")) { for (JsonElement o : when.getAsJsonArray("AND")) if (!matchesWhen(o.getAsJsonObject(), b)) return false; return true; }
            for (var e : when.entrySet()) {
                String v = b.getProperty(e.getKey());
                boolean ok = false;
                for (String alt : e.getValue().getAsString().split("\\|")) if (alt.equals(v)) { ok = true; break; }
                if (!ok) return false;
            }
            return true;
        }

        private record Model(List<Elem> elements, Map<String, String> textures) {
            String particle() {
                String p = Resolver.tex("#particle", textures);
                if (p == null) for (String v : textures.values()) if (!v.startsWith("#")) return v;
                return p == null ? "minecraft:block/stone" : p;
            }
        }

        private final Map<String, Model> models = new HashMap<>();

        private Model model(String id) {
            Model cached = models.get(id);
            if (cached != null) return cached;
            Map<String, String> textures = new HashMap<>();
            JsonArray elements = null;
            String cur = id;
            for (int depth = 0; cur != null && depth < 16; depth++) {
                JsonObject m = read("assets/minecraft/models/" + path(cur) + ".json");
                if (m == null) break;
                if (m.has("textures")) for (var e : m.getAsJsonObject("textures").entrySet()) textures.putIfAbsent(e.getKey(), e.getValue().getAsString());
                if (elements == null && m.has("elements")) elements = m.getAsJsonArray("elements");
                cur = m.has("parent") ? m.get("parent").getAsString() : null;
            }
            List<Elem> els = new ArrayList<>();
            if (elements != null) for (JsonElement je : elements) {
                JsonObject e = je.getAsJsonObject();
                Map<String, JsonObject> faces = new LinkedHashMap<>();
                for (var f : e.getAsJsonObject("faces").entrySet()) {
                    JsonObject face = f.getValue().getAsJsonObject().deepCopy();
                    String t = tex(face.get("texture").getAsString(), textures);
                    if (t == null) continue;
                    face.addProperty("texture", t);
                    // UV явно: иначе игра выводит его из положения элемента, а мы элементы сдвигаем и уменьшаем
                    if (!face.has("uv")) face.add("uv", defaultUv(f.getKey(), arr(e.getAsJsonArray("from")), arr(e.getAsJsonArray("to"))));
                    faces.put(f.getKey(), face);
                }
                els.add(new Elem(arr(e.getAsJsonArray("from")), arr(e.getAsJsonArray("to")),
                        e.has("rotation") ? e.getAsJsonObject("rotation") : null, faces));
            }
            Model m = new Model(els, textures);
            models.put(id, m);
            return m;
        }

        /** "minecraft:block/stone" / "block/stone" → "block/stone". */
        private static String path(String id) { int i = id.indexOf(':'); return i >= 0 ? id.substring(i + 1) : id; }

        /** "#side" → конечная текстура (через цепочку ссылок) с пространством имён. */
        static String tex(String t, Map<String, String> textures) {
            for (int i = 0; i < 10 && t != null && t.startsWith("#"); i++) t = textures.get(t.substring(1));
            if (t == null || t.startsWith("#")) return null;
            return t.contains(":") ? t : "minecraft:" + t;
        }

        /** UV по умолчанию, как у игры (BlockElement.uvsByFace): из положения элемента для данной грани. */
        static JsonArray defaultUv(String face, double[] f, double[] t) {
            double[] uv = switch (face) {
                case "down" -> new double[]{f[0], 16 - t[2], t[0], 16 - f[2]};
                case "up" -> new double[]{f[0], f[2], t[0], t[2]};
                case "north" -> new double[]{16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]};
                case "south" -> new double[]{f[0], 16 - t[1], t[0], 16 - f[1]};
                case "west" -> new double[]{f[2], 16 - t[1], t[2], 16 - f[1]};
                default -> new double[]{16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]};   // east
            };
            JsonArray a = new JsonArray();
            for (double v : uv) a.add(v);
            return a;
        }

        private static double[] arr(JsonArray a) { return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()}; }

        private static Elem cube(String texture) {
            Map<String, JsonObject> faces = new LinkedHashMap<>();
            for (String d : new String[]{"north", "south", "east", "west", "up", "down"}) {
                JsonObject f = new JsonObject();
                f.addProperty("texture", texture);
                f.addProperty("cullface", d);
                f.add("uv", defaultUv(d, new double[]{0, 0, 0}, new double[]{16, 16, 16}));
                faces.put(d, f);
            }
            return new Elem(new double[]{0, 0, 0}, new double[]{16, 16, 16}, null, faces);
        }

        /**
         * Поворот варианта как в игре: R = RotY(−y) · RotX(−x) вокруг центра блока (8,8,8). Переносятся углы,
         * направления граней, cullface и ось/угол поворота элемента.
         */
        static Elem rotate(Elem e, int rx, int ry, boolean uvlock) {
            if (rx % 360 == 0 && ry % 360 == 0) return e;
            double[] a = turn(new double[]{e.from[0] - 8, e.from[1] - 8, e.from[2] - 8}, rx, ry);
            double[] b = turn(new double[]{e.to[0] - 8, e.to[1] - 8, e.to[2] - 8}, rx, ry);
            double[] from = {Math.min(a[0], b[0]) + 8, Math.min(a[1], b[1]) + 8, Math.min(a[2], b[2]) + 8};
            double[] to = {Math.max(a[0], b[0]) + 8, Math.max(a[1], b[1]) + 8, Math.max(a[2], b[2]) + 8};
            Map<String, JsonObject> faces = new LinkedHashMap<>();
            for (var f : e.faces.entrySet()) {
                JsonObject face = f.getValue().deepCopy();
                if (face.has("cullface")) face.addProperty("cullface", turnDir(face.get("cullface").getAsString(), rx, ry));
                String nd = turnDir(f.getKey(), rx, ry);
                if (uvlock) { face.add("uv", defaultUv(nd, from, to)); face.remove("rotation"); }   // текстура выровнена по миру
                faces.put(nd, face);
            }
            JsonObject rot = null;
            if (e.rotation != null) {
                rot = e.rotation.deepCopy();
                JsonArray o = rot.getAsJsonArray("origin");
                double[] oo = turn(new double[]{o.get(0).getAsDouble() - 8, o.get(1).getAsDouble() - 8, o.get(2).getAsDouble() - 8}, rx, ry);
                JsonArray no = new JsonArray();
                no.add(oo[0] + 8); no.add(oo[1] + 8); no.add(oo[2] + 8);
                rot.add("origin", no);
                String ax = rot.get("axis").getAsString();
                double[] unit = ax.equals("x") ? new double[]{1, 0, 0} : ax.equals("y") ? new double[]{0, 1, 0} : new double[]{0, 0, 1};
                double[] u = turn(unit, rx, ry);
                int i = Math.abs(u[0]) > 0.5 ? 0 : Math.abs(u[1]) > 0.5 ? 1 : 2;
                rot.addProperty("axis", i == 0 ? "x" : i == 1 ? "y" : "z");
                if (u[i] < 0) rot.addProperty("angle", -rot.get("angle").getAsDouble());
            }
            return new Elem(from, to, rot, faces);
        }

        /** v' = RotY(−y) · RotX(−x) · v (точные повороты на 90°). */
        static double[] turn(double[] v, int rx, int ry) {
            double x = v[0], y = v[1], z = v[2];
            for (int i = 0; i < Math.floorMod(-rx, 360) / 90; i++) { double ny = -z, nz = y; y = ny; z = nz; }     // RotX(+90): y' = -z, z' = y
            for (int i = 0; i < Math.floorMod(-ry, 360) / 90; i++) { double nx = z, nz = -x; x = nx; z = nz; }     // RotY(+90): x' = z, z' = -x
            return new double[]{x, y, z};
        }

        static String turnDir(String d, int rx, int ry) {
            int[] v = dir(d);
            if (v == null) return d;
            double[] r = turn(new double[]{v[0], v[1], v[2]}, rx, ry);
            if (r[0] > 0.5) return "east"; if (r[0] < -0.5) return "west";
            if (r[1] > 0.5) return "up"; if (r[1] < -0.5) return "down";
            return r[2] > 0.5 ? "south" : "north";
        }
    }
}
