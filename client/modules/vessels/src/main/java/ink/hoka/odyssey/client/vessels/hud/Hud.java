package ink.hoka.odyssey.client.vessels.hud;

/** Палитра и мелочи HUD кораблей — как у старого HUD (ships/ShipHud). */
public final class Hud {

    private Hud() {}

    /** Светло-серый, белый, тёмный, янтарный (форсаж, предупреждения); красный — только «пусто» и убийство. */
    public static final int GRAY = 0xD9E2EE, WHITE = 0xFFFFFF, DARK = 0x05080F, AMBER = 0xFFC46B, RED = 0xFF6A5A, GOOD = 0xB8F0C8;

    public static int argb(int rgb, float a) {
        return (Math.round(Math.clamp(Float.isFinite(a) ? a : 0, 0, 1) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    /** Масштаб текста под целые пиксели экрана (не меньше 1 пикселя экрана на пиксель шрифта). */
    public static float crisp(float want, float gs) {
        return Math.max(1, Math.round(want * gs)) / gs;
    }

    /** Размер в пикселях экрана (чётный, не меньше 2) для size пикселей интерфейса. */
    public static int px(float size, float gs) {
        return Math.max(2, Math.round(size * gs / 2) * 2);
    }

    public static float wrap(float d) {
        d %= 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    public static float smooth(float a, float b, float x) {
        float t = Math.clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Доля шкалы: value / max в 0..1 (NaN, бесконечность, max ≤ 0 — 0). */
    public static float frac(double value, double max) {
        if (!(max > 0) || !Double.isFinite(value) || !Double.isFinite(max)) return 0;
        return (float) Math.clamp(value / max, 0, 1);
    }

    /** Число с пробелами между тысячами: 10000 → «10 000». */
    public static String thousands(long v) {
        String s = Long.toString(Math.abs(v));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return (v < 0 ? "-" : "") + b;
    }
}
