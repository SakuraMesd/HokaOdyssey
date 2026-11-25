package ink.hoka.odyssey.server.tall.region;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Файл региона (CUBIC-CHUNKS.md §4.9, образец — RegionLib CC1): N записей, сектора, сжатие.
 * <pre>
 * v1 (HCR1): заголовок int MAGIC, int N, затем N × (int сектор начала, int секторов, int время, с); сектор 4 КБ;
 *            запись: int длина gzip, int CRC32 gzip, gzip-данные.
 * v2 (HCR2): заголовок int MAGIC2, int N, int размер сектора (256), затем те же строки; запись: int длина, int CRC32,
 *            затем байт сжатия (0 — gzip, 1 — raw deflate) и сжатые данные (длина и CRC — вместе с этим байтом).
 * </pre>
 * Версия — у каждого файла своя: v1 читается и пишется как раньше, новые файлы — v2 (однородный куб ~60 байт
 * занимает 256 байт, а не 4 КБ). Уровень deflate — -Dhoka.region.level (по умолчанию 6).
 * Запись — копированием: сначала данные в свободные сектора, потом строка таблицы, потом старые сектора свободны.
 * Упали посреди записи — в таблице остаётся старая версия. Битая запись (CRC, длина, смещение) → {@link Corrupt}.
 * Все методы под замком файла (synchronized): кубы одного региона пишут разные столбцы параллельно.
 * Один объект на путь — через {@link RegionFiles}.
 */
public final class RegionFile implements AutoCloseable {

    /** Сектор v1. */
    public static final int SECTOR = 4096;
    /** Сектор новых файлов (v2). */
    public static final int SECTOR_V2 = 256;
    private static final int MAGIC = 0x48435231;                       // HCR1
    private static final int MAGIC2 = 0x48435232;                      // HCR2
    private static final int ROW = 12;
    static final int LEVEL = Math.max(1, Math.min(9, Integer.getInteger("hoka.region.level", 6)));
    /** Новые файлы — v2 (false — только для тестов совместимости и старых сборок). */
    public static volatile boolean createV2 = true;

    /** Запись повреждена: читать её нельзя (вызывающий решает — сгенерировать, взять из запаса). */
    public static final class Corrupt extends IOException {
        public Corrupt(String m) { super(m); }
    }

    private final Path path;
    private final FileChannel ch;
    private final int n, headerSectors, sector, rowBase;
    private final boolean v2;
    private final int[] start, count, time;
    private final BitSet used = new BitSet();
    private boolean closed;

    private static int headerSectors(int n, int sector, int rowBase) { return (rowBase + n * ROW + sector - 1) / sector; }

    private RegionFile(Path path, FileChannel ch, int n) throws IOException {
        this.path = path;
        this.ch = ch;
        this.n = n;
        start = new int[n]; count = new int[n]; time = new int[n];
        long size = ch.size();
        int magic = 0;
        if (size >= 4) {
            ByteBuffer m = ByteBuffer.allocate(4);
            readFully(m, 0);
            magic = m.getInt(0);
        }
        boolean fresh, nv2 = createV2;
        int sec, rb;
        if (magic == MAGIC) { nv2 = false; sec = SECTOR; rb = 8; fresh = false; }
        else if (magic == MAGIC2) {
            nv2 = true; rb = 12; fresh = false; sec = 0;
            if (size >= 12) { ByteBuffer m = ByteBuffer.allocate(4); readFully(m, 8); sec = m.getInt(0); }
            if (sec < 64 || sec > 65536 || Integer.bitCount(sec) != 1) throw new Corrupt(path.getFileName() + ": размер сектора " + sec);
        } else {
            sec = nv2 ? SECTOR_V2 : SECTOR; rb = nv2 ? 12 : 8;
            // новый файл или упали, пока писали таблицу нового файла (даже MAGIC не дописан) — записей нет
            if (magic == 0 && size < (long) headerSectors(n, sec, rb) * sec) fresh = true;
            else throw new Corrupt(path.getFileName() + ": не тот формат (" + Integer.toHexString(magic) + ")");
        }
        this.v2 = nv2; this.sector = sec; this.rowBase = rb;
        this.headerSectors = headerSectors(n, sector, rowBase);
        if (fresh) {                                                         // новый файл — пустая таблица
            ByteBuffer b = ByteBuffer.allocate(headerSectors * sector);
            b.putInt(v2 ? MAGIC2 : MAGIC).putInt(n);
            if (v2) b.putInt(sector);
            b.rewind();
            writeFully(b, 0);
        } else {
            ByteBuffer b = ByteBuffer.allocate(rowBase + n * ROW);
            b.limit((int) Math.min(b.capacity(), size));                     // таблица обрезана — хвост строк нули (записей нет)
            readFully(b, 0);
            b.clear();
            b.getInt();
            int nn = b.getInt();
            if (v2) b.getInt();
            if (nn != n) throw new Corrupt(path.getFileName() + ": не тот формат (записей " + nn + ")");
            long sectors = (size + sector - 1) / sector;
            for (int i = 0; i < n; i++) {
                int s = b.getInt(), c = b.getInt(), t = b.getInt();
                if (s == 0 && c == 0) continue;
                if (s < headerSectors || c <= 0 || s + (long) c > sectors) continue;   // строка битая — записи нет (читать нечего)
                start[i] = s; count[i] = c; time[i] = t;
                used.set(s, s + c);
            }
        }
        used.set(0, headerSectors);
    }

    /** Формат файла: 1 или 2. */
    public int version() { return v2 ? 2 : 1; }

    /** Размер сектора файла. */
    public int sectorSize() { return sector; }

    /** [байт секторов под записи, байт полезных данных записей] — для /hocubes size. */
    public synchronized long[] usage() throws IOException {
        long secs = 0, bytes = 0;
        ByteBuffer h = ByteBuffer.allocate(4);
        for (int i = 0; i < n; i++) {
            if (count[i] == 0) continue;
            secs += count[i];
            h.clear();
            readFully(h, (long) start[i] * sector);
            bytes += h.getInt(0) + 8;
        }
        return new long[]{secs * sector, bytes};
    }

    /** Открыть (создать, если create) файл региона на n записей; нет файла и !create — null. */
    public static RegionFile open(Path path, int n, boolean create) throws IOException {
        if (!create && !Files.isRegularFile(path)) return null;
        if (create) Files.createDirectories(path.getParent());
        FileChannel ch = create
                ? FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
                : FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try { return new RegionFile(path, ch, n); }
        catch (IOException | RuntimeException e) { ch.close(); throw e; }
    }

    public Path path() { return path; }
    public int entries() { return n; }
    synchronized boolean closed() { return closed; }

    public synchronized boolean has(int i) { return count[i] > 0; }

    /** Время записи (секунды эпохи) или 0. */
    public synchronized int timestamp(int i) { return time[i]; }

    /** Данные записи (распакованные) или null — записи нет. */
    public synchronized byte[] read(int i) throws IOException {
        check();
        if (count[i] == 0) return null;
        ByteBuffer h = ByteBuffer.allocate(8);
        readFully(h, (long) start[i] * sector);
        h.flip();
        int len = h.getInt(), crc = h.getInt();
        if (len <= (v2 ? 1 : 0) || len > count[i] * sector - 8) throw new Corrupt(path.getFileName() + " #" + i + ": длина " + len);
        ByteBuffer d = ByteBuffer.allocate(len);
        readFully(d, (long) start[i] * sector + 8);
        byte[] z = d.array();
        CRC32 c = new CRC32();
        c.update(z);
        if ((int) c.getValue() != crc) throw new Corrupt(path.getFileName() + " #" + i + ": CRC не сходится");
        int comp = v2 ? z[0] : 0, off = v2 ? 1 : 0;
        if (comp != 0 && comp != 1) throw new Corrupt(path.getFileName() + " #" + i + ": сжатие " + comp);
        var bin = new java.io.ByteArrayInputStream(z, off, z.length - off);
        java.util.zip.Inflater inf = comp == 1 ? new java.util.zip.Inflater(true) : null;
        try (InputStream in = comp == 0 ? new GZIPInputStream(bin) : new java.util.zip.InflaterInputStream(bin, inf)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new Corrupt(path.getFileName() + " #" + i + ": распаковка " + e.getMessage());
        } finally {
            if (inf != null) inf.end();
        }
    }

    /** Записать данные (сожмутся). Старая версия остаётся в таблице до конца записи новой. */
    public synchronized void write(int i, byte[] data) throws IOException {
        check();
        ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(64, data.length / 4));
        if (v2) {
            bo.write(1);                                                     // raw deflate: без 18 байт обвязки gzip
            var def = new java.util.zip.Deflater(LEVEL, true);
            try (var g = new java.util.zip.DeflaterOutputStream(bo, def)) { g.write(data); }
            finally { def.end(); }
        } else try (GZIPOutputStream g = new GZIPOutputStream(bo)) { g.write(data); }
        byte[] z = bo.toByteArray();
        CRC32 c = new CRC32();
        c.update(z);
        int need = (z.length + 8 + sector - 1) / sector;
        int s = alloc(need);
        ByteBuffer b = ByteBuffer.allocate(need * sector);
        b.putInt(z.length).putInt((int) c.getValue()).put(z);
        b.rewind();
        writeFully(b, (long) s * sector);
        int os = start[i], oc = count[i];
        setRow(i, s, need, (int) (System.currentTimeMillis() / 1000));
        if (oc > 0) used.clear(os, os + oc);
    }

    /** Убрать запись (сектора свободны). */
    public synchronized void remove(int i) throws IOException {
        check();
        if (count[i] == 0) return;
        int os = start[i], oc = count[i];
        setRow(i, 0, 0, 0);
        used.clear(os, os + oc);
    }

    /** Сколько записей есть. */
    public synchronized int size() { int k = 0; for (int c : count) if (c > 0) k++; return k; }

    /** Сбросить на диск (при остановке/после конвертации). */
    public synchronized void flush() throws IOException { if (!closed) ch.force(false); }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        ch.close();
    }

    private void check() throws IOException { if (closed) throw new IOException(path.getFileName() + " закрыт"); }

    /** Свободный отрезок из need секторов (первый подходящий; не нашли — в конец файла). */
    private int alloc(int need) {
        int s = headerSectors;
        while (true) {
            s = used.nextClearBit(s);
            int e = used.nextSetBit(s);
            if (e < 0 || e - s >= need) break;
            s = e;
        }
        used.set(s, s + need);
        return s;
    }

    private void setRow(int i, int s, int c, int t) throws IOException {
        start[i] = s; count[i] = c; time[i] = t;
        ByteBuffer b = ByteBuffer.allocate(ROW);
        b.putInt(s).putInt(c).putInt(t);
        b.rewind();
        writeFully(b, rowBase + (long) i * ROW);
    }

    private void readFully(ByteBuffer b, long pos) throws IOException {
        while (b.hasRemaining()) {
            int r = ch.read(b, pos + b.position());
            if (r < 0) throw new Corrupt(path.getFileName() + ": файл обрезан");
        }
    }

    private void writeFully(ByteBuffer b, long pos) throws IOException {
        while (b.hasRemaining()) ch.write(b, pos + b.position());
    }
}
