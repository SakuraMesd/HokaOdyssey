package ink.hoka.odyssey.client.vessels.hud;

import static ink.hoka.odyssey.client.vessels.hud.Hud.AMBER;
import static ink.hoka.odyssey.client.vessels.hud.Hud.DARK;
import static ink.hoka.odyssey.client.vessels.hud.Hud.GOOD;
import static ink.hoka.odyssey.client.vessels.hud.Hud.GRAY;
import static ink.hoka.odyssey.client.vessels.hud.Hud.RED;
import static ink.hoka.odyssey.client.vessels.hud.Hud.WHITE;
import static ink.hoka.odyssey.client.vessels.hud.Hud.argb;
import static ink.hoka.odyssey.client.vessels.hud.MenuLayout.*;

/**
 * Рисунок окна корабля в стиле HUD: тёмная полупрозрачная панель с мягкой тенью и тонкой светлой рамкой, клетки
 * слотов, группы с подписями (ГРУЗ, УЛУЧШЕНИЯ, ТОПЛИВО со шкалой бака, ИНВЕНТАРЬ), сводка справа снизу; в пустых
 * слотах улучшений и топлива — бледные значки, что туда класть. Фон — один холст (перерисовывается только при смене
 * масштаба GUI), подписи и шкала — каждый кадр. Предметы рисует игра (слоты — её).
 */
public final class MenuArt {

    private Canvas bg, upIcon, fuelIcon;
    private float bgGs;
    private static final float SH = 7;

    /** Фон окна: left/top — левый верх окна (пиксели интерфейса). */
    public void background(Gfx g, float left, float top) {
        float gs = g.scale();
        if (bg == null || bgGs != gs) {
            bg = paintBackground(gs);
            bgGs = gs;
            upIcon = icon(gs, true);
            fuelIcon = icon(gs, false);
        }
        g.image("menuBg", bg, left - SH, top - SH, 1);
    }

    static Canvas paintBackground(float gs) {
        Canvas c = new Canvas(Hud.px(W + 2 * SH, gs), Hud.px(H + 2 * SH, gs));
        float o = SH * gs;
        PilotHud.paintPanel(c, o, o, W * gs, H * gs, gs, 0.84f);
        float t = Math.max(1, 0.9f * gs);
        for (float y : new float[]{20, DIV_Y}) {                                  // разделители: заголовок и низ
            for (int x = Math.round(o + PAD * gs); x < Math.round(o + (W - PAD) * gs); x++) {
                float e = Math.min(1, Math.min(x - (o + PAD * gs), o + (W - PAD) * gs - x) / (24 * gs));
                for (int k = 0; k < Math.round(t); k++) c.put(x, Math.round(o + y * gs) + k, GRAY, 0.28f * e);
            }
        }
        for (int i = 0; i < SLOTS; i++) {                                        // клетки слотов
            if (kind(i) == Kind.HIDDEN) continue;
            int[] p = cell(i);
            float x = o + p[0] * gs, y = o + p[1] * gs, s = CELL * gs;
            Kind k = kind(i);
            float inset = 0.5f * gs;
            c.roundRect(x + inset, y + inset, s - 2 * inset, s - 2 * inset, 1.5f * gs, DARK, 0.62f);
            c.roundRectStroke(x + inset + t / 2, y + inset + t / 2, s - 2 * inset - t, s - 2 * inset - t, 1.5f * gs, t,
                    k == Kind.UPGRADE || k == Kind.FUEL ? WHITE : GRAY, k == Kind.UPGRADE || k == Kind.FUEL ? 0.26f : 0.16f);
        }
        float gx = o + GAUGE_X * gs, gy = o + GAUGE_Y * gs;                       // дорожка шкалы бака
        c.roundRect(gx - t, gy - t, GAUGE_W * gs + 2 * t, GAUGE_H * gs + 2 * t, 1.5f * gs, DARK, 0.7f);
        c.roundRectStroke(gx - t / 2, gy - t / 2, GAUGE_W * gs + t, GAUGE_H * gs + t, 1.5f * gs, t, GRAY, 0.3f);
        c.touch();
        return c;
    }

    /** Бледный значок пустого слота: улучшение — двойной шеврон «вверх», топливо — канистра с каплей. */
    static Canvas icon(float gs, boolean upgrade) {
        int n = Hud.px(16, gs);
        Canvas c = new Canvas(n, n);
        float x = n / 2f, t = Math.max(1.2f, 1.3f * gs), u = gs;
        if (upgrade) {
            c.line(x - 5 * u, 8.5f * u, x, 3.5f * u, t, GRAY, 1, false);
            c.line(x, 3.5f * u, x + 5 * u, 8.5f * u, t, GRAY, 1, false);
            c.line(x - 5 * u, 12.5f * u, x, 7.5f * u, t, GRAY, 1, false);
            c.line(x, 7.5f * u, x + 5 * u, 12.5f * u, t, GRAY, 1, false);
        } else {
            c.roundRectStroke(x - 4.5f * u, 4 * u, 9 * u, 10.5f * u, 1.5f * u, t, GRAY, 1);
            c.line(x - 2 * u, 2.2f * u, x + 1.5f * u, 2.2f * u, t, GRAY, 1, false);   // ручка
            c.line(x + 3 * u, 4 * u, x + 4.5f * u, 2.6f * u, t, GRAY, 1, false);     // горлышко
            c.disk(x, 10.2f * u, 1.8f * u, GRAY, 1);                                // капля
            c.triangle(x, 6.6f * u, x - 1.7f * u, 9.8f * u, x + 1.7f * u, 9.8f * u, GRAY, 1, false);
        }
        c.touch();
        return c;
    }

    /**
     * Подписи, шкала бака, сводка, значки пустых слотов, рамка слота под мышью. left/top — левый верх окна (в игре —
     * 0, 0: подписи рисуются уже в системе окна). empty — пуст ли слот (по номеру в меню), hovered — слот под мышью.
     */
    public void labels(Gfx g, float left, float top, MenuInfo m, boolean[] empty, int hovered) {
        float gs = g.scale(), small = Hud.crisp(0.75f, gs);
        if (bg == null || bgGs != gs) { bg = paintBackground(gs); bgGs = gs; upIcon = icon(gs, true); fuelIcon = icon(gs, false); }
        float x0 = left, y0 = top;

        // заголовок
        String title = "КОРАБЛЬ" + (m.id > 0 ? " #" + m.id : "") + (m.kind.isEmpty() ? "" : " · " + m.kind.toUpperCase(java.util.Locale.ROOT));
        g.text(title, x0 + PAD, y0 + 7, 1, argb(WHITE, 0.95f), -1, true);
        if (m.owner != null) {
            float room = W - 2 * PAD - g.textWidth(title) - 12;
            String own = "владелец " + m.owner;
            if (g.textWidth(own) * small > room) own = m.owner;
            if (g.textWidth(own) * small <= room) g.text(own, x0 + W - PAD, y0 + 7 + (1 - small) * 7, small, argb(GRAY, 0.6f), 1, true);
        }

        // группы
        label(g, "ГРУЗ", x0 + PAD, y0 + GRID_Y - 10, small);
        label(g, "УЛУЧШЕНИЯ", x0 + RIGHT_X, y0 + GRID_Y - 10, small);
        label(g, "ТОПЛИВО", x0 + RIGHT_X, y0 + FUEL_LABEL_Y, small);
        label(g, "ИНВЕНТАРЬ", x0 + PAD, y0 + INV_LABEL_Y, small);
        label(g, "СВОДКА", x0 + RIGHT_X, y0 + INV_LABEL_Y, small);

        // значки в пустых слотах улучшений и топлива
        for (int i = UP0; i < FUEL0 + FUEL; i++) {
            Kind k = kind(i);
            if ((k != Kind.UPGRADE && k != Kind.FUEL) || empty == null || i >= empty.length || !empty[i]) continue;
            int[] p = slot(i);
            g.image(k == Kind.UPGRADE ? "iconUp" : "iconFuel", k == Kind.UPGRADE ? upIcon : fuelIcon, x0 + p[0], y0 + p[1], 0.32f);
        }

        // слот под мышью — своя рамка (под предметом)
        if (hovered >= 0 && kind(hovered) != Kind.HIDDEN) {
            int[] p = cell(hovered);
            float px = 1 / gs, cx = x0 + p[0], cy = y0 + p[1];
            g.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, argb(GRAY, 0.16f));
            g.fill(cx + 1, cy + 0.5f, cx + CELL - 1, cy + 0.5f + px, argb(WHITE, 0.75f));
            g.fill(cx + 1, cy + CELL - 0.5f - px, cx + CELL - 1, cy + CELL - 0.5f, argb(WHITE, 0.75f));
            g.fill(cx + 0.5f, cy + 1, cx + 0.5f + px, cy + CELL - 1, argb(WHITE, 0.75f));
            g.fill(cx + CELL - 0.5f - px, cy + 1, cx + CELL - 0.5f, cy + CELL - 1, argb(WHITE, 0.75f));
        }

        gauge(g, x0, y0, m, small);
        summary(g, x0 + RIGHT_X, y0 + INV_LABEL_Y + 11, W - PAD - RIGHT_X, m, small);
    }

    private static void label(Gfx g, String s, float x, float y, float small) {
        g.text(s, x, y + (1 - small) * 7, small, argb(GRAY, 0.62f), -1, true);
    }

    /** Шкала бака (вертикальная, рядом со слотами топлива): заливка, деления по слотам, цена варпа, числа. */
    private static void gauge(Gfx g, float x0, float y0, MenuInfo m, float small) {
        float gs = g.scale(), px = 1 / gs;
        float x = x0 + GAUGE_X, y = y0 + GAUGE_Y, w = GAUGE_W, h = GAUGE_H;
        float f = Hud.frac(m.fuel, m.capacity);
        boolean low = m.fuel < m.warpCost, empty = m.fuel <= 0;
        int top = empty ? RED : low ? AMBER : WHITE, bot = empty ? RED : low ? AMBER : GRAY;
        if (f > 0) {
            float fy = y + h * (1 - f);
            g.fill(x, fy, x + w / 2, y + h, argb(top, 0.92f));
            g.fill(x + w / 2, fy, x + w, y + h, argb(bot, 0.72f));
            g.fill(x - px, fy - px, x + w + px, fy, argb(WHITE, 0.95f));
        }
        for (int i = 1; i < 4; i++) g.fill(x, y + h * i / 4f, x + w, y + h * i / 4f + px, argb(DARK, 0.75f));
        float tx = x + w + 4;
        g.text(Integer.toString(m.fuel), tx, y, 1, argb(top, 0.95f), -1, true);
        g.text("/" + m.capacity, tx, y + 10, small, argb(GRAY, 0.6f), -1, true);
        if (m.warpCost > 0) {
            float my = y + h * (1 - Hud.frac(m.warpCost, m.capacity));
            g.fill(x - 2, my - px, x + w + 2, my + px, argb(AMBER, 0.95f));
            g.text(Integer.toString(m.warpCost), tx, my - 3.5f * small, small, argb(AMBER, 0.9f), -1, true);
            g.text("варп", tx, my + 5 * small, small, argb(AMBER, 0.6f), -1, true);
        }
    }

    /** Сводка: ключ слева, значение справа; лучше, чем без улучшений, — мягким зелёным. */
    private static void summary(Gfx g, float x, float y, float w, MenuInfo m, float small) {
        Object[][] rows = {
                {"Варп: цена", m.warpCost + " топл.", m.mulWarpCost < 0.999},
                {"Варп: прыжок", Hud.thousands(Math.round(m.warpDistance)) + " бл", false},
                {"Варп: перезар.", num(m.warpReload) + " с", m.mulWarpReload < 0.999},
                {"Урон орудия", num(m.gunDamage), m.mulGunDamage > 1.001},
                {"Темп огня", num(m.gunReload) + " с", m.mulGunReload < 0.999},
                {"Скорость", "×" + num(m.speedMul), m.mulSpeed > 1.001},
                {"Улучшения", m.upgrades + " / " + UPGRADES, m.upgrades > 0},
        };
        for (int i = 0; i < rows.length; i++) {
            float ry = y + i * 10;
            g.text((String) rows[i][0], x, ry + (1 - small) * 7, small, argb(GRAY, 0.62f), -1, true);
            g.text((String) rows[i][1], x + w, ry, 1, argb((Boolean) rows[i][2] ? GOOD : WHITE, 0.92f), 1, true);
        }
    }

    static String num(double v) {
        if (Math.abs(v - Math.round(v)) < 0.005) return Long.toString(Math.round(v));
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }
}
