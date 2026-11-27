package ink.hoka.odyssey.client.vessels;

/**
 * Луч против формы корабля в его системе: сначала коробка (быстро отбросить), потом треугольники (Мёллер–Трумбор,
 * с обеих сторон — меши Blockbench рисуются без отсечения изнанки). Без классов игры — для тестов.
 */
final class MeshRay {

    private MeshRay() {}

    /** Луч (начало o, направление d — единичное) против коробки ±half: расстояние входа (0 — начало внутри) или −1. */
    static double box(double[] o, double[] d, double max, double[] half) {
        double t0 = 0, t1 = max;
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (Math.abs(o[a]) > half[a]) return -1;
                continue;
            }
            double ta = (-half[a] - o[a]) / d[a], tb = (half[a] - o[a]) / d[a];
            if (ta > tb) { double t = ta; ta = tb; tb = t; }
            t0 = Math.max(t0, ta);
            t1 = Math.min(t1, tb);
            if (t0 > t1) return -1;
        }
        return t0;
    }

    /** Ближайшее попадание по треугольникам (по 9 чисел) не дальше max, или −1. */
    static double triangles(double[] o, double[] d, double max, float[] tris) {
        double best = -1;
        for (int i = 0; i + 8 < tris.length; i += 9) {
            double t = triangle(o, d, tris, i);
            if (t >= 0 && t <= max && (best < 0 || t < best)) best = t;
        }
        return best;
    }

    /** Коробка, потом треугольники. Треугольников ещё нет (модель качается) — по коробке. */
    static double hit(double[] o, double[] d, double max, double[] half, float[] tris) {
        if (box(o, d, max, half) < 0) return -1;
        if (tris == null || tris.length == 0) return box(o, d, max, half);
        return triangles(o, d, max, tris);
    }

    /** Мёллер–Трумбор: расстояние вдоль луча или −1. */
    static double triangle(double[] o, double[] d, float[] t, int i) {
        double e1x = t[i + 3] - t[i], e1y = t[i + 4] - t[i + 1], e1z = t[i + 5] - t[i + 2];
        double e2x = t[i + 6] - t[i], e2y = t[i + 7] - t[i + 1], e2z = t[i + 8] - t[i + 2];
        double px = d[1] * e2z - d[2] * e2y, py = d[2] * e2x - d[0] * e2z, pz = d[0] * e2y - d[1] * e2x;
        double det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-12) return -1;                                   // луч вдоль плоскости / вырожденный
        double inv = 1 / det;
        double sx = o[0] - t[i], sy = o[1] - t[i + 1], sz = o[2] - t[i + 2];
        double u = (sx * px + sy * py + sz * pz) * inv;
        if (u < 0 || u > 1) return -1;
        double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
        double v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv;
        if (v < 0 || u + v > 1) return -1;
        double dist = (e2x * qx + e2y * qy + e2z * qz) * inv;
        return dist >= 0 ? dist : -1;
    }
}
