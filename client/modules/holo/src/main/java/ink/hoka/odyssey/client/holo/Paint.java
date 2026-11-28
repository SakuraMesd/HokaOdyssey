package ink.hoka.odyssey.client.holo;

/**
 * Вид голограммы: что рисовать по готовой раскладке ({@link Layout.Panel}) — тёмная полупрозрачная панель, сканлинии,
 * рамка из светящихся кубиков-вокселей (уголки крупнее, у уголков — янтарный акцент), элементы, подсветка наведённого,
 * точка-курсор. Появление: кубики «собираются» из точек ({@link State#appear} 0→1), удаление — рассыпаются (1→0).
 * Рисует через {@link Painter} — в игре это буферы мира, в превью PNG и окне редактора — свои реализации.
 * Координаты — точки панели: x вправо, y вниз от левого верхнего угла, z — к зрителю.
 */
public final class Paint {

    /** Куда рисовать. Цвета — ARGB. */
    public interface Painter {
        /** Заливка с обычным смешиванием (фон). */
        void rect(float x0, float y0, float x1, float y1, float z, int argb);

        /** Заливка-свечение (сложение цветов). */
        void glow(float x0, float y0, float x1, float y1, float z, int argb);

        /** Кубик-воксель со стороной s (свечение), центр (cx, cy, cz). */
        void cube(float cx, float cy, float cz, float s, int argb);

        /** Сканлинии по прямоугольнику; phase — сдвиг полос (0..1, бежит со временем). */
        void scan(float x0, float y0, float x1, float y1, float z, int argb, float phase);

        void text(Layout.Box b, Layout.TLine line, float x, float y, float z, float scale, float alpha);

        void item(Layout.Box b, float cx, float cy, float z, float size, float spinDeg, float alpha);

        void image(String name, float x0, float y0, float x1, float y1, float z, float alpha);
    }

    /** Состояние кадра: время (с), появление 0..1, наведённый элемент, курсор (точки; NaN — нет). */
    public static final class State {
        public float t, appear = 1;
        public Layout.Box hovered;
        public boolean panelHovered;
        public float cu = Float.NaN, cv = Float.NaN;
        public int seed;
    }

    public static final float CUBE = Layout.PX_PER_BLOCK / 16f;            // 1/16 блока
    static final float CORNER = CUBE * 1.7f;

    private Paint() {}

    public static int argb(float a, int rgb) {
        int al = Math.max(0, Math.min(255, Math.round(a * 255)));
        return (al << 24) | (rgb & 0xFFFFFF);
    }

    public static int mul(int rgb, float k) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 255) * k));
        int g = Math.min(255, Math.round(((rgb >> 8) & 255) * k));
        int b = Math.min(255, Math.round((rgb & 255) * k));
        return (r << 16) | (g << 8) | b;
    }

    static float ease(float x) {
        x = Math.max(0, Math.min(1, x));
        return 1 - (1 - x) * (1 - x) * (1 - x);
    }

    /** Псевдослучайное 0..1 от (seed, i) — одинаковое каждый кадр. */
    static float hash(int seed, int i) {
        int h = seed * 0x9E3779B1 + i * 0x85EBCA6B;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    public static void draw(Holo h, Layout.Panel p, Holo.Theme th, State st, Painter out) {
        float w = p.w(), hh = p.h();
        float show = ease((st.appear - 0.35f) / 0.65f);                       // содержимое — после рамки
        if (h.background) {
            out.rect(0, 0, w, hh, 0, argb((float) h.opacity * Math.min(1, st.appear * 1.6f), th.bg()));
            out.glow(0, 0, w, Math.min(hh, 3), 0.02f, argb(0.10f * show, th.glow()));          // блик сверху
            out.glow(0, hh - 1.2f, w, hh, 0.02f, argb(0.08f * show, th.glow()));
            out.scan(0, 0, w, hh, 0.04f, argb(0.09f * show, th.glow()), (st.t * 0.07f) % 1f);
        }
        if (st.panelHovered && h.panelClick) out.glow(0, 0, w, hh, 0.05f, argb(0.05f, th.accent()));
        if (h.frame) frame(w, hh, th, st, out);
        if (show <= 0.01f) return;
        for (Layout.Box b : p.boxes()) element(b, th, st, show, out);
        if (!Float.isNaN(st.cu)) {
            out.cube(st.cu, st.cv, 1.2f, CUBE * 0.55f, argb(0.95f, th.accent()));
            out.glow(st.cu - 2.2f, st.cv - 2.2f, st.cu + 2.2f, st.cv + 2.2f, 1.1f, argb(0.18f, th.accent()));
        }
    }

    /** Рамка из кубиков по краю: шаг ~2 кубика, уголки крупнее и светлые, рядом с уголками — янтарь. */
    static void frame(float w, float h, Holo.Theme th, State st, Painter out) {
        float step = CUBE * 2.1f;
        int i = 0;
        float[][] corners = {{0, 0}, {w, 0}, {w, h}, {0, h}};
        for (float[] c : corners) cube(c[0], c[1], CORNER, th.frame(), 1f, i++, w, h, st, out);
        float[][] edges = {{0, 0, w, 0}, {w, 0, w, h}, {w, h, 0, h}, {0, h, 0, 0}};
        for (float[] e : edges) {
            float len = Math.abs(e[2] - e[0]) + Math.abs(e[3] - e[1]);
            int n = Math.max(1, Math.round(len / step));
            for (int k = 1; k < n; k++) {
                float t = k / (float) n;
                float x = e[0] + (e[2] - e[0]) * t, y = e[1] + (e[3] - e[1]) * t;
                boolean nearCorner = k == 1 || k == n - 1;
                int col = nearCorner ? th.accent() : (k % 4 == 0 ? th.frame() : th.glow());
                float size = nearCorner ? CUBE * 0.9f : CUBE * (k % 4 == 0 ? 0.85f : 0.7f);
                cube(x, y, size, col, nearCorner ? 1f : 0.9f, i++, w, h, st, out);
            }
        }
    }

    private static void cube(float tx, float ty, float size, int rgb, float a, int i, float w, float h, State st, Painter out) {
        float delay = hash(st.seed, i) * 0.45f;
        float k = ease((st.appear - delay) / 0.55f);
        if (k <= 0.001f) return;
        float ang = hash(st.seed + 7, i) * 6.2832f, r = (0.3f + hash(st.seed + 13, i)) * Math.max(w, h) * 0.5f;
        float sx = w / 2 + (float) Math.cos(ang) * r, sy = h / 2 + (float) Math.sin(ang) * r;
        float sz = (hash(st.seed + 29, i) - 0.5f) * r;
        float x = sx + (tx - sx) * k, y = sy + (ty - sy) * k, z = sz * (1 - k);
        float flicker = 0.82f + 0.18f * (float) Math.sin(st.t * 2.3f + i * 0.71f + st.seed);
        out.cube(x, y, z, size * (0.25f + 0.75f * k), argb(a * Math.min(1, k * 1.5f), mul(rgb, flicker)));
    }

    static void element(Layout.Box b, Holo.Theme th, State st, float show, Painter out) {
        Holo.El e = b.el;
        boolean hov = st.hovered == b;
        switch (e.type) {
            case "text" -> text(b, b.x, b.y, b.w, 0.3f, show, out);
            case "item" -> {
                float cx = b.x + b.w / 2, cy = b.y + b.h / 2;
                float bob = (float) Math.sin(st.t * 1.7f + b.line) * 0.6f;
                out.glow(b.x + 2, b.y + 2, b.x + b.w - 2, b.y + b.h - 2, 0.1f, argb(0.06f * show, th.glow()));
                float c = CUBE * 0.45f;
                out.cube(b.x + 1, b.y + 1, 0.3f, c, argb(0.8f * show, th.glow()));
                out.cube(b.x + b.w - 1, b.y + 1, 0.3f, c, argb(0.8f * show, th.glow()));
                out.cube(b.x + 1, b.y + b.h - 1, 0.3f, c, argb(0.8f * show, th.glow()));
                out.cube(b.x + b.w - 1, b.y + b.h - 1, 0.3f, c, argb(0.8f * show, th.glow()));
                out.item(b, cx, cy + bob, 4f, b.w * 0.8f, e.spin ? st.t * 60f % 360f : 0, show);
            }
            case "image" -> out.image(e.image, b.x, b.y, b.x + b.w, b.y + b.h, 0.2f, show);
            case "bar" -> {
                int col = Holo.rgb(e.color, th.glow());
                float y0 = b.y + (b.h - 7) / 2, y1 = y0 + 7;
                out.rect(b.x, y0, b.x + b.w, y1, 0.1f, argb(0.55f * show, mul(th.bg(), 0.5f)));
                float fx = b.x + 1 + (b.w - 2) * b.fill;
                if (b.fill > 0) {
                    out.glow(b.x + 1, y0 + 1, fx, y1 - 1, 0.2f, argb(0.75f * show, col));
                    out.glow(b.x + 1, y0 + 1, fx, y0 + 2.2f, 0.25f, argb(0.35f * show, 0xFFFFFF));
                }
                for (float sx = b.x + 6; sx < b.x + b.w - 2; sx += 6)              // деления
                    out.rect(sx, y0 + 1, sx + 0.7f, y1 - 1, 0.3f, argb(0.5f * show, th.bg()));
                out.cube(b.x, (y0 + y1) / 2, 0.3f, CUBE * 0.8f, argb(show, th.frame()));
                out.cube(b.x + b.w, (y0 + y1) / 2, 0.3f, CUBE * 0.8f, argb(show, th.frame()));
                out.cube(fx, (y0 + y1) / 2, 0.5f, CUBE * 0.6f, argb(show * (b.fill > 0 ? 1 : 0.4f), th.accent()));
                if (!b.text.isEmpty()) text(b, b.x, b.y + (b.h - Layout.LINE * b.textScale) / 2 + 0.5f, b.w, 0.6f, show, out);
            }
            case "button" -> {
                int col = Holo.rgb(e.color, th.glow());
                float z = hov ? 0.8f : 0.2f, k = hov ? 1f : 0.7f;
                out.rect(b.x, b.y, b.x + b.w, b.y + b.h, 0.1f, argb((hov ? 0.75f : 0.55f) * show, mul(Mini.lerp(th.bg(), col, 0.16f), hov ? 1.6f : 1f)));
                if (hov) out.glow(b.x, b.y, b.x + b.w, b.y + b.h, 0.15f, argb(0.12f * show, col));
                float step = CUBE * 1.6f, c = CUBE * (hov ? 0.6f : 0.5f);
                int n = Math.max(1, Math.round(b.w / step));
                for (int i = 0; i <= n; i++) {
                    float x = b.x + b.w * i / n;
                    out.cube(x, b.y, z, c, argb(k * show, i == 0 || i == n ? th.frame() : col));
                    out.cube(x, b.y + b.h, z, c, argb(k * show, i == 0 || i == n ? th.frame() : col));
                }
                int m = Math.max(1, Math.round(b.h / step));
                for (int i = 1; i < m; i++) {
                    float y = b.y + b.h * i / m;
                    out.cube(b.x, y, z, c, argb(k * show, col));
                    out.cube(b.x + b.w, y, z, c, argb(k * show, col));
                }
                float ty = b.y + (b.h - b.text.size() * Layout.LINE * b.textScale) / 2 + 0.5f;
                text(b, b.x + Layout.BTN_PAD / 2, ty, b.w - Layout.BTN_PAD, z + 0.3f, show * (hov ? 1f : 0.92f), out);
            }
            case "separator" -> {
                float y = b.y + b.h / 2;
                out.glow(b.x + 3, y - 0.35f, b.x + b.w - 3, y + 0.35f, 0.1f, argb(0.45f * show, th.glow()));
                out.cube(b.x + b.w / 2, y, 0.3f, CUBE * 0.8f, argb(show, th.accent()));
                out.cube(b.x + 2, y, 0.3f, CUBE * 0.5f, argb(show, th.frame()));
                out.cube(b.x + b.w - 2, y, 0.3f, CUBE * 0.5f, argb(show, th.frame()));
            }
            default -> { }
        }
        if (hov && !e.type.equals("button") && e.clickable) {                     // наведённый кликабельный — рамка свечения
            out.glow(b.x - 1, b.y - 1, b.x + b.w + 1, b.y, 0.4f, argb(0.5f * show, th.accent()));
            out.glow(b.x - 1, b.y + b.h, b.x + b.w + 1, b.y + b.h + 1, 0.4f, argb(0.5f * show, th.accent()));
        }
    }

    /** Строки текста бокса с выравниванием в ширине areaW. */
    static void text(Layout.Box b, float x, float y, float areaW, float z, float alpha, Painter out) {
        float s = b.textScale;
        for (int i = 0; i < b.text.size(); i++) {
            Layout.TLine l = b.text.get(i);
            float lw = l.width() * s;
            float ox = switch (b.el.type.equals("text") ? b.el.align : "center") {
                case "left" -> 0;
                case "right" -> areaW - lw;
                default -> (areaW - lw) / 2;
            };
            out.text(b, l, x + ox, y + i * Layout.LINE * s, z, s, alpha);
        }
    }
}
