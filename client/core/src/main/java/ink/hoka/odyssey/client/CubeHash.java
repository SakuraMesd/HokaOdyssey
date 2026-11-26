package ink.hoka.odyssey.client;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Версия куба для кэша кубов (0.51); 0.59: ядро алгоритма — общий GenHash (gen-api), второй копии нет. Хэш содержимого: 4096 id блоков (y&lt;&lt;8|z&lt;&lt;4|x) и 64 id биомов (y&lt;&lt;4|z&lt;&lt;2|x) по
 * сетевым номерам реестров; порядок палитры не важен. Секция клиента считается через её сетевую форму
 * (LevelChunkSection.write → тот же разбор, что проверяет серверный тест на секциях Minestom). Эталоны (как в
 * CubeCacheTest сервера): нули — d7b11457959f700c, узор i*7%23 / i*7%5 — f12b4d5e0f896b11, свет «формула 0..16,
 * блоки 0» — 8abcaff9818b4ab1. Секции — только главный поток (PalettedContainer не любит чужие потоки); 0.63.6: разбор снимка (wire) — в фоне ({@link #later}).
 */
public final class CubeHash {

    private CubeHash() {}

    public static final int FORMAT = ink.hoka.odyssey.gen.GenHash.FORMAT;
    static final long SEED_LIGHT = ink.hoka.odyssey.gen.GenHash.SEED_LIGHT;

    static long round(long h, long v) { return ink.hoka.odyssey.gen.GenHash.round(h, v); }

    static long avalanche(long h) { return ink.hoka.odyssey.gen.GenHash.avalanche(h); }

    /** 0.59: алгоритм — единственная копия в общем API (GenHash), сервер делегирует туда же. */
    static long ids(int[] blocks, int[] biomes) { return ink.hoka.odyssey.gen.GenHash.ids(blocks, biomes); }

    /** Хэш живой секции клиента (главный поток). */
    public static long of(LevelChunkSection s) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(s.getSerializedSize()));
        s.write(buf);
        byte[] a = new byte[buf.readableBytes()];
        buf.readBytes(a);
        return wire(a, new int[]{0});
    }

    /** 0.63.6: хэш секции, посчитанный в фоне; h = 0 — ещё нет (тогда считать на месте). */
    static final class Slot { volatile long h; }

    private static final java.util.concurrent.ThreadPoolExecutor BG = new java.util.concurrent.ThreadPoolExecutor(1, 1,
            30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(8192), r -> {
        Thread t = new Thread(r, "hoka-cube-hash");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    }, new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
    static { BG.allowCoreThreadTimeOut(true); }

    /**
     * 0.63.6: хэш секции, уходящей в кэш кубов, — в фоне. На главном потоке только снимок сетевой формы (write —
     * копия палитры и массива; секция дальше не меняется, но PalettedContainer читаем только тут), разбор 4096 клеток и
     * GenHash — в потоке hoka-cube-hash. Было: CubeHash.of при взятии из кэша (CubeCache.take) — 3.6% кадра в полёте.
     * null — снимка нет (посчитают при взятии, как раньше).
     */
    static Slot later(LevelChunkSection s) {
        byte[] a;
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(s.getSerializedSize()));
            s.write(buf);
            a = new byte[buf.readableBytes()];
            buf.readBytes(a);
        } catch (RuntimeException e) {
            return null;
        }
        Slot slot = new Slot();
        BG.execute(() -> {                                              // очередь полна — отброшено: посчитают при взятии
            try { slot.h = wire(a, new int[]{0}); } catch (RuntimeException ignored) {}
        });
        return slot;
    }

    /** Хэш секции в сетевой форме: short блоков, контейнер блоков, контейнер биомов. */
    static long wire(byte[] a, int[] off) {
        int[] b = new int[4096], m = new int[64];
        off[0] += 2;
        container(a, off, b, 8);
        container(a, off, m, 3);
        return ids(b, m);
    }

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
            for (int k = 0; k < 8; k++) v = v << 8 | (a[o + k] & 0xFF);
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

    /** Хэш света куба (канонический вид — ServerLight.Cube; сервер: LightOp.records). */
    static long light(ServerLight.Cube c) {
        return light(c.sky(), c.sd(), c.blk(), c.bd());
    }

    static long light(int sky, byte[] sd, int blk, byte[] bd) {
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

    /** Самопроверка эталонами (при первом включении кэша): false — формула разошлась с сервером, кэш не включать. */
    static boolean selfTest() {
        int[] b = new int[4096], m = new int[64];
        if (ids(b, m) != 0xd7b11457959f700cL) return false;
        for (int i = 0; i < 4096; i++) b[i] = i * 7 % 23;
        for (int i = 0; i < 64; i++) m[i] = i * 7 % 5;
        if (ids(b, m) != 0xf12b4d5e0f896b11L) return false;
        byte[] sd = new byte[256];
        for (int i = 0; i < 256; i++) sd[i] = (byte) (i % 17);
        return light(0, sd, 1, null) == 0x8abcaff9818b4ab1L;
    }
}
