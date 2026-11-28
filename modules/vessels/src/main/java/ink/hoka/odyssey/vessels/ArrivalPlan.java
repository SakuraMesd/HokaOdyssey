package ink.hoka.odyssey.vessels;

import java.util.ArrayList;
import java.util.List;

/**
 * Путь прилёта корабля (/hovessel call) без Minestom — для тестов. Корабль не проходит сквозь блоки: путь проверяется
 * по клеткам корпуса ({@link Free}) с шагом не больше блока. Космос — прямо из свободного пространства (направления
 * по очереди: откуда смотрит игрок, потом в стороны), занято — обход (через точку выше/ниже/сбоку середины пути).
 * Планета — сверху: появляется высоко, снижается по наклонной до точки над местом зависания и опускается к нему.
 * Ничего не нашлось — null (корабль появится варпом).
 */
final class ArrivalPlan {

    private ArrivalPlan() {}

    /** Свободно ли кораблю с серединой в (x, y, z) и курсом yaw (как у Minecraft, °). */
    interface Free { boolean free(double x, double y, double z, double yaw); }

    /** Итог: точки пути (первая — где появится, последняя — где встанет), курс в конце. */
    record Path(List<double[]> points, double endYaw) {
        double length() {
            double l = 0;
            for (int i = 1; i < points.size(); i++) l += dist(points.get(i - 1), points.get(i));
            return l;
        }
    }

    /** Курс (как у Minecraft) по направлению (dx, dz): 0 — на юг (+Z), 90 — на запад (−X). */
    static double yawOf(double dx, double dz) { return Math.toDegrees(Math.atan2(-dx, dz)); }

    /**
     * n направлений по горизонтали: первое — по курсу firstYaw, дальше по очереди вправо-влево (±45°, ±90° …) —
     * единичные векторы {x, 0, z}.
     */
    static List<double[]> directions(double firstYaw, int n) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; out.size() < n; i++) {
            int k = (i + 1) / 2 * (i % 2 == 1 ? 1 : -1);
            double a = Math.toRadians(firstYaw + k * (360.0 / Math.max(2, n)));
            out.add(new double[]{-Math.sin(a), 0, Math.cos(a)});
            if (i > 2 * n) break;
        }
        return out;
    }

    /** Отрезок a→b свободен: середина корабля через каждые step (≤ 1) блока, курс — по отрезку (горизонталь). */
    static boolean segmentFree(double[] a, double[] b, double step, Free free) { return segmentFree(a, b, step, free, 0); }

    /** То же; отвесный отрезок — с курсом fallback (как полетит корабль). */
    static boolean segmentFree(double[] a, double[] b, double step, Free free, double fallback) {
        double len = dist(a, b);
        double yaw = Math.abs(b[0] - a[0]) + Math.abs(b[2] - a[2]) > 1e-3 ? yawOf(b[0] - a[0], b[2] - a[2]) : fallback;
        int n = Math.max(1, (int) Math.ceil(len / Math.min(1, step)));
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            if (!free.free(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, yaw)) return false;
        }
        return true;
    }

    /** Весь путь свободен. */
    static boolean pathFree(List<double[]> pts, double step, Free free) {
        double yaw = 0;
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            if (Math.abs(b[0] - a[0]) + Math.abs(b[2] - a[2]) > 1e-3) yaw = yawOf(b[0] - a[0], b[2] - a[2]);
            if (!segmentFree(a, b, step, free, yaw)) return false;
            if (i > 1 && !free.free(a[0], a[1], a[2], yaw)) return false;      // на повороте — и с новым курсом
        }
        return true;
    }

    /**
     * Космос: прилёт к target с расстояния dist по одному из направлений dirs (направление — откуда прилетает).
     * Сперва прямо по каждому; занято — обход через точку у середины пути, сдвинутую вверх/вниз/вбок на detour и
     * 2·detour. null — пути нет.
     */
    static Path space(double[] target, List<double[]> dirs, double dist, double detour, Free free) {
        for (double[] d : dirs) {
            double[] start = add(target, d, dist);
            List<double[]> p = List.of(start, target);
            if (free.free(start[0], start[1], start[2], yawOf(-d[0], -d[2])) && pathFree(p, 1, free)) return new Path(p, yawOf(-d[0], -d[2]));
        }
        for (double[] d : dirs) {
            double[] start = add(target, d, dist), mid = add(target, d, dist / 2);
            double[] side = {-d[2], 0, d[0]};
            for (double k : new double[]{detour, 2 * detour}) {
                for (double[] off : new double[][]{{0, k, 0}, {0, -k, 0}, {side[0] * k, 0, side[2] * k}, {-side[0] * k, 0, -side[2] * k}}) {
                    double[] m = {mid[0] + off[0], mid[1] + off[1], mid[2] + off[2]};
                    double[] s2 = {start[0] + off[0], start[1] + off[1], start[2] + off[2]};
                    for (double[] s : new double[][]{start, s2}) {
                        List<double[]> p = List.of(s, m, target);
                        if (free.free(s[0], s[1], s[2], 0) && pathFree(p, 1, free)) return new Path(p, yawOf(target[0] - m[0], target[2] - m[2]));
                    }
                }
            }
        }
        return null;
    }

    /**
     * Планета: сверху. Появляется на высоте target.y + rise (не выше topY) в стороне по направлению d на dist·0.6,
     * снижается по наклонной до точки над целью (+hover над ней) и опускается к цели. Пробует направления по очереди,
     * потом — выше (rise × 1.5). null — пути нет.
     */
    static Path planet(double[] target, List<double[]> dirs, double dist, double rise, double topY, double hover, Free free) {
        for (double r : new double[]{rise, rise * 1.5}) {
            for (double[] d : dirs) {
                double y = Math.min(topY, target[1] + r);
                if (y < target[1] + hover) continue;
                double[] start = {target[0] + d[0] * dist * 0.6, y, target[2] + d[2] * dist * 0.6};
                double[] above = {target[0], Math.min(topY, target[1] + hover), target[2]};
                List<double[]> p = above[1] - target[1] > 0.5 ? List.of(start, above, target) : List.of(start, target);
                double yaw = yawOf(target[0] - start[0], target[2] - start[2]);
                if (free.free(start[0], start[1], start[2], yaw) && pathFree(p, 1, free)) return new Path(p, yaw);
            }
        }
        return null;
    }

    /**
     * Свободное место у игрока: кольца радиусов rings вокруг p (направления dirs, высоты dy), корабль свободен и не
     * накрывает игрока (clear). null — нет.
     */
    static double[] spotNear(double[] p, List<double[]> dirs, double[] rings, double[] dys, double yaw, Free free, java.util.function.Predicate<double[]> clear) {
        for (double r : rings) for (double dy : dys) for (double[] d : dirs) {
            double[] c = {p[0] + d[0] * r, p[1] + dy, p[2] + d[2] * r};
            if (free.free(c[0], c[1], c[2], yaw) && clear.test(c)) return c;
        }
        return null;
    }

    // ───────────────────────── полёт ─────────────────────────

    /** Доля пути к доле времени u: быстро в начале, плавно тормозит к концу. */
    static double ease(double u) {
        u = Math.max(0, Math.min(1, u));
        return 1 - Math.pow(1 - u, 2.4);
    }

    /** Время полёта (тики): длина / скорость, в пределах [minT, maxT]. */
    static int duration(double length, double speed, int minT, int maxT) {
        return (int) Math.max(minT, Math.min(maxT, Math.round(length / Math.max(1, speed) * 20)));
    }

    /** Точка пути на расстоянии s от начала и направление там: {x, y, z, dx, dy, dz}. */
    static double[] at(List<double[]> pts, double s) {
        double left = Math.max(0, s);
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            double l = dist(a, b);
            if (left <= l || i == pts.size() - 1) {
                double t = l < 1e-9 ? 1 : Math.min(1, left / l);
                double[] d = l < 1e-9 ? new double[]{0, 0, 1} : new double[]{(b[0] - a[0]) / l, (b[1] - a[1]) / l, (b[2] - a[2]) / l};
                return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, d[0], d[1], d[2]};
            }
            left -= l;
        }
        double[] e = pts.get(pts.size() - 1);
        return new double[]{e[0], e[1], e[2], 0, 0, 1};
    }

    static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double[] add(double[] p, double[] d, double k) { return new double[]{p[0] + d[0] * k, p[1] + d[1] * k, p[2] + d[2] * k}; }
}
