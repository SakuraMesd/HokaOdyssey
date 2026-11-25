package ink.hoka.odyssey.server.tall.gen;

import java.util.Arrays;

/** Обход прямоугольника клеток (регионов) квадратной спиралью от центра: каждая клетка ровно один раз. */
public final class Spiral {

    private Spiral() {}

    /** Клетки [x0…x1]×[z0…z1] (включительно) по кольцам вокруг (cx, cz); центр вне — с ближайшей клетки. Ключ — pack. */
    public static long[] order(int x0, int z0, int x1, int z1, int cx, int cz) {
        if (x0 > x1 || z0 > z1) return new long[0];
        cx = Math.max(x0, Math.min(x1, cx));
        cz = Math.max(z0, Math.min(z1, cz));
        long total = (long) (x1 - x0 + 1) * (z1 - z0 + 1);
        if (total > 50_000_000L) throw new IllegalArgumentException("слишком большая область: " + total + " клеток");
        long[] out = new long[(int) total];
        int n = 0;
        int maxR = Math.max(Math.max(cx - x0, x1 - cx), Math.max(cz - z0, z1 - cz));
        if (in(cx, cz, x0, z0, x1, z1)) out[n++] = pack(cx, cz);
        for (int r = 1; r <= maxR; r++) {
            // кольцо r: верх (слева направо), правый край (сверху вниз), низ (справа налево), левый край (снизу вверх)
            for (int x = cx - r; x <= cx + r; x++) if (in(x, cz - r, x0, z0, x1, z1)) out[n++] = pack(x, cz - r);
            for (int z = cz - r + 1; z <= cz + r; z++) if (in(cx + r, z, x0, z0, x1, z1)) out[n++] = pack(cx + r, z);
            for (int x = cx + r - 1; x >= cx - r; x--) if (in(x, cz + r, x0, z0, x1, z1)) out[n++] = pack(x, cz + r);
            for (int z = cz + r - 1; z > cz - r; z--) if (in(cx - r, z, x0, z0, x1, z1)) out[n++] = pack(cx - r, z);
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    private static boolean in(int x, int z, int x0, int z0, int x1, int z1) { return x >= x0 && x <= x1 && z >= z0 && z <= z1; }

    public static long pack(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    public static int x(long k) { return (int) (k >> 32); }
    public static int z(long k) { return (int) k; }
}
