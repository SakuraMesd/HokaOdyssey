package ink.hoka.odyssey.gen;

/**
 * Детерминированная случайность (SplitMix64): от сида и координат, одинаково на любой JVM. Экземпляр — на один вызов
 * generate (не делить между потоками); статические {@link #hash} — без состояния.
 */
public final class GenRandom {

    private long s;

    public GenRandom(long seed) { this.s = seed; }

    /** Генератор для точки (куба, колонки) — от сида и координат. */
    public static GenRandom at(long seed, int x, int y, int z) { return new GenRandom(hash(seed, x, y, z)); }

    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Хэш сида и координат (без состояния). */
    public static long hash(long seed, int x, int y, int z) {
        long h = mix(seed + 0x9E3779B97F4A7C15L);
        h = mix(h ^ (x * 0x9E3779B97F4A7C15L));
        h = mix(h ^ (y * 0xC2B2AE3D27D4EB4FL));
        return mix(h ^ (z * 0x165667B19E3779F9L));
    }

    public long nextLong() { s += 0x9E3779B97F4A7C15L; return mix(s); }

    public int nextInt() { return (int) (nextLong() >>> 32); }

    /** 0 ≤ r &lt; bound. */
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound");
        return (int) (((nextLong() >>> 33) * bound) >>> 31);
    }

    /** [0, 1). */
    public double nextDouble() { return (nextLong() >>> 11) * 0x1.0p-53; }

    public boolean chance(double p) { return nextDouble() < p; }
}
