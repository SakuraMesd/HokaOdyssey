package ink.hoka.odyssey.server.tall.region;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Заголовок столбца (CUBIC-CHUNKS.md §4.9), файл cols/r.&lt;rx&gt;.&lt;rz&gt;.hcl (32×32 столбца):
 * <pre>
 * byte версия; short кубов в столбце; long[] «куб есть на диске»; long[] «куб изменён» (пока = на диске);
 * 3 × 256 short высот (skyTop, motion, surface — HeightIndex; Y или NONE); UTF биом по умолчанию;
 * short тегов, на тег UTF ключ + UTF значение.
 * </pre>
 * Шаг 13: тег {@code hclear} — у каких кубов на диске нет блоков с высотой вида (CubeMark); полных сводок §4.4 нет.
 */
public final class ColumnHeader {

    private static final int VERSION = 1;
    public static final int HEIGHT_KINDS = 3;

    public final int cubes;
    /** Куб (индекс от нижнего) записан в регионе кубов. */
    public final BitSet onDisk;
    /** Куб отличается от генератора (на шаге 7 совпадает с onDisk). */
    public final BitSet modified;
    /** Высоты колонок: [вид][z*16+x]. */
    public final short[][] heights;
    public String biome;
    public final Map<String, String> tags = new LinkedHashMap<>();

    public ColumnHeader(int cubes) {
        this.cubes = cubes;
        onDisk = new BitSet(cubes);
        modified = new BitSet(cubes);
        heights = new short[HEIGHT_KINDS][256];
        biome = "minecraft:plains";
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(2048);
            DataOutputStream out = new DataOutputStream(bo);
            out.writeByte(VERSION);
            out.writeShort(cubes);
            writeBits(out, onDisk);
            writeBits(out, modified);
            for (short[] h : heights) for (short v : h) out.writeShort(v);
            out.writeUTF(biome == null ? "" : biome);
            out.writeShort(tags.size());
            for (var e : tags.entrySet()) { out.writeUTF(e.getKey()); out.writeUTF(e.getValue()); }
            out.flush();
            return bo.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    public static ColumnHeader decode(byte[] data) throws IOException {
        try { return read(data); }
        catch (RegionFile.Corrupt e) { throw e; }
        catch (IOException | RuntimeException e) { throw new RegionFile.Corrupt("заголовок столбца: " + e); }   // обрезан — битый, не сбой диска
    }

    private static ColumnHeader read(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int v = in.readUnsignedByte();
        if (v != VERSION) throw new RegionFile.Corrupt("заголовок столбца: версия " + v);
        ColumnHeader h = new ColumnHeader(in.readUnsignedShort());
        readBits(in, h.onDisk, h.cubes);
        readBits(in, h.modified, h.cubes);
        for (short[] a : h.heights) for (int i = 0; i < 256; i++) a[i] = in.readShort();
        h.biome = in.readUTF();
        int t = in.readUnsignedShort();
        for (int i = 0; i < t; i++) h.tags.put(in.readUTF(), in.readUTF());
        return h;
    }

    private static void writeBits(DataOutputStream out, BitSet b) throws IOException {
        long[] l = b.toLongArray();
        out.writeShort(l.length);
        for (long x : l) out.writeLong(x);
    }

    private static void readBits(DataInputStream in, BitSet b, int cubes) throws IOException {
        int n = in.readUnsignedShort();
        if (n > (cubes + 63) / 64) throw new RegionFile.Corrupt("заголовок столбца: маска " + n);
        long[] l = new long[n];
        for (int i = 0; i < n; i++) l[i] = in.readLong();
        b.or(BitSet.valueOf(l));
    }
}
