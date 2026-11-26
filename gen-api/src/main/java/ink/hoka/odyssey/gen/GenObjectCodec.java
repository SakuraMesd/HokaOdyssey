package ink.hoka.odyssey.gen;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Формат объекта .hobj — один код для сервера (/hoobj save) и клиента (пакет объектов). Файл = gzip(тело):
 * <pre>
 * "HOBJ" | u8 version=1 | u8 flags (bit0 keepAir, bit1 hasData)
 * vi sx, sy, sz (1..128) | zi ax, ay, az (якорь в рамке, −128..255)
 * vi P (1..4096) | P × str — канонические состояния по алфавиту
 * vi N | N × (vi d, vi p) — index = (y·sz + z)·sx + x по возрастанию: первый d = index, далее d = index − prev − 1
 * vi D | D × (vi k, str snbt) — k: номер блока в списке N по возрастанию, snbt ≤ 65536 Б
 * </pre>
 * vi — LEB128, zi — zigzag, str — vi длины + UTF-8. Чтение с лимитами (битый ввод — IOException).
 */
public final class GenObjectCodec {

    private GenObjectCodec() {}

    public static final int VERSION = 1, MAX_SIZE = 128, MAX_PALETTE = 4096, MAX_SNBT = 65536;
    public static final int MAX_BODY = 8 << 20;
    static final byte[] MAGIC = {'H', 'O', 'B', 'J'};
    static final int F_KEEP_AIR = 1, F_DATA = 2;

    public static final String AIR = "minecraft:air";

    /** Воздух (без keep-air не пишется). */
    public static boolean isAir(String blockId) {
        return "minecraft:air".equals(blockId) || "minecraft:cave_air".equals(blockId) || "minecraft:void_air".equals(blockId);
    }

    /** structure_void — «не трогать»: не пишется никогда. */
    public static boolean isVoid(String blockId) { return "minecraft:structure_void".equals(blockId); }

    static void checkAnchor(int ax, int ay, int az) {
        if (ax < -128 || ax > 255 || ay < -128 || ay > 255 || az < -128 || az > 255)
            throw new IllegalArgumentException("якорь вне −128..255 от рамки: " + ax + " " + ay + " " + az);
    }

    // ───────────── сборка ─────────────

    /** Сборщик объекта из блоков мира: воздух/structure_void отбрасываются, рамка обрезается, палитра сортируется. */
    public static final class Builder {
        private final boolean keepAir;
        private final Map<Long, String> blocks = new HashMap<>();
        private final Map<Long, String> data = new HashMap<>();
        private Integer anx, any, anz;
        /** Начало отсчёта ключей (первый блок): координаты мира любые, ключ — относительный (±1M). */
        private boolean originSet;
        private int ox, oy, oz;

        public Builder(boolean keepAir) { this.keepAir = keepAir; }

        private long rel(int x, int y, int z) {
            if (!originSet) { ox = x; oy = y; oz = z; originSet = true; }
            return key(x - ox, y - oy, z - oz);
        }

        private static long key(int x, int y, int z) { return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF); }
        private static int kx(long k) { return (int) (k >> 42 << 43 >> 43); }
        private static int ky(long k) { return (int) ((k >> 21) << 43 >> 43); }
        private static int kz(long k) { return (int) (k << 43 >> 43); }

        /** Блок (любые координаты). Ответ — блок попадёт в объект. */
        public boolean add(int x, int y, int z, String state) {
            String s = GenIds.normalize(state);
            int b = s.indexOf('[');
            String id = b < 0 ? s : s.substring(0, b);
            long k = rel(x, y, z);
            if (isVoid(id)) { blocks.remove(k); return false; }
            if (isAir(id)) {
                if (!keepAir) { blocks.remove(k); return false; }
                s = AIR;
            }
            blocks.put(k, s);
            return true;
        }

        /** Данные блок-сущности (SNBT) блока x, y, z (пишутся, только если блок есть и не воздух). */
        public void data(int x, int y, int z, String snbt) {
            if (snbt == null || snbt.isEmpty()) return;
            if (snbt.getBytes(StandardCharsets.UTF_8).length > MAX_SNBT) throw new IllegalArgumentException("SNBT больше 64 КБ в " + x + " " + y + " " + z);
            data.put(rel(x, y, z), snbt);
        }

        /** Якорь в тех же координатах, что add (по умолчанию — центр-низ обрезанной рамки). */
        public Builder anchor(int x, int y, int z) {
            rel(x, y, z);
            anx = x - ox; any = y - oy; anz = z - oz;
            return this;
        }

        public int size() { return blocks.size(); }

        private int bx, by, bz;

        /** Начало обрезанной рамки в координатах add (после build). */
        public int minX() { return bx; }
        public int minY() { return by; }
        public int minZ() { return bz; }

        /** Собрать: id "кат/имя", meta — метаданные (null — пусто). Пусто или больше 128 по оси — исключение. */
        public GenObject build(String id, Map<String, Object> meta) {
            if (blocks.isEmpty()) throw new IllegalStateException("в выделении нет блоков (воздух не сохраняется)");
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (long k : blocks.keySet()) {
                int x = kx(k), y = ky(k), z = kz(k);
                x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
                x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
            }
            int sx = x1 - x0 + 1, sy = y1 - y0 + 1, sz = z1 - z0 + 1;
            if (sx > MAX_SIZE || sy > MAX_SIZE || sz > MAX_SIZE) throw new IllegalArgumentException("объект больше 128 по оси: " + sx + "×" + sy + "×" + sz);
            int ax = anx != null ? anx - x0 : Math.floorDiv(x0 + x1, 2) - x0;
            int ay = any != null ? any - y0 : 0;
            int az = anz != null ? anz - z0 : Math.floorDiv(z0 + z1, 2) - z0;
            checkAnchor(ax, ay, az);
            bx = x0 + ox; by = y0 + oy; bz = z0 + oz;
            List<String> palette = new ArrayList<>(new TreeSet<>(blocks.values()));
            if (palette.size() > MAX_PALETTE) throw new IllegalArgumentException("палитра больше 4096 состояний");
            Map<String, Integer> pi = new HashMap<>();
            for (int i = 0; i < palette.size(); i++) pi.put(palette.get(i), i);
            TreeMap<Integer, String> byIdx = new TreeMap<>();
            Map<Integer, String> dataByIdx = new HashMap<>();
            for (var e : blocks.entrySet()) {
                long k = e.getKey();
                int idx = ((ky(k) - y0) * sz + (kz(k) - z0)) * sx + (kx(k) - x0);
                byIdx.put(idx, e.getValue());
                String d = data.get(k);
                if (d != null && !AIR.equals(e.getValue())) dataByIdx.put(idx, d);
            }
            int n = byIdx.size();
            int[] index = new int[n], pal = new int[n];
            TreeMap<Integer, String> dm = new TreeMap<>();
            int i = 0;
            for (var e : byIdx.entrySet()) {
                index[i] = e.getKey();
                pal[i] = pi.get(e.getValue());
                String d = dataByIdx.get(e.getKey());
                if (d != null) dm.put(i, d);
                i++;
            }
            return new GenObject(id, sx, sy, sz, ax, ay, az, keepAir, palette, index, pal, dm, meta);
        }
    }

    // ───────────── запись ─────────────

    /** Тело .hobj (без gzip) — байты стабильны для одного объекта. */
    public static byte[] writeBody(GenObject o) {
        GenWire.Out w = new GenWire.Out();
        w.raw(MAGIC).b(VERSION).b((o.keepAir() ? F_KEEP_AIR : 0) | (o.hasData() ? F_DATA : 0));
        w.vi(o.sizeX()).vi(o.sizeY()).vi(o.sizeZ());
        w.vi(zig(o.anchorX())).vi(zig(o.anchorY())).vi(zig(o.anchorZ()));
        w.vi(o.palette().size());
        for (String s : o.palette()) w.str(s);
        w.vi(o.count());
        int prev = -1;
        for (int i = 0; i < o.count(); i++) {
            int idx = o.boxIndex(i);
            w.vi(idx - prev - 1).vi(o.paletteIndex(i));
            prev = idx;
        }
        w.vi(o.data().size());
        for (var e : o.data().entrySet()) w.vi(e.getKey()).str(e.getValue());
        return w.bytes();
    }

    /** Файл .hobj (gzip тела). */
    public static byte[] writeFile(GenObject o) { return gzip(writeBody(o)); }

    static int zig(int v) { return (v << 1) ^ (v >> 31); }
    static int unzig(int v) { return (v >>> 1) ^ -(v & 1); }

    // ───────────── чтение ─────────────

    /** Прочитать файл .hobj (gzip). */
    public static GenObject readFile(String id, byte[] file, Map<String, Object> meta) throws IOException {
        return readBody(id, gunzip(file, MAX_BODY), meta);
    }

    /** Прочитать тело .hobj. Любое нарушение — IOException. */
    public static GenObject readBody(String id, byte[] body, Map<String, Object> meta) throws IOException {
        R r = new R(body);
        for (byte m : MAGIC) if (r.u8() != (m & 0xFF)) throw new IOException("не .hobj");
        int ver = r.u8();
        if (ver != VERSION) throw new IOException(".hobj версии " + ver + " (умею " + VERSION + ")");
        int flags = r.u8();
        if ((flags & ~3) != 0) throw new IOException(".hobj: флаги " + flags);
        boolean keepAir = (flags & F_KEEP_AIR) != 0;
        int sx = r.vi(), sy = r.vi(), sz = r.vi();
        if (sx < 1 || sy < 1 || sz < 1 || sx > MAX_SIZE || sy > MAX_SIZE || sz > MAX_SIZE) throw new IOException(".hobj: размер " + sx + "×" + sy + "×" + sz);
        int ax = unzig(r.vi()), ay = unzig(r.vi()), az = unzig(r.vi());
        try { checkAnchor(ax, ay, az); } catch (IllegalArgumentException e) { throw new IOException(".hobj: " + e.getMessage()); }
        int p = r.vi();
        if (p < 1 || p > MAX_PALETTE) throw new IOException(".hobj: палитра " + p);
        List<String> palette = new ArrayList<>(p);
        String last = null;
        for (int i = 0; i < p; i++) {
            String s = r.str(256);
            String c;
            try { c = GenIds.normalize(s); } catch (RuntimeException e) { throw new IOException(".hobj: состояние «" + s + "»"); }
            if (!c.equals(s)) throw new IOException(".hobj: состояние не каноническое «" + s + "»");
            if (last != null && last.compareTo(s) >= 0) throw new IOException(".hobj: палитра не по алфавиту");
            int b = s.indexOf('[');
            String bid = b < 0 ? s : s.substring(0, b);
            if (isVoid(bid) || (isAir(bid) && !(keepAir && AIR.equals(s)))) throw new IOException(".hobj: в палитре " + s);
            palette.add(s);
            last = s;
        }
        long vol = (long) sx * sy * sz;
        int n = r.vi();
        if (n < 1 || n > vol) throw new IOException(".hobj: блоков " + n);
        int[] index = new int[n], pal = new int[n];
        long prev = -1;
        for (int i = 0; i < n; i++) {
            long idx = prev + 1 + (r.vi() & 0xFFFFFFFFL);
            if (idx >= vol) throw new IOException(".hobj: блок вне рамки");
            int pi = r.vi();
            if (pi < 0 || pi >= p) throw new IOException(".hobj: номер палитры " + pi);
            index[i] = (int) idx;
            pal[i] = pi;
            prev = idx;
        }
        int d = r.vi();
        if (d < 0 || d > n) throw new IOException(".hobj: данных " + d);
        TreeMap<Integer, String> data = new TreeMap<>();
        int pk = -1;
        for (int i = 0; i < d; i++) {
            int k = r.vi();
            if (k <= pk || k >= n) throw new IOException(".hobj: номер блока данных " + k);
            if (keepAir && AIR.equals(palette.get(pal[k]))) throw new IOException(".hobj: данные у воздуха");
            data.put(k, r.str(MAX_SNBT));
            pk = k;
        }
        if (((flags & F_DATA) != 0) != (d > 0)) throw new IOException(".hobj: флаг данных");
        if (r.left() != 0) throw new IOException(".hobj: лишние байты");
        return new GenObject(id, sx, sy, sz, ax, ay, az, keepAir, palette, index, pal, data, meta);
    }

    // ───────────── gzip ─────────────

    public static byte[] gzip(byte[] b) {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream(b.length / 2 + 64);
            try (GZIPOutputStream z = new GZIPOutputStream(o)) { z.write(b); }
            return o.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    /** Распаковать gzip, не больше max байт (защита от zip-бомбы). */
    public static byte[] gunzip(byte[] gz, int max) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int total = 0;
            for (int k; (k = in.read(buf)) > 0; ) {
                total += k;
                if (total > max) throw new IOException("распакованное больше " + (max >> 20) + " МиБ");
                o.write(buf, 0, k);
            }
            return o.toByteArray();
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("битый gzip: " + e); }
    }

    /** Чтение с проверкой границ (IOException вместо исключений массива). */
    static final class R {
        private final byte[] a;
        private int p;

        R(byte[] a) { this.a = a; }

        int left() { return a.length - p; }

        int u8() throws IOException {
            if (p >= a.length) throw new IOException("обрезано");
            return a[p++] & 0xFF;
        }

        int vi() throws IOException {
            int v = 0;
            for (int s = 0; s < 35; s += 7) {
                int x = u8();
                v |= (x & 0x7F) << s;
                if ((x & 0x80) == 0) return v;
            }
            throw new IOException("varint");
        }

        byte[] raw(int k) throws IOException {
            if (k < 0 || k > left()) throw new IOException("обрезано");
            byte[] r = java.util.Arrays.copyOfRange(a, p, p + k);
            p += k;
            return r;
        }

        String str(int max) throws IOException {
            int k = vi();
            if (k < 0 || k > max) throw new IOException("строка " + k);
            return new String(raw(k), StandardCharsets.UTF_8);
        }
    }
}
