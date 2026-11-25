package ink.hoka.odyssey.server.tall.gen;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.RegistryKey;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Индекс предгенерации одного региона 32×32 столбца: maps/&lt;карта&gt;/genidx/r.&lt;rx&gt;.&lt;rz&gt;.hgi.
 * <pre>
 * int 'HGI1'; byte ver=1; UTF genId; int genVersion; long seed; int fingerprint; int minCy, maxCy; byte flags (1 — высоты)
 * short n; UTF[n] палитра «state|biome» (0 — воздух)
 * int rawLen; int crc32(raw); int zLen; deflate(9)(raw):
 *   long[16] done — какие столбцы посчитаны (края области бывают частичными)
 *   на столбец (done): varint flags (1 — есть высоты)
 *     zigzag lo, varint count          — кубы вне [lo, lo+count) пусты; count 0 — столбец пуст
 *     varint nRuns; (varint from−lo, varint len, varint pal) — однородные отрезки (pal 0 — воздух)
 *     [высоты] SKY 256: zigzag(h − сосед слева/сверху); MOTION/SURFACE 256: zigzag(h − SKY)
 * </pre>
 * Пишется целиком через временный файл и move: обрыв посередине не оставляет полузаписанного. Битый (CRC) —
 * исключение, вызывающий игнорирует файл.
 */
public final class GenIndexFile {

    public static final int MAGIC = 0x48474931;                         // HGI1
    public static final int SIDE = 32, COLS = SIDE * SIDE;

    public record Header(String genId, int genVersion, long seed, int fingerprint, int minCy, int maxCy, boolean heights) {
        public boolean sameGen(Header o) {
            return o != null && genId.equals(o.genId) && genVersion == o.genVersion && seed == o.seed
                    && fingerprint == o.fingerprint && minCy == o.minCy && maxCy == o.maxCy;
        }
    }

    /** Столбец индекса. */
    public static final class Col {
        public static final int OUT = -2, MIXED = -1;
        /** Кубы с блоками — lo…lo+count−1; count 0 — пусто. */
        public final int lo, count;
        /** Тройки (from cy, len, pal). */
        public final int[] runs;
        /** [вид][z*16+x] или null. */
        public final short[][] heights;

        public Col(int lo, int count, int[] runs, short[][] heights) {
            this.lo = lo; this.count = count; this.runs = runs; this.heights = heights;
        }

        /** Куб cy: OUT — вне диапазона (пусто), MIXED — генерировать, иначе индекс палитры (0 — воздух). */
        public int at(int cy) {
            if (count == 0 || cy < lo || cy >= lo + count) return OUT;
            for (int i = 0; i < runs.length; i += 3) if (cy >= runs[i] && cy < runs[i] + runs[i + 1]) return runs[i + 2];
            return MIXED;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Col c && lo == c.lo && count == c.count && java.util.Arrays.equals(runs, c.runs)
                    && java.util.Arrays.deepEquals(heights, c.heights);
        }
        @Override public int hashCode() { return 31 * lo + count; }
    }

    public final Header header;
    /** Палитра «state|biome»; 0 — воздух. */
    final List<String> palette = new ArrayList<>();
    private final Map<String, Integer> palIdx = new HashMap<>();
    final Col[] cols = new Col[COLS];
    private volatile int[][] resolved;

    public GenIndexFile(Header h) {
        this.header = h;
        pal("air");
    }

    /** Индекс записи палитры (добавляет). */
    synchronized int pal(String s) {
        Integer i = palIdx.get(s);
        if (i != null) return i;
        palette.add(s);
        palIdx.put(s, palette.size() - 1);
        resolved = null;
        return palette.size() - 1;
    }

    /** Ключ палитры для однородного куба. */
    static String palKey(int stateId, int biomeId) {
        if (stateId == 0) return "air";
        Block b = Block.fromStateId(stateId);
        var key = MinecraftServer.getBiomeRegistry().getKey(biomeId);
        return (b == null ? "minecraft:air" : b.state()) + "|" + (key == null ? "" : key.key().asString());
    }

    /** [stateId, biomeId] записи палитры. */
    int[] resolved(int p) {
        int[][] r = resolved;
        if (r == null || p >= r.length) {
            synchronized (this) {
                r = new int[palette.size()][];
                for (int i = 0; i < r.length; i++) r[i] = resolve(palette.get(i));
                resolved = r;
            }
        }
        return r[p];
    }

    private static int[] resolve(String s) {
        if (s.equals("air")) return new int[]{0, 0};
        int bar = s.lastIndexOf('|');
        Block b = Block.fromState(bar < 0 ? s : s.substring(0, bar));
        int biome = 0;
        if (bar >= 0 && bar + 1 < s.length()) {
            int id = MinecraftServer.getBiomeRegistry().getId(RegistryKey.unsafeOf(s.substring(bar + 1)));
            if (id >= 0) biome = id;
        }
        return new int[]{b == null ? 0 : b.stateId(), biome};
    }

    public static int index(int x, int z) { return (x & 31) | (z & 31) << 5; }

    public Col col(int x, int z) { return cols[index(x, z)]; }

    public void put(int x, int z, Col c) { cols[index(x, z)] = c; }

    public int done() { int n = 0; for (Col c : cols) if (c != null) n++; return n; }

    // ───────────── запись / чтение ─────────────

    public byte[] encode() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(4096);
        DataOutputStream r = new DataOutputStream(raw);
        long[] done = new long[16];
        for (int i = 0; i < COLS; i++) if (cols[i] != null) done[i >> 6] |= 1L << (i & 63);
        for (long l : done) r.writeLong(l);
        for (Col c : cols) {
            if (c == null) continue;
            varint(r, c.heights != null ? 1 : 0);
            varint(r, zig(c.lo));
            varint(r, c.count);
            varint(r, c.runs.length / 3);
            for (int i = 0; i < c.runs.length; i += 3) { varint(r, c.runs[i] - c.lo); varint(r, c.runs[i + 1]); varint(r, c.runs[i + 2]); }
            if (c.heights != null) {
                short[] sky = c.heights[0];
                for (int k = 0; k < 256; k++) {
                    int pred = (k & 15) > 0 ? sky[k - 1] : k >= 16 ? sky[k - 16] : 0;
                    varint(r, zig(sky[k] - pred));
                }
                for (int t = 1; t < 3; t++) for (int k = 0; k < 256; k++) varint(r, zig(c.heights[t][k] - sky[k]));
            }
        }
        r.flush();
        byte[] rb = raw.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(rb);
        ByteArrayOutputStream zb = new ByteArrayOutputStream(rb.length / 4 + 64);
        Deflater def = new Deflater(9);
        try (DeflaterOutputStream d = new DeflaterOutputStream(zb, def)) { d.write(rb); }
        finally { def.end(); }
        byte[] z = zb.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream(z.length + 256);
        DataOutputStream o = new DataOutputStream(out);
        o.writeInt(MAGIC);
        o.writeByte(1);
        o.writeUTF(header.genId);
        o.writeInt(header.genVersion);
        o.writeLong(header.seed);
        o.writeInt(header.fingerprint);
        o.writeInt(header.minCy);
        o.writeInt(header.maxCy);
        o.writeByte(header.heights ? 1 : 0);
        o.writeShort(palette.size());
        for (String s : palette) o.writeUTF(s);
        o.writeInt(rb.length);
        o.writeInt((int) crc.getValue());
        o.writeInt(z.length);
        o.write(z);
        o.flush();
        return out.toByteArray();
    }

    /** Только заголовок (быстро: без распаковки) или исключение. */
    public static Header readHeader(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("не HGI1");
        int ver = in.readUnsignedByte();
        if (ver != 1) throw new IOException("версия индекса " + ver);
        return new Header(in.readUTF(), in.readInt(), in.readLong(), in.readInt(), in.readInt(), in.readInt(), in.readUnsignedByte() == 1);
    }

    public static GenIndexFile decode(byte[] data) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            Header h = readHeader(in);
            GenIndexFile f = new GenIndexFile(h);
            int n = in.readUnsignedShort();
            f.palette.clear();
            f.palIdx.clear();
            for (int i = 0; i < n; i++) { String s = in.readUTF(); f.palette.add(s); f.palIdx.put(s, i); }
            if (f.palette.isEmpty() || !f.palette.get(0).equals("air")) throw new IOException("палитра без воздуха");
            int rawLen = in.readInt(), crc = in.readInt(), zLen = in.readInt();
            if (rawLen < 128 || rawLen > 64 << 20 || zLen <= 0 || zLen > data.length) throw new IOException("длины " + rawLen + "/" + zLen);
            byte[] z = in.readNBytes(zLen);
            if (z.length != zLen) throw new IOException("файл обрезан");
            byte[] raw;
            Inflater inf = new Inflater();
            try (var zi = new InflaterInputStream(new ByteArrayInputStream(z), inf)) { raw = zi.readNBytes(rawLen); }
            finally { inf.end(); }
            if (raw.length != rawLen) throw new IOException("распаковано " + raw.length + " из " + rawLen);
            CRC32 c = new CRC32();
            c.update(raw);
            if ((int) c.getValue() != crc) throw new IOException("CRC не сходится");
            DataInputStream r = new DataInputStream(new ByteArrayInputStream(raw));
            long[] done = new long[16];
            for (int i = 0; i < 16; i++) done[i] = r.readLong();
            for (int i = 0; i < COLS; i++) {
                if ((done[i >> 6] & (1L << (i & 63))) == 0) continue;
                int flags = varint(r);
                int lo = unzig(varint(r)), count = varint(r), nr = varint(r);
                if (count < 0 || nr < 0 || nr > 4096) throw new IOException("столбец " + i + ": отрезков " + nr);
                int[] runs = new int[nr * 3];
                for (int k = 0; k < nr; k++) {
                    runs[k * 3] = varint(r) + lo; runs[k * 3 + 1] = varint(r); runs[k * 3 + 2] = varint(r);
                    if (runs[k * 3 + 2] >= n) throw new IOException("столбец " + i + ": палитра " + runs[k * 3 + 2]);
                }
                short[][] hs = null;
                if ((flags & 1) != 0) {
                    hs = new short[3][256];
                    for (int k = 0; k < 256; k++) {
                        int pred = (k & 15) > 0 ? hs[0][k - 1] : k >= 16 ? hs[0][k - 16] : 0;
                        hs[0][k] = (short) (pred + unzig(varint(r)));
                    }
                    for (int t = 1; t < 3; t++) for (int k = 0; k < 256; k++) hs[t][k] = (short) (hs[0][k] + unzig(varint(r)));
                }
                f.cols[i] = new Col(lo, count, runs, hs);
            }
            return f;
        } catch (EOFException e) {
            throw new IOException("файл обрезан", e);
        }
    }

    public static GenIndexFile read(Path p) throws IOException { return decode(Files.readAllBytes(p)); }

    /** Записать атомарно (временный файл → move). Ответ — байт. */
    public long write(Path p) throws IOException {
        byte[] b = encode();
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        // на диск до move: иначе после сбоя питания файл мог бы оказаться пустым, а pregen.yml уже отметил регион готовым
        try (var ch = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            var bb = java.nio.ByteBuffer.wrap(b);
            while (bb.hasRemaining()) ch.write(bb);
            ch.force(true);
        }
        try { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING); }
        return b.length;
    }

    public static Path path(Path dir, int rx, int rz) { return dir.resolve("r." + rx + "." + rz + ".hgi"); }

    // ───────────── varint ─────────────

    static int zig(int v) { return (v << 1) ^ (v >> 31); }
    static int unzig(int v) { return (v >>> 1) ^ -(v & 1); }

    static void varint(DataOutputStream o, int v) throws IOException {
        while ((v & ~0x7F) != 0) { o.writeByte((v & 0x7F) | 0x80); v >>>= 7; }
        o.writeByte(v);
    }

    static int varint(DataInputStream in) throws IOException {
        int v = 0, s = 0;
        while (true) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << s;
            if ((b & 0x80) == 0) return v;
            s += 7;
            if (s > 28) throw new IOException("varint");
        }
    }
}
