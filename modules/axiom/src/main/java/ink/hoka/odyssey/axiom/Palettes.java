package ink.hoka.odyssey.axiom;

import net.minestom.server.instance.palette.Palette;

/**
 * Секция блоков 16³ в сетевом формате PalettedContainer 1.21.11 (как в пакете чанка с 1.21.5):
 * {@code u8 bits}; bits = 0 — {@code VarInt id}; 1…8 — палитра {@code VarInt n, n × VarInt id}; больше — прямые id
 * (глобальная палитра); затем {@code long[]} БЕЗ длины, записи не переходят границу long. Индекс — y·256 + z·16 + x.
 */
final class Palettes {

    private Palettes() {}

    /** Бит на запись у глобальной палитры блоков (как у клиента: ceil(log2(число состояний))). */
    static final int DIRECT_BITS = Palette.BLOCK_PALETTE_DIRECT_BITS;

    /** Прочитать секцию: 4096 id состояний по индексу y<<8 | z<<4 | x. */
    static int[] read(Buf b) {
        int bits = b.readByte() & 0xFF;
        int[] out = new int[4096];
        if (bits == 0) {
            java.util.Arrays.fill(out, b.readVarInt());
            return out;
        }
        int[] pal = null;
        int sb;
        if (bits <= 8) {
            int n = b.readVarInt();
            if (n <= 0 || n > 4096) throw new IllegalStateException("палитра на " + n + " значений");
            pal = new int[n];
            for (int i = 0; i < n; i++) pal[i] = b.readVarInt();
            sb = Math.max(4, bits);                                     // у блоков 1…4 бита хранятся как 4
        } else {
            if (bits > 32) throw new IllegalStateException("бит на запись: " + bits);
            sb = bits;
        }
        int per = 64 / sb;
        int longs = (4096 + per - 1) / per;
        long mask = (1L << sb) - 1;
        int i = 0;
        for (int k = 0; k < longs; k++) {
            long l = b.readLong();
            for (int j = 0; j < per && i < 4096; j++, i++) {
                int v = (int) ((l >>> (j * sb)) & mask);
                if (pal != null) v = v < pal.length ? pal[v] : 0;
                out[i] = v;
            }
        }
        return out;
    }

    /**
     * Записать палитру Minestom в сетевом формате — её же сериализатором (тот же формат, что у секций в пакете чанка).
     * Быстро: без перебора 4096 значений через HashMap (ответ на запрос секций идёт в тике, их бывают тысячи).
     */
    static void write(Out o, Palette p) {
        o.writeBytes(net.minestom.server.network.NetworkBuffer.makeArray(buf -> buf.write(Palette.BLOCK_SERIALIZER, p)));
    }

    static void write(Out o, int[] ids) {
        // палитра по порядку появления
        java.util.HashMap<Integer, Integer> index = new java.util.HashMap<>();
        int[] pal = new int[4096];
        int n = 0;
        for (int id : ids) if (index.putIfAbsent(id, n) == null) pal[n++] = id;
        if (n == 1) {
            o.writeByte(0).writeVarInt(pal[0]);
            return;
        }
        int sb;
        boolean direct = n > 256;
        if (direct) {
            sb = DIRECT_BITS;
            o.writeByte(sb);
        } else {
            sb = Math.max(4, 32 - Integer.numberOfLeadingZeros(n - 1));
            o.writeByte(sb).writeVarInt(n);
            for (int i = 0; i < n; i++) o.writeVarInt(pal[i]);
        }
        int per = 64 / sb;
        int longs = (4096 + per - 1) / per;
        int i = 0;
        for (int k = 0; k < longs; k++) {
            long l = 0;
            for (int j = 0; j < per && i < 4096; j++, i++) {
                long v = direct ? ids[i] : index.get(ids[i]);
                l |= v << (j * sb);
            }
            o.writeLong(l);
        }
    }
}
