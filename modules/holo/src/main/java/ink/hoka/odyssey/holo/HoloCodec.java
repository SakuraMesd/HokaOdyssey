package ink.hoka.odyssey.holo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ink.hoka.odyssey.holo.Hologram.Action;
import ink.hoka.odyssey.holo.Hologram.Element;
import ink.hoka.odyssey.holo.Hologram.Line;
import ink.hoka.odyssey.holo.Hologram.Page;

import java.util.List;

/**
 * Голограмма ↔ JSON (файл holograms.json, пакеты моду, правки редактора). Один формат везде:
 * <pre>
 * { "id": "hello", "map": "world", "pos": [x, y, z], "yaw": 0, "pitch": 0, "facing": "billboard_y",
 *   "scale": 1, "theme": "space", "width": 0, "padding": 0.12, "background": true, "opacity": 0.78,
 *   "frame": true, "hover": true, "range": 32, "view": "all", "creator_op": true,
 *   "left": [действия], "right": [действия],
 *   "pages": [ { "lines": [ [элемент, элемент…], … ] } ] }
 * элемент: { "type": "text", "text": "&lt;b&gt;Привет, {player}", "size": 1, "align": "center" }
 *          { "type": "item", "item": "minecraft:diamond", "spin": true, "size": 1, "hint": "…" }
 *          { "type": "image", "image": "logo.png", "w": 1, "h": 1 }
 *          { "type": "bar", "value": "{fuel}", "max": 1, "color": "#8FD8FF", "label": "Топливо", "w": 2 }
 *          { "type": "button", "label": "Купить", "color": "#FFC46B", "cond": "op", "cooldown": 10,
 *            "left": [ { "type": "cmd", "value": "spawn" } ], "right": [] }
 *          { "type": "separator" }  { "type": "spacer", "h": 0.2 }
 * действие: { "type": "cmd|console|msg|sound|tp|ui|page", "value": "…" }
 * </pre>
 * Разбор строгий: неизвестный тип, плохой id, размер вне границ — {@link IllegalArgumentException} с понятным текстом.
 */
public final class HoloCodec {

    public static final int MAX_PAGES = 16, MAX_LINES = 64, MAX_ROW = 8, MAX_ACTIONS = 16, MAX_TEXT = 2000;

    static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private HoloCodec() {}

    // ───────────────────────────── разбор ─────────────────────────────

    public static Hologram parse(String json) {
        return parse(JsonParser.parseString(json).getAsJsonObject());
    }

    public static Hologram parse(JsonObject o) {
        String id = str(o, "id", null);
        if (!Hologram.validId(id)) throw new IllegalArgumentException("плохой id голограммы «" + id + "» (a-z 0-9 _ -)");
        Hologram h = new Hologram(id);
        h.map = str(o, "map", "world");
        if (!h.map.matches("[a-z0-9_\\-]{1,32}")) throw new IllegalArgumentException(id + ": плохая карта «" + h.map + "»");
        JsonArray pos = o.has("pos") ? o.getAsJsonArray("pos") : null;
        if (pos == null || pos.size() != 3) throw new IllegalArgumentException(id + ": pos — [x, y, z]");
        h.x = finite(pos.get(0).getAsDouble(), id);
        h.y = finite(pos.get(1).getAsDouble(), id);
        h.z = finite(pos.get(2).getAsDouble(), id);
        h.yaw = (float) num(o, "yaw", 0, -100000, 100000, id);
        h.pitch = (float) num(o, "pitch", 0, -90, 90, id);
        h.facing = Hologram.Facing.parse(str(o, "facing", "billboard_y"));
        h.scale = num(o, "scale", 1, 0.1, 10, id);
        h.theme = str(o, "theme", "space");
        if (!Hologram.THEMES.contains(h.theme)) throw new IllegalArgumentException(id + ": тема — space | amber | danger | green");
        h.width = num(o, "width", 0, 0, 32, id);
        h.padding = num(o, "padding", 0.12, 0, 2, id);
        h.background = bool(o, "background", true);
        h.opacity = num(o, "opacity", 0.78, 0, 1, id);
        h.frame = bool(o, "frame", true);
        h.hover = bool(o, "hover", true);
        h.range = num(o, "range", 32, 2, 256, id);
        h.view = Hologram.View.parse(str(o, "view", "all"));
        h.creatorOp = bool(o, "creator_op", false);
        h.left = actions(o, "left", id);
        h.right = actions(o, "right", id);
        JsonArray pages = o.has("pages") ? o.getAsJsonArray("pages") : new JsonArray();
        if (pages.size() > MAX_PAGES) throw new IllegalArgumentException(id + ": страниц больше " + MAX_PAGES);
        for (JsonElement pe : pages) {
            Page p = new Page();
            JsonArray lines = pe.getAsJsonObject().has("lines") ? pe.getAsJsonObject().getAsJsonArray("lines") : new JsonArray();
            if (lines.size() > MAX_LINES) throw new IllegalArgumentException(id + ": строк на странице больше " + MAX_LINES);
            for (JsonElement le : lines) {
                Line l = new Line();
                JsonArray row = le.isJsonArray() ? le.getAsJsonArray() : wrap(le);
                if (row.size() > MAX_ROW) throw new IllegalArgumentException(id + ": элементов в ряду больше " + MAX_ROW);
                for (JsonElement ee : row) l.elements.add(element(ee.getAsJsonObject(), id));
                p.lines.add(l);
            }
            h.pages.add(p);
        }
        if (h.pages.isEmpty()) h.pages.add(new Page());
        return h;
    }

    private static JsonArray wrap(JsonElement e) {
        JsonArray a = new JsonArray();
        a.add(e);
        return a;
    }

    static Element element(JsonObject o, String id) {
        Element e = new Element(Hologram.Type.parse(str(o, "type", "text")));
        e.text = text(o, "text", id);
        e.size = num(o, "size", 1, 0.5, 4, id);
        e.align = str(o, "align", "center");
        if (!e.align.equals("left") && !e.align.equals("center") && !e.align.equals("right"))
            throw new IllegalArgumentException(id + ": align — left | center | right");
        e.item = str(o, "item", null);
        if (e.item != null && !e.item.matches("[A-Za-z0-9_:.\\-/]{1,80}"))
            throw new IllegalArgumentException(id + ": плохой id предмета «" + e.item + "»");
        e.spin = bool(o, "spin", true);
        e.hint = text(o, "hint", id);
        e.image = str(o, "image", null);
        if (e.image != null && !e.image.matches("[A-Za-z0-9_\\-./]{1,80}") || e.image != null && e.image.contains(".."))
            throw new IllegalArgumentException(id + ": плохое имя картинки «" + e.image + "»");
        e.w = num(o, "w", 0, 0, 32, id);
        e.h = num(o, "h", 0, 0, 32, id);
        e.value = text(o, "value", id);
        e.max = num(o, "max", 1, 1e-9, 1e12, id);
        e.color = str(o, "color", null);
        if (e.color != null && !e.color.isEmpty() && !e.color.matches("#?[0-9a-fA-F]{6}"))
            throw new IllegalArgumentException(id + ": цвет — #RRGGBB, а не «" + e.color + "»");
        e.label = text(o, "label", id);
        e.cond = str(o, "cond", "all");
        if (!e.cond.equals("all") && !e.cond.equals("op") && !e.cond.matches("perm:[a-z0-9_.\\-]{1,64}"))
            throw new IllegalArgumentException(id + ": cond — all | op | perm:узел");
        e.cooldown = (int) num(o, "cooldown", -1, -1, 72000, id);
        e.left = actions(o, "left", id);
        e.right = actions(o, "right", id);
        return e;
    }

    static List<Action> actions(JsonObject o, String key, String id) {
        List<Action> out = new java.util.ArrayList<>();
        if (!o.has(key) || o.get(key).isJsonNull()) return out;
        JsonArray a = o.getAsJsonArray(key);
        if (a.size() > MAX_ACTIONS) throw new IllegalArgumentException(id + ": действий больше " + MAX_ACTIONS);
        for (JsonElement ae : a) {
            JsonObject ao = ae.getAsJsonObject();
            Action act = Action.of(str(ao, "type", "?"), text(ao, "value", id));
            out.add(act);
        }
        return out;
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }

    private static String text(JsonObject o, String k, String id) {
        String s = str(o, k, null);
        if (s != null && s.length() > MAX_TEXT) throw new IllegalArgumentException(id + ": «" + k + "» длиннее " + MAX_TEXT);
        return s;
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : def;
    }

    private static double num(JsonObject o, String k, double def, double min, double max, String id) {
        if (!o.has(k) || o.get(k).isJsonNull()) return def;
        double v = o.get(k).getAsDouble();
        if (!(v >= min && v <= max)) throw new IllegalArgumentException(id + ": «" + k + "» = " + v + " — вне " + min + "…" + max);
        return v;
    }

    private static double finite(double v, String id) {
        if (!Double.isFinite(v) || Math.abs(v) > 3e7) throw new IllegalArgumentException(id + ": координата вне мира");
        return v;
    }

    // ───────────────────────────── запись ─────────────────────────────

    public static JsonObject toJson(Hologram h) {
        JsonObject o = new JsonObject();
        o.addProperty("id", h.id);
        o.addProperty("map", h.map);
        JsonArray pos = new JsonArray();
        pos.add(round(h.x)); pos.add(round(h.y)); pos.add(round(h.z));
        o.add("pos", pos);
        o.addProperty("yaw", round(h.yaw));
        o.addProperty("pitch", round(h.pitch));
        o.addProperty("facing", h.facing.key());
        o.addProperty("scale", h.scale);
        o.addProperty("theme", h.theme);
        o.addProperty("width", h.width);
        o.addProperty("padding", h.padding);
        o.addProperty("background", h.background);
        o.addProperty("opacity", h.opacity);
        o.addProperty("frame", h.frame);
        o.addProperty("hover", h.hover);
        o.addProperty("range", h.range);
        o.addProperty("view", h.view.key());
        o.addProperty("creator_op", h.creatorOp);
        if (!h.left.isEmpty()) o.add("left", actions(h.left));
        if (!h.right.isEmpty()) o.add("right", actions(h.right));
        JsonArray pages = new JsonArray();
        for (Page p : h.pages) {
            JsonObject po = new JsonObject();
            JsonArray lines = new JsonArray();
            for (Line l : p.lines) {
                JsonArray row = new JsonArray();
                for (Element e : l.elements) row.add(toJson(e));
                lines.add(row);
            }
            po.add("lines", lines);
            pages.add(po);
        }
        o.add("pages", pages);
        return o;
    }

    static JsonObject toJson(Element e) {
        JsonObject o = new JsonObject();
        o.addProperty("type", e.type.key());
        switch (e.type) {
            case TEXT -> {
                o.addProperty("text", e.text == null ? "" : e.text);
                if (e.size != 1) o.addProperty("size", e.size);
                if (!e.align.equals("center")) o.addProperty("align", e.align);
            }
            case ITEM -> {
                o.addProperty("item", e.item == null ? "minecraft:stone" : e.item);
                if (!e.spin) o.addProperty("spin", false);
                if (e.size != 1) o.addProperty("size", e.size);
            }
            case IMAGE -> {
                o.addProperty("image", e.image == null ? "" : e.image);
                if (e.w > 0) o.addProperty("w", e.w);
                if (e.h > 0) o.addProperty("h", e.h);
            }
            case BAR -> {
                o.addProperty("value", e.value == null ? "0" : e.value);
                if (e.max != 1) o.addProperty("max", e.max);
                if (e.label != null) o.addProperty("label", e.label);
                if (e.w > 0) o.addProperty("w", e.w);
            }
            case BUTTON -> {
                o.addProperty("label", e.label == null ? "" : e.label);
                if (e.size != 1) o.addProperty("size", e.size);
                if (e.w > 0) o.addProperty("w", e.w);
            }
            case SPACER -> { if (e.h > 0) o.addProperty("h", e.h); }
            case SEPARATOR -> { }
        }
        if (e.color != null && !e.color.isEmpty()) o.addProperty("color", e.color);
        if (e.hint != null && !e.hint.isEmpty()) o.addProperty("hint", e.hint);
        if (!"all".equals(e.cond)) o.addProperty("cond", e.cond);
        if (e.cooldown >= 0) o.addProperty("cooldown", e.cooldown);
        if (!e.left.isEmpty()) o.add("left", actions(e.left));
        if (!e.right.isEmpty()) o.add("right", actions(e.right));
        return o;
    }

    static JsonArray actions(List<Action> as) {
        JsonArray a = new JsonArray();
        for (Action x : as) {
            JsonObject ao = new JsonObject();
            ao.addProperty("type", x.type().key());
            ao.addProperty("value", x.value());
            a.add(ao);
        }
        return a;
    }

    private static double round(double v) { return Math.round(v * 10000) / 10000.0; }

    public static String compact(Hologram h) { return COMPACT.toJson(toJson(h)); }

    /** Глубокая копия (через JSON) — для copy и шаблонов. */
    public static Hologram copy(Hologram h, String newId) {
        JsonObject o = toJson(h);
        o.addProperty("id", newId);
        Hologram c = parse(o);
        c.temporary = h.temporary;
        return c;
    }
}
