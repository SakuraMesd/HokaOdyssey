package ink.hoka.odyssey.client.vessels.hud;

import java.util.Locale;

import static ink.hoka.odyssey.client.vessels.hud.Hud.AMBER;
import static ink.hoka.odyssey.client.vessels.hud.Hud.DARK;
import static ink.hoka.odyssey.client.vessels.hud.Hud.GOOD;
import static ink.hoka.odyssey.client.vessels.hud.Hud.GRAY;
import static ink.hoka.odyssey.client.vessels.hud.Hud.WHITE;
import static ink.hoka.odyssey.client.vessels.hud.Hud.argb;

/**
 * HUD редактора камер (vessels-5) — в стиле HUD пилота: слева сверху — плашка «РЕДАКТОР КАМЕР» с областью и списком
 * камер (текущая — светлой полосой; «есть» — сохранена, «от вида», * — правка не сохранена), справа сверху — числа текущей
 * камеры (точка в системе корабля, курс, наклон, FOV, скорость, плавность), в центре — перекрестие, снизу — подсказки
 * клавиш, сверху по центру — строка состояния («Сохранено…»).
 */
public final class EditorHud {

    static final float M = 4, PANEL_W = 132, ROW = 10;

    private final java.util.Map<String, Canvas> panels = new java.util.HashMap<>();

    /** Подсказки: клавиша — что делает (две строки; на узком экране — сколько влезет). */
    static final String[][] KEYS = {
            {"WASD", "лететь"}, {"Пробел/C", "вверх/вниз"}, {"Shift", "медленно"}, {"Ctrl", "быстро"}, {"Колёсико", "скорость"},
            {"Alt+колёс.", "FOV"}, {"1-6", "камера"}, {"[ ]", "плавность"}, {"V", "на корабль"},
            {"ЛКМ/Enter", "сохранить"}, {"R", "сброс"}, {"ПКМ/Esc", "выйти"}};

    public void draw(Gfx g, EditorView v) {
        float gs = g.scale(), W = g.width(), H = g.height();
        float small = Hud.crisp(0.75f, gs);
        // перекрестие
        float cx = W / 2, cy = H / 2, px = 1 / gs;
        g.fill(cx - 5, cy - px, cx - 1.5f, cy + px, argb(WHITE, 0.85f));
        g.fill(cx + 1.5f, cy - px, cx + 5, cy + px, argb(WHITE, 0.85f));
        g.fill(cx - px, cy - 5, cx + px, cy - 1.5f, argb(WHITE, 0.85f));
        g.fill(cx - px, cy + 1.5f, cx + px, cy + 5, argb(WHITE, 0.85f));

        int n = v.names().length;
        float lh = 26 + n * ROW + 4;
        float pw = Math.min(PANEL_W, (W - 3 * M) / 2);
        panel(g, "edL", M, M, pw, lh, gs);
        float x = M + 6, y = M + 4, iw = pw - 12;
        g.text("РЕДАКТОР КАМЕР", x, y, 1, argb(AMBER, 0.95f), -1, true);
        g.text(v.shipScope() ? v.title() + " (поверх вида)" : v.title(), x, y + 11, small, argb(GRAY, 0.75f), -1, true);
        float ry = y + 22;
        for (int i = 0; i < n; i++) {
            int s = v.states()[i];
            boolean cur = i == v.which(), locked = (s & EditorView.LOCKED) != 0;
            if (cur) {
                g.fill(x - 3, ry - 1, x + iw + 3, ry + ROW - 1, argb(WHITE, 0.14f));
                g.fill(x - 3, ry - 1, x - 3 + 1.5f, ry + ROW - 1, argb(AMBER, 0.95f));
            }
            int col = locked ? GRAY : cur ? WHITE : GRAY;
            float a = locked ? 0.35f : cur ? 0.98f : 0.8f;
            g.text(v.names()[i] + ((s & EditorView.DIRTY) != 0 ? " *" : ""), x, ry, small, argb((s & EditorView.DIRTY) != 0 ? AMBER : col, a), -1, true);
            String tag = (s & EditorView.SAVED) != 0 ? "есть" : (s & EditorView.INHERITED) != 0 ? "от вида" : locked ? "" : "нет";
            int tc = (s & EditorView.SAVED) != 0 ? GOOD : GRAY;
            if (!tag.isEmpty()) g.text(tag, x + iw, ry, small, argb(tc, locked ? 0.35f : 0.8f), 1, true);
            ry += ROW;
        }

        // числа текущей камеры
        float rw = Math.min(PANEL_W, (W - 3 * M) / 2), rx = W - M - rw;
        boolean f5 = !Double.isNaN(v.smooth());
        int rows = 6 + (f5 ? 1 : 0);
        float rh = 19 + rows * ROW;
        panel(g, "edR", rx, M, rw, rh, gs);
        float tx = rx + 6, tw = rw - 12, ty = M + 4;
        String name = v.names()[v.which()].substring(2);
        g.text(name, tx, ty, 1, argb(WHITE, 0.95f), -1, true);
        g.text(v.exists() ? "правка" : "новая", tx + tw, ty, small, argb(v.exists() ? GOOD : AMBER, 0.9f), 1, true);
        ty += 13;
        row(g, "X  Y  Z", String.format(Locale.ROOT, "%.2f %.2f %.2f", v.x(), v.y(), v.z()), tx, ty, tw, small, WHITE); ty += ROW;
        row(g, "Курс", String.format(Locale.ROOT, "%.1f°", v.yaw()), tx, ty, tw, small, v.look() ? GRAY : WHITE); ty += ROW;
        row(g, "Наклон", String.format(Locale.ROOT, "%.1f°", v.pitch()), tx, ty, tw, small, v.look() ? GRAY : WHITE); ty += ROW;
        row(g, "FOV", v.fov() > 0 ? Math.round(v.fov()) + "°" : Math.round(v.fovNow()) + "° (игрока)", tx, ty, tw, small, v.fov() > 0 ? AMBER : WHITE); ty += ROW;
        row(g, "Скорость", String.format(Locale.ROOT, "%.1f бл/с", v.speed()), tx, ty, tw, small, WHITE); ty += ROW;
        row(g, "Взгляд", v.look() ? "на корабль" : "мышью", tx, ty, tw, small, v.look() ? AMBER : WHITE); ty += ROW;
        if (f5) row(g, "Плавность", String.format(Locale.ROOT, "%.2f", v.smooth()), tx, ty, tw, small, WHITE);

        // строка состояния
        if (v.status() != null && v.statusAlpha() > 0.02f) {
            float a = Math.min(1, v.statusAlpha());
            float sw = Math.min(W - 2 * M, g.textWidth(v.status()) + 12), sy = M + Math.max(lh, rh) + 6;
            if (W - 2 * pw - 4 * M > sw) sy = M + 4;                                 // влезает между плашками — сверху
            g.fill(cx - sw / 2, sy - 2, cx + sw / 2, sy + 10, argb(DARK, 0.5f * a));
            g.fill(cx - sw / 2, sy + 10, cx + sw / 2, sy + 10 + px, argb(v.statusWarn() ? AMBER : GOOD, 0.6f * a));
            g.text(v.status(), cx, sy, 1, argb(v.statusWarn() ? AMBER : WHITE, a), 0, true);
        }

        // подсказки — снизу, по строкам сколько влезет
        float k = small, gap = 8, hx = M, hy = H - M - 10;
        java.util.List<java.util.List<String[]>> lines = new java.util.ArrayList<>();
        java.util.List<String[]> line = new java.util.ArrayList<>();
        float lw = 0;
        for (String[] kv : KEYS) {
            float w = (g.textWidth(kv[0]) + 4) * k + 3 * k + g.textWidth(kv[1]) * k;
            if (lw > 0 && lw + gap + w > W - 2 * M) { lines.add(line); line = new java.util.ArrayList<>(); lw = 0; }
            lw += (lw > 0 ? gap : 0) + w;
            line.add(kv);
        }
        if (!line.isEmpty()) lines.add(line);
        hy = H - M - lines.size() * 11 + 1;
        for (java.util.List<String[]> l : lines) {
            float w = 0;
            for (String[] kv : l) w += (w > 0 ? gap : 0) + (g.textWidth(kv[0]) + 4) * k + 3 * k + g.textWidth(kv[1]) * k;
            hx = W / 2 - w / 2;
            for (String[] kv : l) {
                float kw = (g.textWidth(kv[0]) + 4) * k;
                g.fill(hx, hy, hx + kw, hy + 9 * k, argb(DARK, 0.45f));
                g.fill(hx, hy + 9 * k, hx + kw, hy + 9 * k + px, argb(GRAY, 0.45f));
                g.text(kv[0], hx + 2 * k, hy + k, k, argb(WHITE, 0.95f), -1, false);
                g.text(kv[1], hx + kw + 3 * k, hy + k, k, argb(GRAY, 0.85f), -1, true);
                hx += kw + 3 * k + g.textWidth(kv[1]) * k + gap;
            }
            hy += 11;
        }
    }

    private static void row(Gfx g, String key, String val, float x, float y, float w, float small, int col) {
        g.text(key, x, y + (1 - small) * 7, small, argb(GRAY, 0.65f), -1, true);
        g.text(val, x + w, y, 1, argb(col, 0.95f), 1, true);
    }

    private void panel(Gfx g, String key, float x, float y, float w, float h, float gs) {
        float m = 5;
        int cw = Hud.px(w + 2 * m, gs), ch = Hud.px(h + 2 * m, gs);
        String spec = cw + "x" + ch;
        Canvas c = panels.get(key + spec);
        if (c == null) {
            c = new Canvas(cw, ch);
            PilotHud.paintPanel(c, m * gs, m * gs, w * gs, h * gs, gs, 0.55f);
            c.touch();
            panels.put(key + spec, c);
        }
        g.image(key + spec, c, x - m, y - m, 1);
    }
}
