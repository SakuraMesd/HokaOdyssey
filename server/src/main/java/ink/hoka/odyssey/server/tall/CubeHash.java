package ink.hoka.odyssey.server.tall;

import net.minestom.server.instance.Section;
import net.minestom.server.instance.palette.Palette;

/**
 * Версия куба для кэша клиента (ядро 0.51): 64-битный хэш СОДЕРЖИМОГО — 4096 stateId (индекс y&lt;&lt;8|z&lt;&lt;4|x) и 64 id
 * биомов (y&lt;&lt;4|z&lt;&lt;2|x). От порядка палитры и истории правок не зависит, поэтому одинаков после выгрузки, загрузки
 * с диска и перезапуска. Свет — хэш записи op 11 в каноническом виде (как ServerLight.Cube клиента).
 * <p>Копия ядра — в клиенте ({@code ink.hoka.odyssey.client.CubeHash}): менять ОБА файла и {@link #FORMAT} вместе.
 * Разбор сетевой формы секции ({@link #wire}) — тот, которым считает клиент; тест сверяет его с {@link #of}.</p>
 */
public final class CubeHash {

    private CubeHash() {}

    /** Версия формата хэша (меняется — у старых кэшей просто промахи). */
    public static final int FORMAT = 1;
    static final long SEED = 0x484F4B4143554245L ^ FORMAT;                 // "HOKACUBE"
    static final long SEED_LIGHT = 0x484F4B414C494748L ^ FORMAT;           // "HOKALIGH"
    private static final long P1 = 0x9E3779B185EBCA87L, P2 = 0xC2B2AE3D27D4EB4FL, P3 = 0x165667B19E3779F9L,
            P4 = 0x85EBCA77C2B2AE63L, P5 = 0x27D4EB2F165667C5L;

    static long round(long h, long v) {
        h ^= v * P2;
        return Long.rotateLeft(h, 31) * P1 + P4;
    }

    static long avalanche(long h) {
        h ^= h >>> 33;
        h *= P2;
        h ^= h >>> 29;
        h *= P3;
        h ^= h >>> 32;
        return h == 0 ? 1 : h;                                            // 0 — «неизвестно»
    }

    /** Хэш по значениям (blocks — 4096, biomes — 64). */
    public static long ids(int[] blocks, int[] biomes) {
        long h = SEED;
        for (int v : blocks) h = round(h, v & 0xFFFFFFFFL);
        h = round(h, P5);
        for (int v : biomes) h = round(h, v & 0xFFFFFFFFL);
        return avalanche(h ^ (blocks.length + biomes.length));
    }

    /** Хэш секции сервера (под замком чтения столбца). */
    public static long of(Section s) {
        int[] b = new int[4096], m = new int[64];
        values(s.blockPalette(), b, 8, 4);
        values(s.biomePalette(), m, 4, 2);
        return ids(b, m);
    }

    private static void values(Palette p, int[] out, int shiftY, int shiftZ) {
        int one = p.singleValue();
        if (one >= 0) { java.util.Arrays.fill(out, one); return; }
        p.getAll((x, y, z, v) -> out[y << shiftY | z << shiftZ | x] = v);
    }

    // ───────────── сетевая форма секции (как пишет PalettedContainer) ─────────────

    /**
     * Хэш секции в сетевой форме op 2 / LevelChunkSection.write: short блоков, контейнер блоков, контейнер биомов.
     * Ответ — хэш; off[0] сдвигается за секцию. Битая форма — исключение.
     */
    public static long wire(byte[] a, int[] off) {
        int[] b = new int[4096], m = new int[64];
        off[0] += 2;                                                       // short блоков — производное, не хэшируем
        container(a, off, b, 8);
        container(a, off, m, 3);
        return ids(b, m);
    }

    /** Контейнер: byte бит; 0 — varint значение; ≤ indirect — varint n, n×varint; дальше long[] (без длины). */
    static void container(byte[] a, int[] off, int[] out, int indirect) {
        int bits = a[off[0]++] & 0xFF;
        if (bits == 0) {
            java.util.Arrays.fill(out, varint(a, off));
            return;
        }
        int[] pal = null;
        if (bits <= indirect) {
            int n = varint(a, off);
            if (n < 0 || n > 4096) throw new IllegalArgumentException("палитра " + n);
            pal = new int[n];
            for (int k = 0; k < n; k++) pal[k] = varint(a, off);
        }
        if (bits > 32) throw new IllegalArgumentException("бит " + bits);
        int vpl = 64 / bits, longs = (out.length + vpl - 1) / vpl;
        long mask = (1L << bits) - 1;
        int i = 0;
        for (int w = 0; w < longs; w++) {
            long v = 0;
            int o = off[0];
            for (int k = 0; k < 8; k++) v = v << 8 | (a[o + k] & 0xFF);   // big-endian, как writeLong
            off[0] = o + 8;
            for (int k = 0; k < vpl && i < out.length; k++, i++) {
                int raw = (int) (v >>> (k * bits) & mask);
                out[i] = pal == null ? raw : raw < pal.length ? pal[raw] : 0;
            }
        }
    }

    static int varint(byte[] a, int[] off) {
        int v = 0, s = 0;
        while (true) {
            byte b = a[off[0]++];
            v |= (b & 0x7F) << s;
            if (b >= 0) return v;
            s += 7;
            if (s >= 35) throw new IllegalArgumentException("varint");
        }
    }

    // ───────────── свет ─────────────

    /**
     * Хэш записи света в каноническом виде ServerLight.Cube клиента: sky 0 формула (sd — 256 Б d по колонкам),
     * 1 всё 0, 2 всё 15, 3 массив 2048, 4 слой 128; blk 1 всё 0, 3 массив (bd 2048).
     */
    public static long light(int sky, byte[] sd, int blk, byte[] bd) {
        long h = SEED_LIGHT;
        h = round(h, sky);
        h = bytes(h, sd);
        h = round(h, blk);
        h = bytes(h, bd);
        return avalanche(h);
    }

    private static long bytes(long h, byte[] a) {
        if (a == null) return round(h, -1);
        h = round(h, a.length);
        int n = a.length, i = 0;
        for (; i + 8 <= n; i += 8) {
            long v = (a[i] & 0xFFL) | (a[i + 1] & 0xFFL) << 8 | (a[i + 2] & 0xFFL) << 16 | (a[i + 3] & 0xFFL) << 24
                    | (a[i + 4] & 0xFFL) << 32 | (a[i + 5] & 0xFFL) << 40 | (a[i + 6] & 0xFFL) << 48 | (a[i + 7] & 0xFFL) << 56;
            h = round(h, v);
        }
        for (; i < n; i++) h = round(h, a[i] & 0xFF);
        return h;
    }
}
