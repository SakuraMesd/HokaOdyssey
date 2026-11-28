package ink.hoka.odyssey.client.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 0.84 Форма и размер своего блока из описания сервера (blocks.json): часть многоклеточного блока ({@code part},
 * {@code size}), хитбокс клетки ({@code shape}, px) и анимация модели ({@code animation}). Без классов игры — для тестов.
 * Поворот — как у модели блока (CustomBlocks.rotation): вокруг середины клетки, rotateY(−rot).
 */
public record BlockForm(int part, int[] size, double[] box, String animation) {

    public static final BlockForm CUBE = new BlockForm(0, new int[]{1, 1, 1}, null, null);

    /** Главная клетка (в ней модель). */
    public boolean main() { return part == 0; }

    public static BlockForm parse(JsonObject d) {
        int part = d.has("part") ? d.get("part").getAsInt() : 0;
        int[] size = ints(d.has("size") ? d.getAsJsonArray("size") : null, 3);
        if (size == null) size = new int[]{1, 1, 1};
        for (int i = 0; i < 3; i++) size[i] = Math.max(1, Math.min(4, size[i]));
        int rot = d.has("rot") ? d.get("rot").getAsInt() : 0;
        int[] shape = ints(d.has("shape") ? d.getAsJsonArray("shape") : null, 6);
        String anim = d.has("animation") ? d.get("animation").getAsString() : null;
        return new BlockForm(part, size, shape == null ? null : rotateBox(shape, rot), anim);
    }

    private static int[] ints(JsonArray a, int n) {
        if (a == null || a.size() != n) return null;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = a.get(i).getAsInt();
        return out;
    }

    /** Хитбокс клетки {x1, y1, z1, x2, y2, z2} (px), повёрнутый на rot вокруг середины клетки: px, min ≤ max. */
    public static double[] rotateBox(int[] s, int rot) {
        double ax = s[0] - 8, az = s[2] - 8, bx = s[3] - 8, bz = s[5] - 8;
        double[] a = rotate(ax, az, rot), b = rotate(bx, bz, rot);
        return new double[]{Math.min(a[0], b[0]) + 8, Math.min(s[1], s[4]), Math.min(a[1], b[1]) + 8,
                Math.max(a[0], b[0]) + 8, Math.max(s[1], s[4]), Math.max(a[1], b[1]) + 8};
    }

    /** Точка (x, z) от середины, повёрнутая на rot: 90 → (−z, x), 180 → (−x, −z), 270 → (z, −x). */
    static double[] rotate(double x, double z, int rot) {
        return switch (Math.floorMod(rot, 360)) {
            case 90 -> new double[]{-z, x};
            case 180 -> new double[]{-x, -z};
            case 270 -> new double[]{z, -x};
            default -> new double[]{x, z};
        };
    }

    /**
     * Сдвиг модели (px) от угла главной клетки до поворота: «Java Block» — 0 (модель уже 0..16·X), прочие форматы —
     * центр модели в середине всего габарита (1×1 — 8, 8, как было; 2×2 — 16, 16: стык четырёх клеток).
     */
    public float[] modelOffset(boolean javaBlock) {
        return javaBlock ? new float[]{0, 0} : new float[]{size[0] * 8f, size[2] * 8f};
    }
}
