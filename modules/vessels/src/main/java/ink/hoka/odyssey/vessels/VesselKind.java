package ink.hoka.odyssey.vessels;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Вид корабля (раздел kinds: конфига) и его форма из модели. Система корабля: начало — середина габарита модели,
 * оси — оси модели, повёрнутые на turn вокруг вертикали (нос — +Z, верх — +Y, левый борт — +X), единица — блок.
 * Положение корабля в мире — где начало этой системы, поворот — кватернион система корабля → мир.
 */
final class VesselKind {

    final String id;
    final String model;
    final double scale;
    /** Поворот модели вокруг вертикали (°), чтобы нос смотрел в +Z. */
    final double turn;
    /** Место пилота — глаза пилота в системе корабля (блоки). */
    final double[] seat;
    final double speed, boostSpeed, accel, turnSpeed, rollSpeed;
    /** Середина габарита модели (пиксели Blockbench) — начало системы корабля. */
    final double[] centerPx;
    /** Половины коробки в системе корабля (блоки). */
    final double[] half;
    /** Клетки корпуса (система корабля, блоки, floor): по ним сервер проверяет «не в блоках ли». */
    final List<int[]> hull;
    /**
     * Мелкие клетки корпуса — по полблока (индексы floor(x·2)…): по ним игрок стоит на корабле и упирается в него
     * (как у клиента: Kind.fine), сервер — узнаёт, кто стоит на корабле.
     */
    final Set<Long> fine;
    /** Стволы орудия (система корабля, блоки): стреляют по очереди. */
    final List<double[]> guns;
    final int triangles;

    VesselKind(String id, Map<?, ?> cfg, ModelShape shape) {
        this.id = id;
        this.model = str(cfg, "model", "vessels/" + id + ".bbmodel");
        this.scale = num(cfg, "scale", 1);
        this.turn = num(cfg, "turn", 0);
        this.seat = vec(cfg, "seat", new double[]{0, 1, 0});
        this.speed = num(cfg, "speed", 30);
        this.boostSpeed = Math.max(speed, num(cfg, "boost-speed", 50));
        this.accel = num(cfg, "accel", 15);
        this.turnSpeed = num(cfg, "turn-speed", 90);
        this.rollSpeed = num(cfg, "roll-speed", 120);
        double[] b = shape.box;
        this.centerPx = new double[]{(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2};
        this.triangles = shape.triangleCount();
        // коробка в системе корабля: 8 углов габарита → поворот turn → габарит (при turn, кратном 90°, — точный)
        double[] h = new double[3];
        for (int c = 0; c < 8; c++) {
            double[] p = toShip((c & 1) != 0 ? b[3] : b[0], (c & 2) != 0 ? b[4] : b[1], (c & 4) != 0 ? b[5] : b[2]);
            for (int a = 0; a < 3; a++) h[a] = Math.max(h[a], Math.abs(p[a]));
        }
        this.half = h;
        this.hull = hullOf(shape);
        this.fine = fineOf(shape);
        List<double[]> g = new ArrayList<>();
        if (cfg.get("guns") instanceof List<?> l) for (Object o : l) {
            if (o instanceof List<?> v && v.size() >= 3 && v.get(0) instanceof Number gx && v.get(1) instanceof Number gy && v.get(2) instanceof Number gz)
                g.add(new double[]{gx.doubleValue(), gy.doubleValue(), gz.doubleValue()});
        }
        if (g.isEmpty()) g.add(new double[]{0, 0, half[2] + 0.5});                // нет в конфиге — из носа
        this.guns = List.copyOf(g);
    }

    /** Ключ мелкой клетки (индексы полублоков). */
    static long fineKey(int ix, int iy, int iz) {
        return ((long) (ix & 0x1FFFFF) << 42) | ((long) (iy & 0x1FFFFF) << 21) | (iz & 0x1FFFFF);
    }

    /** Мелкая клетка с точкой (система корабля, блоки) — корпус? */
    boolean fineAt(double x, double y, double z) {
        return fine.contains(fineKey((int) Math.floor(x * 2), (int) Math.floor(y * 2), (int) Math.floor(z * 2)));
    }

    /**
     * Стоит ли игрок (ноги — точка системы корабля, «верх» — +Y корабля) на корпусе: под ступнями (квадрат 0.6) в
     * пределах below блоков вниз есть мелкая клетка, а сами ноги не в корпусе.
     */
    boolean standsOn(double x, double y, double z, double below) {
        for (double dx : new double[]{-0.3, 0, 0.3}) for (double dz : new double[]{-0.3, 0, 0.3}) {
            for (double d = 0.05; d <= below + 1e-9; d += 0.25) if (fineAt(x + dx, y - d, z + dz)) return true;
        }
        return false;
    }

    /** Клетки по полблока, которых касаются треугольники (точки по поверхности через ~1/6 блока). */
    private Set<Long> fineOf(ModelShape shape) {
        Set<Long> out = new HashSet<>();
        float[] t = shape.triangles;
        for (int i = 0; i + 8 < t.length; i += 9) {
            double[] a = toShip(t[i], t[i + 1], t[i + 2]), b = toShip(t[i + 3], t[i + 4], t[i + 5]), c = toShip(t[i + 6], t[i + 7], t[i + 8]);
            double len = Math.max(dist(a, b), Math.max(dist(b, c), dist(a, c)));
            int n = Math.max(1, (int) Math.ceil(len * 6));
            for (int u = 0; u <= n; u++) for (int v = 0; u + v <= n; v++) {
                double fu = (double) u / n, fv = (double) v / n, fw = 1 - fu - fv;
                out.add(fineKey((int) Math.floor((a[0] * fw + b[0] * fu + c[0] * fv) * 2), (int) Math.floor((a[1] * fw + b[1] * fu + c[1] * fv) * 2),
                        (int) Math.floor((a[2] * fw + b[2] * fu + c[2] * fv) * 2)));
            }
        }
        return out;
    }

    /** Точка модели (пиксели) → система корабля (блоки). */
    double[] toShip(double px, double py, double pz) {
        double k = scale / 16.0;
        double x = (px - centerPx[0]) * k, y = (py - centerPx[1]) * k, z = (pz - centerPx[2]) * k;
        return Quat.aroundY(turn).rotate(x, y, z);
    }

    /** Наибольшее расстояние от начала до угла коробки. */
    double radius() { return Math.sqrt(half[0] * half[0] + half[1] * half[1] + half[2] * half[2]); }

    /** Клетки, которых касаются треугольники модели (точки по поверхности через ~1/3 блока). */
    private List<int[]> hullOf(ModelShape shape) {
        Set<Long> seen = new HashSet<>();
        List<int[]> out = new ArrayList<>();
        float[] t = shape.triangles;
        for (int i = 0; i + 8 < t.length; i += 9) {
            double[] a = toShip(t[i], t[i + 1], t[i + 2]), b = toShip(t[i + 3], t[i + 4], t[i + 5]), c = toShip(t[i + 6], t[i + 7], t[i + 8]);
            double len = Math.max(dist(a, b), Math.max(dist(b, c), dist(a, c)));
            int n = Math.max(1, (int) Math.ceil(len * 3));
            for (int u = 0; u <= n; u++) for (int v = 0; u + v <= n; v++) {
                double fu = (double) u / n, fv = (double) v / n, fw = 1 - fu - fv;
                int x = (int) Math.floor(a[0] * fw + b[0] * fu + c[0] * fv);
                int y = (int) Math.floor(a[1] * fw + b[1] * fu + c[1] * fv);
                int z = (int) Math.floor(a[2] * fw + b[2] * fu + c[2] * fv);
                if (seen.add(((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF))) out.add(new int[]{x, y, z});
            }
        }
        return out;
    }

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Наибольшая допустимая скорость поворота (°/с): курс и наклон вместе с креном. */
    double maxTurnRate() { return Math.sqrt(2 * turnSpeed * turnSpeed + rollSpeed * rollSpeed); }

    // ───────────────────────── конфиг ─────────────────────────

    private static String str(Map<?, ?> m, String k, String def) {
        Object o = m.get(k);
        return o == null ? def : o.toString();
    }

    static double num(Map<?, ?> m, String k, double def) {
        Object o = m.get(k);
        if (o instanceof Number n) return n.doubleValue();
        if (o != null) try { return Double.parseDouble(o.toString()); } catch (NumberFormatException ignored) { }
        return def;
    }

    private static double[] vec(Map<?, ?> m, String k, double[] def) {
        if (m.get(k) instanceof List<?> l && l.size() >= 3) {
            double[] v = new double[3];
            for (int i = 0; i < 3; i++) v[i] = l.get(i) instanceof Number n ? n.doubleValue() : def[i];
            return v;
        }
        return def;
    }
}
