package ink.hoka.odyssey.gen;

import java.nio.charset.StandardCharsets;

/**
 * Хэш содержимого куба — ЕДИНСТВЕННАЯ копия алгоритма (CubeHash сервера и клиента делегируют сюда): 4096 номеров
 * состояний (индекс y&lt;&lt;8|z&lt;&lt;4|x) и 64 номера биомов (y&lt;&lt;4|z&lt;&lt;2|x). Формат {@link #FORMAT} 1 — тот же, что у кэша
 * кубов клиента (0.51): хэш видимой части сгенерированного куба равен хэшу op 2 того же куба.
 */
public final class GenHash {

    private GenHash() {}

    /** Версия формата (меняется — у старых кэшей просто промахи). */
    public static final int FORMAT = 1;
    public static final long SEED = 0x484F4B4143554245L ^ FORMAT;              // "HOKACUBE"
    public static final long SEED_LIGHT = 0x484F4B414C494748L ^ FORMAT;        // "HOKALIGH"
    static final long SEED_STR = 0x484F4B4153545253L;                          // "HOKASTRS"
    private static final long P1 = 0x9E3779B185EBCA87L, P2 = 0xC2B2AE3D27D4EB4FL, P3 = 0x165667B19E3779F9L,
            P4 = 0x85EBCA77C2B2AE63L, P5 = 0x27D4EB2F165667C5L;

    public static long round(long h, long v) {
        h ^= v * P2;
        return Long.rotateLeft(h, 31) * P1 + P4;
    }

    public static long avalanche(long h) {
        h ^= h >>> 33;
        h *= P2;
        h ^= h >>> 29;
        h *= P3;
        h ^= h >>> 32;
        return h == 0 ? 1 : h;                                                // 0 — «неизвестно»
    }

    /** Хэш куба по значениям (blocks — 4096, biomes — 64). */
    public static long ids(int[] blocks, int[] biomes) {
        long h = SEED;
        for (int v : blocks) h = round(h, v & 0xFFFFFFFFL);
        h = round(h, P5);
        for (int v : biomes) h = round(h, v & 0xFFFFFFFFL);
        return avalanche(h ^ (blocks.length + biomes.length));
    }

    /** Хэш однородного куба (все блоки state, все биомы biome) без массивов. */
    public static long uniform(int state, int biome) {
        long h = SEED;
        for (int i = 0; i < 4096; i++) h = round(h, state & 0xFFFFFFFFL);
        h = round(h, P5);
        for (int i = 0; i < 64; i++) h = round(h, biome & 0xFFFFFFFFL);
        return avalanche(h ^ (4096 + 64));
    }

    /** Дайджест списка строк по порядку (таблицы номеров: op 20 statesDigest/biomesDigest). */
    public static long strings(Iterable<String> list) {
        long h = SEED_STR;
        long n = 0;
        for (String s : list) {
            byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
            h = round(h, b.length);
            for (byte x : b) h = round(h, x & 0xFF);
            n++;
        }
        return avalanche(h ^ n);
    }
}
