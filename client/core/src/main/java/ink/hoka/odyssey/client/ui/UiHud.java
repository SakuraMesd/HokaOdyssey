package ink.hoka.odyssey.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HUD с сервера: слои поверх игры, каждый — раскладка {@code ui/hud/<id>.json} (тот же движок, что у окон:
 * {@link UiNode}, {@link UiExpr}) + данные, которые сервер обновляет. Поправили раскладку — слой перестроится сразу.
 * <pre>
 * { "size": [ш, в], "anchor": [0..1, 0..1] (точка экрана и та же точка слоя), "offset": [x, y], "scale": 1, "z": 0,
 *   "styles": {…}, "data": {…}, "children": [ узлы ] }
 * </pre>
 * Полосе ({@code bar}) можно дать {@code smooth} (секунды) — доезжает до нового значения плавно.
 * Ванильные части экрана сервер может прятать: health, armor, food, air, xp, xp_level, hotbar, crosshair, effects,
 * bossbar, scoreboard, mount, actionbar, title, held_item.
 */
public final class UiHud {

    private UiHud() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey/hud");

    private static final class Layer {
        final String id, path;
        String sha;
        UiNode root;
        Map<String, JsonObject> styles = Map.of();
        final JsonObject data = new JsonObject();
        int dataVersion, z;
        float w, h, ax, ay, ox, oy, scale = 1;
        long shownAt = System.currentTimeMillis(), lastFrame;

        Layer(String id, String path) { this.id = id; this.path = path; }
    }

    private static final Map<String, Layer> LAYERS = new LinkedHashMap<>();
    private static volatile Set<String> HIDDEN = Set.of();

    private static final Map<String, Identifier> VANILLA = new LinkedHashMap<>();

    static {
        VANILLA.put("health", VanillaHudElements.HEALTH_BAR);
        VANILLA.put("armor", VanillaHudElements.ARMOR_BAR);
        VANILLA.put("food", VanillaHudElements.FOOD_BAR);
        VANILLA.put("air", VanillaHudElements.AIR_BAR);
        VANILLA.put("xp", VanillaHudElements.INFO_BAR);
        VANILLA.put("xp_level", VanillaHudElements.EXPERIENCE_LEVEL);
        VANILLA.put("hotbar", VanillaHudElements.HOTBAR);
        VANILLA.put("crosshair", VanillaHudElements.CROSSHAIR);
        VANILLA.put("effects", VanillaHudElements.STATUS_EFFECTS);
        VANILLA.put("bossbar", VanillaHudElements.BOSS_BAR);
        VANILLA.put("scoreboard", VanillaHudElements.SCOREBOARD);
        VANILLA.put("mount", VanillaHudElements.MOUNT_HEALTH);
        VANILLA.put("actionbar", VanillaHudElements.OVERLAY_MESSAGE);
        VANILLA.put("title", VanillaHudElements.TITLE_AND_SUBTITLE);
        VANILLA.put("held_item", VanillaHudElements.HELD_ITEM_TOOLTIP);
    }

    /** При запуске мода: свой слой HUD (под чатом) и «выключатели» ванильных частей. */
    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("hoka_odyssey", "hud"), UiHud::render);
        for (var e : VANILLA.entrySet()) {
            String name = e.getKey();
            HudElementRegistry.replaceElement(e.getValue(), old -> (g, t) -> {
                if (!HIDDEN.contains(name)) old.render(g, t);
            });
        }
    }

    /**
     * S2C op 8: 1 SHOW (UTF id, UTF путь раскладки, int n + JSON данных); 2 DATA (UTF id, int n + JSON);
     * 3 HIDE (UTF id, "" — все); 4 VANILLA (int n, n×UTF имя) — спрятать эти ванильные части (остальные показать).
     */
    public static void handle(DataInputStream in) throws IOException {
        int op = in.readUnsignedByte();
        switch (op) {
            case 1 -> {
                String id = in.readUTF(), path = in.readUTF();
                JsonObject d = json(in);
                Layer l = new Layer(id, path);
                for (var e : d.entrySet()) l.data.add(e.getKey(), e.getValue());
                LAYERS.put(id, l);
                build(l);
            }
            case 2 -> {
                String id = in.readUTF();
                JsonObject d = json(in);
                Layer l = LAYERS.get(id);
                if (l != null) {
                    for (var e : d.entrySet()) l.data.add(e.getKey(), e.getValue());
                    l.dataVersion++;
                }
            }
            case 3 -> {
                String id = in.readUTF();
                if (id.isEmpty()) LAYERS.clear();
                else LAYERS.remove(id);
            }
            case 4 -> {
                int n = Math.min(in.readInt(), 64);
                Set<String> s = new HashSet<>();
                for (int i = 0; i < n; i++) s.add(in.readUTF());
                HIDDEN = Set.copyOf(s);
            }
            default -> LOG.warn("неизвестная команда HUD {}", op);
        }
    }

    private static JsonObject json(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n <= 0) return new JsonObject();
        byte[] b = new byte[n];
        in.readFully(b);
        return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** Собрать слой из файла раскладки (данные сохраняются). Файла ещё нет — соберётся, когда придёт. */
    private static void build(Layer l) {
        UiScreen.Source src = UiScreen.source();
        byte[] b = src == null ? null : src.get(l.path);
        if (b == null) { l.root = null; l.sha = null; return; }
        l.sha = src.sha(l.path);
        try {
            JsonObject doc = JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray size = doc.getAsJsonArray("size");
            l.w = size.get(0).getAsFloat();
            l.h = size.get(1).getAsFloat();
            float[] a = pair(doc, "anchor", 0), o = pair(doc, "offset", 0);
            l.ax = a[0]; l.ay = a[1]; l.ox = o[0]; l.oy = o[1];
            l.scale = doc.has("scale") ? doc.get("scale").getAsFloat() : 1f;
            l.z = doc.has("z") ? doc.get("z").getAsInt() : 0;
            Map<String, JsonObject> styles = new HashMap<>();
            if (doc.has("styles")) for (var e : doc.getAsJsonObject("styles").entrySet()) styles.put(e.getKey(), e.getValue().getAsJsonObject());
            l.styles = styles;
            if (doc.has("data")) for (var e : doc.getAsJsonObject("data").entrySet()) if (!l.data.has(e.getKey())) l.data.add(e.getKey(), e.getValue());
            JsonObject r = new JsonObject();
            r.addProperty("type", "group");
            r.addProperty("w", l.w);
            r.addProperty("h", l.h);
            r.add("children", doc.has("children") ? doc.get("children") : new JsonArray());
            UiScreen.useStyles(styles);
            l.root = new UiNode(null, r, styles, new UiExpr.Ctx(l.data, null, 0));
            l.dataVersion++;
        } catch (Exception e) {
            LOG.warn("слой HUD {} не собран: {}", l.id, e.toString());
            l.root = null;
        }
    }

    private static float[] pair(JsonObject o, String k, float d) {
        if (!o.has(k) || !o.get(k).isJsonArray()) return new float[]{d, d};
        JsonArray a = o.getAsJsonArray(k);
        return new float[]{a.get(0).getAsFloat(), a.size() > 1 ? a.get(1).getAsFloat() : a.get(0).getAsFloat()};
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        if (LAYERS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        UiScreen.Source src = UiScreen.source();
        List<Layer> order = new ArrayList<>(LAYERS.values());
        order.sort(Comparator.comparingInt(l -> l.z));
        int sw = g.guiWidth(), sh = g.guiHeight();
        long now = System.currentTimeMillis();
        for (Layer l : order) {
            String sha = src == null ? null : src.sha(l.path);
            if (sha != null && !sha.equals(l.sha)) build(l);          // раскладку поправили (или файл пришёл позже)
            if (l.root == null) continue;
            try {
                float dt = l.lastFrame == 0 ? 0 : Math.min(0.1f, (now - l.lastFrame) / 1000f);
                l.lastFrame = now;
                float t = (now - l.shownAt) / 1000f;
                UiScreen.useStyles(l.styles);
                l.root.layout(mc.font, 0, 0, l.w, l.h, l.data, l.dataVersion, true, 0, 0);
                float x = Math.round(l.ax * sw - l.ax * l.w * l.scale + l.ox), y = Math.round(l.ay * sh - l.ay * l.h * l.scale + l.oy);
                var pose = g.pose();
                pose.pushMatrix();
                pose.translate(x, y);
                if (l.scale != 1) pose.scale(l.scale, l.scale);
                l.root.render(g, mc.font, 1f, t, l.scale, dt);
                pose.popMatrix();
            } catch (Exception e) {
                LOG.warn("слой HUD {}: {}", l.id, e.toString());
                l.root = null;
            }
        }
    }

    /** Выход с сервера: убрать все слои, ваниль — как была. */
    public static void clear() {
        LAYERS.clear();
        HIDDEN = Set.of();
    }
}
