package ink.hoka.odyssey.client.vessels.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Вертикальная лента (скорость слева, высота справа — как в War Thunder): риски через step, подписи через label,
 * текущее значение — посередине в рамке. Здесь — только расчёт меток.
 */
public final class Tape {

    private Tape() {}

    /** Метка: значение, сдвиг от середины в долях полувысоты (−1 — низ… +1 — верх), подписана ли. */
    public record Mark(double value, float pos, boolean major, String label) {}

    /**
     * Метки при текущем value; на полувысоту ленты приходится halfRange единиц. min — ниже не рисовать (скорость ≥ 0;
     * NaN — без предела). Подпись — через labelStep (кратно step).
     */
    public static List<Mark> marks(double value, double halfRange, double step, double labelStep, double min) {
        List<Mark> out = new ArrayList<>();
        if (!Double.isFinite(value) || !(halfRange > 0) || !(step > 0) || Math.abs(value) > 1e12 || halfRange / step > 400) return out;
        long from = (long) Math.ceil((value - halfRange) / step), to = (long) Math.floor((value + halfRange) / step);
        if (to - from > 400) return out;
        for (long i = from; i <= to; i++) {
            double v = i * step;
            if (!Double.isNaN(min) && v < min - 1e-9) continue;
            float pos = (float) ((v - value) / halfRange);
            if (pos < -1 || pos > 1) continue;
            boolean major = Math.abs(v / labelStep - Math.rint(v / labelStep)) < 1e-6;
            out.add(new Mark(v, pos, major, major ? Long.toString(Math.round(v)) : null));
        }
        return out;
    }

    /** Шаг подписей: base, а если подписи ближе minPx друг к другу — вдвое, впятеро… реже. */
    public static double labelStep(double base, double halfRange, float halfPx, float minPx) {
        double s = base;
        for (int i = 0; i < 8 && s / halfRange * halfPx < minPx; i++) s *= i % 2 == 0 ? 2 : 2.5;
        return s;
    }
}
