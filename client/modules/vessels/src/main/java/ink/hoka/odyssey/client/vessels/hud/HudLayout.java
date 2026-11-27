package ink.hoka.odyssey.client.vessels.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Раскладка HUD пилота по экрану (пиксели интерфейса) — без рисования, проверяется тестом: ничего не налезает и не
 * выходит за экран на масштабах GUI 1–4 (наименьший экран игры — 320×240; ещё меньше — у крошечного окна, тогда
 * лишнее прячется, а не налезает).
 * <pre>
 *            [ лента компаса + курс ]
 *            [ сообщение сервера    ]
 *   скорость │      лесенка       │ высота
 *   тяга     │   прицел и нос     │ верт. скорость
 *  ┌───────┐   [ зарядка варпа ]   ┌───────┐
 *  │силуэт │   [ подсказки     ]   │орудие │
 *  │топливо│                       │варп   │
 *  └───────┘                       └───────┘
 * </pre>
 */
public final class HudLayout {

    public static final float M = 4, COMPASS_H = 33, PANEL_W = 104, RIGHT_H = 40, TAPE_W = 36, TAPE_LABELS = 22,
            TAPE_FOOT = 64, WARP_H = 28, MSG_H = 12;

    /** Прямоугольник (пиксели интерфейса). */
    public record Box(float x, float y, float w, float h) {
        public float right() { return x + w; }

        public float bottom() { return y + h; }

        public boolean overlaps(Box o) {
            return o != null && x < o.right() && o.x < right() && y < o.bottom() && o.y < bottom();
        }

        public boolean overlapsX(float x0, float x1) { return x < x1 && x0 < right(); }

        public boolean inside(float sw, float sh) { return x >= 0 && y >= 0 && right() <= sw + 1e-3f && bottom() <= sh + 1e-3f; }
    }

    /** Строка подсказок: какие клавиши (from..to) и где. */
    public record HintLine(int from, int to, Box box) {}

    public final float w, h, cx, cy, k;
    public Box compass, msg, left, right, hints, warp, speed, alt;
    /** Полувысота лент (0 — лент нет), сдвиг лент от середины, радиус лесенки (0 — нет), высота силуэта. */
    public float tapeHalf, dx, ladderR, silH;
    public final List<HintLine> hintLines = new ArrayList<>();

    /**
     * @param k     множитель текста подсказок ({@link #hintScale})
     * @param width ширина текста шрифта игры при множителе 1
     */
    public HudLayout(float w, float h, float k, ToIntFunction<String> width) {
        this.w = w;
        this.h = h;
        this.k = k;
        cx = w / 2;
        cy = h / 2;
        float cw = Math.min(w - 2 * M, Math.clamp(Math.round(w * 0.42f), 140, 260));
        compass = new Box(cx - cw / 2, M, cw, COMPASS_H);
        msg = new Box(M, compass.bottom() + 3, w - 2 * M, MSG_H);

        // углы снизу: силуэт с топливом слева, орудие и варп справа
        silH = h >= 300 ? 40 : 28;
        float lh = silH + 37;
        left = new Box(M, h - M - lh, PANEL_W, lh);
        if (w >= 2 * PANEL_W + 3 * M) right = new Box(w - M - PANEL_W, h - M - RIGHT_H, PANEL_W, RIGHT_H);
        else right = new Box(M, left.y - M - RIGHT_H, PANEL_W, RIGHT_H);          // крошечное окно — друг над другом
        if (right.y < msg.bottom() + M) right = null;
        if (left.y < msg.bottom() + M) { left = null; right = null; }

        // подсказки — между углами снизу; не влезают — над углами во всю ширину
        float gx0 = left != null ? left.right() + 6 : M;
        float gx1 = right != null && right.x > cx ? right.x - 6 : w - M;
        float bottom = h - M;
        if (!flowHints(gx0, gx1, bottom, width)) {
            float top = h - M;
            if (left != null) top = Math.min(top, left.y);
            if (right != null) top = Math.min(top, right.y);
            flowHints(M, w - M, top - 4, width);
        }
        if (hints != null && hints.y < msg.bottom() + M) { hints = null; hintLines.clear(); }

        // зарядка варпа — над подсказками по центру
        float ww = Math.min(150, Math.max(96, gx1 - gx0)), wx = cx - ww / 2;
        float wb = hints != null ? hints.y - 4 : h - M;
        for (Box b : new Box[]{left, right}) if (b != null && b.overlapsX(wx, wx + ww)) wb = Math.min(wb, b.y - 4);
        warp = new Box(wx, wb - WARP_H, ww, WARP_H);
        if (warp.y < msg.bottom() + M || wx < 0) warp = null;

        // ленты скорости и высоты — по бокам от прицела, ниже сообщения и выше нижних блоков
        dx = Math.clamp(w * 0.21f, 62, 170);
        float top = msg.bottom() + 4;
        float bs = limitBelow(cx - dx - TAPE_FOOT, cx - dx), ba = limitBelow(cx + dx, cx + dx + TAPE_FOOT);
        float half = Math.min(Math.min(cy - top, Math.min(bs, ba) - TAPE_LABELS - 4 - cy), 100);
        if (half >= 22 && cx - dx - TAPE_FOOT >= 0 && cx + dx + TAPE_FOOT <= w) {
            tapeHalf = half;
            speed = new Box(cx - dx - TAPE_W, cy - half, TAPE_W, 2 * half);
            alt = new Box(cx + dx, cy - half, TAPE_W, 2 * half);
        }

        // лесенка тангажа — круг у прицела, между лентами
        float lr = Math.min(dx - 10, Math.min(cy - top, 90));
        lr = Math.min(lr, limitBelow(cx - lr, cx + lr) - 4 - cy);
        ladderR = lr >= 24 ? lr : 0;
    }

    /** Самый верхний край нижних блоков, задевающих полосу x0..x1 (или низ экрана). */
    private float limitBelow(float x0, float x1) {
        float b = h - M;
        for (Box o : new Box[]{left, right, hints, warp}) if (o != null && o.overlapsX(x0, x1)) b = Math.min(b, o.y);
        return b;
    }

    /** Множитель текста подсказок: мелко, но целыми пикселями экрана (GUI 2 и 4 — половина, 3 — две трети). */
    public static float hintScale(float gs) { return Hud.crisp(0.6f, gs); }

    /** Ширина подсказки «[клавиша] текст» в пикселях интерфейса. */
    public float hintWidth(String[] kv, ToIntFunction<String> width) {
        return (width.applyAsInt(kv[0]) + 4) * k + 3 * k + width.applyAsInt(kv[1]) * k;
    }

    public float hintGap() { return 8 * k; }

    public float hintLineH() { return 11 * k; }

    /** Разложить подсказки строками в полосе x0..x1 снизу вверх от bottom; false — не влезли (длинная подсказка). */
    private boolean flowHints(float x0, float x1, float bottom, ToIntFunction<String> width) {
        hintLines.clear();
        hints = null;
        float avail = x1 - x0;
        List<int[]> lines = new ArrayList<>();
        List<Float> widths = new ArrayList<>();
        int from = 0;
        float lw = 0;
        for (int i = 0; i < Hints.KEYS.length; i++) {
            float iw = hintWidth(Hints.KEYS[i], width);
            if (iw > avail) return false;
            float add = (i > from ? hintGap() : 0) + iw;
            if (lw + add > avail) {
                lines.add(new int[]{from, i});
                widths.add(lw);
                from = i;
                lw = iw;
            } else lw += add;
        }
        lines.add(new int[]{from, Hints.KEYS.length});
        widths.add(lw);
        float lh = hintLineH(), y = bottom - lines.size() * lh;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        for (int i = 0; i < lines.size(); i++) {
            float bw = widths.get(i), bx = Math.clamp(cx - bw / 2, x0, x1 - bw);
            Box b = new Box(bx, y + i * lh, bw, lh);
            hintLines.add(new HintLine(lines.get(i)[0], lines.get(i)[1], b));
            minX = Math.min(minX, bx);
            maxX = Math.max(maxX, bx + bw);
        }
        hints = new Box(minX, y, maxX - minX, lines.size() * lh);
        return true;
    }

    /** Всё, что есть на экране (для тестов: не налезают друг на друга). */
    public List<Box> boxes() {
        List<Box> out = new ArrayList<>();
        for (Box b : new Box[]{compass, left, right, hints, warp}) if (b != null) out.add(b);
        if (speed != null) {
            out.add(new Box(speed.x - (TAPE_FOOT - TAPE_W), speed.y, TAPE_FOOT, speed.h + TAPE_LABELS));
            out.add(new Box(alt.x, alt.y, TAPE_FOOT, alt.h + TAPE_LABELS));
        }
        return out;
    }
}
