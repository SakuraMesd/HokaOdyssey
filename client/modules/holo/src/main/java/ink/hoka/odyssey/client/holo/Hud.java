package ink.hoka.odyssey.client.holo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Поверх HUD: подсказка наведённого элемента (под прицелом), режим «двигать», ответ сервера на правку. */
final class Hud {

    private Hud() {}

    static void draw(HoloClientModule m, GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        m.session.tickAuto(System.currentTimeMillis());
        if (mc.screen != null) return;
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        Layout.Box b = m.hoverBox;
        if (b != null) {
            String hint = b.el.hint != null && !b.el.hint.isEmpty() ? Mini.plain(Holo.fill(b.el.hint, m.values)) : null;
            if (hint == null && b.el.type.equals("item")) {
                var it = m.stack(m.hoverPlaced == null ? null : m.hoverPlaced.live(), b.el.item);
                if (it != null) hint = it.getHoverName().getString();
            }
            if (hint != null) box(g, hint, cx, cy + 12, 0xFFE6EEF8, 0x8FD8FF);
        }
        if (m.mover.active()) box(g, m.mover.hint(), cx, 6, 0xFFFFE2B0, 0xFFC46B);
        long age = System.currentTimeMillis() - m.lastResultAt;
        if (age < 4000 && !m.lastResult.isEmpty())
            box(g, m.lastResult, cx, m.mover.active() ? 22 : 6, m.lastOk ? 0xFFB8FFD0 : 0xFFFFB0B0, m.lastOk ? 0x6BFFAE : 0xFF5A6E);
    }

    /** Тёмная плашка с кубиками-уголками и текстом по центру. */
    static void box(GuiGraphics g, String s, int cx, int y, int textArgb, int accent) {
        var font = Minecraft.getInstance().font;
        int w = font.width(s) + 10, x = cx - w / 2;
        g.fill(x, y, x + w, y + 13, 0xC805080F);
        int a = 0xFF000000 | accent;
        g.fill(x - 1, y - 1, x + 1, y + 1, a);
        g.fill(x + w - 1, y - 1, x + w + 1, y + 1, a);
        g.fill(x - 1, y + 12, x + 1, y + 14, a);
        g.fill(x + w - 1, y + 12, x + w + 1, y + 14, a);
        g.fill(x + 2, y + 12, x + w - 2, y + 13, (0x50 << 24) | accent);
        g.drawString(font, s, x + 5, y + 3, textArgb, false);
    }
}
