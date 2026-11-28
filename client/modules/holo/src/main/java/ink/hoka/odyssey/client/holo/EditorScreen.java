package ink.hoka.odyssey.client.holo;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Окно редактора голограмм в игре: тонкая обёртка над {@link EditorUi} (рисование — {@link McGui}, ввод — в EditorUi).
 * Мир за окном слегка затемнён — голограмма с правками видна вживую.
 */
final class EditorScreen extends Screen implements Gui.Host {

    private final HoloClientModule m;
    private final EditorUi ui;
    private boolean moving;

    EditorScreen(HoloClientModule m) {
        super(Component.literal("Голограммы"));
        this.m = m;
        this.ui = new EditorUi(this);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x50000000);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        m.session.tickAuto(System.currentTimeMillis());
        McGui mg = new McGui(g, m);
        ui.render(mg, width, height, mouseX, mouseY);
        mg.unclip();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ev, boolean doubleClick) {
        return ui.mouseDown(ev.x(), ev.y(), ev.button()) || super.mouseClicked(ev, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent ev) {
        ui.mouseUp(ev.x(), ev.y(), ev.button());
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent ev, double dx, double dy) {
        ui.mouseMove(ev.x(), ev.y());
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        return ui.scroll(x, y, sy) || super.mouseScrolled(x, y, sx, sy);
    }

    @Override
    public boolean keyPressed(KeyEvent ev) {
        if (ev.key() == EditorUi.K_ESC) {
            if (ui.escape()) return true;
            onClose();
            return true;
        }
        return ui.key(ev.key(), ev.modifiers()) || super.keyPressed(ev);
    }

    @Override
    public boolean charTyped(CharacterEvent ev) {
        if (ui.focus() == null) return false;
        ui.typed(ev.codepointAsString());
        return true;
    }

    @Override
    public void removed() {
        if (!moving) m.session.discard();                                   // несохранённое — отброшено (предупреждали)
    }

    // ───────────────────────────── Host ─────────────────────────────

    @Override public Session session() { return m.session; }

    @Override public JsonObject info() { return m.info; }

    @Override public Map<String, Holo.Theme> themes() { return m.themes; }

    @Override public Layout.Metrics metrics() { return m.metrics; }

    @Override public Map<String, String> values() { return m.values; }

    @Override public String map() { return m.map; }

    @Override
    public double[] player() {
        var p = Minecraft.getInstance().player;
        if (p == null) return null;
        Vec3 e = p.getEyePosition();
        return new double[]{e.x, e.y, e.z, p.getYRot(), p.getXRot()};
    }

    @Override public double[] blockTarget() { return HoloClientModule.blockTarget(); }

    @Override
    public String handItem() {
        var p = Minecraft.getInstance().player;
        if (p == null) return null;
        ItemStack it = p.getMainHandItem();
        if (it.isEmpty()) return null;
        var cd = it.get(DataComponents.CUSTOM_DATA);
        if (cd != null) {
            var id = cd.copyTag().getString("hoka_item");
            if (id.isPresent() && !id.get().isEmpty()) return "hoka:" + id.get();
        }
        return BuiltInRegistries.ITEM.getKey(it.getItem()).toString();
    }

    @Override
    public List<String> itemSuggest(String prefix) {
        String q = prefix.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (var e : EditorUi.arr(m.info, "items")) if (e.getAsString().contains(q) && out.size() < 4) out.add(e.getAsString());
        if (q.length() >= 2) for (var key : BuiltInRegistries.ITEM.keySet()) {
            if (out.size() >= 7) break;
            String s = key.toString();
            if (s.contains(q) && !out.contains(s)) out.add(s);
        }
        return out;
    }

    @Override
    public void delete(String id) { m.send(HoloClientModule.C_DELETE, out -> out.writeUTF(id)); }

    @Override
    public void tp(String id) { if (id != null) m.send(HoloClientModule.C_TP, out -> out.writeUTF(id)); }

    @Override
    public void saveTemplate(String name, JsonObject holo) {
        m.send(HoloClientModule.C_TPL_SAVE, out -> { out.writeUTF(name); HoloClientModule.str(out, Session.json(holo)); });
    }

    @Override
    public void deleteTemplate(String name) { m.send(HoloClientModule.C_TPL_DELETE, out -> out.writeUTF(name)); }

    @Override
    public void move(String id) {
        if (id == null) return;
        moving = true;
        m.mover.start(id);
    }

    @Override public void close() { onClose(); }

    @Override public String clipboard() { return Minecraft.getInstance().keyboardHandler.getClipboard(); }

    @Override public void setClipboard(String s) { Minecraft.getInstance().keyboardHandler.setClipboard(s); }

    @Override
    public String status() { return System.currentTimeMillis() - m.lastResultAt < 8000 ? m.lastResult : ""; }

    @Override public boolean statusOk() { return m.lastOk; }

    // ───────────────────────────── рисование ─────────────────────────────

    /** {@link Gui} поверх GuiGraphics: дробные координаты — через масштаб ×4. */
    static final class McGui implements Gui {
        private final GuiGraphics g;
        private final HoloClientModule m;
        private final net.minecraft.client.gui.Font font = Minecraft.getInstance().font;

        McGui(GuiGraphics g, HoloClientModule m) {
            this.g = g;
            this.m = m;
        }

        @Override public int width(String s) { return font.width(s); }

        @Override
        public void fill(float x0, float y0, float x1, float y1, int argb) {
            if ((argb >>> 24) == 0 || x1 <= x0 || y1 <= y0) return;
            if (x0 == (int) x0 && y0 == (int) y0 && x1 == (int) x1 && y1 == (int) y1) { g.fill((int) x0, (int) y0, (int) x1, (int) y1, argb); return; }
            var p = g.pose();
            p.pushMatrix();
            p.scale(0.25f, 0.25f);
            g.fill(Math.round(x0 * 4), Math.round(y0 * 4), Math.round(x1 * 4), Math.round(y1 * 4), argb);
            p.popMatrix();
        }

        @Override
        public void text(String s, float x, float y, int argb) {
            if (x == (int) x && y == (int) y) { g.drawString(font, s, (int) x, (int) y, argb, false); return; }
            var p = g.pose();
            p.pushMatrix();
            p.translate(x, y);
            g.drawString(font, s, 0, 0, argb, false);
            p.popMatrix();
        }

        @Override
        public void runs(List<Mini.Run> runs, float x, float y, float s, float alpha) {
            int a = Math.round(alpha * 255);
            if (a < 6) return;
            var p = g.pose();
            p.pushMatrix();
            p.translate(x, y);
            p.scale(s, s);
            g.drawString(font, HoloClientModule.component(new Layout.TLine(runs, 0)), 0, 0, (a << 24) | 0xFFFFFF, false);
            p.popMatrix();
        }

        @Override
        public void item(String key, float x, float y, float size) {
            ItemStack it = m.stack(null, key);
            if (it == null) return;
            var p = g.pose();
            p.pushMatrix();
            p.translate(x, y);
            p.scale(size / 16f, size / 16f);
            g.renderItem(it, 0, 0);
            p.popMatrix();
        }

        @Override
        public void image(String name, float x, float y, float w, float h, float alpha) {
            if (name == null || name.isEmpty()) return;
            var p = g.pose();
            p.pushMatrix();
            p.translate(x, y);
            p.scale(w / 64f, h / 64f);
            g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, m.ctx().texture("holo/img/" + name), 0, 0, 0, 0, 64, 64, 64, 64,
                    Paint.argb(alpha, 0xFFFFFF));
            p.popMatrix();
        }

        @Override
        public void push(float tx, float ty, float s) {
            g.pose().pushMatrix();
            g.pose().translate(tx, ty);
            g.pose().scale(s, s);
        }

        @Override public void pop() { g.pose().popMatrix(); }

        private int depth;

        @Override public void clip(int x0, int y0, int x1, int y1) {
            if (depth > 0) g.disableScissor();
            g.enableScissor(x0, y0, Math.max(x0, Math.min(x1, 100000)), Math.max(y0, Math.min(y1, 100000)));
            depth = 1;
        }

        @Override public void unclip() {
            if (depth > 0) g.disableScissor();
            depth = 0;
        }
    }
}
