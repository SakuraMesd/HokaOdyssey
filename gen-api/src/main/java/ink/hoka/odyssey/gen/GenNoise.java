package ink.hoka.odyssey.gen;

/**
 * Градиентный шум (улучшенный шум Перлина) 2D/3D от сида: только + − × и floor — одинаково на любой JVM и платформе.
 * Неизменяемый после создания — можно звать из многих потоков. Значения примерно в [−1, 1].
 */
public final class GenNoise {

    private final int[] p = new int[512];

    public GenNoise(long seed) {
        int[] perm = new int[256];
        for (int i = 0; i < 256; i++) perm[i] = i;
        GenRandom r = new GenRandom(seed);
        for (int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = perm[i]; perm[i] = perm[j]; perm[j] = t;
        }
        for (int i = 0; i < 512; i++) p[i] = perm[i & 255];
    }

    private static int floor(double v) { int i = (int) v; return v < i ? i - 1 : i; }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }

    private static double lerp(double t, double a, double b) { return a + t * (b - a); }

    private static double grad(int h, double x, double y, double z) {
        int k = h & 15;
        double u = k < 8 ? x : y, v = k < 4 ? y : k == 12 || k == 14 ? x : z;
        return ((k & 1) == 0 ? u : -u) + ((k & 2) == 0 ? v : -v);
    }

    public double noise(double x, double y, double z) {
        int fx = floor(x), fy = floor(y), fz = floor(z);
        int X = fx & 255, Y = fy & 255, Z = fz & 255;
        x -= fx; y -= fy; z -= fz;
        double u = fade(x), v = fade(y), w = fade(z);
        int A = p[X] + Y, AA = p[A] + Z, AB = p[A + 1] + Z, B = p[X + 1] + Y, BA = p[B] + Z, BB = p[B + 1] + Z;
        return lerp(w, lerp(v, lerp(u, grad(p[AA], x, y, z), grad(p[BA], x - 1, y, z)),
                        lerp(u, grad(p[AB], x, y - 1, z), grad(p[BB], x - 1, y - 1, z))),
                lerp(v, lerp(u, grad(p[AA + 1], x, y, z - 1), grad(p[BA + 1], x - 1, y, z - 1)),
                        lerp(u, grad(p[AB + 1], x, y - 1, z - 1), grad(p[BB + 1], x - 1, y - 1, z - 1))));
    }

    public double noise(double x, double z) { return noise(x, 0.5, z); }

    /** Сумма октав (амплитуда ×gain, частота ×2), нормирована к [−1, 1]. */
    public double fbm(double x, double z, int octaves, double gain) {
        double sum = 0, amp = 1, norm = 0, f = 1;
        for (int o = 0; o < octaves; o++) {
            sum += amp * noise(x * f, 0.5 + o * 17.0, z * f);
            norm += amp;
            amp *= gain;
            f *= 2;
        }
        return sum / norm;
    }
}
