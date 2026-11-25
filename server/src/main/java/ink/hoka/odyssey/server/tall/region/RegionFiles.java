package ink.hoka.odyssey.server.tall.region;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Открытые файлы регионов: один объект {@link RegionFile} на путь (его замок — замок файла), не больше {@link #MAX}
 * открытых сразу (давно не нужные закрываются). Работа с файлом — только внутри {@link #use}: закрытый вытеснением
 * файл откроется заново.
 */
public final class RegionFiles {

    private RegionFiles() {}

    static final int MAX = 256;
    private static final Map<Path, RegionFile> OPEN = new LinkedHashMap<>(64, 0.75f, true);

    public interface Op<T> { T run(RegionFile f) throws IOException; }

    /**
     * Вытесненные/закрываемые файлы, которые ещё не закрыты (кто-то внутри op). Пока путь здесь — новый объект на
     * него не открываем: у двух объектов на один файл разные таблицы и карты секторов — запись одного затрёт другой.
     */
    private static final Map<Path, RegionFile> CLOSING = new java.util.HashMap<>();

    /** Выполнить op с файлом (под его замком). Нет файла и !create — op не зовётся, ответ null. */
    public static <T> T use(Path path, int entries, boolean create, Op<T> op) throws IOException {
        Path key = path.toAbsolutePath().normalize();
        while (true) {
            RegionFile f, evict = null, wait = null;
            synchronized (OPEN) {
                f = OPEN.get(key);
                if (f == null) {
                    wait = CLOSING.get(key);
                    if (wait == null) {
                        f = RegionFile.open(key, entries, create);
                        if (f == null) return null;
                        OPEN.put(key, f);
                        if (OPEN.size() > MAX) {
                            var it = OPEN.entrySet().iterator();
                            var e = it.next();
                            evict = e.getValue();
                            it.remove();
                            CLOSING.put(e.getKey(), evict);
                        }
                    }
                }
            }
            if (wait != null) { finish(key, wait); continue; }             // старый объект ещё в работе — дождаться, закрыть
            if (evict != null) finish(evict.path().toAbsolutePath().normalize(), evict);
            synchronized (f) {
                if (f.closed()) continue;                                   // вытеснили, пока ждали — открыть заново
                return op.run(f);
            }
        }
    }

    /** Дождаться, пока файлом перестанут пользоваться, закрыть и убрать из CLOSING. */
    private static void finish(Path key, RegionFile f) {
        synchronized (f) { try { f.close(); } catch (IOException ignored) { } }
        synchronized (OPEN) { CLOSING.remove(key, f); }
    }

    /** Закрыть все файлы в папке dir (и ниже): перед переименованием/удалением папки, при выгрузке карты. */
    public static void closeUnder(Path dir) {
        Path d = dir.toAbsolutePath().normalize();
        Map<Path, RegionFile> list = new java.util.HashMap<>();
        synchronized (OPEN) {
            var it = OPEN.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getKey().startsWith(d)) { list.put(e.getKey(), e.getValue()); CLOSING.put(e.getKey(), e.getValue()); it.remove(); }
            }
        }
        for (var e : list.entrySet()) {
            RegionFile f = e.getValue();
            synchronized (f) { try { f.flush(); } catch (IOException ignored) { } }
            finish(e.getKey(), f);
        }
    }

    /** Сбросить на диск все открытые. */
    public static void flushAll() {
        java.util.List<RegionFile> list;
        synchronized (OPEN) { list = java.util.List.copyOf(OPEN.values()); }
        for (RegionFile f : list) synchronized (f) { try { f.flush(); } catch (IOException ignored) { } }
    }
}
