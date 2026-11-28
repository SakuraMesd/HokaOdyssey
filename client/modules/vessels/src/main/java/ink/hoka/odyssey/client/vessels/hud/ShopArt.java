package ink.hoka.odyssey.client.vessels.hud;

import java.util.ArrayList;
import java.util.List;

import static ink.hoka.odyssey.client.vessels.hud.Hud.AMBER;
import static ink.hoka.odyssey.client.vessels.hud.Hud.DARK;
import static ink.hoka.odyssey.client.vessels.hud.Hud.GOOD;
import static ink.hoka.odyssey.client.vessels.hud.Hud.GRAY;
import static ink.hoka.odyssey.client.vessels.hud.Hud.RED;
import static ink.hoka.odyssey.client.vessels.hud.Hud.WHITE;
import static ink.hoka.odyssey.client.vessels.hud.Hud.argb;

/**
 * Окно мастерской корабля в стиле HUD (как окно корабля): сервер открывает сундук на 6 рядов — 0–7 рецепты (значок —
 * что получится), 18–44 груз корабля (только посмотреть), 45 «Назад»; здесь слоты стоят так:
 * <pre>
 *  МАСТЕРСКАЯ · КОРАБЛЬ #3                         [◂] НАЗАД
 *  из груза корабля — в груз
 *  ───────────────────────────────────────────────────────
 *  [▣] Топливо ×16            [▣] Ремкомплект
 *      Кристалл 10/4              Железная 4/4  Никелевая 1/2
 *  …  (2 столбца × 4 строки)
 *  ───────────────────────────────────────────────────────
 *  ГРУЗ КОРАБЛЯ
 *  ▢▢▢▢▢▢▢▢▢          ЛКМ — сделать 1 раз
 *  ▢▢▢▢▢▢▢▢▢          Shift — сколько хватит
 *  ▢▢▢▢▢▢▢▢▢          топливо — сразу в бак
 * </pre>
 * Инвентарь игрока и прочие слоты сундука спрятаны далеко за окном. Что нужно и сколько есть — из S2C SHOP.
 */
public final class ShopArt {

    public static final int W = 304, H = 232, CELL = 18, PAD = 8, HEAD_Y = 24;
    public static final int REC_Y = 28, REC_W = 146, REC_H = 32, DIV_Y = 158, CARGO_LABEL_Y = 161, CARGO_Y = 171;
    public static final int RECIPES = 8, CARGO0 = 18, BACK = 45, SLOTS = 90, BACK_W = 58;
    public static final int BACK_X = W - PAD - BACK_W, BACK_Y = 3;
    private static final float SH = 7;

    public enum Kind { RECIPE, CARGO, BACK, HIDDEN }

    public static Kind kind(int i) {
        if (i >= 0 && i < RECIPES) return Kind.RECIPE;
        if (i >= CARGO0 && i < CARGO0 + 27) return Kind.CARGO;
        if (i == BACK) return Kind.BACK;
        return Kind.HIDDEN;
    }

    /** Левый верх клетки слота в окне; спрятанный — {AWAY, AWAY}. */
    public static int[] cell(int i) {
        return switch (kind(i)) {
            case RECIPE -> new int[]{PAD + (i % 2) * REC_W, REC_Y + (i / 2) * REC_H + 1};
            case CARGO -> new int[]{PAD + ((i - CARGO0) % 9) * CELL, CARGO_Y + ((i - CARGO0) / 9) * CELL};
            case BACK -> new int[]{BACK_X, BACK_Y};
            case HIDDEN -> new int[]{MenuLayout.AWAY, MenuLayout.AWAY};
        };
    }

    public static int[] slot(int i) {
        int[] c = cell(i);
        return kind(i) == Kind.HIDDEN ? c : new int[]{c[0] + 1, c[1] + 1};
    }

    public static int[] origin(int screenW, int screenH) { return new int[]{(screenW - W) / 2, (screenH - H) / 2}; }

    private Canvas bg;
    private float bgGs;

    public void background(Gfx g, float left, float top) {
        float gs = g.scale();
        if (bg == null || bgGs != gs) { bg = paint(gs); bgGs = gs; }
        g.image("shopBg", bg, left - SH, top - SH, 1);
    }

    static Canvas paint(float gs) {
        Canvas c = new Canvas(Hud.px(W + 2 * SH, gs), Hud.px(H + 2 * SH, gs));
        float o = SH * gs, t = Math.max(1, 0.9f * gs);
        PilotHud.paintPanel(c, o, o, W * gs, H * gs, gs, 0.84f);
        for (float y : new float[]{HEAD_Y, DIV_Y}) {
            for (int x = Math.round(o + PAD * gs); x < Math.round(o + (W - PAD) * gs); x++) {
                float e = Math.min(1, Math.min(x - (o + PAD * gs), o + (W - PAD) * gs - x) / (24 * gs));
                for (int k = 0; k < Math.round(t); k++) c.put(x, Math.round(o + y * gs) + k, GRAY, 0.28f * e);
            }
        }
        for (int i = 0; i < RECIPES; i++) {                                     // плашки рецептов
            int[] p = cell(i);
            c.roundRect(o + p[0] * gs, o + (p[1] - 1) * gs, (REC_W - 4) * gs, (REC_H - 2) * gs, 2 * gs, GRAY, 0.05f);
        }
        int[] b = cell(BACK);                                                   // кнопка «Назад»
        c.roundRect(o + b[0] * gs, o + b[1] * gs, BACK_W * gs, CELL * gs, 2 * gs, GRAY, 0.07f);
        c.roundRectStroke(o + b[0] * gs + t / 2, o + b[1] * gs + t / 2, BACK_W * gs - t, CELL * gs - t, 2 * gs, t, WHITE, 0.3f);
        for (int i = 0; i < SLOTS; i++) {
            Kind k = kind(i);
            if (k == Kind.HIDDEN) continue;
            int[] p = cell(i);
            float x = o + p[0] * gs, y = o + p[1] * gs, s = CELL * gs, in = 0.5f * gs;
            c.roundRect(x + in, y + in, s - 2 * in, s - 2 * in, 1.5f * gs, DARK, 0.62f);
            c.roundRectStroke(x + in + t / 2, y + in + t / 2, s - 2 * in - t, s - 2 * in - t, 1.5f * gs, t, k == Kind.RECIPE ? WHITE : GRAY,
                    k == Kind.RECIPE ? 0.26f : 0.16f);
        }
        c.touch();
        return c;
    }

    /** Короче: «Урановая руда» → «Урановая», «Топливный кристалл» → «Кристалл». */
    static String shortName(String s) {
        if (s == null) return "";
        if (s.equals("Топливный кристалл")) return "Кристалл";
        return s.endsWith(" руда") ? s.substring(0, s.length() - 5) : s;
    }

    /** Строки «что нужно» в ширину w (пиксели интерфейса при множителе k), не больше lines строк; не влезло — «…». */
    static List<List<ShopInfo.Need>> wrap(List<ShopInfo.Need> needs, float w, float k, java.util.function.ToIntFunction<String> width, int lines) {
        List<List<ShopInfo.Need>> out = new ArrayList<>();
        List<ShopInfo.Need> cur = new ArrayList<>();
        float lw = 0, gap = width.applyAsInt("  ") * k;
        for (ShopInfo.Need n : needs) {
            float nw = width.applyAsInt(needText(n)) * k;
            if (!cur.isEmpty() && lw + gap + nw > w) {
                out.add(cur);
                cur = new ArrayList<>();
                lw = 0;
            }
            lw += (cur.isEmpty() ? 0 : gap) + nw;
            cur.add(n);
        }
        if (!cur.isEmpty()) out.add(cur);
        return out.size() > lines ? out.subList(0, lines) : out;
    }

    static String needText(ShopInfo.Need n) { return shortName(n.name()) + " " + n.have() + "/" + n.need(); }

    /** Подписи: заголовок, рецепты (название, сколько, что нужно — цветом «хватает»), груз, подсказки, рамка под мышью. */
    public void labels(Gfx g, float x0, float y0, ShopInfo s, int hovered) {
        float gs = g.scale(), small = Hud.crisp(0.75f, gs);
        g.text("МАСТЕРСКАЯ" + (s.id > 0 ? " · КОРАБЛЬ #" + s.id : ""), x0 + PAD, y0 + 4, 1, argb(WHITE, 0.95f), -1, true);
        g.text("из груза корабля — в груз", x0 + PAD, y0 + 14, small, argb(GRAY, 0.6f), -1, true);
        g.text("НАЗАД", x0 + BACK_X + CELL + 4, y0 + BACK_Y + 6 + (1 - small) * 3, small, argb(WHITE, 0.9f), -1, true);
        for (int i = 0; i < RECIPES; i++) {
            if (i >= s.recipes.size()) continue;
            ShopInfo.Recipe r = s.recipes.get(i);
            int[] p = cell(i);
            float tx = x0 + p[0] + CELL + 4, ty = y0 + p[1] + 1, tw = REC_W - CELL - 10;
            String name = r.name();
            String cnt = r.count() > 1 ? " ×" + r.count() : "";
            g.text(name, tx, ty, 1, argb(r.ok() ? WHITE : GRAY, r.ok() ? 0.95f : 0.75f), -1, true);
            if (!cnt.isEmpty()) g.text(cnt, tx + g.textWidth(name), ty, 1, argb(AMBER, 0.85f), -1, true);
            if (r.ok()) g.fill(x0 + p[0] - 2, y0 + p[1] + 2, x0 + p[0] - 1, y0 + p[1] + CELL - 2, argb(GOOD, 0.9f));
            List<List<ShopInfo.Need>> lines = wrap(r.needs(), tw, small, g::textWidth, 3);
            int shown = 0;
            for (int li = 0; li < lines.size(); li++) {
                float lx = tx, ly = ty + 10 + li * 7;
                for (ShopInfo.Need n : lines.get(li)) {
                    String t = needText(n);
                    g.text(t, lx, ly, small, argb(n.ok() ? GRAY : RED, n.ok() ? 0.7f : 0.95f), -1, true);
                    lx += (g.textWidth(t) + g.textWidth("  ")) * small;
                    shown++;
                }
            }
            if (shown < r.needs().size()) g.text("…", tx + tw, ty + 10 + 2 * 7, small, argb(GRAY, 0.7f), 1, true);
        }
        g.text("ГРУЗ КОРАБЛЯ", x0 + PAD, y0 + CARGO_LABEL_Y + (1 - small) * 7, small, argb(GRAY, 0.62f), -1, true);
        float hx = x0 + PAD + 9 * CELL + 10, hy = y0 + CARGO_Y + 4;
        String[][] tips = {{"ЛКМ", "сделать 1 раз"}, {"Shift", "сколько хватит"}, {"", "топливо — сразу в бак"}, {"", "наведите — что нужно"}};
        for (int i = 0; i < tips.length; i++) {
            float ly = hy + i * 11;
            float kx = hx;
            if (!tips[i][0].isEmpty()) {
                float kw = (g.textWidth(tips[i][0]) + 4) * small;
                g.fill(kx, ly - 1, kx + kw, ly + 7, argb(GRAY, 0.18f));
                g.text(tips[i][0], kx + 2 * small, ly, small, argb(WHITE, 0.9f), -1, false);
                kx += kw + 3;
            }
            g.text(tips[i][1], kx, ly, small, argb(GRAY, 0.75f), -1, true);
        }
        if (hovered >= 0 && kind(hovered) != Kind.HIDDEN) {                    // рамка под мышью
            int[] p = cell(hovered);
            float px = 1 / gs, cx = x0 + p[0], cy = y0 + p[1];
            float bw = hovered == BACK ? BACK_W : CELL;
            g.fill(cx + 1, cy + 1, cx + bw - 1, cy + CELL - 1, argb(GRAY, 0.16f));
            g.fill(cx + 1, cy + 0.5f, cx + bw - 1, cy + 0.5f + px, argb(WHITE, 0.75f));
            g.fill(cx + 1, cy + CELL - 0.5f - px, cx + bw - 1, cy + CELL - 0.5f, argb(WHITE, 0.75f));
            g.fill(cx + 0.5f, cy + 1, cx + 0.5f + px, cy + CELL - 1, argb(WHITE, 0.75f));
            g.fill(cx + bw - 0.5f - px, cy + 1, cx + bw - 0.5f, cy + CELL - 1, argb(WHITE, 0.75f));
        }
    }
}
