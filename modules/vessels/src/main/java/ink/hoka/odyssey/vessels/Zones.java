package ink.hoka.odyssey.vessels;

/**
 * Зоны корпуса (без Minestom и игры — один и тот же файл у сервера и клиентского модуля, сверяется тестом): силуэт
 * корабля сверху (система корабля: нос +Z, левый борт +X) делится на 5 зон по геометрии коробки:
 * <pre>
 *        корма (tail)        середина             нос (nose)
 *   +X ┌──────────┬───────────────────────────────────────┐
 *      │          │  ЛЕВЫЙ БОРТ (|x| > side·half.x)       │
 *      │ ДВИГАТЕЛИ├──────────────────────────┬────────────┤
 *      │          │  КОРПУС                  │   НОС      │   нос — перед средней части (её длины,
 *      │          ├──────────────────────────┴────────────┤   а не коробки: у corabll вперёд торчат
 *      │          │  ПРАВЫЙ БОРТ                          │   балки-борта, кабина — дальше)
 *   −X └──────────┴───────────────────────────────────────┘
 * </pre>
 * Доли — в конфиге вида (zones: side, nose, tail). Попадание бьёт зону ближайшей к точке клетки корпуса; прочность
 * зоны — для картинки (белый → янтарный → красный).
 */
final class Zones {

    static final int NOSE = 0, BODY = 1, LEFT = 2, RIGHT = 3, ENGINE = 4, COUNT = 5;
    /** Названия зон (меню, сообщения). */
    static final String[] NAMES = {"нос", "корпус", "левый борт", "правый борт", "двигатели"};
    /** Коротко (сводка окна): Н, К, Л, П, Д. */
    static final String[] SHORT = {"Н", "К", "Л", "П", "Д"};

    private Zones() {}

    /** Доли: side — борта дальше side·half.x от оси; nose — передняя доля длины; tail — задняя (двигатели). */
    record Params(double side, double nose, double tail) {
        static final Params DEFAULT = new Params(0.35, 0.25, 0.22);

        Params {
            side = clamp(side, 0.05, 0.95);
            nose = clamp(nose, 0.05, 0.6);
            tail = clamp(tail, 0.05, 0.6);
        }
    }

    /**
     * Зона точки (система корабля, блоки); half — половины коробки; noseFrom — с какого z начинается нос
     * ({@link #noseFrom}).
     */
    static int zoneOf(double x, double z, double[] half, Params p, double noseFrom) {
        double len = 2 * Math.max(1e-6, half[2]);
        if (z < -half[2] + p.tail() * len) return ENGINE;
        double sx = p.side() * Math.max(1e-6, half[0]);
        if (x > sx) return LEFT;
        if (x < -sx) return RIGHT;
        if (z >= noseFrom) return NOSE;
        return BODY;
    }

    /**
     * Начало носа: перед средней части (точки с |x| ≤ side·half.x) минус доля nose длины коробки. points — x, z
     * точек корпуса парами (у сервера — середины клеток корпуса); средней части нет — от передней стенки коробки.
     */
    static double noseFrom(double[] points, double[] half, Params p) {
        double sx = p.side() * Math.max(1e-6, half[0]), front = -Double.MAX_VALUE;
        for (int i = 0; i + 1 < points.length; i += 2) if (Math.abs(points[i]) <= sx) front = Math.max(front, points[i + 1]);
        if (front == -Double.MAX_VALUE) front = half[2];
        return front - p.nose() * 2 * Math.max(1e-6, half[2]);
    }

    /** Цвет зоны по её целости (0…1): 1 — белый, ≤ 0.66 — янтарный, ≤ 0.33 — красный. */
    static int level(double health) {
        if (health > 0.66) return 0;
        if (health > 0.33) return 1;
        return 2;
    }

    /** Целость зоны: 1 − урон зоны / (наибольшая прочность × max(доля клеток зоны, 0.1)). */
    static double health(double damage, double maxHp, double share) {
        double cap = maxHp * Math.max(share, 0.1);
        if (cap <= 0) return 1;
        return clamp(1 - damage / cap, 0, 1);
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }
}
