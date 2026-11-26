package ink.hoka.odyssey.server.objects;

import ink.hoka.odyssey.gen.GenObject;
import ink.hoka.odyssey.gen.GenObjects;
import ink.hoka.odyssey.server.tall.gen.PortableGens;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Пакеты объектов карт (.hopk): сборка из {@link ObjectLibrary} по ключу map.yml {@code objects:} и раздача клиентам
 * файлами clientmods ({@code genobj/<sha16>.hopk}) — пока карта с пакетом загружена (подсчёт по владельцам).
 */
public final class ObjectPacks {

    private ObjectPacks() {}

    /** Владелец («карта mars») → пакет. */
    private static final Map<String, GenObjects> ACTIVE = new ConcurrentHashMap<>();

    /**
     * Пакет для карты: spec — значение map.yml objects: (список категорий, строка через запятую, "*" — все; null — нет
     * ключа: все, если генератор просит, иначе пусто). Библиотеки нет — пусто.
     */
    public static GenObjects forMap(Object spec, boolean generatorWants) {
        ObjectLibrary lib = ObjectLibrary.global();
        if (lib == null) return GenObjects.EMPTY;
        List<String> cats;
        if (spec == null) cats = generatorWants ? null : List.of();
        else {
            List<String> l = new ArrayList<>();
            if (spec instanceof Collection<?> c) for (Object o : c) l.add(String.valueOf(o).trim());
            else for (String s : spec.toString().split(",")) if (!s.isBlank()) l.add(s.trim());
            cats = l.contains("*") ? null : l;
            if (cats != null) for (String c : cats)
                if (!GenObjects.NAME.matcher(c).matches()) throw new IllegalArgumentException("категория «" + c + "»");
        }
        return build(lib, cats);
    }

    /** Пакет из категорий cats (null — все). */
    public static GenObjects build(ObjectLibrary lib, Collection<String> cats) {
        if (cats != null && cats.isEmpty()) return GenObjects.EMPTY;
        List<GenObject> all = new ArrayList<>();
        for (String c : cats == null ? lib.categories() : new TreeSet<>(cats))
            for (GenObject o : lib.category(c)) if (GenObjects.checkLimits(o) == null) all.add(o);   // негодные — в /hoobj info
        return GenObjects.of(all);
    }

    /** Путь файла пакета у клиента. */
    public static String path(GenObjects o) { return "genobj/" + o.packSha256().substring(0, 16) + ".hopk"; }

    /** Занять пакет (карта загружена). Пустой — ничего. */
    public static void acquire(String owner, GenObjects o) {
        if (o == null || o.isEmpty()) { release(owner); return; }
        boolean was = published(o);
        GenObjects old = ACTIVE.put(owner, o);
        if (!was || (old != null && old != o && !published(old))) PortableGens.filesChanged();
    }

    public static void release(String owner) {
        GenObjects old = ACTIVE.remove(owner);
        if (old != null && !published(old)) PortableGens.filesChanged();
    }

    /** Пакет сейчас раздаётся. */
    public static boolean published(GenObjects o) {
        for (GenObjects a : ACTIVE.values()) if (a.hash() == o.hash() && a.packSha256().equals(o.packSha256())) return true;
        return false;
    }

    /** Файлы для clientmods: путь → байты (по sha — без повторов). */
    public static Map<String, byte[]> files() {
        Map<String, byte[]> r = new TreeMap<>();
        for (GenObjects o : ACTIVE.values()) if (!o.isEmpty()) r.putIfAbsent(path(o), o.pack());
        return r;
    }

    /** Активный пакет владельца («карта mars») или null. */
    public static GenObjects active(String owner) { return ACTIVE.get(owner); }
}
