package ink.hoka.odyssey.vessels;

/**
 * Точка варп-прыжка (без Minestom — для тестов): вперёд по носу на distance, но в пределах границы карты (квадрат
 * map.yml border) и высоты мира с запасом в радиус корабля. Если там твёрдое (по клеткам корпуса) — сдвиг назад по
 * курсу или вверх, пока не найдётся свободное место (не дальше maxShift).
 */
final class WarpMath {

    private WarpMath() {}

    /** Где можно стоять середине корабля: |x−cx|, |z−cz| ≤ half, minY ≤ y ≤ maxY (запас уже учтён). */
    record Bounds(double cx, double cz, double half, double minY, double maxY) {
        boolean inside(double x, double y, double z) {
            return Math.abs(x - cx) <= half + 1e-9 && Math.abs(z - cz) <= half + 1e-9 && y >= minY - 1e-9 && y <= maxY + 1e-9;
        }

        /** Граница карты (поперечник border, центр) и высота мира, урезанные на margin (радиус корабля и запас). */
        static Bounds of(double borderDiameter, double cx, double cz, double worldMinY, double worldMaxY, double margin) {
            return new Bounds(cx, cz, borderDiameter / 2 - margin, worldMinY + margin, worldMaxY - margin);
        }
    }

    /** Свободно ли кораблю с серединой в (x, y, z): ни одна клетка корпуса не в твёрдом/незагруженном. */
    interface Free { boolean free(double x, double y, double z); }

    /** Итог: точка (null — некуда) и почему. */
    record Result(double[] at, String why, boolean shortened, boolean shifted) {}

    /** Насколько далеко можно уйти от start по dir (единичный), не выходя из рамки; start вне рамки — 0. */
    static double reach(double[] start, double[] dir, double dist, Bounds b) {
        if (!b.inside(start[0], start[1], start[2])) return 0;
        double t = dist;
        double[] lo = {b.cx - b.half, b.minY, b.cz - b.half}, hi = {b.cx + b.half, b.maxY, b.cz + b.half};
        for (int a = 0; a < 3; a++) {
            if (dir[a] > 1e-12) t = Math.min(t, (hi[a] - start[a]) / dir[a]);
            else if (dir[a] < -1e-12) t = Math.min(t, (lo[a] - start[a]) / dir[a]);
        }
        return Math.max(0, t);
    }

    /**
     * Точка прыжка: start + dir·distance, урезанная рамкой (minJump — меньше этого прыгать незачем: null), затем
     * сдвиг до свободного места: на s = 0, step, 2·step… ≤ maxShift — сначала назад по курсу, потом вверх.
     */
    static Result target(double[] start, double[] dir, double distance, Bounds b, double minJump, double maxShift, double step, Free free) {
        double t = reach(start, dir, distance, b);
        boolean shortened = t < distance - 1e-6;
        if (t < minJump) return new Result(null, "впереди граница карты", shortened, false);
        double[] p = {start[0] + dir[0] * t, start[1] + dir[1] * t, start[2] + dir[2] * t};
        for (double s = 0; s <= maxShift + 1e-9; s += step) {
            double[][] cand = s == 0 ? new double[][]{p}
                    : new double[][]{{p[0] - dir[0] * s, p[1] - dir[1] * s, p[2] - dir[2] * s}, {p[0], p[1] + s, p[2]}};
            for (double[] c : cand) {
                if (!b.inside(c[0], c[1], c[2])) continue;
                if (free.free(c[0], c[1], c[2])) return new Result(c, null, shortened, s > 0);
            }
        }
        return new Result(null, "точка прыжка занята", shortened, false);
    }
}
