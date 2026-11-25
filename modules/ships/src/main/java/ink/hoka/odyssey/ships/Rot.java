package ink.hoka.odyssey.ships;

import net.minestom.server.instance.block.Block;

import java.util.HashMap;
import java.util.Map;

/**
 * Повороты на 90° по часовой стрелке (вид сверху): север → восток → юг → запад.
 * Координаты чертежа: x — восток, z — юг; размер sx × sz. Поворот r = 0..3.
 */
final class Rot {

    private Rot() {}

    private static final String[] H = {"north", "east", "south", "west"};

    /** Локальная позиция (x,z) в чертеже → позиция в повёрнутом прямоугольнике (минимальный угол = 0,0). */
    static int[] xz(int x, int z, int sx, int sz, int r) {
        return switch (r & 3) {
            case 1 -> new int[]{sz - 1 - z, x};
            case 2 -> new int[]{sx - 1 - x, sz - 1 - z};
            case 3 -> new int[]{z, sx - 1 - x};
            default -> new int[]{x, z};
        };
    }

    /** Размер повёрнутого прямоугольника: {sx', sz'}. */
    static int[] size(int sx, int sz, int r) {
        return (r & 1) == 1 ? new int[]{sz, sx} : new int[]{sx, sz};
    }

    /** Горизонтальное направление, повёрнутое на r шагов по часовой. Не горизонтальное — как есть. */
    static String dir(String d, int r) {
        for (int i = 0; i < 4; i++) if (H[i].equals(d)) return H[(i + r) & 3];
        return d;
    }

    /** Состояние блока, повёрнутое на r × 90° по часовой: facing, axis, rotation, стороны заборов/стен. */
    static Block block(Block b, int r) {
        r &= 3;
        if (r == 0 || b.isAir()) return b;
        Map<String, String> p = b.properties();
        if (p.isEmpty()) return b;
        Map<String, String> out = new HashMap<>(p);
        if (p.containsKey("facing")) out.put("facing", dir(p.get("facing"), r));
        if (p.containsKey("axis") && (r & 1) == 1) {
            String a = p.get("axis");
            out.put("axis", a.equals("x") ? "z" : a.equals("z") ? "x" : a);
        }
        if (p.containsKey("rotation")) out.put("rotation", String.valueOf((Integer.parseInt(p.get("rotation")) + 4 * r) & 15));
        if (p.containsKey("shape") && b.key().value().endsWith("rail")) out.put("shape", rail(p.get("shape"), r));
        boolean sides = false;
        for (String h : H) if (p.containsKey(h)) { sides = true; break; }
        if (sides) for (String h : H) if (p.containsKey(h)) out.put(dir(h, r), p.get(h));
        try {
            return b.withProperties(out);
        } catch (IllegalArgumentException e) {
            return b;                                                    // необычный блок — оставить как есть
        }
    }

    /** Форма рельсов: north_south/east_west, ascending_*, повороты (south_east и т.п.). */
    static String rail(String s, int r) {
        if (s.equals("north_south") || s.equals("east_west")) return (r & 1) == 0 ? s : s.equals("north_south") ? "east_west" : "north_south";
        if (s.startsWith("ascending_")) return "ascending_" + dir(s.substring(10), r);
        String[] ab = s.split("_");
        if (ab.length != 2) return s;
        String a = dir(ab[0], r), c = dir(ab[1], r);
        String ns = a.equals("north") || a.equals("south") ? a : c, ew = a.equals("north") || a.equals("south") ? c : a;
        return ns + "_" + ew;                                                   // допустимый порядок: south_east, north_west…
    }

    /** Поворот вектора (x,z) на угол a (градусы, как yaw Minecraft: +90 — юг → запад). */
    static double[] yaw(double x, double z, double deg) {
        double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
        return new double[]{x * c - z * s, x * s + z * c};
    }

    /** Кватернион поворота вокруг Y на тот же угол (для трансформации display-сущности): {x, y, z, w}. */
    static float[] quat(double deg) {
        double h = -Math.toRadians(deg) / 2;                              // yaw Minecraft — по часовой, ось Y — против
        return new float[]{0f, (float) Math.sin(h), 0f, (float) Math.cos(h)};
    }

    /** Кватернион наклона вокруг X: pitch > 0 (как у Minecraft — взгляд вниз) опускает нос (+z → вниз). */
    static float[] quatPitch(double deg) {
        double h = Math.toRadians(deg) / 2;
        return new float[]{(float) Math.sin(h), 0f, 0f, (float) Math.cos(h)};
    }

    /** Произведение кватернионов a·b (сначала b, потом a): {x, y, z, w}. */
    static float[] mul(float[] a, float[] b) {
        return new float[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }

    /** Кватернион крена вокруг Z (оси носа в системе «нос на юг»): roll > 0 опускает правый борт (−x). */
    static float[] quatRoll(double deg) {
        double h = Math.toRadians(deg) / 2;
        return new float[]{0f, 0f, (float) Math.sin(h), (float) Math.cos(h)};
    }

    /**
     * Ориентация модели: её перед (направление с yaw {@code base}) смотрит по курсу yaw с наклоном pitch.
     * = Y(yaw) · X(pitch) · Y(−base).
     */
    static float[] quat(double yaw, double pitch, double base) {
        return mul(mul(quat(yaw), quatPitch(pitch)), quat(-base));
    }

    /** То же с креном: Y(yaw) · X(pitch) · Z(roll) · Y(−base). */
    static float[] quat(double yaw, double pitch, double roll, double base) {
        return mul(mul(mul(quat(yaw), quatPitch(pitch)), quatRoll(roll)), quat(-base));
    }

    /** Повернуть вектор кватернионом q: q·v·q*. */
    static double[] rotate(float[] q, double x, double y, double z) {
        double qx = q[0], qy = q[1], qz = q[2], qw = q[3];
        double tx = 2 * (qy * z - qz * y), ty = 2 * (qz * x - qx * z), tz = 2 * (qx * y - qy * x);
        return new double[]{x + qw * tx + (qy * tz - qz * ty), y + qw * ty + (qz * tx - qx * tz), z + qw * tz + (qx * ty - qy * tx)};
    }

    /** Вектор из системы «нос на юг, горизонтально» → наклон pitch, затем курс yaw (как взгляд игрока). */
    static double[] orient(double x, double y, double z, double yaw, double pitch) {
        double p = Math.toRadians(pitch), c = Math.cos(p), s = Math.sin(p);
        double y2 = y * c - z * s, z2 = y * s + z * c;
        double[] h = yaw(x, z2, yaw);
        return new double[]{h[0], y2, h[1]};
    }

    /** Углы {курс, наклон, крен} кватерниона q в виде Y(курс)·X(наклон)·Z(крен) (как quat(yaw, pitch, roll, 0)). */
    static double[] euler(float[] q) {
        double[] f = rotate(q, 0, 0, 1);
        double pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -f[1]))));
        double yaw = Math.toDegrees(Math.atan2(-f[0], f[2]));
        float[] q0 = quat(yaw, pitch, 0);
        float[] r = mul(new float[]{-q0[0], -q0[1], -q0[2], q0[3]}, q);
        double[] u = rotate(r, 0, 1, 0);
        double roll = Math.toDegrees(Math.atan2(-u[0], u[1]));
        return new double[]{wrap(yaw), pitch, wrap(roll)};
    }

    /** Тот же курс, но ровно (наклон и крен 0). Нос вертикально — курс по «верху». */
    static float[] level(float[] q) {
        double[] f = rotate(q, 0, 0, 1);
        double hx = -f[0], hz = f[2];
        if (hx * hx + hz * hz < 1e-6) {
            double[] u = rotate(q, 0, 1, 0);
            double sg = f[1] < 0 ? 1 : -1;                                  // нос вниз — «верх» смотрит по курсу
            hx = -u[0] * sg;
            hz = u[2] * sg;
        }
        return quat(Math.toDegrees(Math.atan2(hx, hz)), 0, 0, 0);
    }

    /** Угол между поворотами (радианы). */
    static double angle(float[] a, float[] b) {
        double d = Math.abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]);
        return 2 * Math.acos(Math.min(1, d));
    }

    /** Сферическая интерполяция a → b на долю t. */
    static float[] slerp(float[] a, float[] b, double t) {
        double d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        float[] bb = b;
        if (d < 0) { d = -d; bb = new float[]{-b[0], -b[1], -b[2], -b[3]}; }
        double k0, k1;
        if (d > 0.9995) { k0 = 1 - t; k1 = t; }
        else {
            double th = Math.acos(d), sn = Math.sin(th);
            k0 = Math.sin((1 - t) * th) / sn;
            k1 = Math.sin(t * th) / sn;
        }
        float[] r = new float[4];
        double len = 0;
        for (int i = 0; i < 4; i++) { r[i] = (float) (a[i] * k0 + bb[i] * k1); len += r[i] * r[i]; }
        len = Math.sqrt(len);
        for (int i = 0; i < 4; i++) r[i] /= (float) len;
        return r;
    }

    /** Угол [-180, 180). */
    static double wrap(double deg) {
        deg %= 360;
        if (deg >= 180) deg -= 360;
        if (deg < -180) deg += 360;
        return deg;
    }
}
