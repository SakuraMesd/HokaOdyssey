package ink.hoka.odyssey.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Узел интерфейса меню. Свойства — JSON (свои поверх стиля {@code style}); раскладка, состояния, анимации и
 * отрисовка — здесь. Координаты — в единицах меню (пиксели интерфейса до масштаба меню).
 * <p>Общие свойства: {@code type} (group, box, text, item, image, slice, bar, repeat, scroll),
 * {@code x y} (сдвиг), {@code w h} (число, {@code "50%"}, {@code "fill"}, {@code "auto"}), {@code align origin}
 * ([0..1, 0..1] — точка крепления у родителя и у себя), {@code layout} (none|row|column|grid) + {@code gap},
 * {@code padding}, {@code cols}, {@code cross} (start|center|end), {@code if} (условие), {@code alpha}, {@code scale},
 * {@code hover}/{@code press} (свойства при наведении/нажатии, плавно), {@code transition} (с),
 * {@code appear} {alpha, x, y, scale, duration, delay, stagger, easing}, {@code action}, {@code tooltip}, {@code disabled}.
 */
final class UiNode {

    final UiNode parent;
    final JsonObject o;
    final String type;
    final UiExpr.Ctx ctx;
    List<UiNode> kids = new ArrayList<>();
    /** Расчётный прямоугольник (единицы меню) и видимость. */
    float x, y, w, h;
    boolean shown;
    /** Плавные состояния 0..1. */
    float hoverT, pressT;
    /** Прокрутка ({@code scroll}): сдвиг и высота содержимого. */
    float scrollY, contentH;
    /** Полоса прокрутки ({@code scroll}): жёлоб и бегунок в единицах меню (считаются при отрисовке). */
    float barX, barY, barW, barH, thumbY, thumbH;
    boolean hasBar;
    private int builtVersion = -1;
    private final JsonObject template;

    UiNode(UiNode parent, JsonObject src, Map<String, JsonObject> styles, UiExpr.Ctx ctx) {
        this.parent = parent;
        this.ctx = ctx;
        JsonObject merged = new JsonObject();
        if (src.has("style")) {   // стиль(и): "a" или "a b" — по очереди, свои свойства поверх
            for (String st : src.get("style").getAsString().split(" ")) {
                JsonObject s = styles.get(st);
                if (s != null) for (var e : s.entrySet()) merged.add(e.getKey(), e.getValue());
            }
        }
        for (var e : src.entrySet()) merged.add(e.getKey(), e.getValue());
        this.o = merged;
        this.type = str("type", "group");
        this.template = type.equals("repeat") && o.has("template") ? o.getAsJsonObject("template") : null;
        if (o.has("children")) for (JsonElement c : o.getAsJsonArray("children")) kids.add(new UiNode(this, c.getAsJsonObject(), styles, ctx));
    }

    // ------------------------------------------------------------------ свойства

    String str(String k, String d) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? d : e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    String text(String k, String d) {
        return UiExpr.fill(str(k, d), ctx);
    }

    float num(String k, float d) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) return d;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsFloat();
        return UiExpr.num(UiExpr.fill(e.getAsString(), ctx), d);
    }

    /** Число с учётом наведения/нажатия: база → hover → press (плавно). */
    float anim(String k, float d) {
        float v = num(k, d);
        if (hoverT > 0 && o.has("hover") && o.getAsJsonObject("hover").has(k)) v += (num(o.getAsJsonObject("hover"), k, v) - v) * ease(hoverT);
        if (pressT > 0 && o.has("press") && o.getAsJsonObject("press").has(k)) v += (num(o.getAsJsonObject("press"), k, v) - v) * ease(pressT);
        return v;
    }

    /** Цвет ARGB с учётом наведения/нажатия; {@code #RRGGBB} или {@code #AARRGGBB}. */
    int animColor(String k, int d) {
        int c = color(text(k, null), d);
        if (hoverT > 0 && o.has("hover") && o.getAsJsonObject("hover").has(k))
            c = lerpColor(c, color(UiExpr.fill(o.getAsJsonObject("hover").get(k).getAsString(), ctx), c), ease(hoverT));
        if (pressT > 0 && o.has("press") && o.getAsJsonObject("press").has(k))
            c = lerpColor(c, color(UiExpr.fill(o.getAsJsonObject("press").get(k).getAsString(), ctx), c), ease(pressT));
        return c;
    }

    private float num(JsonObject src, String k, float d) {
        JsonElement e = src.get(k);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsFloat();
        return UiExpr.num(UiExpr.fill(e.getAsString(), ctx), d);
    }

    boolean interactive() {
        return o.has("action") || o.has("tooltip") || o.has("hover") || type.equals("scroll");
    }

    boolean disabled() {
        return o.has("disabled") && UiExpr.test(str("disabled", ""), ctx);
    }

    // ------------------------------------------------------------------ раскладка

    /** Раскладка поддерева в прямоугольнике родителя ({@code free} — родитель сам задал позицию). */
    void layout(Font font, float px, float py, float pw, float ph, JsonObject data, int dataVersion, boolean free, float fx, float fy) {
        shown = UiExpr.test(str("if", ""), ctx);
        if (!shown) return;
        if (template != null && builtVersion != dataVersion) rebuild(data, dataVersion);
        w = dim(o.get("w"), pw, autoW(font));
        h = dim(o.get("h"), ph, autoH(font));
        if (free) {
            x = fx + num("x", 0);
            y = fy + num("y", 0);
        } else {
            float[] al = pair("align", 0, 0), or = pair("origin", 0, 0);
            x = px + al[0] * pw - or[0] * w + num("x", 0);
            y = py + al[1] * ph - or[1] * h + num("y", 0);
        }
        float pad = num("padding", 0), gap = num("gap", 0);
        float ix = x + pad, iy = y + pad - (type.equals("scroll") ? scrollY : 0), iw = w - 2 * pad, ih = h - 2 * pad;
        String mode = str("layout", "none");
        switch (mode) {
            case "row", "column" -> {
                boolean row = mode.equals("row");
                float fixed = 0;
                int fills = 0;
                List<UiNode> vis = new ArrayList<>();
                for (UiNode k : kids) {
                    k.shown = UiExpr.test(k.str("if", ""), k.ctx);
                    if (!k.shown) continue;
                    vis.add(k);
                    JsonElement main = k.o.get(row ? "w" : "h");
                    if (main != null && main.isJsonPrimitive() && "fill".equals(main.getAsString())) fills++;
                    else fixed += k.dim(main, row ? iw : ih, row ? k.autoW(font) : k.autoH(font));
                }
                float rest = Math.max(0, (row ? iw : ih) - fixed - gap * Math.max(0, vis.size() - 1));
                float cur = row ? ix : iy;
                String cross = str("cross", "start");
                for (UiNode k : vis) {
                    JsonElement main = k.o.get(row ? "w" : "h");
                    boolean fill = main != null && main.isJsonPrimitive() && "fill".equals(main.getAsString());
                    float size = fill ? rest / fills : k.dim(main, row ? iw : ih, row ? k.autoW(font) : k.autoH(font));
                    float other = k.dim(k.o.get(row ? "h" : "w"), row ? ih : iw, row ? k.autoH(font) : k.autoW(font));
                    float off = switch (cross) { case "center" -> ((row ? ih : iw) - other) / 2f; case "end" -> (row ? ih : iw) - other; default -> 0; };
                    if (row) k.layoutFixed(font, cur, iy + off, size, other, data, dataVersion);
                    else k.layoutFixed(font, ix + off, cur, other, size, data, dataVersion);
                    cur += size + gap;
                }
                contentH = (row ? ih : cur - gap - iy) + 2 * pad;
            }
            case "grid" -> {
                int cols = Math.max(1, (int) num("cols", 3));
                float cw = (iw - gap * (cols - 1)) / cols;
                float chh = num("cell_h", cw);
                int n = 0;
                for (UiNode k : kids) {
                    k.shown = UiExpr.test(k.str("if", ""), k.ctx);
                    if (!k.shown) continue;
                    int c = n % cols, r = n / cols;
                    k.layoutFixed(font, ix + c * (cw + gap), iy + r * (chh + gap), cw, chh, data, dataVersion);
                    n++;
                }
                int rows = (n + cols - 1) / cols;
                contentH = rows * chh + Math.max(0, rows - 1) * gap + 2 * pad;
            }
            default -> {
                float bottom = 0;
                for (UiNode k : kids) {
                    k.layout(font, ix, iy, iw, ih, data, dataVersion, false, 0, 0);
                    if (k.shown) bottom = Math.max(bottom, k.y + k.h - iy);
                }
                contentH = bottom + 2 * pad;
            }
        }
        if ("auto".equals(str("h", "")) && !type.equals("text") && !type.equals("icon") && !type.equals("item")) h = contentH;   // высота по содержимому (списки)
        if (type.equals("scroll")) scrollY = Math.max(0, Math.min(scrollY, Math.max(0, contentH - h)));
    }

    /** Позиция и размер заданы раскладкой родителя (ряд/столбец/сетка); свои x/y — небольшой сдвиг. */
    private void layoutFixed(Font font, float fx, float fy, float fw, float fh, JsonObject data, int dataVersion) {
        JsonElement ow = o.get("w"), oh = o.get("h");
        o.addProperty("w", fw);
        o.addProperty("h", fh);
        layout(font, 0, 0, fw, fh, data, dataVersion, true, fx, fy);
        if (ow == null) o.remove("w"); else o.add("w", ow);
        if (oh == null) o.remove("h"); else o.add("h", oh);
    }

    private void rebuild(JsonObject data, int version) {
        builtVersion = version;
        kids = new ArrayList<>();
        JsonElement list = data == null ? null : data.get(str("for", ""));
        if (list == null || !list.isJsonArray()) return;
        JsonArray arr = list.getAsJsonArray();
        Map<String, JsonObject> styles = UiScreen.styles();   // окно или слой HUD — кто сейчас раскладывается
        for (int i = 0; i < arr.size(); i++) {
            JsonObject item = arr.get(i).isJsonObject() ? arr.get(i).getAsJsonObject() : new JsonObject();
            kids.add(new UiNode(this, template, styles, new UiExpr.Ctx(ctx.data(), item, i)));
        }
    }

    float dim(JsonElement e, float parent, float auto) {
        if (e == null || e.isJsonNull()) return auto >= 0 ? auto : parent;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsFloat();
        String s = e.getAsString().trim();
        if (s.equals("fill")) return parent - num("x", 0);
        if (s.equals("auto")) return Math.max(0, auto);
        if (s.endsWith("%")) return parent * UiExpr.num(s.substring(0, s.length() - 1), 100) / 100f;
        return UiExpr.num(UiExpr.fill(s, ctx), parent);
    }

    private float autoW(Font font) {
        return switch (type) {
            case "text" -> font.width(UiScreen.parse(text("text", ""))) * num("font", 1);
            case "icon", "item" -> num("size", 16);
            default -> -1;
        };
    }

    private float autoH(Font font) {
        return switch (type) {
            case "text" -> {
                float k = num("font", 1);
                if (o.has("wrap")) yield lines(font).size() * (font.lineHeight + 1) * k;
                yield 8 * k;
            }
            case "icon", "item" -> num("size", 16);
            default -> -1;
        };
    }

    private float[] pair(String k, float a, float b) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonArray()) return new float[]{a, b};
        JsonArray arr = e.getAsJsonArray();
        return new float[]{arr.get(0).getAsFloat(), arr.size() > 1 ? arr.get(1).getAsFloat() : arr.get(0).getAsFloat()};
    }

    List<FormattedCharSequence> lines(Font font) {
        String t = text("text", "");
        int wrap = Math.max(8, Math.round(num("wrap", 100)));
        String key = wrap + "|" + t;
        List<FormattedCharSequence> l = LINES.get(key);
        if (l != null) return l;
        if (LINES.size() > 2048) LINES.clear();
        l = font.split(UiScreen.parse(t), wrap);
        LINES.put(key, l);
        return l;
    }

    /** Перенос строк по словам — дорогой, а тексты меню почти не меняются: кэш (текст + ширина). */
    private static final Map<String, List<FormattedCharSequence>> LINES = new HashMap<>();

    static void clearLines() {
        LINES.clear();
    }

    // ------------------------------------------------------------------ отрисовка

    /** Отрисовка поддерева; {@code parentAlpha} — прозрачность предков, {@code t} — время с открытия (с). */
    void render(GuiGraphics g, Font font, float parentAlpha, float t, float menuScale, float dt) {
        if (!shown) return;
        // плавные состояния (наведение выставляет экран: hoverT цель 1/0 через флаги)
        float tr = Math.max(0.01f, num("transition", 0.12f));
        frameDt = dt;
        hoverT = approach(hoverT, hoverTarget ? 1 : 0, dt / tr);
        pressT = approach(pressT, pressTarget ? 1 : 0, dt / (tr * 0.6f));
        // появление
        float ax = 0, ay = 0, as = 1, aa = 1;
        if (o.has("appear")) {
            JsonObject ap = o.getAsJsonObject("appear");
            float dur = Math.max(0.01f, f(ap, "duration", 0.25f));
            float delay = f(ap, "delay", 0) + f(ap, "stagger", 0) * (ctx.item() != null ? ctx.index() : 0);
            float p = Math.max(0, Math.min(1, (t - delay) / dur));
            float e = easing(ap.has("easing") ? ap.get("easing").getAsString() : "out", p);
            ax = f(ap, "x", 0) * (1 - e);
            ay = f(ap, "y", 0) * (1 - e);
            as = f(ap, "scale", 1) + (1 - f(ap, "scale", 1)) * e;
            aa = f(ap, "alpha", 1) + (1 - f(ap, "alpha", 1)) * e;
        }
        float alpha = parentAlpha * aa * anim("alpha", 1) * (disabled() ? 0.5f : 1);
        if (alpha <= 0.003f) return;
        float sc = as * anim("scale", 1);
        float dx = ax + (anim("x", 0) - num("x", 0)), dy = ay + (anim("y", 0) - num("y", 0));   // сдвиг состояния
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(dx, dy);
        if (sc != 1) {
            pose.translate(x + w / 2f, y + h / 2f);
            pose.scale(sc, sc);
            pose.translate(-(x + w / 2f), -(y + h / 2f));
        }
        draw(g, font, alpha, menuScale);
        boolean clip = type.equals("scroll") || "true".equals(str("clip", "false"));
        if (clip) UiScreen.scissor(g, x, y, w, h);
        for (UiNode k : kids) k.render(g, font, alpha, t, menuScale, dt);
        if (clip) g.disableScissor();
        if (type.equals("scroll")) drawScrollbar(g, alpha);
        pose.popMatrix();
    }

    /**
     * Полоса прокрутки у правого края {@code scroll}, если содержимое не влезает: жёлоб {@code bar_track} и бегунок
     * {@code bar_color} (светлый сверху, темнее снизу), ширина {@code bar_w}, отступ от края {@code bar_gap}.
     */
    private void drawScrollbar(GuiGraphics g, float alpha) {
        hasBar = contentH > h + 0.5f && !"false".equals(str("bar", "true"));
        if (!hasBar) return;
        barW = num("bar_w", 5);
        float gap = num("bar_gap", 1);
        barX = x + w - barW - gap;
        barY = y + gap;
        barH = h - 2 * gap;
        thumbH = Math.max(12, barH * h / contentH);
        float max = Math.max(1, contentH - h);
        thumbY = barY + (barH - thumbH) * (scrollY / max);
        int track = mulAlpha(color(text("bar_track", null), 0xFF141B33), alpha);
        int border = mulAlpha(color(text("bar_border", null), 0xFF2A3A6A), alpha);
        int c1 = mulAlpha(color(text("bar_color", null), 0xFFD8DCE6), alpha);
        int c2 = mulAlpha(color(text("bar_color2", null), 0xFF9AA3B8), alpha);
        box(g, barX, barY, barW, barH, track, track, border, 1, 1);
        box(g, barX, thumbY, barW, thumbH, c1, c2, mulAlpha(0xFF5A6380, alpha), 1, 1);
    }

    /** Прокрутить так, чтобы бегунок встал верхом в {@code thumbTop} (единицы меню). */
    void scrollToThumb(float thumbTop) {
        float max = Math.max(0, contentH - h);
        float range = Math.max(1, barH - thumbH);
        scrollY = Math.max(0, Math.min(max, (thumbTop - barY) / range * max));
    }

    boolean hoverTarget, pressTarget;
    /** Полоса ({@code bar} со {@code smooth}): показанная доля, доезжает до настоящей. */
    private float barShown = -1, frameDt;

    private void draw(GuiGraphics g, Font font, float alpha, float menuScale) {
        switch (type) {
            case "box" -> {
                int fill = mulAlpha(animColor("color", 0xFF1A2342), alpha);
                int border = o.has("border") ? mulAlpha(animColor("border", 0xFF2A3A6A), alpha) : 0;
                int fill2 = o.has("color2") ? mulAlpha(animColor("color2", fill), alpha) : fill;
                box(g, x, y, w, h, fill, fill2, border, (int) num("bw", 1), (int) num("radius", 2));
            }
            case "text" -> {
                int col = mulAlpha(animColor("color", 0xFFFFFFFF), alpha);
                float k = num("font", 1);   // размер шрифта; scale — масштаб узла (анимируется отдельно)
                boolean shadow = !"false".equals(str("shadow", "true"));
                String align = str("text_align", str("talign", "left"));
                var pose = g.pose();
                pose.pushMatrix();
                pose.translate(x, y);
                if (k != 1) pose.scale(k, k);
                if (o.has("wrap")) {
                    int ly = 0;
                    for (FormattedCharSequence line : lines(font)) {
                        int lw = font.width(line);
                        int tx = switch (align) { case "center" -> Math.round((w / k - lw) / 2f); case "right" -> Math.round(w / k - lw); default -> 0; };
                        g.drawString(font, line, tx, ly, col, shadow);
                        ly += font.lineHeight + 1;
                    }
                } else {
                    Component c = UiScreen.parse(text("text", ""));
                    int lw = font.width(c);
                    int tx = switch (align) { case "center" -> Math.round((w / k - lw) / 2f); case "right" -> Math.round(w / k - lw); default -> 0; };
                    g.drawString(font, c, tx, 0, col, shadow);
                }
                pose.popMatrix();
            }
            case "icon" -> { if (o.has("item")) item(g, alpha, menuScale); }
            case "item" -> item(g, alpha, menuScale);
            case "image" -> {
                Tex t = tex(text("src", ""));
                if (t != null) blitTex(g, t.id, t.w, t.h, x, y, w, h, alpha);
            }
            case "slice" -> {
                Tex t = tex(text("src", ""));
                if (t != null) slice(g, t, alpha);
            }
            case "bar" -> {
                float v = UiExpr.num(text("value", "0"), 0), m = UiExpr.num(text("max", "1"), 1);
                float f = m <= 0 ? 0 : Math.max(0, Math.min(1, v / m));
                float smooth = num("smooth", 0);
                if (smooth > 0) {   // плавно: за smooth секунд почти доезжает до нового значения
                    barShown = barShown < 0 ? f : barShown + (f - barShown) * Math.min(1, frameDt * 3f / smooth);
                    f = barShown;
                }
                if (o.has("back")) box(g, x, y, w, h, mulAlpha(animColor("back", 0xFF1A2342), alpha), mulAlpha(animColor("back", 0xFF1A2342), alpha),
                        o.has("border") ? mulAlpha(animColor("border", 0xFF2A3A6A), alpha) : 0, 1, (int) num("radius", 1));
                float in = o.has("back") ? num("inset", 1) : 0;
                float fw = (w - 2 * in) * f;
                if (fw >= 1) {
                    int c = mulAlpha(animColor("color", 0xFF3E7BFF), alpha);
                    box(g, x + in, y + in, fw, h - 2 * in, c, o.has("color2") ? mulAlpha(animColor("color2", c), alpha) : c, 0, 0, 0);
                }
            }
            default -> { }
        }
    }

    private void item(GuiGraphics g, float alpha, float menuScale) {
        String id = text("item", "");
        if (id.isEmpty()) return;
        ItemStack it = stack(id);
        if (it.isEmpty()) return;
        float size = Math.min(w, h);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x + (w - size) / 2f, y + (h - size) / 2f);
        pose.scale(size / 16f, size / 16f);
        g.renderItem(it, 0, 0);
        int count = (int) num("count", 1);
        if (count > 1) g.renderItemDecorations(Minecraft.getInstance().font, it.copyWithCount(count), 0, 0);
        pose.popMatrix();
    }

    /**
     * Предмет по имени: {@code minecraft:wheat} — предмет игры; {@code hoka:ruby} — свой предмет (модуль hoitems);
     * {@code block:test_crate} — свой блок (модуль blocks).
     */
    static ItemStack stack(String id) {
        try {
            if (id.startsWith("hoka:") || id.startsWith("block:")) {
                ItemStack it = new ItemStack(Items.PAPER);
                net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                tag.putString(id.startsWith("hoka:") ? "hoka_item" : "hoka_block", id.substring(id.indexOf(':') + 1));
                it.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
                return it;
            }
            Identifier rid = Identifier.parse(id);
            ItemStack it = BuiltInRegistries.ITEM.getOptional(rid).map(ItemStack::new).orElse(ItemStack.EMPTY);
            if (it.is(Items.POTION))   // бутылочка без зелья — вода (иначе розовое «неизвестное» зелье)
                it.set(DataComponents.POTION_CONTENTS, new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.WATER));
            return it;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /** Коробка с пиксельным скруглением ступенькой радиуса r, вертикальным градиентом fill→fill2 и рамкой bw. */
    static void box(GuiGraphics g, float fx, float fy, float fw, float fh, int fill, int fill2, int border, int bw, int r) {
        int x = Math.round(fx), y = Math.round(fy), w = Math.round(fw), h = Math.round(fh);
        if (w < 1 || h < 1) return;
        r = Math.min(r, Math.min(w, h) / 2);
        boolean grad = fill != fill2;
        for (int i = 0; i < r; i++) {
            int in = r - i;
            g.fill(x + in, y + i, x + w - in, y + i + 1, grad ? lerpColor(fill, fill2, (float) i / h) : fill);
            g.fill(x + in, y + h - 1 - i, x + w - in, y + h - i, grad ? lerpColor(fill, fill2, (float) (h - 1 - i) / h) : fill);
        }
        if (grad) g.fillGradient(x, y + r, x + w, y + h - r, lerpColor(fill, fill2, (float) r / h), lerpColor(fill, fill2, (float) (h - r) / h));
        else g.fill(x, y + r, x + w, y + h - r, fill);
        if (border == 0 || bw <= 0) return;
        for (int k = 0; k < bw; k++) {
            int X = x + k, Y = y + k, W = w - 2 * k, H = h - 2 * k;
            if (W <= 2 * r || H <= 2 * r) break;
            g.fill(X + r, Y, X + W - r, Y + 1, border);
            g.fill(X + r, Y + H - 1, X + W - r, Y + H, border);
            g.fill(X, Y + r, X + 1, Y + H - r, border);
            g.fill(X + W - 1, Y + r, X + W, Y + H - r, border);
            for (int i = 1; i < r; i++) {
                g.fill(X + r - i, Y + i, X + r - i + 1, Y + i + 1, border);
                g.fill(X + W - r + i - 1, Y + i, X + W - r + i, Y + i + 1, border);
                g.fill(X + r - i, Y + H - 1 - i, X + r - i + 1, Y + H - i, border);
                g.fill(X + W - r + i - 1, Y + H - 1 - i, X + W - r + i, Y + H - i, border);
            }
        }
    }

    private void slice(GuiGraphics g, Tex t, float alpha) {
        float[] b = new float[]{num("border_l", num("edge", 8)), num("border_t", num("edge", 8)), num("border_r", num("edge", 8)), num("border_b", num("edge", 8))};
        float px = num("px", 1);
        int[] su = {0, (int) b[0], t.w - (int) b[2], t.w}, sv = {0, (int) b[1], t.h - (int) b[3], t.h};
        float[] dx = {x, x + b[0] / px, x + w - b[2] / px, x + w}, dy = {y, y + b[1] / px, y + h - b[3] / px, y + h};
        for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++) {
            int uw = su[i + 1] - su[i], vh = sv[j + 1] - sv[j];
            float ww = dx[i + 1] - dx[i], hh = dy[j + 1] - dy[j];
            if (uw <= 0 || vh <= 0 || ww <= 0 || hh <= 0) continue;
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(dx[i], dy[j]);
            pose.scale(ww / uw, hh / vh);
            g.blit(RenderPipelines.GUI_TEXTURED, t.id, 0, 0, su[i], sv[j], uw, vh, t.w, t.h, mulAlpha(0xFFFFFFFF, alpha));
            pose.popMatrix();
        }
    }

    private static void blitTex(GuiGraphics g, Identifier id, int tw, int th, float x, float y, float w, float h, float alpha) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(w / tw, h / th);
        g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0, 0, tw, th, tw, th, mulAlpha(0xFFFFFFFF, alpha));
        pose.popMatrix();
    }

    // ------------------------------------------------------------------ картинки

    record Tex(Identifier id, int w, int h) {}

    /**
     * Картинка: {@code shop/bg.png} — файл с сервера ({@code ui/shop/bg.png}, сменится у игрока сразу);
     * {@code minecraft:textures/gui/...png} — ресурс игры.
     */
    static Tex tex(String id) {
        if (id == null || id.isEmpty()) return null;
        if (id.indexOf(':') < 0) return UiScreen.fileTex("ui/" + id);
        Tex t = TEX.get(id);
        if (t != null || TEX.containsKey(id)) return t;
        Identifier tid = Identifier.parse(id);
        var res = Minecraft.getInstance().getResourceManager().getResource(tid);
        if (res.isPresent()) {
            try (var in = res.get().open()) {
                int[] wh = UiScreen.pngSize(in.readNBytes(24));
                if (wh != null) t = new Tex(tid, wh[0], wh[1]);
            } catch (Exception ignored) {
            }
        }
        TEX.put(id, t);
        return t;
    }

    private static final Map<String, Tex> TEX = new HashMap<>();

    static void clearTex() {
        TEX.clear();
    }

    // ------------------------------------------------------------------ мелочи

    static float f(JsonObject o, String k, float d) {
        return o.has(k) ? o.get(k).getAsFloat() : d;
    }

    static float approach(float v, float target, float step) {
        return v < target ? Math.min(target, v + step) : Math.max(target, v - step);
    }

    static float ease(float t) {
        return t * t * (3f - 2f * t);
    }

    /** Кривые плавности: linear, in, out, inout, back (с перелётом), bounce. */
    static float easing(String name, float p) {
        return switch (name) {
            case "linear" -> p;
            case "in" -> p * p * p;
            case "inout" -> p < 0.5f ? 4 * p * p * p : 1 - (float) Math.pow(-2 * p + 2, 3) / 2;
            case "back" -> {
                float c1 = 1.70158f, c3 = c1 + 1;
                yield 1 + c3 * (float) Math.pow(p - 1, 3) + c1 * (float) Math.pow(p - 1, 2);
            }
            case "bounce" -> {
                float n = 7.5625f, d = 2.75f, q = p;
                if (q < 1 / d) yield n * q * q;
                if (q < 2 / d) { q -= 1.5f / d; yield n * q * q + 0.75f; }
                if (q < 2.5 / d) { q -= 2.25f / d; yield n * q * q + 0.9375f; }
                q -= 2.625f / d;
                yield n * q * q + 0.984375f;
            }
            default -> 1 - (float) Math.pow(1 - p, 3);   // out
        };
    }

    static int color(String s, int d) {
        if (s == null) return d;
        try {
            String h = s.trim().replace("#", "");
            long v = Long.parseLong(h, 16);
            return h.length() <= 6 ? (int) (0xFF000000L | v) : (int) v;
        } catch (NumberFormatException e) {
            return d;
        }
    }

    static int lerpColor(int a, int b, float t) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }

    static int mulAlpha(int argb, float k) {
        int a = Math.round((argb >>> 24) * Math.max(0, Math.min(1, k)));
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
