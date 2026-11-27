package ink.hoka.odyssey.vessels;

import java.util.List;
import java.util.Set;

/**
 * Луч выстрела (без Minestom — для тестов): блоки мира (шаг по клеткам, Amanatides–Woo), сущности (коробки) и чужие
 * корабли (клетки корпуса). Незагруженный куб — луч обрывается, это промах (что там — неизвестно). Свой корабль и
 * пилота сюда просто не передают.
 */
final class ShotRay {

    private ShotRay() {}

    /** Мир для луча: что в блоке. */
    interface Blocks {
        int AIR = 0, SOLID = 1, UNLOADED = 2;

        int at(int x, int y, int z);
    }

    /** Сущность-цель: коробка в мире. */
    record Target(Object ref, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

    /** Чужой корабль: середина, поворот, половины коробки, клетки корпуса (система корабля, floor, 1 блок). */
    record Hull(Object ref, double[] center, Quat rot, double[] half, List<int[]> cells) {}

    enum Kind { NONE, BLOCK, ENTITY, VESSEL, UNLOADED }

    /** Итог: что задели, на каком расстоянии (NONE/UNLOADED — до конца луча), цель (сущность/корабль) и блок. */
    record Hit(Kind kind, double t, Object ref, int bx, int by, int bz) {
        double[] point(double[] o, double[] d) { return new double[]{o[0] + d[0] * t, o[1] + d[1] * t, o[2] + d[2] * t}; }
    }

    /** Луч из o по d (единичный) не дальше max. */
    static Hit cast(double[] o, double[] d, double max, Blocks blocks, List<Target> targets, List<Hull> hulls) {
        Hit best = blocks(o, d, max, blocks);
        double lim = best.t;
        for (Target tg : targets) {
            double t = box(o, d, lim, tg.minX, tg.minY, tg.minZ, tg.maxX, tg.maxY, tg.maxZ);
            if (t >= 0 && t < lim) { lim = t; best = new Hit(Kind.ENTITY, t, tg.ref, 0, 0, 0); }
        }
        for (Hull h : hulls) {
            double t = hull(o, d, lim, h);
            if (t >= 0 && t < lim) { lim = t; best = new Hit(Kind.VESSEL, t, h.ref, 0, 0, 0); }
        }
        return best;
    }

    /** Блоки по пути: первый твёрдый (BLOCK) / незагруженный (UNLOADED) / ничего до max (NONE). */
    static Hit blocks(double[] o, double[] d, double max, Blocks w) {
        int x = (int) Math.floor(o[0]), y = (int) Math.floor(o[1]), z = (int) Math.floor(o[2]);
        int sx = d[0] > 0 ? 1 : d[0] < 0 ? -1 : 0, sy = d[1] > 0 ? 1 : d[1] < 0 ? -1 : 0, sz = d[2] > 0 ? 1 : d[2] < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.MAX_VALUE : Math.abs(1 / d[0]), tdy = sy == 0 ? Double.MAX_VALUE : Math.abs(1 / d[1]), tdz = sz == 0 ? Double.MAX_VALUE : Math.abs(1 / d[2]);
        double tx = sx == 0 ? Double.MAX_VALUE : ((sx > 0 ? x + 1 - o[0] : o[0] - x) * tdx);
        double ty = sy == 0 ? Double.MAX_VALUE : ((sy > 0 ? y + 1 - o[1] : o[1] - y) * tdy);
        double tz = sz == 0 ? Double.MAX_VALUE : ((sz > 0 ? z + 1 - o[2] : o[2] - z) * tdz);
        double t = 0;
        for (int guard = 0; guard < 4096 && t <= max; guard++) {
            int s = w.at(x, y, z);
            if (s == Blocks.SOLID) return new Hit(Kind.BLOCK, t, null, x, y, z);
            if (s == Blocks.UNLOADED) return new Hit(Kind.UNLOADED, t, null, x, y, z);
            if (tx <= ty && tx <= tz) { t = tx; tx += tdx; x += sx; }
            else if (ty <= tz) { t = ty; ty += tdy; y += sy; }
            else { t = tz; tz += tdz; z += sz; }
        }
        return new Hit(Kind.NONE, max, null, 0, 0, 0);
    }

    /** Луч против коробки (мир): расстояние входа (0 — начало внутри) или −1. */
    static double box(double[] o, double[] d, double max, double x0, double y0, double z0, double x1, double y1, double z1) {
        double t0 = 0, t1 = max;
        double[] lo = {x0, y0, z0}, hi = {x1, y1, z1};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] > hi[a]) return -1;
                continue;
            }
            double ta = (lo[a] - o[a]) / d[a], tb = (hi[a] - o[a]) / d[a];
            if (ta > tb) { double s = ta; ta = tb; tb = s; }
            t0 = Math.max(t0, ta);
            t1 = Math.min(t1, tb);
            if (t0 > t1) return -1;
        }
        return t0;
    }

    /** Луч против клеток корпуса корабля (в его системе). */
    static double hull(double[] o, double[] d, double max, Hull h) {
        if (VesselMath.rayBox(o, d, max, h.center, h.rot, new double[]{h.half[0] + 1, h.half[1] + 1, h.half[2] + 1}) < 0) return -1;
        double[] lo = VesselMath.toLocal(o[0], o[1], o[2], h.center, h.rot);
        double[] ld = h.rot.conjugate().rotate(d[0], d[1], d[2]);
        double best = -1;
        for (int[] c : h.cells) {
            double t = box(lo, ld, best < 0 ? max : best, c[0], c[1], c[2], c[0] + 1, c[1] + 1, c[2] + 1);
            if (t >= 0 && (best < 0 || t < best)) best = t;
        }
        return best;
    }

    /** Не задевать: набор ссылок (пилот, стоящие на своём корабле). */
    static List<Target> without(List<Target> all, Set<Object> skip) {
        return all.stream().filter(t -> !skip.contains(t.ref)).toList();
    }
}
