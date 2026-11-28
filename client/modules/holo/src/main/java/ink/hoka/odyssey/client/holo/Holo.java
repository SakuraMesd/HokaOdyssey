package ink.hoka.odyssey.client.holo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Голограмма у мода — только то, что нужно для рисования и наведения (формат JSON — серверный
 * {@code modules/holo/.../HoloCodec}). Разбор мягкий: сервер уже проверил, редактор может прислать черновик.
 */
public final class Holo {

    public static final int FIXED = 0, BILLBOARD_Y = 1, BILLBOARD = 2;
    static final Pattern PH = Pattern.compile("\\{([a-zA-Z0-9_.:\\-]{1,48})}");

    public String id, map;
    public double x, y, z;
    public float yaw, pitch;
    public int facing = BILLBOARD_Y;
    public double scale = 1, width, padding = 0.12, opacity = 0.78, range = 32;
    public String theme = "space";
    public boolean background = true, frame = true, hover = true, panelClick;
    public final List<List<List<El>>> pages = new ArrayList<>();
    /** Плейсхолдеры, которые встречаются в голограмме. */
    public final Set<String> names = new LinkedHashSet<>();

    /** Элемент строки. */
    public static final class El {
        public String type = "text", text, align = "center", item, hint, image, value, color, label;
        public double size = 1, w, h, max = 1;
        public boolean spin = true, clickable;
    }

    public static Holo parse(JsonObject o) {
        Holo h = new Holo();
        h.id = s(o, "id", "?");
        h.map = s(o, "map", "world");
        JsonArray p = o.has("pos") ? o.getAsJsonArray("pos") : null;
        if (p != null && p.size() == 3) { h.x = p.get(0).getAsDouble(); h.y = p.get(1).getAsDouble(); h.z = p.get(2).getAsDouble(); }
        h.yaw = (float) d(o, "yaw", 0);
        h.pitch = (float) d(o, "pitch", 0);
        h.facing = switch (s(o, "facing", "billboard_y")) { case "fixed" -> FIXED; case "billboard" -> BILLBOARD; default -> BILLBOARD_Y; };
        h.scale = clamp(d(o, "scale", 1), 0.1, 10);
        h.theme = s(o, "theme", "space");
        h.width = clamp(d(o, "width", 0), 0, 32);
        h.padding = clamp(d(o, "padding", 0.12), 0, 2);
        h.background = b(o, "background", true);
        h.opacity = clamp(d(o, "opacity", 0.78), 0, 1);
        h.frame = b(o, "frame", true);
        h.hover = b(o, "hover", true);
        h.range = clamp(d(o, "range", 32), 2, 256);
        h.panelClick = nonEmpty(o, "left") || nonEmpty(o, "right");
        if (o.has("pages")) for (JsonElement pe : o.getAsJsonArray("pages")) {
            List<List<El>> lines = new ArrayList<>();
            JsonObject po = pe.getAsJsonObject();
            if (po.has("lines")) for (JsonElement le : po.getAsJsonArray("lines")) {
                List<El> row = new ArrayList<>();
                if (le.isJsonArray()) for (JsonElement ee : le.getAsJsonArray()) row.add(el(ee.getAsJsonObject(), h.names));
                else if (le.isJsonObject()) row.add(el(le.getAsJsonObject(), h.names));
                lines.add(row);
            }
            h.pages.add(lines);
        }
        if (h.pages.isEmpty()) h.pages.add(new ArrayList<>());
        return h;
    }

    static El el(JsonObject o, Set<String> names) {
        El e = new El();
        e.type = s(o, "type", "text");
        e.text = s(o, "text", null);
        e.size = clamp(d(o, "size", 1), 0.5, 4);
        e.align = s(o, "align", "center");
        e.item = s(o, "item", null);
        e.spin = b(o, "spin", true);
        e.hint = s(o, "hint", null);
        e.image = s(o, "image", null);
        e.w = clamp(d(o, "w", 0), 0, 32);
        e.h = clamp(d(o, "h", 0), 0, 32);
        e.value = s(o, "value", null);
        e.max = Math.max(1e-9, d(o, "max", 1));
        e.color = s(o, "color", null);
        e.label = s(o, "label", null);
        e.clickable = nonEmpty(o, "left") || nonEmpty(o, "right");
        for (String t : new String[]{e.text, e.label, e.value, e.hint}) scan(t, names);
        return e;
    }

    static void scan(String s, Set<String> out) {
        if (s == null || s.indexOf('{') < 0) return;
        Matcher m = PH.matcher(s);
        while (m.find()) out.add(m.group(1));
    }

    /** Подставить значения плейсхолдеров; «<» в значении экранируется (чужой текст не ломает разметку). */
    public static String fill(String s, Map<String, String> values) {
        if (s == null) return "";
        if (s.indexOf('{') < 0) return s;
        Matcher m = PH.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(b, Matcher.quoteReplacement(v == null ? m.group() : v.replace("<", "\\<")));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** Цвет «#RRGGBB» или def. */
    public static int rgb(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try { return Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16) & 0xFFFFFF; }
        catch (NumberFormatException e) { return def; }
    }

    public List<List<El>> page(int i) { return pages.get(Math.floorMod(i, pages.size())); }

    private static boolean nonEmpty(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() && !o.getAsJsonArray(k).isEmpty();
    }

    static String s(JsonObject o, String k, String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }

    static double d(JsonObject o, String k, double def) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : def; }
        catch (RuntimeException e) { return def; }
    }

    static boolean b(JsonObject o, String k, boolean def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : def;
    }

    static double clamp(double v, double lo, double hi) { return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo; }

    // ───────────────────────────── темы ─────────────────────────────

    /** Палитра: фон, рамка (светлая), свечение (голубая), акцент, текст. */
    public record Theme(int bg, int frame, int glow, int accent, int text) {
        public static final Theme SPACE = new Theme(0x05080F, 0xD9E2EE, 0x8FD8FF, 0xFFC46B, 0xE6EEF8);
        static final Map<String, Theme> DEFAULTS = Map.of(
                "space", SPACE,
                "amber", new Theme(0x0F0A04, 0xF2E2C4, 0xFFC46B, 0xFF8A3D, 0xFFF1D6),
                "danger", new Theme(0x12040A, 0xF2C9CF, 0xFF5A6E, 0xFFC46B, 0xFFE6EA),
                "green", new Theme(0x03100A, 0xCFF2DC, 0x6BFFAE, 0xFFC46B, 0xE2FFEE));

        /** Темы из конфига сервера (JSON {имя: {bg, frame, glow, accent, text}}) поверх встроенных. */
        public static Map<String, Theme> parse(JsonObject o) {
            Map<String, Theme> m = new HashMap<>(DEFAULTS);
            if (o != null) for (var e : o.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject t = e.getValue().getAsJsonObject();
                Theme d = DEFAULTS.getOrDefault(e.getKey(), SPACE);
                m.put(e.getKey(), new Theme(rgb(s(t, "bg", null), d.bg), rgb(s(t, "frame", null), d.frame),
                        rgb(s(t, "glow", null), d.glow), rgb(s(t, "accent", null), d.accent), rgb(s(t, "text", null), d.text)));
            }
            return m;
        }
    }
}
