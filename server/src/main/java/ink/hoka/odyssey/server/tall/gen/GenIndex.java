package ink.hoka.odyssey.server.tall.gen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Индекс pregen в рантайме: файлы genidx/*.hgi по регионам 32×32 столбца, LRU ~64 региона. Файл не того
 * генератора (id, версия, сид, отпечаток) или битый — не используется (в лог один раз). Нет файла / не подошёл —
 * повторная проверка не чаще раза в 30 с (pregen может дописать его на ходу).
 */
public final class GenIndex {

    private static final Logger LOG = LoggerFactory.getLogger("worldgen");
    private static final int MAX = 64;
    private static final long RECHECK_MS = 30_000;

    private final Path dir;
    private final ModuleCubeGen gen;
    private volatile GenIndexFile.Header expect;
    private final java.util.Set<Path> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record Slot(GenIndexFile file, long checkedAt, long mtime) {}

    /** LRU регионов без упаковки Long (было LinkedHashMap<Long>: хэш rx^rz сталкивал диагонали — JFR s55). Под своим замком. */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<Slot> lru = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>(MAX * 2);
    /** Последний регион (без замка): initLazy/columnPass спрашивают 1876 кубов одного столбца подряд. */
    private record Last(long key, Slot slot) {}
    private volatile Last last;

    GenIndex(Path dir, ModuleCubeGen gen) { this.dir = dir; this.gen = gen; }

    public Path dir() { return dir; }

    /** Какой заголовок должен быть у файла (отпечаток — при первом обращении, несколько мс). */
    public GenIndexFile.Header expected() {
        GenIndexFile.Header h = expect;
        if (h == null) {
            h = new GenIndexFile.Header(gen.provider.id(), gen.provider.version(), gen.settings.seed(), gen.fingerprint(),
                    gen.minCy, gen.maxCy, false);
            expect = h;
        }
        return h;
    }

    private static long key(int rx, int rz) { return ((long) rx << 32) | (rz & 0xFFFFFFFFL); }

    /** Столбец (cx, cz) из индекса или null — не посчитан / индекса нет. */
    public GenIndexFile.Col col(int cx, int cz) {
        GenIndexFile f = file(cx, cz);
        return f == null ? null : f.col(cx, cz);
    }

    /** Файл региона со столбцом (cx, cz) или null. */
    GenIndexFile file(int cx, int cz) {
        int rx = cx >> 5, rz = cz >> 5;
        long k = key(rx, rz), now = System.currentTimeMillis();
        Last l = last;
        if (l != null && l.key == k && now - l.slot.checkedAt < RECHECK_MS) return l.slot.file;
        Slot s;
        synchronized (lru) { s = lru.getAndMoveToLast(k); }
        if (s != null && now - s.checkedAt < RECHECK_MS) { last = new Last(k, s); return s.file; }
        Path p = GenIndexFile.path(dir, rx, rz);
        long mt;
        try { mt = Files.getLastModifiedTime(p).toMillis(); }
        catch (NoSuchFileException e) { mt = -1; }
        catch (IOException e) { mt = -1; }
        if (s != null && s.mtime == mt) {                                   // не менялся — продлить
            put(k, new Slot(s.file, now, mt));
            return s.file;
        }
        GenIndexFile f = null;
        if (mt >= 0) {
            try {
                byte[] b = Files.readAllBytes(p);
                GenIndexFile.Header h;
                try (var in = new DataInputStream(new java.io.ByteArrayInputStream(b))) { h = GenIndexFile.readHeader(in); }
                if (!h.sameGen(expected())) {
                    if (warned.add(p)) LOG.warn("индекс {} от другого генератора ({}@{}, сид {}, отпечаток {}) — не использую, нужен /hocubes pregen",
                            p.getFileName(), h.genId(), h.genVersion(), h.seed(), h.fingerprint());
                } else f = GenIndexFile.decode(b);
            } catch (IOException e) {
                if (warned.add(p)) LOG.error("индекс {} битый ({}) — не использую", p.getFileName(), e.getMessage());
            }
        }
        put(k, new Slot(f, now, mt));
        return f;
    }

    private void put(long k, Slot s) {
        synchronized (lru) {
            lru.putAndMoveToLast(k, s);
            while (lru.size() > MAX) lru.removeFirst();
        }
        last = new Last(k, s);
    }

    /** Забыть кэш (после записи pregen в этом же процессе). */
    public void invalidate(int rx, int rz) {
        synchronized (lru) { lru.remove(key(rx, rz)); }
        last = null;
    }
}
