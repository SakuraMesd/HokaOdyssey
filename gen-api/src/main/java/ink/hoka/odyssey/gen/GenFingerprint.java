package ink.hoka.odyssey.gen;

/**
 * Отпечаток генератора: хэш видимой части 16 фиксированных кубов по {@link GenHash} — одинаков у сервера и клиента,
 * если у них один jar, одни номера и одна арифметика. Сервер шлёт его в op 20, клиент сверяет (статус 3 при расхождении).
 * <p>{@link #visible} — канонический путь «куб глазами клиента» (границы → hasBlocks → uniform → generate без секретов);
 * им же пользуются отпечаток, выборочная сверка сервера и тесты.</p>
 */
public final class GenFingerprint {

    private GenFingerprint() {}

    /** Видимая часть куба в out (сброшен внутри). Пустой по границам/hasBlocks — воздух, однородный — заливка, биомы 0. */
    public static GenArrayCube visible(PlanetGenerator g, GenBounds b, long seed, int cx, int cy, int cz, GenArrayCube out) {
        out.reset(cx, cy, cz, seed);
        if (!b.cubeInside(cx, cy, cz) || !g.hasBlocks(cx, cy, cz)) return out;
        int u = g.uniform(cx, cy, cz);
        if (u >= 0) { out.fill(u); return out; }
        g.generate(out);
        return out;
    }

    /** Отпечаток: 16 кубов от minY до maxY границ, по X/Z — у центра (в пределах size). */
    public static long of(PlanetGenerator g, GenBounds b, long seed) {
        GenArrayCube buf = new GenArrayCube(false);
        long h = GenHash.SEED ^ 0x46494E474552L;                              // "FINGER"
        int minCy = b.minY() >> 4, maxCy = b.maxY() >> 4;
        int half = b.size() > 0 ? Math.max(1, b.size() / 32) : 32;
        for (int k = 0; k < 16; k++) {
            int cy = minCy + (int) ((long) (maxCy - minCy) * k / 15);
            int cx = Math.floorMod(k * 37, 2 * half) - half, cz = Math.floorMod(k * 61 + 7, 2 * half) - half;
            h = GenHash.round(h, visible(g, b, seed, cx, cy, cz, buf).visHash());
        }
        return GenHash.avalanche(h);
    }
}
