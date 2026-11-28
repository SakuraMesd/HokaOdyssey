package ink.hoka.odyssey.client.stations;

import java.util.Locale;

/**
 * Надпись входа/выхода по центру сверху: «СТАНЦИЯ АЛЬФА» крупно, под ней тонкая линия, гаснущая к краям, и строка
 * поменьше («ГРАВИТАЦИЯ 1G»). Проявляется за 0.35 с (чуть съезжая сверху), держится ~2.6 с, гаснет за 1 с.
 * Без классов игры: рисует через {@link Canvas} — в игре GuiGraphics, в тесте — PNG.
 */
public final class Banner {

    /** Холст в пикселях GUI. */
    public interface Canvas {
        int width();

        void fill(float x0, float y0, float x1, float y1, int argb);

        /** Текст: левый верх (x, y), масштаб (1 — шрифт игры), без тени. */
        void text(String s, float x, float y, float scale, int argb);

        /** Ширина строки при масштабе 1. */
        int textWidth(String s);
    }

    public static final long IN = 350, HOLD = 2600, OUT = 1000;

    private String title = "", sub = "";
    private int rgb = 0xFFFFFF;
    private long start = Long.MIN_VALUE;

    /** Показать (заменяет текущую). */
    public void show(String title, String sub, int rgb, long now) {
        this.title = spaced(title.toUpperCase(Locale.ROOT));
        this.sub = sub == null || sub.isEmpty() ? "" : spaced(sub.toUpperCase(Locale.ROOT));
        this.rgb = rgb & 0xFFFFFF;
        this.start = now;
    }

    public boolean active(long now) { return start != Long.MIN_VALUE && now - start < IN + HOLD + OUT; }

    /** Слова чуть врозь (двойной пробел): шрифт игры без тонких пробелов, так «приборнее». */
    static String spaced(String s) { return s.strip().replaceAll("\\s+", "  "); }

    /** Прозрачность и сдвиг (пиксели вверх) в момент now. */
    float alpha(long now) {
        long a = now - start;
        if (a < 0) return 0;
        if (a < IN) { float k = a / (float) IN; return k * k * (3 - 2 * k); }
        if (a < IN + HOLD) return 1;
        float k = 1 - (a - IN - HOLD) / (float) OUT;
        return Math.max(0, k * k);
    }

    public void draw(Canvas g, long now) {
        if (!active(now)) return;
        float al = alpha(now);
        if (al <= 0.01f) return;
        long a = now - start;
        float slide = a < IN ? (1 - a / (float) IN) * 6 : 0;
        float cx = g.width() / 2f;
        float y = Math.max(18, g.width() * 0.045f) - slide;
        float ts = 1.6f;
        float tw = g.textWidth(title) * ts;
        int glow = BorderMesh.lighten(rgb, 0.15f);
        // мягкая подложка: несколько прозрачных полос, к краям прозрачнее
        float bw = Math.max(tw, g.textWidth(sub)) + 70, bh = sub.isEmpty() ? 26 : 38;
        for (int i = 0; i < 12; i++) {
            float k = i / 12f, x0 = cx - bw / 2 * (1 - k * 0.6f), x1 = cx + bw / 2 * (1 - k * 0.6f);
            g.fill(x0, y - 7, x1, y - 7 + bh, argb(0x050A12, al * 0.055f));
        }
        g.text(title, cx - tw / 2 + 0.8f, y + 0.8f, ts, argb(0x000000, al * 0.55f));          // тень
        g.text(title, cx - tw / 2, y, ts, argb(BorderMesh.lighten(rgb, 0.7f), al));
        float ly = y + 9 * ts + 3;
        float lw = Math.max(tw * 0.75f, 80);
        line(g, cx, ly, lw, glow, al);
        if (!sub.isEmpty()) {
            float sw = g.textWidth(sub) * 0.9f;
            g.text(sub, cx - sw / 2, ly + 4, 0.9f, argb(BorderMesh.lighten(rgb, 0.4f), al * 0.85f));
        }
    }

    /** Горизонтальная линия из центра, гаснущая к краям, с тонким ореолом. */
    private static void line(Canvas g, float cx, float y, float w, int rgb, float al) {
        int n = 24;
        for (int i = 0; i < n; i++) {
            float k0 = i / (float) n, k1 = (i + 1) / (float) n;
            float f = 1 - (k0 + k1) / 2;
            float a = f * f * al;
            g.fill(cx + k0 * w / 2, y, cx + k1 * w / 2, y + 1, argb(rgb, a * 0.95f));
            g.fill(cx - k1 * w / 2, y, cx - k0 * w / 2, y + 1, argb(rgb, a * 0.95f));
            g.fill(cx - k1 * w / 2, y - 1, cx + k1 * w / 2, y + 2, argb(rgb, a * 0.05f));
        }
    }

    static int argb(int rgb, float a) {
        return (Math.round(Math.clamp(a, 0, 1) * 255) << 24) | (rgb & 0xFFFFFF);
    }
}
