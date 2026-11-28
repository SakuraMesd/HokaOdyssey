package ink.hoka.odyssey.client.vessels;

/**
 * «Подушка» над планетой (без Minestom и игры — один и тот же файл у сервера и клиентского модуля, сверяется тестом):
 * корпус не подходит к блокам ближе clearance блоков — во все стороны (вниз к земле, вбок к горам, вверх к сводам).
 * Не стена, а ограничение скорости к блокам: в мягкой зоне (clearance … clearance + soft) скорость «к блокам» не больше
 * k·(зазор − clearance) за тик — корабль плавно тормозит и подходит к границе, не перескакивая её (k ≤ 0.5); отнятое
 * у стенки (гора, обрыв) уходит вверх (climb) — корабль огибает препятствие сверху; ближе clearance — мягко выталкивает.
 * Зазор — от формы корпуса: по точкам корпуса (середины клеток) расстояние до середины ближайшего блока минус 1
 * (полблока корпуса + полблока блока = зазор между гранями).
 */
final class Cushion {

    private Cushion() {}

    /**
     * Настройки карты-планеты. clearance — зазор, soft — мягкая зона, climb — доля «отнятой» скорости вверх,
     * beamReach — дальность луча, tolerance — допуск сервера.
     */
    record Settings(double clearance, double soft, double climb, double beamReach, double tolerance) {
        static final Settings DEFAULT = new Settings(10, 6, 0.8, 16, 3);

        /** До какого расстояния от корпуса нужно поле расстояний. */
        double reach() { return clearance + soft + 2; }

        /** Из S2C WORLD: не планета — null. */
        static Settings read(com.google.gson.JsonObject o) {
            if (o == null || !o.has("planet") || !o.get("planet").getAsBoolean()) return null;
            return new Settings(num(o, "clearance", 10), num(o, "soft", 6), num(o, "climb", 0.8), num(o, "beam-reach", 16), num(o, "tolerance", 3));
        }

        private static double num(com.google.gson.JsonObject o, String k, double def) {
            try { return o.has(k) ? o.get(k).getAsDouble() : def; } catch (RuntimeException e) { return def; }
        }
    }

    /** Зазор между гранями корпуса и блоков по точкам корпуса (мир, по 3 числа): наименьший. Блоков нет — FAR. */
    static double clearance(DistField f, double[] probes) {
        double min = DistField.FAR;
        for (int i = 0; i + 2 < probes.length; i += 3) min = Math.min(min, f.at(probes[i], probes[i + 1], probes[i + 2]) - 1);
        return min;
    }

    /** Сила отталкивания 0…1 при зазоре c: 0 — дальше clearance + soft, растёт к clearance, 1 — у clearance и ближе. */
    static double force(double c, Settings s) {
        if (c >= s.clearance() + s.soft()) return 0;
        if (c <= s.clearance()) return 1;
        double t = 1 - (c - s.clearance()) / Math.max(1e-6, s.soft());
        return t * t * (3 - 2 * t);
    }

    /** Наибольшая скорость к блокам (блоков за тик) при зазоре c: k·(c − clearance), k = min(0.5, cap / soft). */
    static double approach(double c, double cap, Settings s) {
        if (c >= s.clearance() + s.soft()) return Double.MAX_VALUE;
        double k = Math.min(0.5, cap / Math.max(1e-6, s.soft()));
        return Math.max(0, c - s.clearance()) * k;
    }

    /**
     * Скорость корабля (блоков за тик, мир) с подушкой. probes — точки корпуса в мире (по 3 числа) там, где корабль
     * сейчас; cap — наибольшая скорость (для мягкости торможения). Вернуть новую скорость {vx, vy, vz}.
     */
    static double[] apply(DistField f, double[] probes, double vx, double vy, double vz, double cap, Settings s) {
        double lim = s.clearance() + s.soft();
        int n = 0;
        double[] cs = new double[probes.length / 3], ns = new double[probes.length];
        double[] g = new double[3];
        double cmin = DistField.FAR, ox = 0, oy = 0, oz = 0;
        for (int i = 0; i + 2 < probes.length; i += 3) {                        // ограничения: точки корпуса в мягкой зоне
            double c = f.at(probes[i], probes[i + 1], probes[i + 2]) - 1;
            if (c >= lim) continue;
            f.grad(probes[i], probes[i + 1], probes[i + 2], g);
            double gl = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
            if (gl < 1e-6) continue;
            cs[n] = c;
            ns[n * 3] = g[0] / gl; ns[n * 3 + 1] = g[1] / gl; ns[n * 3 + 2] = g[2] / gl;
            if (c < cmin) { cmin = c; ox = ns[n * 3]; oy = ns[n * 3 + 1]; oz = ns[n * 3 + 2]; }
            n++;
        }
        if (n == 0) return new double[]{vx, vy, vz};
        for (int pass = 0; pass < 4; pass++) {                                  // каждая точка: к блокам не быстрее approach(зазор)
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                double nx = ns[i * 3], ny = ns[i * 3 + 1], nz = ns[i * 3 + 2];
                double vn = vx * nx + vy * ny + vz * nz, a = approach(cs[i], cap, s);
                if (vn >= -a - 1e-9) continue;
                double dv = -a - vn;
                vx += nx * dv; vy += ny * dv; vz += nz * dv;
                double side = 1 - Math.abs(ny);                                 // упёрлись вбок (гора, стена) — вверх
                if (pass == 0 && side > 0.05 && ny > -0.5) vy += dv * s.climb() * side;
                changed = true;
            }
            if (!changed) break;
        }
        if (cmin < s.clearance()) {                                             // ближе clearance — мягко наружу
            double push = Math.min((s.clearance() - cmin) * 0.15, Math.max(0.05, cap * 0.3));
            double out = vx * ox + vy * oy + vz * oz;
            if (out < push) {
                double add = push - out;
                vx += ox * add; vy += oy * add; vz += oz * add;
            }
        }
        return new double[]{vx, vy, vz};
    }

    /**
     * Высота зависания над точкой: наименьшая высота середины корабля (от fromY вверх, шагом step, не выше toY), на
     * которой зазор не меньше clearance. offsets — точки корпуса относительно середины (мир, по 3 числа). Нет — NaN.
     */
    static double hoverY(DistField f, double[] offsets, double x, double z, double fromY, double toY, double step, Settings s) {
        double[] p = new double[offsets.length];
        for (double y = fromY; y <= toY + 1e-9; y += step) {
            for (int i = 0; i + 2 < offsets.length; i += 3) {
                p[i] = x + offsets[i]; p[i + 1] = y + offsets[i + 1]; p[i + 2] = z + offsets[i + 2];
            }
            if (clearance(f, p) >= s.clearance() - 1e-6) return y;
        }
        return Double.NaN;
    }
}
