package ink.hoka.odyssey.vessels;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Посадка на планету с кораблём и взлёт (vessels-6) — расчёты без Minestom (для тестов): точка появления в круге,
 * высоты по карте, высота конца спуска над рельефом, точка выхода в космос у планеты.
 */
final class LandingMath {

    private LandingMath() {}

    /**
     * Планета (снимок из модуля planets) с настройками посадки корабля. Точка (x, y, z) — середина скруглённого куба
     * со стороной size; ближе approach к поверхности — вход.
     */
    record Planet(String id, String map, double x, double y, double z, double size, double round, double approach,
                  String target, boolean star, double scatter, double shipX, double shipZ, double entryY, double shipY,
                  double descentSec, double exitY) {

        boolean enterable() { return !star && target != null && !target.isEmpty(); }

        /** Расстояние от точки до поверхности (отрицательное — внутри). Та же формула, что у planets. */
        double distance(double px, double py, double pz) {
            double h = size / 2, r = Math.min(round, h), in = h - r;
            double qx = Math.abs(px - x) - in, qy = Math.abs(py - y) - in, qz = Math.abs(pz - z) - in;
            double ox = Math.max(qx, 0), oy = Math.max(qy, 0), oz = Math.max(qz, 0);
            return Math.sqrt(ox * ox + oy * oy + oz * oz) + Math.min(Math.max(qx, Math.max(qy, qz)), 0) - r;
        }
    }

    // ───────────────────────── точка появления ─────────────────────────

    /**
     * Случайная точка равномерно по площади круга радиуса radius у (cx, cz): r = R·√u, угол — равномерно. Вернуть {x, z}.
     */
    static double[] scatter(RandomGenerator rnd, double cx, double cz, double radius) {
        double r = Math.max(0, radius) * Math.sqrt(rnd.nextDouble()), a = rnd.nextDouble() * Math.PI * 2;
        return new double[]{cx + r * Math.cos(a), cz + r * Math.sin(a)};
    }

    /** Точку — внутрь границы карты (квадрат diameter у центра) с запасом pad. Вернуть {x, z}. */
    static double[] insideBorder(double x, double z, double diameter, double bx, double bz, double pad) {
        double h = diameter / 2 - pad;
        if (h <= 0) return new double[]{bx, bz};
        return new double[]{Math.clamp(x, bx - h, bx + h), Math.clamp(z, bz - h, bz + h)};
    }

    // ───────────────────────── высоты ─────────────────────────

    /** Высоты посадки по карте: появление, конец спуска, взлёт; notes — что пришлось урезать (в лог). */
    record Heights(double entry, double rest, double exit, List<String> notes) {}

    /** Между концом спуска, появлением и взлётом — не меньше стольких блоков (взлёт не срабатывает сразу после посадки). */
    static final double GAP = 40;

    /**
     * Высоты по карте minY…maxY для корабля радиуса radius. Верх для середины корабля — maxY − radius − 2. Взлёт — не
     * выше верха; появление — не выше взлёта − GAP; конец спуска — не выше появления (и не ниже дна + radius + 2).
     */
    static Heights heights(int minY, int maxY, double radius, double entry, double rest, double exit) {
        List<String> notes = new ArrayList<>();
        double top = maxY - radius - 2, bottom = minY + radius + 2;
        double ex = exit, en = entry, re = rest;
        if (ex > top) { notes.add("ship-exit-y " + r(exit) + " выше верха карты — " + r(top)); ex = top; }
        if (en > ex - GAP) { notes.add("ship-entry-y " + r(entry) + " → " + r(ex - GAP) + " (ниже взлёта на " + r(GAP) + ")"); en = ex - GAP; }
        if (re > en) { notes.add("ship-y " + r(rest) + " → " + r(en) + " (не выше появления)"); re = en; }
        if (re < bottom) { notes.add("ship-y " + r(rest) + " ниже дна карты — " + r(bottom)); re = bottom; }
        if (en < re) en = re;
        return new Heights(en, re, ex, List.copyOf(notes));
    }

    private static long r(double v) { return Math.round(v); }

    /**
     * Высота конца спуска над точкой (x, z): ship-y, а если рельеф (поле расстояний f) ближе подушки — выше, пока весь
     * корпус (offsets — точки корпуса от середины) не дальше clearance + soft от блоков (подушка в конце не толкает).
     * Не выше maxY; не нашлось — NaN.
     */
    static double restY(DistField f, double[] offsets, double x, double z, double shipY, double maxY, Cushion.Settings s) {
        Cushion.Settings calm = new Cushion.Settings(s.clearance() + s.soft() + 1, s.soft(), s.climb(), s.beamReach(), s.tolerance());
        return Cushion.hoverY(f, offsets, x, z, shipY, maxY, 1, calm);
    }

    // ───────────────────────── выход в космос ─────────────────────────

    /**
     * Грань куба планеты, над которой выйти: по направлению полёта (vx, vy, vz). Летит больше вверх, чем вбок (горизонталь
     * меньше половины вертикали) — верхняя грань (+Y), иначе боковая по горизонтали: ±X или ±Z (по большей составляющей).
     * Стоит на месте — по носу nose (так же). Вернуть нормаль грани {nx, ny, nz}.
     */
    static int[] exitFace(double vx, double vy, double vz, double[] nose) {
        double h = Math.hypot(vx, vz);
        if (h + Math.abs(vy) < 1e-3 && nose != null) { vx = nose[0]; vy = nose[1]; vz = nose[2]; h = Math.hypot(vx, vz); }
        if (h < 1e-6 || h < Math.max(0, vy) * 0.5) return new int[]{0, 1, 0};
        if (Math.abs(vx) >= Math.abs(vz)) return new int[]{vx > 0 ? 1 : -1, 0, 0};
        return new int[]{0, 0, vz > 0 ? 1 : -1};
    }

    /**
     * Точка выхода над гранью n: середина корабля в dist блоках от поверхности по нормали. Сдвиг по грани — по месту на
     * планете: (px, pz) относительно круга посадки (ship-x/z, scatter) → доля (−1…1) плоской части грани (×0.8).
     */
    static double[] exitPoint(Planet pl, int[] n, double px, double pz, double dist) {
        double half = pl.size() / 2, flat = Math.max(0, half - Math.min(pl.round(), half)) * 0.8;
        double sc = Math.max(1, pl.scatter());
        double a = Math.clamp((px - pl.shipX()) / sc, -1, 1) * flat, b = Math.clamp((pz - pl.shipZ()) / sc, -1, 1) * flat;
        double ox = 0, oy = 0, oz = 0;
        if (n[1] != 0) { ox = a; oz = b; }                                      // верх/низ: по X и Z
        else if (n[0] != 0) oz = b;                                             // ±X: вдоль Z
        else ox = a;                                                            // ±Z: вдоль X
        double out = half + dist;
        return new double[]{pl.x() + n[0] * out + ox, pl.y() + n[1] * out + oy, pl.z() + n[2] * out + oz};
    }

    /** Поворот «нос (+Z) по нормали n»: бок — по курсу, вверх/вниз — нос в зенит/надир. */
    static Quat noseAlong(int[] n) {
        if (n[1] > 0) return new Quat(-Math.sin(Math.PI / 4), 0, 0, Math.cos(Math.PI / 4));   // −90° вокруг X: +Z → +Y
        if (n[1] < 0) return new Quat(Math.sin(Math.PI / 4), 0, 0, Math.cos(Math.PI / 4));
        return Quat.yaw(ArrivalPlan.yawOf(n[0], n[2]));
    }
}
