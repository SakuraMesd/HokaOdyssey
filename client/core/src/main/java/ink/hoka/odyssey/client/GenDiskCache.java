package ink.hoka.odyssey.client;

import ink.hoka.odyssey.gen.GenArrayCube;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 0.63: дисковый кэш сгенерированных на клиенте кубов. Генератор детерминирован: куб = функция (jar, сид, настройки,
 * границы, объекты, номера состояний/биомов) — ключ {@link #open(String)}; при повторном входе куб читается с диска
 * (~десятки мкс) вместо генерации. Храним только кубы, что реально прошли generate (пустые/однородные и так даром).
 * <p>Раскладка: root/&lt;sha ключа&gt;/r.X.Y.Z.hgc — регион 16×16 столбцов × 8 кубов, файл — журнал записей
 * [int cx, int cy, int cz, long visHash, int len, len Б deflate(палитра + индексы + биомы)]; последний — верный.
 * Хэш при чтении пересчитывается (GenHash) — битая/чужая запись = промах. Чтение — рабочие потоки GenPool
 * (позиционное, без замков на файл), запись — свой поток (очередь, при переполнении — пропуск). Объём — LRU по
 * файлам регионов (время последнего обращения = mtime), предел — настройка (МБ), старые ключи вытесняются первыми.</p>
 * Без классов игры: тестируется отдельно (GenDiskCacheTest).
 */
public final class GenDiskCache {

    private static final int RX = 16, RY = 8, RZ = 16, SLOTS = RX * RY * RZ, HEAD = 24, MAX_REC = 64 << 10;
    private static final int MAX_OPEN = 512;
    private static final String EXT = ".hgc";

    private final Path root;
    private volatile long limit;
    private volatile Store store;
    private final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(4096);
    private final Thread writer;
    private volatile boolean closed;
    /** Файлы регионов всех ключей: путь → [размер, последнее обращение] (только поток записи). */
    private final Map<Path, long[]> files = new HashMap<>();
    private volatile long total;
    private boolean scanned;

    public final AtomicLong hits = new AtomicLong(), misses = new AtomicLong(), writes = new AtomicLong(), dropped = new AtomicLong(),
            bad = new AtomicLong(), evictedFiles = new AtomicLong(), readNs = new AtomicLong();

    public GenDiskCache(Path root, long limitBytes) {
        this.root = root;
        this.limit = limitBytes;
        writer = new Thread(this::writeLoop, "hoka-gen-disk");
        writer.setDaemon(true);
        writer.setPriority(Thread.MIN_PRIORITY);
        writer.start();
    }

    /** Предел (байт); 0 — выключен (чтение и запись не идут). */
    public void limit(long bytes) { limit = bytes; queue.offer(TRIM); }

    public boolean on() { return limit > 0 && store != null && !closed; }

    /** Кэш под ключ генератора (null — никакого). Прежний закрывается (его хвост записи допишется). */
    public void open(String key) {
        Store old = store;
        if (key == null) { store = null; if (old != null) queue.offer(new Close(old)); return; }
        String dir = sha(key).substring(0, 24);
        if (old != null && old.dir.getFileName().toString().equals(dir)) return;
        Store s = new Store(root.resolve(dir), key);
        store = s;
        if (old != null) queue.offer(new Close(old));
        queue.offer(new Init(s));
    }

    public long bytes() { return total; }

    // ───────────── чтение (любой поток) ─────────────

    /** Прочитать куб в c (после reset). true — найден и цел. */
    public boolean get(int cx, int cy, int cz, GenArrayCube c) {
        Store s = store;
        if (s == null || limit <= 0) return false;
        long t0 = System.nanoTime();
        try {
            Region r = s.region(Math.floorDiv(cx, RX), Math.floorDiv(cy, RY), Math.floorDiv(cz, RZ), false);
            if (r == null) { misses.incrementAndGet(); return false; }
            long e = r.slot(slot(cx, cy, cz));
            if (e == 0) { misses.incrementAndGet(); return false; }
            long off = (e >>> 20) - 1;
            int len = (int) (e & 0xFFFFF);
            ByteBuffer b = ByteBuffer.allocate(HEAD + len).order(ByteOrder.LITTLE_ENDIAN);
            FileChannel ch = r.channel();
            while (b.hasRemaining()) if (ch.read(b, off + b.position()) < 0) throw new IOException("конец файла");
            b.flip();
            if (b.getInt() != cx || b.getInt() != cy || b.getInt() != cz) throw new IOException("не тот куб");
            long h = b.getLong();
            if (b.getInt() != len) throw new IOException("длина");
            decode(b.array(), HEAD, len, c);
            if (c.visHash() != h) throw new IOException("хэш");
            hits.incrementAndGet();
            return true;
        } catch (java.nio.channels.ClosedChannelException ex) {           // регион закрыли/переоткрыли на запись — промах
            misses.incrementAndGet();
            return false;
        } catch (IOException | DataFormatException | RuntimeException ex) {
            bad.incrementAndGet();
            java.util.Arrays.fill(c.blocks, 0);
            java.util.Arrays.fill(c.biomes, 0);
            return false;
        } finally {
            readNs.addAndGet(System.nanoTime() - t0);
        }
    }

    // ───────────── запись ─────────────

    /** Записать сгенерированный куб (кодирует в вызывающем потоке, пишет поток записи). Очередь полна — пропуск. */
    public void put(int cx, int cy, int cz, GenArrayCube c, long visHash) {
        Store s = store;
        if (s == null || limit <= 0 || closed) return;
        byte[] body = encode(c);
        if (body.length > MAX_REC) { dropped.incrementAndGet(); return; }
        if (!queue.offer(new Rec(s, cx, cy, cz, visHash, body))) dropped.incrementAndGet();
    }

    private record Rec(Store s, int cx, int cy, int cz, long h, byte[] body) {}
    private record Close(Store s) {}
    private record Init(Store s) {}
    private static final Object TRIM = new Object(), SYNC = new Object();

    /** Дождаться записи очереди (тесты). */
    public void flush() throws InterruptedException {
        Object m = new Object();
        synchronized (m) { queue.put(new Object[]{m}); m.wait(10_000); }
    }

    public void close() {
        closed = true;
        Store s = store;
        store = null;
        if (s != null) queue.offer(new Close(s));
        queue.offer(SYNC);
        try { writer.join(5_000); } catch (InterruptedException ignored) {}
    }

    private void writeLoop() {
        long lastTrim = 0;
        while (true) {
            Object o;
            try { o = queue.poll(500, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { return; }
            try {
                if (!scanned) scan();
                if (o instanceof Rec r) write(r);
                else if (o instanceof Close c) c.s().closeAll();
                else if (o instanceof Init i) i.s().init();
                else if (o instanceof Object[] a) { synchronized (a[0]) { a[0].notifyAll(); } }
                else if (o == SYNC && closed) return;
                long now = System.currentTimeMillis();
                if (o == TRIM || total > limit && now - lastTrim > 1000) { lastTrim = now; trim(); }
            } catch (Throwable ex) {
                bad.incrementAndGet();
            }
            if (closed && queue.isEmpty()) {
                Store s = store;
                if (s != null) s.closeAll();
                return;
            }
        }
    }

    private void write(Rec r) throws IOException {
        Store s = r.s();
        if (s != store) return;                                        // ключ сменился
        Region g = s.region(Math.floorDiv(r.cx(), RX), Math.floorDiv(r.cy(), RY), Math.floorDiv(r.cz(), RZ), true);
        if (g == null) return;
        ByteBuffer b = ByteBuffer.allocate(HEAD + r.body().length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(r.cx()).putInt(r.cy()).putInt(r.cz()).putLong(r.h()).putInt(r.body().length).put(r.body()).flip();
        FileChannel ch = g.channel();
        long off = g.end;
        while (b.hasRemaining()) off += ch.write(b, off);
        long start = g.end;
        g.end = off;
        g.setSlot(slot(r.cx(), r.cy(), r.cz()), ((start + 1) << 20) | r.body().length);
        long[] f = files.computeIfAbsent(g.path, k -> new long[2]);
        total += off - start;
        f[0] += off - start;
        f[1] = System.currentTimeMillis();
        writes.incrementAndGet();
    }

    /** Все файлы под root (размеры и mtime) — один раз, в потоке записи. */
    private void scan() {
        scanned = true;
        if (!Files.isDirectory(root)) return;
        try (var st = Files.walk(root, 2)) {
            st.filter(p -> p.getFileName().toString().endsWith(EXT)).forEach(p -> {
                try { files.put(p, new long[]{Files.size(p), Files.getLastModifiedTime(p).toMillis()}); }
                catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
        total = 0;
        for (long[] f : files.values()) total += f[0];
    }

    /** Больше предела — удалить давно не нужные регионы до 90 % предела. */
    private void trim() {
        if (limit <= 0 || total <= limit) return;                      // 0 — выключен: файлы не трогаем
        Store s = store;
        if (s != null) for (Region r : s.open.values()) { long[] f = files.get(r.path); if (f != null) f[1] = Math.max(f[1], r.used); }
        List<Map.Entry<Path, long[]>> all = new ArrayList<>(files.entrySet());
        all.sort(Comparator.comparingLong(e -> e.getValue()[1]));
        long target = limit / 10 * 9;
        for (var e : all) {
            if (total <= target) break;
            Path p = e.getKey();
            if (s != null) s.forget(p);
            try { Files.deleteIfExists(p); } catch (IOException ex) { continue; }
            total -= e.getValue()[0];
            files.remove(p);
            evictedFiles.incrementAndGet();
        }
    }

    // ───────────── ключ и регионы ─────────────

    private final class Store {
        final Path dir;
        final String key;
        final ConcurrentHashMap<Long, Region> open = new ConcurrentHashMap<>();

        Store(Path dir, String key) { this.dir = dir; this.key = key; }

        void init() throws IOException {
            Files.createDirectories(dir);
            Path k = dir.resolve("key.txt");
            if (!Files.exists(k)) Files.writeString(k, key, StandardCharsets.UTF_8);
        }

        /** Регион; create — создать файл (поток записи), иначе null, если файла нет. */
        Region region(int rx, int ry, int rz, boolean create) throws IOException {
            long id = ((long) (rx & 0x1FFFFF) << 42) | ((long) (ry & 0x1FFFFF) << 21) | (rz & 0x1FFFFF);
            Region r = open.get(id);
            if (r == null) {
                Path p = dir.resolve("r." + rx + "." + ry + "." + rz + EXT);
                if (!create && !Files.exists(p)) return null;
                r = open.computeIfAbsent(id, x -> new Region(p));
                if (open.size() > MAX_OPEN) shrink(r);
            }
            r.ensure(create);
            r.used = System.currentTimeMillis();
            return r.ok ? r : null;
        }

        /** Закрыть самые давние открытые регионы (кроме нужного). */
        private void shrink(Region keep) {
            List<Map.Entry<Long, Region>> all = new ArrayList<>(open.entrySet());
            all.sort(Comparator.comparingLong(e -> e.getValue().used));
            for (int i = 0; i < all.size() - MAX_OPEN / 2; i++) {
                if (all.get(i).getValue() == keep) continue;
                open.remove(all.get(i).getKey());
                all.get(i).getValue().close();
            }
        }

        void forget(Path p) {
            for (var it = open.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue().path.equals(p)) { it.remove(); e.getValue().close(); }
            }
        }

        void closeAll() { for (Region r : open.values()) r.close(); open.clear(); }
    }

    private static final class Region {
        final Path path;
        private final long[] slots = new long[SLOTS];                  // (смещение+1) << 20 | длина; 0 — нет
        private FileChannel ch;
        volatile boolean ok;
        boolean loaded, writable;
        long end;
        volatile long used;

        Region(Path path) { this.path = path; }

        long slot(int i) { synchronized (slots) { return slots[i]; } }
        void setSlot(int i, long v) { synchronized (slots) { slots[i] = v; } }
        FileChannel channel() { return ch; }

        /** Открыть и прочитать оглавление (один раз); для записи — переоткрыть на запись. */
        synchronized void ensure(boolean write) throws IOException {
            if (loaded && (!write || writable)) return;
            if (ch != null) ch.close();
            if (write) Files.createDirectories(path.getParent());
            ch = write ? FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
                    : FileChannel.open(path, StandardOpenOption.READ);
            writable = write;
            if (!loaded) {
                long size = ch.size(), off = 0;
                ByteBuffer h = ByteBuffer.allocate(HEAD).order(ByteOrder.LITTLE_ENDIAN);
                while (off + HEAD <= size) {
                    h.clear();
                    while (h.hasRemaining()) if (ch.read(h, off + h.position()) < 0) break;
                    if (h.hasRemaining()) break;
                    h.flip();
                    int cx = h.getInt(), cy = h.getInt(), cz = h.getInt();
                    h.getLong();
                    int len = h.getInt();
                    if (len <= 0 || len > MAX_REC || off + HEAD + len > size) break;   // обрезанный хвост (выключили посреди записи)
                    synchronized (slots) { slots[GenDiskCache.slot(cx, cy, cz)] = ((off + 1) << 20) | len; }
                    off += HEAD + len;
                }
                end = off;
                if (write && off < size) ch.truncate(off);
                loaded = true;
                try { Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis())); } catch (IOException ignored) {}
            }
            ok = true;
        }

        synchronized void close() {
            ok = false;
            try { if (ch != null) ch.close(); } catch (IOException ignored) {}
            ch = null;
        }
    }

    private static int slot(int cx, int cy, int cz) {
        return Math.floorMod(cy, RY) << 8 | Math.floorMod(cz, RZ) << 4 | Math.floorMod(cx, RX);
    }

    // ───────────── кодек куба ─────────────

    private static final ThreadLocal<Deflater> DEF = ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_SPEED, true));
    private static final ThreadLocal<Inflater> INF = ThreadLocal.withInitial(() -> new Inflater(true));

    /** Палитра блоков (varint), индексы (1 или 2 Б, при палитре из 1 — нет), 64 биома (varint) → deflate. */
    static byte[] encode(GenArrayCube c) {
        int[] b = c.blocks;
        HashMap<Integer, Integer> idx = new HashMap<>();
        int[] pal = new int[4096];
        int np = 0;
        ByteArrayOutputStream raw = new ByteArrayOutputStream(4600);
        byte[] ix = new byte[8192];
        for (int i = 0; i < 4096; i++) {
            Integer k = idx.get(b[i]);
            if (k == null) { idx.put(b[i], k = np); pal[np++] = b[i]; }
            ix[2 * i] = (byte) k.intValue();
            ix[2 * i + 1] = (byte) (k >>> 8);
        }
        varint(raw, np);
        for (int i = 0; i < np; i++) varint(raw, pal[i]);
        if (np > 256) raw.write(ix, 0, 8192);
        else if (np > 1) for (int i = 0; i < 4096; i++) raw.write(ix[2 * i]);
        for (int v : c.biomes) varint(raw, v);
        byte[] in = raw.toByteArray();
        Deflater d = DEF.get();
        d.reset();
        d.setInput(in);
        d.finish();
        byte[] out = new byte[in.length + 64];
        int n = 0;
        while (!d.finished()) {
            if (n == out.length) out = Arrays.copyOf(out, out.length * 2);
            n += d.deflate(out, n, out.length - n);
        }
        return Arrays.copyOf(out, n);
    }

    static void decode(byte[] src, int off, int len, GenArrayCube c) throws DataFormatException {
        Inflater f = INF.get();
        f.reset();
        f.setInput(src, off, len);
        byte[] raw = new byte[8192 + 4096 * 5 + 64 * 5 + 16];
        int n = 0;
        while (!f.finished()) {
            int k = f.inflate(raw, n, raw.length - n);
            if (k == 0 && (f.needsInput() || f.needsDictionary() || n == raw.length)) throw new DataFormatException("обрыв");
            n += k;
        }
        int[] p = {0};
        int np = rvar(raw, p, n);
        if (np < 1 || np > 4096) throw new DataFormatException("палитра");
        int[] pal = new int[np];
        for (int i = 0; i < np; i++) pal[i] = rvar(raw, p, n);
        int[] b = c.blocks;
        if (np == 1) Arrays.fill(b, pal[0]);
        else if (np <= 256) {
            if (p[0] + 4096 > n) throw new DataFormatException("индексы");
            for (int i = 0; i < 4096; i++) { int k = raw[p[0]++] & 0xFF; if (k >= np) throw new DataFormatException("индекс"); b[i] = pal[k]; }
        } else {
            if (p[0] + 8192 > n) throw new DataFormatException("индексы");
            for (int i = 0; i < 4096; i++) {
                int k = (raw[p[0]] & 0xFF) | (raw[p[0] + 1] & 0xFF) << 8;
                p[0] += 2;
                if (k >= np) throw new DataFormatException("индекс");
                b[i] = pal[k];
            }
        }
        for (int i = 0; i < 64; i++) c.biomes[i] = rvar(raw, p, n);
    }

    private static void varint(ByteArrayOutputStream o, int v) {
        while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; }
        o.write(v);
    }

    private static int rvar(byte[] a, int[] p, int n) throws DataFormatException {
        int v = 0, s = 0;
        while (true) {
            if (p[0] >= n || s > 28) throw new DataFormatException("varint");
            int b = a[p[0]++];
            v |= (b & 0x7F) << s;
            if ((b & 0x80) == 0) return v;
            s += 7;
        }
    }

    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public String stats() {
        long h = hits.get(), m = misses.get();
        return String.format(Locale.ROOT, "диск: %d МБ из %d, попаданий %d/%d%s, записано %d%s%s", total >> 20, limit >> 20, h, h + m,
                h > 0 ? String.format(Locale.ROOT, " (%.0f мкс)", readNs.get() / 1e3 / Math.max(1, h + m)) : "", writes.get(),
                dropped.get() > 0 ? ", пропущено " + dropped.get() : "", bad.get() > 0 ? ", битых " + bad.get() : "");
    }
}
