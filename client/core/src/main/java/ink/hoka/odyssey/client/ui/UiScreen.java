package ink.hoka.odyssey.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Окно, которое рисует мод по раскладке с сервера. Раскладка — файл {@code ui/menus/<id>.json} (приходит как
 * остальные файлы; поправили на сервере — открытое окно перестроится сразу), данные — с командой «открыть» и
 * обновлениями, пока окно открыто. Действие узла ({@code action}) уходит на сервер; {@code close} закрывает здесь.
 * <pre>
 * { "size": [ш, в], "fit": [доля ширины экрана, доля высоты], "pixel": true (масштаб только целый),
 *   "styles": { "имя": { свойства } },  "data": { значения по умолчанию },  "children": [ узлы ] }
 * </pre>
 * Узлы и свойства — {@link UiNode}; подстановки и условия — {@link UiExpr}.
 */
public final class UiScreen extends Screen {

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey/ui");

    /** Связь с ядром: файлы с сервера и отправка на сервер (C2S op 5). */
    public interface Source {
        byte[] get(String path);
        String sha(String path);
        Identifier texture(String path);
        void send(byte[] payload);
    }

    private static Source source;
    private static Map<String, JsonObject> currentStyles = Map.of();

    public static void init(Source s) { source = s; }

    private final String menuId, layoutPath;
    private String layoutSha;
    private float sizeW, sizeH, fitW, fitH;
    private boolean pixel;
    private Map<String, JsonObject> styles;
    private final JsonObject data;
    private UiNode root;
    private int dataVersion;
    private float scale = 1f, left, top;
    private long openedAt, lastFrame;
    private UiNode hovered, pressed;
    private UiNode dragging;
    private float dragOffset;

    private UiScreen(String menuId, String layoutPath, JsonObject serverData) {
        super(Component.literal(menuId));
        this.menuId = menuId;
        this.layoutPath = layoutPath;
        this.data = new JsonObject();
        build(serverData);
    }

    /** Собрать дерево из файла раскладки (данные сервера — поверх значений по умолчанию из раскладки). */
    private void build(JsonObject serverData) {
        byte[] b = source.get(layoutPath);
        if (b == null) throw new IllegalStateException("нет файла " + layoutPath);
        layoutSha = source.sha(layoutPath);
        JsonObject doc = JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray size = doc.getAsJsonArray("size");
        sizeW = size.get(0).getAsFloat();
        sizeH = size.get(1).getAsFloat();
        JsonArray fit = doc.has("fit") ? doc.getAsJsonArray("fit") : null;
        fitW = fit == null ? 0 : fit.get(0).getAsFloat();
        fitH = fit == null ? 0 : fit.get(1).getAsFloat();
        pixel = doc.has("pixel") && doc.get("pixel").getAsBoolean();
        styles = new HashMap<>();
        if (doc.has("styles")) for (var e : doc.getAsJsonObject("styles").entrySet()) styles.put(e.getKey(), e.getValue().getAsJsonObject());
        currentStyles = styles;
        if (doc.has("data")) for (var e : doc.getAsJsonObject("data").entrySet()) if (!data.has(e.getKey())) data.add(e.getKey(), e.getValue());
        if (serverData != null) for (var e : serverData.entrySet()) data.add(e.getKey(), e.getValue());
        JsonObject r = new JsonObject();
        r.addProperty("type", "group");
        r.addProperty("w", sizeW);
        r.addProperty("h", sizeH);
        r.add("children", doc.has("children") ? doc.get("children") : new JsonArray());
        root = new UiNode(null, r, styles, new UiExpr.Ctx(data, null, 0));
        hovered = pressed = dragging = null;
        dataVersion++;
        if (width > 0) init();
    }

    static Map<String, JsonObject> styles() {
        return currentStyles;
    }

    /** Стили для раскладки, которая идёт сейчас (HUD перед своими слоями). */
    static void useStyles(Map<String, JsonObject> s) {
        currentStyles = s;
    }

    static Source source() {
        return source;
    }

    // ───────────────────────── сеть ─────────────────────────

    /**
     * S2C op 7: 1 OPEN (UTF id, UTF путь раскладки, int n + JSON данных); 2 CLOSE (UTF id, "" — любое);
     * 3 DATA (UTF id, int n + JSON) — обновить данные открытого окна без переоткрытия.
     */
    public static void handle(DataInputStream in) throws IOException {
        int op = in.readUnsignedByte();
        String id = in.readUTF();
        Minecraft mc = Minecraft.getInstance();
        switch (op) {
            case 1 -> {
                String path = in.readUTF();
                JsonObject d = json(in);
                try {
                    UiScreen s = new UiScreen(id, path, d);
                    mc.setScreen(s);
                } catch (Exception e) {
                    LOG.warn("окно {} не собрано: {}", id, e.toString());
                    sendClosed(id);
                }
            }
            case 2 -> {
                if (mc.screen instanceof UiScreen m && (id.isEmpty() || m.menuId.equals(id))) {
                    m.closedByServer = true;
                    mc.setScreen(null);
                }
            }
            case 3 -> {
                JsonObject d = json(in);
                if (mc.screen instanceof UiScreen m && m.menuId.equals(id)) {
                    for (var e : d.entrySet()) m.data.add(e.getKey(), e.getValue());
                    m.dataVersion++;
                }
            }
            default -> LOG.warn("неизвестная команда окна {}", op);
        }
    }

    private boolean closedByServer;

    private static JsonObject json(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n <= 0) return new JsonObject();
        byte[] b = new byte[n];
        in.readFully(b);
        return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void send(int sub, String id, String action, int button) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) {
            out.writeByte(sub);
            out.writeUTF(id);
            if (sub == 1) {
                out.writeUTF(action);
                out.writeByte(button);
            }
        } catch (IOException ignored) {
        }
        source.send(b.toByteArray());
    }

    private static void sendClosed(String id) { send(2, id, null, 0); }

    // ───────────────────────── экран ─────────────────────────

    @Override
    protected void init() {
        if (fitW > 0) {
            float want = Math.min(width * fitW / sizeW, height * fitH / sizeH);
            float max = Math.min((width - 8) / sizeW, (height - 8) / sizeH);
            float sc = Math.min(want, max);
            if (pixel) {   // чёткость: целое число экранных пикселей на пиксель окна, если так помещается
                double gs = Minecraft.getInstance().getWindow().getGuiScale();
                float snapped = (float) (Math.floor(sc * gs) / gs);
                if (snapped * gs >= 1) sc = snapped;
            }
            scale = Math.max(0.2f, sc);
        } else scale = 1f;
        left = Math.round((width - sizeW * scale) / 2f);
        top = Math.round((height - sizeH * scale) / 2f);
        if (openedAt == 0) openedAt = System.currentTimeMillis();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x88000000);   // затемнить мир, без размытия
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // раскладку поправили на сервере — перестроить на лету (данные сохраняются)
        String sha = source.sha(layoutPath);
        if (sha != null && !sha.equals(layoutSha)) {
            try {
                build(null);
            } catch (Exception e) {
                LOG.warn("окно {}: новая раскладка не собрана: {}", menuId, e.toString());
                layoutSha = sha;
            }
        }
        super.render(g, mouseX, mouseY, partial);
        currentStyles = styles;
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float t = (now - openedAt) / 1000f;
        root.layout(font, 0, 0, sizeW, sizeH, data, dataVersion, true, 0, 0);
        float mx = (mouseX - left) / scale, my = (mouseY - top) / scale;
        UiNode h = pick(root, mx, my, null);
        if (h != hovered) {
            if (hovered != null) hovered.hoverTarget = false;
            hovered = h;
            if (h != null) h.hoverTarget = true;
        }
        if (pressed != null) pressed.pressTarget = pressed == hovered;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(left, top);
        pose.scale(scale, scale);
        root.render(g, font, 1f, t, scale, dt);
        pose.popMatrix();
        if (hovered != null && hovered.o.has("tooltip")) {
            List<Component> tip = new ArrayList<>();
            JsonElement tt = hovered.o.get("tooltip");
            if (tt.isJsonArray()) for (JsonElement l : tt.getAsJsonArray()) tip.add(parse(UiExpr.fill(l.getAsString(), hovered.ctx)));
            else tip.add(parse(UiExpr.fill(tt.getAsString(), hovered.ctx)));
            g.setTooltipForNextFrame(font, tip, java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    private static UiNode pick(UiNode n, float mx, float my, UiNode best) {
        if (!n.shown) return best;
        boolean in = mx >= n.x && mx < n.x + n.w && my >= n.y && my < n.y + n.h;
        if (n.type.equals("scroll") && !in) return best;
        if (in && n.interactive() && !n.disabled() && !n.type.equals("scroll")) best = n;
        for (UiNode k : n.kids) best = pick(k, mx, my, best);
        return best;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ev, boolean doubleClick) {
        float bx = (float) (ev.x() - left) / scale, by = (float) (ev.y() - top) / scale;
        UiNode bar = barAt(root, bx, by, null);
        if (bar != null) {
            if (by >= bar.thumbY && by < bar.thumbY + bar.thumbH) dragOffset = by - bar.thumbY;
            else {
                dragOffset = bar.thumbH / 2f;
                bar.scrollToThumb(by - dragOffset);
            }
            dragging = bar;
            return true;
        }
        if (hovered != null && hovered.o.has("action")) {
            pressed = hovered;
            pressed.pressTarget = true;
            return true;
        }
        return super.mouseClicked(ev, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent ev) {
        if (dragging != null) {
            dragging = null;
            return true;
        }
        UiNode p = pressed;
        pressed = null;
        if (p != null) {
            p.pressTarget = false;
            if (p == hovered) {   // клик засчитан, если отпустили над тем же
                clickSound(p.text("sound", ""));
                String action = UiExpr.fill(p.str("action", ""), p.ctx);
                if (action.equals("close")) onClose();
                else send(1, menuId, action, ev.button());
            }
            return true;
        }
        return super.mouseReleased(ev);
    }

    /** Звук нажатия: свойство {@code sound} узла ({@code hoka:<id>} или ванильный), {@code none} — без звука. */
    private static void clickSound(String id) {
        if ("none".equals(id)) return;
        SoundEvent ev;
        try {
            ev = id.isEmpty() ? SoundEvents.UI_BUTTON_CLICK.value() : SoundEvent.createVariableRangeEvent(Identifier.parse(id));
        } catch (Exception e) {
            ev = SoundEvents.UI_BUTTON_CLICK.value();
        }
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ev, 1f));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        float mx = (float) (mouseX - left) / scale, my = (float) (mouseY - top) / scale;
        UiNode s = scrollAt(root, mx, my, null);
        if (s != null) {
            s.scrollY = Math.max(0, Math.min(s.scrollY - (float) scrollY * s.num("scroll_step", 16), Math.max(0, s.contentH - s.h)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent ev, double dx, double dy) {
        if (dragging != null) {
            dragging.scrollToThumb((float) (ev.y() - top) / scale - dragOffset);
            return true;
        }
        return super.mouseDragged(ev, dx, dy);
    }

    private static UiNode barAt(UiNode n, float mx, float my, UiNode best) {
        if (!n.shown) return best;
        if (n.hasBar && mx >= n.barX - 1 && mx < n.barX + n.barW + 1 && my >= n.barY && my < n.barY + n.barH) best = n;
        for (UiNode k : n.kids) best = barAt(k, mx, my, best);
        return best;
    }

    private static UiNode scrollAt(UiNode n, float mx, float my, UiNode best) {
        if (!n.shown) return best;
        if (mx < n.x || mx >= n.x + n.w || my < n.y || my >= n.y + n.h) return best;
        if (n.type.equals("scroll")) best = n;
        for (UiNode k : n.kids) best = scrollAt(k, mx, my, best);
        return best;
    }

    @Override
    public void removed() {
        if (!closedByServer) sendClosed(menuId);   // закрыли сами (Esc, «close», другой экран)
        super.removed();
    }

    static void scissor(GuiGraphics g, float x, float y, float w, float h) {
        g.enableScissor(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h));
    }

    // ───────────────────────── картинки из файлов ─────────────────────────

    private record Dim(String sha, int w, int h) {}

    private static final Map<String, Dim> DIMS = new HashMap<>();

    /** Картинка-файл с сервера: текстура ядра (меняется вместе с файлом) и её размер. */
    static UiNode.Tex fileTex(String path) {
        String sha = source.sha(path);
        if (sha == null) return null;
        Dim d = DIMS.get(path);
        if (d == null || !d.sha.equals(sha)) {
            byte[] b = source.get(path);
            int[] wh = b == null ? null : pngSize(b);
            if (wh == null) return null;
            d = new Dim(sha, wh[0], wh[1]);
            DIMS.put(path, d);
        }
        return new UiNode.Tex(source.texture(path), d.w, d.h);
    }

    /** Ширина и высота PNG по заголовку (IHDR) или null. */
    static int[] pngSize(byte[] head) {
        if (head == null || head.length < 24 || head[12] != 'I' || head[13] != 'H') return null;
        int w = ((head[16] & 255) << 24) | ((head[17] & 255) << 16) | ((head[18] & 255) << 8) | (head[19] & 255);
        int h = ((head[20] & 255) << 24) | ((head[21] & 255) << 16) | ((head[22] & 255) << 8) | (head[23] & 255);
        return new int[]{w, h};
    }

    /** Выход с сервера: забыть кэши. */
    public static void clear() {
        DIMS.clear();
        PARSED.clear();
        UiNode.clearLines();
        UiNode.clearTex();
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof UiScreen s) {
            s.closedByServer = true;
            mc.setScreen(null);
        }
    }

    // ───────────────────────── текст ─────────────────────────

    /** Цветные коды: {@code &#RRGGBB}, {@code &0-f}, {@code &l &o &n &m &r}. */
    static Component parse(String s) {
        if (s == null) return Component.empty();
        Component c = PARSED.get(s);
        if (c != null) return c;
        if (PARSED.size() > 4096) PARSED.clear();
        c = parseRaw(s);
        PARSED.put(s, c);
        return c;
    }

    private static final Map<String, Component> PARSED = new HashMap<>();

    private static Component parseRaw(String s) {
        MutableComponent out = Component.empty();
        Style style = Style.EMPTY.withItalic(false);
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char n = Character.toLowerCase(s.charAt(i + 1));
                Style next = null;
                int skip = 1;
                if (n == '#' && i + 7 < s.length()) {
                    try {
                        next = Style.EMPTY.withItalic(false).withColor(TextColor.fromRgb(Integer.parseInt(s.substring(i + 2, i + 8), 16)));
                        skip = 7;
                    } catch (NumberFormatException ignored) {
                    }
                } else {
                    var fmt = net.minecraft.ChatFormatting.getByCode(n);
                    if (fmt != null) {
                        if (fmt.isColor()) next = Style.EMPTY.withItalic(false).withColor(fmt);
                        else if (fmt == net.minecraft.ChatFormatting.RESET) next = Style.EMPTY.withItalic(false);
                        else next = style.applyFormat(fmt);
                    }
                }
                if (next != null) {
                    if (run.length() > 0) out.append(Component.literal(run.toString()).withStyle(style));
                    run.setLength(0);
                    style = next;
                    i += skip;
                    continue;
                }
            }
            run.append(c);
        }
        if (run.length() > 0) out.append(Component.literal(run.toString()).withStyle(style));
        return out;
    }
}
