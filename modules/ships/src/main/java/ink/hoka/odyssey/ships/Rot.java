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
        boolean sides = false;
        for (String h : H) if (p.containsKey(h)) { sides = true; break; }
        if (sides) for (String h : H) if (p.containsKey(h)) out.put(dir(h, r), p.get(h));
        try {
            return b.withProperties(out);
        } catch (IllegalArgumentException e) {
            return b;                                                    // необычный блок — оставить как есть
        }
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

    /** Угол [-180, 180). */
    static double wrap(double deg) {
        deg %= 360;
        if (deg >= 180) deg -= 360;
        if (deg < -180) deg += 360;
        return deg;
    }
}
