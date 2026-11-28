package ink.hoka.odyssey.survival;

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PvP-зоны: коробки блоков на картах (имя у каждой — своё на весь сервер) и значение карты по умолчанию вне зон.
 * Неизменяемый снимок: правка даёт новый (читать можно из любого потока без блокировок).
 * <p>
 * Файл {@code module/survival/pvp-zones.json}:
 * <pre>
 * {"version":1,
 *  "maps":{"cosmos":{"default":false}},
 *  "zones":[{"name":"arena","map":"cosmos","min":[-50,0,-50],"max":[50,80,50]},
 *           {"name":"base","map":"cosmos","min":[0,0,0],"max":[9,9,9],"pvp":false}]}
 * </pre>
 * Зона с {@code "pvp": false} — безопасная (база на карте, где вне зон PvP). Зоны вложены — решает меньшая по объёму
 * (при равном — заданная позже).
 * min/max — углы коробки (блоки, включительно): точка (x, y, z) внутри, если min ≤ x &lt; max + 1 по каждой оси.
 * Запись — во временный файл и переименование; битый файл не перезаписывается (копия — pvp-zones.json.broken).
 */
public final class ZoneBook {

    /** Зона: коробка блоков [x0..x1]×[y0..y1]×[z0..z1] (включительно) на карте map. */
    public record Zone(String name, String map, int x0, int y0, int z0, int x1, int y1, int z1, boolean pvp) {

        /** Из двух любых углов (PvP-зона). */
        public static Zone of(String name, String map, int ax, int ay, int az, int bx, int by, int bz) {
            return of(name, map, ax, ay, az, bx, by, bz, true);
        }

        /** Из двух любых углов; pvp false — безопасная зона. */
        public static Zone of(String name, String map, int ax, int ay, int az, int bx, int by, int bz, boolean pvp) {
            return new Zone(name, map, Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz), Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz), pvp);
        }

        /** Точка внутри коробки (граница блока x1 + 1 — уже снаружи). */
        public boolean contains(double x, double y, double z) {
            return x >= x0 && x < x1 + 1 && y >= y0 && y < y1 + 1 && z >= z0 && z < z1 + 1;
        }

        public long volume() { return (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1); }

        public String box() { return x0 + " " + y0 + " " + z0 + " … " + x1 + " " + y1 + " " + z1; }
    }

    public static final ZoneBook EMPTY = new ZoneBook(List.of(), Map.of());

    private final List<Zone> zones;
    private final Map<String, List<Zone>> byMap;
    private final Map<String, Boolean> defaults;

    private ZoneBook(List<Zone> zones, Map<String, Boolean> defaults) {
        this.zones = List.copyOf(zones);
        this.defaults = Map.copyOf(defaults);
        Map<String, List<Zone>> m = new LinkedHashMap<>();
        for (Zone z : this.zones) m.computeIfAbsent(z.map(), k -> new ArrayList<>()).add(z);
        m.replaceAll((k, v) -> List.copyOf(v));
        this.byMap = Map.copyOf(m);
    }

    public List<Zone> zones() { return zones; }

    public List<Zone> zones(String map) { return byMap.getOrDefault(map, List.of()); }

    public Zone zone(String name) {
        for (Zone z : zones) if (z.name().equalsIgnoreCase(name)) return z;
        return null;
    }

    /** Значение карты по умолчанию вне зон (null — не задано, то есть «не PvP»). */
    public Boolean mapDefault(String map) { return defaults.get(map); }

    public Map<String, Boolean> defaults() { return defaults; }

    /** Зона в точке или null; вложенные — меньшая по объёму (при равном — заданная позже). */
    public Zone zoneAt(String map, double x, double y, double z) {
        if (map == null) return null;
        Zone best = null;
        for (Zone zn : zones(map))
            if (zn.contains(x, y, z) && (best == null || zn.volume() <= best.volume())) best = zn;
        return best;
    }

    /** PvP в точке: в зоне — как у зоны (PvP-зона да, безопасная нет); вне зон — значение карты (нет — нет). */
    public boolean isPvp(String map, double x, double y, double z) {
        if (map == null) return false;
        Zone zn = zoneAt(map, x, y, z);
        if (zn != null) return zn.pvp();
        return Boolean.TRUE.equals(defaults.get(map));
    }

    /** С новой зоной (зона с тем же именем заменяется). */
    public ZoneBook with(Zone z) {
        List<Zone> l = new ArrayList<>();
        for (Zone o : zones) if (!o.name().equalsIgnoreCase(z.name())) l.add(o);
        l.add(z);
        return new ZoneBook(l, defaults);
    }

    /** Без зоны name (нет такой — тот же снимок). */
    public ZoneBook without(String name) {
        List<Zone> l = new ArrayList<>();
        for (Zone o : zones) if (!o.name().equalsIgnoreCase(name)) l.add(o);
        return l.size() == zones.size() ? this : new ZoneBook(l, defaults);
    }

    /** Значение карты по умолчанию (null — убрать). */
    public ZoneBook withDefault(String map, Boolean pvp) {
        Map<String, Boolean> d = new LinkedHashMap<>(defaults);
        if (pvp == null) d.remove(map); else d.put(map, pvp);
        return new ZoneBook(zones, d);
    }

    /** Имя зоны годится: буквы, цифры, _ и -, до 32 знаков. */
    public static boolean validName(String n) {
        return n != null && !n.isEmpty() && n.length() <= 32 && n.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '-');
    }

    // ───────────────────────── JSON ─────────────────────────

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject maps = new JsonObject();
        for (var e : defaults.entrySet()) {
            JsonObject m = new JsonObject();
            m.addProperty("default", e.getValue());
            maps.add(e.getKey(), m);
        }
        root.add("maps", maps);
        JsonArray arr = new JsonArray();
        for (Zone z : zones) {
            JsonObject o = new JsonObject();
            o.addProperty("name", z.name());
            o.addProperty("map", z.map());
            o.add("min", ints(z.x0(), z.y0(), z.z0()));
            o.add("max", ints(z.x1(), z.y1(), z.z1()));
            if (!z.pvp()) o.addProperty("pvp", false);
            arr.add(o);
        }
        root.add("zones", arr);
        return GSON.toJson(root);
    }

    private static JsonArray ints(int... v) {
        JsonArray a = new JsonArray();
        for (int i : v) a.add(i);
        return a;
    }

    /** Разобрать; ошибка — исключение (файл битый — не перезаписывать). */
    public static ZoneBook fromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, Boolean> d = new LinkedHashMap<>();
        if (root.get("maps") instanceof JsonObject maps)
            for (Map.Entry<String, JsonElement> e : maps.entrySet())
                if (e.getValue() instanceof JsonObject m && m.has("default")) d.put(e.getKey(), m.get("default").getAsBoolean());
        List<Zone> l = new ArrayList<>();
        if (root.get("zones") instanceof JsonArray arr) for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            String name = o.get("name").getAsString(), map = o.get("map").getAsString();
            JsonArray a = o.getAsJsonArray("min"), b = o.getAsJsonArray("max");
            if (a == null || b == null || a.size() != 3 || b.size() != 3) throw new IllegalStateException("зона " + name + ": нужны min и max по 3 числа");
            boolean pvp = !o.has("pvp") || o.get("pvp").getAsBoolean();
            Zone z = Zone.of(name, map, a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(), pvp);
            for (Zone o2 : l) if (o2.name().equalsIgnoreCase(name)) throw new IllegalStateException("две зоны с именем " + name);
            l.add(z);
        }
        return new ZoneBook(l, d);
    }

    // ───────────────────────── файл ─────────────────────────

    /** Файл зон: чтение, атомарная запись, «битый — не трогать». */
    public static final class Store {
        private final Path file;
        private volatile boolean broken;
        private volatile String brokenWhy;

        public Store(Path file) { this.file = file; }

        public boolean broken() { return broken; }

        public String brokenWhy() { return brokenWhy; }

        /** Нет файла — пусто; битый — пусто и {@link #broken} (копия рядом, писать не будем). */
        public ZoneBook load() {
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

        /** Записать атомарно; файл был битым — false. */
        public synchronized boolean write(String json) throws IOException {
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

    /** Режим боя игроков из строки config.yml (all / zones / off); старое pvp: true/false — all/off. */
    public static String mode(String s, boolean legacyPvp) {
        if (s == null) return legacyPvp ? "all" : "off";
        String m = s.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "all", "zones", "off" -> m;
            case "true", "on", "yes" -> "all";
            case "false", "no" -> "off";
            default -> "all";
        };
    }

    /** Решение для двух игроков по режиму: all — да, off — нет, zones — оба в PvP. */
    public static boolean playerPvp(String mode, boolean attackerIn, boolean targetIn) {
        return switch (mode) {
            case "off" -> false;
            case "zones" -> attackerIn && targetIn;
            default -> true;
        };
    }

    /** Отсортированные имена (для подсказок). */
    public List<String> names() {
        List<String> l = new ArrayList<>();
        for (Zone z : zones) l.add(z.name());
        Collections.sort(l);
        return l;
    }
}
