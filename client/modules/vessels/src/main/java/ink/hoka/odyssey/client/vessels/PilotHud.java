package ink.hoka.odyssey.client.vessels;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * HUD пилота: тёмные полупрозрачные панели с тонкой светлой рамкой, без ванильных текстур. Внизу по центру — скорость
 * (крупно) с полоской до форсажа и топливо (шкала + число, цена варпа); слева — орудие (перезарядка), справа — варп
 * (готов / зарядка / перезарядка / мало топлива); под ними — подсказки клавиш. Числа — «табличные»: каждая цифра в
 * своей клетке, не прыгают. Всё в пикселях GUI: на узком экране (большой масштаб GUI) боковые панели встают над
 * основной, подсказки — в две строки.
 */
final class PilotHud {

    private PilotHud() {}

    static final int PANEL = 0xB40C1220, RIM = 0x78C8DCF0, LABEL = 0xFF8FA3B8, TEXT = 0xFFE8F2FC, DIM = 0xFF6A7A8C;
    static final int CYAN = 0xFF6FD3FF, ORANGE = 0xFFFFB040, RED = 0xFFFF6A5A, GREEN = 0xFF7CF29A, PURPLE = 0xFFC49BFF;
    private static final int MAIN_W = 200, SIDE_W = 78, GAP = 4;

    /** Что рисовать (собирает Pilot). */
    record View(double speed, double maxBoost, boolean boost, ShipState st, long now, long gunReadyAt, float warpCharge,
                String msg, boolean msgWarn, float msgAlpha) {}

    static void draw(GuiGraphics g, View v) {
        Minecraft mc = Minecraft.getInstance();
        Font f = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        boolean narrow = w < MAIN_W + 2 * (SIDE_W + GAP) + 8;
        int hintsH = hints(g, f, w, h, narrow, true);
        int mh = 38, mx = w / 2 - MAIN_W / 2, my = h - hintsH - 4 - mh;
        main(g, f, mx, my, v);
        int sh = narrow ? 26 : 38, sy = narrow ? my - GAP - sh : my;
        int lx = narrow ? mx : mx - GAP - SIDE_W, rx = narrow ? mx + MAIN_W - SIDE_W : mx + MAIN_W + GAP;
        int sw = narrow ? (MAIN_W - GAP) / 2 : SIDE_W;
        if (narrow) rx = mx + MAIN_W - sw;
        gun(g, f, lx, sy, sw, sh, v);
        warp(g, f, rx, sy, sw, sh, v);
        if (v.msg() != null && v.msgAlpha() > 0.02f) {                         // строка от сервера — над панелями
            int a = Math.round(255 * Math.min(1, v.msgAlpha()));
            int col = (a << 24) | ((v.msgWarn() ? RED : CYAN) & 0xFFFFFF);
            int tw = f.width(v.msg()), ty = (narrow ? sy : my) - 14;
            g.fill(w / 2 - tw / 2 - 4, ty - 2, w / 2 + tw / 2 + 4, ty + 10, (Math.round(a * 0.7f) << 24) | 0x0C1220);
            g.drawString(f, v.msg(), w / 2 - tw / 2, ty, col, false);
        }
        if (v.warpCharge() >= 0) {                                              // зарядка варпа — под прицелом
            int bw = 90, bx = w / 2 - bw / 2, by = h / 2 + 18;
            String s = "ВАРП  " + Math.round(v.warpCharge() * 100) + "%";
            panel(g, bx - 4, by - 2, bw + 8, 20);
            tab(g, f, s, w / 2 - tabWidth(f, s) / 2, by + 1, PURPLE);
            bar(g, bx, by + 12, bw, 3, v.warpCharge(), PURPLE);
            g.drawString(f, "R — отмена", w / 2 - f.width("R — отмена") / 2, by + 21, DIM, false);
        }
    }

    // ───────────────────────── панели ─────────────────────────

    private static void main(GuiGraphics g, Font f, int x, int y, View v) {
        panel(g, x, y, MAIN_W, 38);
        g.fill(x + MAIN_W / 2, y + 5, x + MAIN_W / 2 + 1, y + 33, RIM);     // разделитель
        // скорость
        g.drawString(f, "СКОРОСТЬ", x + 6, y + 4, LABEL, false);
        String sp = String.format("%03d", Math.min(999, Math.round(v.speed())));
        g.pose().pushMatrix();
        g.pose().translate(x + 6, y + 14);
        g.pose().scale(2f, 2f);
        tab(g, f, sp, 0, 0, v.boost() ? ORANGE : TEXT);
        g.pose().popMatrix();
        g.drawString(f, "бл/с", x + 6 + tabWidth(f, sp) * 2 + 3, y + 21, DIM, false);
        if (v.boost()) g.drawString(f, "ФОРСАЖ", x + MAIN_W / 2 - 4 - f.width("ФОРСАЖ"), y + 4, ORANGE, false);
        bar(g, x + 6, y + 33, MAIN_W / 2 - 12, 2, (float) (v.speed() / Math.max(1, v.maxBoost())), v.boost() ? ORANGE : CYAN);
        // топливо
        ShipState st = v.st();
        int fx = x + MAIN_W / 2 + 6, fw = MAIN_W / 2 - 12;
        g.drawString(f, "ТОПЛИВО", fx, y + 4, LABEL, false);
        String fuel = st.known ? st.fuel + "/" + st.capacity : "—";
        tab(g, f, fuel, fx + fw - tabWidth(f, fuel), y + 4, st.empty || st.fuel < st.warpCost ? RED : TEXT);
        float frac = st.known ? st.fuel / (float) st.capacity : 0;
        bar(g, fx, y + 16, fw, 6, frac, st.empty ? RED : ORANGE);
        if (st.known && st.warpCost > 0) {                                      // отметка цены варпа на шкале
            int mx = fx + Math.round(fw * Math.min(1, st.warpCost / (float) st.capacity));
            g.fill(mx, y + 14, mx + 1, y + 24, PURPLE);
        }
        String cost = st.empty ? "аварийный ход" : "варп: " + st.warpCost;
        g.drawString(f, cost, fx, y + 26, st.empty ? RED : DIM, false);
    }

    private static void gun(GuiGraphics g, Font f, int x, int y, int w, int h, View v) {
        panel(g, x, y, w, h);
        g.drawString(f, "ОРУДИЕ", x + 5, y + 4, LABEL, false);
        long left = Math.max(0, v.gunReadyAt() - v.now());
        float k = 1 - left / (float) Math.max(1, v.st().gunReload);
        String s = left <= 0 ? "ГОТОВО" : String.format(java.util.Locale.ROOT, "%.1f с", left / 20f);
        int col = left <= 0 ? GREEN : ORANGE;
        if (h >= 30) {
            tab(g, f, s, x + 5, y + 15, col);
            bar(g, x + 5, y + h - 8, w - 10, 3, k, col);
        } else {
            tab(g, f, s, x + w - 5 - tabWidth(f, s), y + 4, col);
            bar(g, x + 5, y + h - 8, w - 10, 3, k, col);
        }
    }

    private static void warp(GuiGraphics g, Font f, int x, int y, int w, int h, View v) {
        panel(g, x, y, w, h);
        g.drawString(f, "ВАРП", x + 5, y + 4, LABEL, false);
        ShipState st = v.st();
        String s;
        int col;
        float k;
        long reload = Math.max(0, st.warpReloadEnd - v.now());
        if (v.warpCharge() >= 0) { s = Math.round(v.warpCharge() * 100) + "%"; col = PURPLE; k = v.warpCharge(); }
        else if (st.warpPhase == 2) { s = "НАВОДКА"; col = PURPLE; k = 1; }
        else if (reload > 0) { s = (reload + 19) / 20 + " с"; col = ORANGE; k = 1 - reload / (float) st.warpReload; }
        else if (st.known && st.fuel < st.warpCost) { s = "НЕТ ТОПЛ."; col = RED; k = st.fuel / (float) Math.max(1, st.warpCost); }
        else { s = "ГОТОВ"; col = GREEN; k = 1; }
        if (h >= 30) tab(g, f, s, x + 5, y + 15, col);
        else tab(g, f, s, x + w - 5 - tabWidth(f, s), y + 4, col);
        bar(g, x + 5, y + h - 8, w - 10, 3, k, col);
    }

    /** Подсказки клавиш внизу; measure — только посчитать высоту. Возвращает высоту полосы. */
    private static int hints(GuiGraphics g, Font f, int w, int h, boolean narrow, boolean draw) {
        String[][] all = {{"F", "выйти"}, {"G", "меню"}, {"ЛКМ", "огонь"}, {"R", "варп"}, {"F5", "вид"}, {"Ctrl+W", "форсаж"}};
        int total = 0;
        for (String[] k : all) total += item(f, k) + 8;
        boolean two = total > w - 8;
        int lines = two ? 2 : 1;
        if (!draw) return lines * 12;
        int per = two ? (all.length + 1) / 2 : all.length;
        for (int line = 0; line < lines; line++) {
            int from = line * per, to = Math.min(all.length, from + per), lw = 0;
            for (int i = from; i < to; i++) lw += item(f, all[i]) + 8;
            int x = w / 2 - (lw - 8) / 2, y = h - 12 * (lines - line);
            for (int i = from; i < to; i++) {
                String key = all[i][0], text = all[i][1];
                int kw = f.width(key) + 4;
                g.fill(x, y, x + kw, y + 10, 0x50FFFFFF);
                g.fill(x, y + 10, x + kw, y + 11, 0x30FFFFFF);
                g.drawString(f, key, x + 2, y + 1, TEXT, false);
                g.drawString(f, text, x + kw + 3, y + 1, LABEL, false);
                x += item(f, all[i]) + 8;
            }
        }
        return lines * 12;
    }

    private static int item(Font f, String[] k) { return f.width(k[0]) + 4 + 3 + f.width(k[1]); }

    // ───────────────────────── примитивы ─────────────────────────

    static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
        g.fill(x + 1, y, x + w - 1, y + 1, RIM);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, RIM);
        g.fill(x, y + 1, x + 1, y + h - 1, RIM);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, RIM);
    }

    static void bar(GuiGraphics g, int x, int y, int w, int h, float frac, int color) {
        frac = Math.clamp(Float.isFinite(frac) ? frac : 0, 0f, 1f);
        g.fill(x, y, x + w, y + h, 0x70000000);
        int fw = Math.round(w * frac);
        if (fw > 0) {
            g.fill(x, y, x + fw, y + h, color);
            g.fill(x, y, x + fw, y + 1, (color & 0xFFFFFF) | 0x60000000 | 0x404040);   // блик сверху
        }
    }

    /** Ширина клетки цифры. */
    private static final int CELL = 6;

    /** Текст «табличными» цифрами: каждая цифра — посередине своей клетки, ширина числа не зависит от цифр. */
    static void tab(GuiGraphics g, Font f, String s, int x, int y, int color) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String ch = String.valueOf(c);
            if (Character.isDigit(c)) {
                g.drawString(f, ch, x + (CELL - f.width(ch)) / 2, y, color, false);
                x += CELL;
            } else {
                g.drawString(f, ch, x, y, color, false);
                x += f.width(ch);
            }
        }
    }

    static int tabWidth(Font f, String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w += Character.isDigit(c) ? CELL : f.width(String.valueOf(c));
        }
        return w;
    }
}
