package ink.hoka.odyssey.vessels;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Камеры кораблей (vessels-5): у вида (для всех кораблей вида) и у конкретного корабля (по id, поверх вида). Камера
 * «первое лицо» — точка глаз и поворот взгляда в системе корабля (нос +Z, верх +Y, левый борт +X; курс + — вправо,
 * наклон + — вниз), FOV (0 — как у игрока); виды F5 (1–{@value #MAX_F5}) — точка, поворот или точка «смотреть на»,
 * плавность следования, FOV. Переопределение: своё первое лицо у корабля — главнее вида; свой список F5 у корабля
 * (не пустой) — вместо списка вида. Нет ничего — как раньше (seat, camera.chase-*).
 * <p>
 * Неизменяемый снимок (правка — новый). Файл {@code module/vessels/cameras.json}:
 * <pre>
 * {"version":1,
 *  "kinds":{"corabll":{"first":{"pos":[0,1.2,2.5],"yaw":0,"pitch":5,"fov":80},
 *                     "f5":[{"pos":[0,4,-14],"yaw":0,"pitch":12,"smooth":0.15,"fov":0},
 *                           {"pos":[9,3,0],"look":[0,0,0],"smooth":1}]}},
 *  "ships":{"12":{"f5":[…]}}}
 * </pre>
 * Запись — во временный файл и переименование; битый файл не перезаписывается (копия — cameras.json.broken).
 */
final class CameraBook {

    static final int MAX_F5 = 5;

    /**
     * Камера в системе корабля. look — точка «смотреть на» (null — по углам yaw/pitch); smooth — плавность следования
     * F5 (доля пути за кадр к повороту корабля: 1 — жёстко, NaN — camera.chase-smoothing); fov — градусы (0 — как у игрока).
     */
    record Cam(double x, double y, double z, double yaw, double pitch, double fov, double[] look, double smooth) {

        Cam {
            yaw = wrap(yaw);
            pitch = Math.clamp(pitch, -90, 90);
            fov = fov > 0 ? Math.clamp(fov, 20, 130) : 0;
            if (look != null && look.length != 3) look = null;
            smooth = Double.isFinite(smooth) ? Math.clamp(smooth, 0.01, 1) : Double.NaN;
        }

        static Cam of(double x, double y, double z, double yaw, double pitch, double fov) { return new Cam(x, y, z, yaw, pitch, fov, null, Double.NaN); }

        boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Double.isFinite(yaw) && Double.isFinite(pitch); }

        @Override public boolean equals(Object o) {
            return o instanceof Cam c && c.x == x && c.y == y && c.z == z && c.yaw == yaw && c.pitch == pitch && c.fov == fov
                    && java.util.Arrays.equals(c.look, look) && Double.compare(c.smooth, smooth) == 0;
        }

        @Override public int hashCode() { return java.util.Objects.hash(x, y, z, yaw, pitch, fov, java.util.Arrays.hashCode(look), smooth); }

        String brief() {
            return String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f  курс %.1f наклон %.1f%s%s%s", x, y, z, yaw, pitch,
                    fov > 0 ? "  FOV " + Math.round(fov) : "",
                    look != null ? String.format(java.util.Locale.ROOT, "  смотрит на %.1f %.1f %.1f", look[0], look[1], look[2]) : "",
                    Double.isNaN(smooth) ? "" : String.format(java.util.Locale.ROOT, "  плавность %.2f", smooth));
        }
    }

    /** Камеры одного вида или корабля: первое лицо (null — нет) и виды F5. */
    record Set(Cam first, List<Cam> f5) {
        static final Set EMPTY = new Set(null, List.of());

        Set { f5 = List.copyOf(f5); }

        boolean isEmpty() { return first == null && f5.isEmpty(); }
    }

    static final CameraBook EMPTY = new CameraBook(Map.of(), Map.of());

    private final Map<String, Set> kinds;
    private final Map<Integer, Set> ships;

    private CameraBook(Map<String, Set> kinds, Map<Integer, Set> ships) {
        Map<String, Set> k = new LinkedHashMap<>(kinds);
        k.values().removeIf(Set::isEmpty);
        Map<Integer, Set> s = new LinkedHashMap<>(ships);
        s.values().removeIf(Set::isEmpty);
        this.kinds = java.util.Collections.unmodifiableMap(k);
        this.ships = java.util.Collections.unmodifiableMap(s);
    }

    Map<String, Set> kinds() { return kinds; }

    Map<Integer, Set> ships() { return ships; }

    /** Свои камеры вида / корабля (нет — пусто). */
    Set kind(String kind) { return kinds.getOrDefault(kind, Set.EMPTY); }

    Set ship(int id) { return ships.getOrDefault(id, Set.EMPTY); }

    /** Где камеры: вид (ключ — id вида) или корабль (ключ — id корабля строкой). */
    record Scope(boolean ship, String key) {
        static Scope kind(String kind) { return new Scope(false, kind); }

        static Scope ship(int id) { return new Scope(true, Integer.toString(id)); }

        String title() { return ship ? "корабль #" + key : "вид " + key; }
    }

    Set get(Scope s) { return s.ship ? ship(Integer.parseInt(s.key)) : kind(s.key); }

    /** Камеры корабля в деле: корабль поверх вида. */
    Set effective(String kind, int id) {
        Set k = kind(kind), s = ship(id);
        return new Set(s.first != null ? s.first : k.first, !s.f5.isEmpty() ? s.f5 : k.f5);
    }

    private CameraBook put(Scope s, Set set) {
        if (s.ship) {
            Map<Integer, Set> m = new LinkedHashMap<>(ships);
            m.put(Integer.parseInt(s.key), set);
            return new CameraBook(kinds, m);
        }
        Map<String, Set> m = new LinkedHashMap<>(kinds);
        m.put(s.key, set);
        return new CameraBook(m, ships);
    }

    /** Первое лицо (null — убрать). */
    CameraBook withFirst(Scope s, Cam c) { return put(s, new Set(c, get(s).f5)); }

    /**
     * Вид F5 номер n (с 1): есть — заменить (правка существующей камеры), n = число видов + 1 — добавить в конец.
     * Иначе — исключение (дыр в списке нет).
     */
    CameraBook withF5(Scope s, int n, Cam c) {
        List<Cam> l = new ArrayList<>(get(s).f5);
        if (n < 1 || n > Math.min(l.size() + 1, MAX_F5)) throw new IllegalArgumentException("вид F5 " + n + ": можно 1…" + Math.min(l.size() + 1, MAX_F5));
        if (n <= l.size()) l.set(n - 1, c); else l.add(c);
        return put(s, new Set(get(s).first, l));
    }

    /** Убрать вид F5 номер n (следующие сдвигаются). */
    CameraBook withoutF5(Scope s, int n) {
        List<Cam> l = new ArrayList<>(get(s).f5);
        if (n < 1 || n > l.size()) throw new IllegalArgumentException("нет вида F5 " + n + " (их " + l.size() + ")");
        l.remove(n - 1);
        return put(s, new Set(get(s).first, l));
    }

    /** Переставить вид F5: from → to (остальные сдвигаются). */
    CameraBook moveF5(Scope s, int from, int to) {
        List<Cam> l = new ArrayList<>(get(s).f5);
        if (from < 1 || from > l.size() || to < 1 || to > l.size())
            throw new IllegalArgumentException("номера видов F5 — 1…" + l.size());
        Cam c = l.remove(from - 1);
        l.add(to - 1, c);
        return put(s, new Set(get(s).first, l));
    }

    /** Всё у вида/корабля — по умолчанию. */
    CameraBook reset(Scope s) { return put(s, Set.EMPTY); }

    /** С корабля на вид: камеры корабля в деле (с учётом вида) становятся камерами вида. */
    CameraBook copyToKind(int id, String shipKind, String toKind) { return put(Scope.kind(toKind), effective(shipKind, id)); }

    /** Корабль удалён — его камеры прочь. */
    CameraBook forgetShip(int id) {
        if (!ships.containsKey(id)) return this;
        Map<Integer, Set> m = new LinkedHashMap<>(ships);
        m.remove(id);
        return new CameraBook(kinds, m);
    }

    // ───────────────────────── JSON ─────────────────────────

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static JsonObject camJson(Cam c) {
        JsonObject o = new JsonObject();
        JsonArray p = new JsonArray();
        p.add(r3(c.x)); p.add(r3(c.y)); p.add(r3(c.z));
        o.add("pos", p);
        o.addProperty("yaw", r3(c.yaw));
        o.addProperty("pitch", r3(c.pitch));
        if (c.fov > 0) o.addProperty("fov", r3(c.fov));
        if (c.look != null) {
            JsonArray l = new JsonArray();
            for (double d : c.look) l.add(r3(d));
            o.add("look", l);
        }
        if (!Double.isNaN(c.smooth)) o.addProperty("smooth", r3(c.smooth));
        return o;
    }

    static Cam camFrom(JsonObject o) {
        JsonArray p = o.getAsJsonArray("pos");
        if (p == null || p.size() != 3) throw new IllegalStateException("камера: нужно pos [x, y, z]");
        double[] look = null;
        if (o.get("look") instanceof JsonArray l && l.size() == 3) look = new double[]{l.get(0).getAsDouble(), l.get(1).getAsDouble(), l.get(2).getAsDouble()};
        if (look != null && !(Double.isFinite(look[0]) && Double.isFinite(look[1]) && Double.isFinite(look[2]))) throw new IllegalStateException("камера: look — не число");
        Cam c = new Cam(p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble(), num(o, "yaw", 0), num(o, "pitch", 0),
                num(o, "fov", 0), look, num(o, "smooth", Double.NaN));
        if (!c.finite() || !Double.isFinite(c.fov()) || !(Double.isNaN(c.smooth()) || Double.isFinite(c.smooth()))) throw new IllegalStateException("камера: не число");
        return c;
    }

    static JsonObject setJson(Set s) {
        JsonObject o = new JsonObject();
        if (s.first != null) o.add("first", camJson(s.first));
        JsonArray a = new JsonArray();
        for (Cam c : s.f5) a.add(camJson(c));
        if (!s.f5.isEmpty()) o.add("f5", a);
        return o;
    }

    static Set setFrom(JsonObject o) {
        Cam first = o.get("first") instanceof JsonObject f ? camFrom(f) : null;
        List<Cam> l = new ArrayList<>();
        if (o.get("f5") instanceof JsonArray a) for (JsonElement e : a) l.add(camFrom(e.getAsJsonObject()));
        if (l.size() > MAX_F5) throw new IllegalStateException("видов F5 больше " + MAX_F5);
        return new Set(first, l);
    }

    String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject k = new JsonObject();
        for (var e : kinds.entrySet()) k.add(e.getKey(), setJson(e.getValue()));
        root.add("kinds", k);
        JsonObject s = new JsonObject();
        for (var e : ships.entrySet()) s.add(Integer.toString(e.getKey()), setJson(e.getValue()));
        root.add("ships", s);
        return GSON.toJson(root);
    }

    static CameraBook fromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, Set> k = new LinkedHashMap<>();
        Map<Integer, Set> s = new LinkedHashMap<>();
        if (root.get("kinds") instanceof JsonObject ko) for (var e : ko.entrySet()) k.put(e.getKey(), setFrom(e.getValue().getAsJsonObject()));
        if (root.get("ships") instanceof JsonObject so) for (var e : so.entrySet()) s.put(Integer.parseInt(e.getKey()), setFrom(e.getValue().getAsJsonObject()));
        return new CameraBook(k, s);
    }

    private static double num(JsonObject o, String k, double def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
    }

    private static double r3(double v) { return Math.round(v * 1000) / 1000.0; }

    static double wrap(double deg) {
        double d = ((deg + 180) % 360 + 360) % 360 - 180;
        return d == -180 ? 180 : d;
    }

    /** Файл камер: чтение, атомарная запись, «битый — не трогать». */
    static final class Store {
        private final Path file;
        private volatile boolean broken;
        private volatile String brokenWhy;

        Store(Path file) { this.file = file; }

        boolean broken() { return broken; }

        String brokenWhy() { return brokenWhy; }

        CameraBook load() {
            broken = false;
            brokenWhy = null;
            if (!Files.isRegularFile(file)) return EMPTY;
            try {
                return fromJson(Files.readString(file, StandardCharsets.UTF_8));
            } catch (Exception e) {
                broken = true;
                brokenWhy = e.toString();
                try { Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { }
                return EMPTY;
            }
        }

        synchronized boolean write(String json) throws IOException {
            if (broken) return false;
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        }
    }
}
