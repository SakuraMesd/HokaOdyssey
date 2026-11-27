package ink.hoka.odyssey.vessels;

/** Геометрия корабля (без мира и Minestom — для тестов): ориентированная коробка, проверка полёта. */
final class VesselMath {

    private VesselMath() {}

    /** Точка мира → система корабля (центр c, поворот q). */
    static double[] toLocal(double px, double py, double pz, double[] c, Quat q) {
        return q.conjugate().rotate(px - c[0], py - c[1], pz - c[2]);
    }

    /** Система корабля → мир. */
    static double[] toWorld(double lx, double ly, double lz, double[] c, Quat q) {
        double[] r = q.rotate(lx, ly, lz);
        return new double[]{c[0] + r[0], c[1] + r[1], c[2] + r[2]};
    }

    /** Расстояние от точки мира до ориентированной коробки (половины half, центр c, поворот q); внутри — 0. */
    static double distanceToBox(double px, double py, double pz, double[] c, Quat q, double[] half) {
        double[] l = toLocal(px, py, pz, c, q);
        double s = 0;
        for (int a = 0; a < 3; a++) {
            double d = Math.abs(l[a]) - half[a];
            if (d > 0) s += d * d;
        }
        return Math.sqrt(s);
    }

    /**
     * Луч (мир: начало o, направление d — единичное) против ориентированной коробки: расстояние до входа (0 — начало
     * внутри) или −1 — мимо / дальше max.
     */
    static double rayBox(double[] o, double[] d, double max, double[] c, Quat q, double[] half) {
        double[] lo = toLocal(o[0], o[1], o[2], c, q);
        double[] ld = q.conjugate().rotate(d[0], d[1], d[2]);
        double t0 = 0, t1 = max;
        for (int a = 0; a < 3; a++) {
            if (Math.abs(ld[a]) < 1e-12) {
                if (Math.abs(lo[a]) > half[a]) return -1;
                continue;
            }
            double ta = (-half[a] - lo[a]) / ld[a], tb = (half[a] - lo[a]) / ld[a];
            if (ta > tb) { double t = ta; ta = tb; tb = t; }
            t0 = Math.max(t0, ta);
            t1 = Math.min(t1, tb);
            if (t0 > t1) return -1;
        }
        return t0;
    }

    /** Пределы проверки полёта (раздел check: конфига). */
    record Limits(double speedMargin, double slack, double turnMargin, double turnSlack, int maxDtTicks) {
        static final Limits DEFAULT = new Limits(1.3, 1.5, 1.5, 10, 40);
    }

    /**
     * Можно ли кораблю за dt тиков перейти из (p0, q0) в (p1, q1) — как Flight старых кораблей: путь не длиннее
     * форсажа × запас × dt + допуск, поворот не больше наибольшей скорости поворота × запас × dt + допуск.
     * null — можно, иначе причина.
     */
    static String check(double[] p0, Quat q0, double[] p1, Quat q1, long dtTicks, VesselKind k, Limits lim) {
        return check(p0, q0, p1, q1, dtTicks, k, lim, 1);
    }

    /** То же, скорость корабля × speedMul (улучшения «Двигатель+», аварийный ход без топлива). */
    static String check(double[] p0, Quat q0, double[] p1, Quat q1, long dtTicks, VesselKind k, Limits lim, double speedMul) {
        for (double v : p1) if (!Double.isFinite(v)) return "плохие числа";
        if (!q1.finite() || q1.length() < 0.5 || q1.length() > 1.5) return "плохой поворот";
        long dt = Math.max(1, Math.min(dtTicks, lim.maxDtTicks()));
        double allowed = k.boostSpeed * Math.max(1, speedMul) / 20.0 * lim.speedMargin() * dt + lim.slack();
        double dx = p1[0] - p0[0], dy = p1[1] - p0[1], dz = p1[2] - p0[2];
        if (dx * dx + dy * dy + dz * dz > allowed * allowed) return "слишком быстро";
        double turn = k.maxTurnRate() / 20.0 * lim.turnMargin() * dt + lim.turnSlack();
        if (q0.angleTo(q1.normalized()) > turn) return "слишком быстрый поворот";
        return null;
    }
}
