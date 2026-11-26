package ink.hoka.odyssey.client;

import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;

/**
 * 0.59: память сгенерированных кубов — позиция → GenHash того, что положили в мир. Ушёл куб (op 4) — секция в
 * CubeCache; повторный op 21 по этой позиции берёт её из кэша по этому хэшу без генерации. LRU, до LIMIT записей.
 * Сменился генератор (id/версия/сид/настройки) или сервер — память стирается. Только главный поток.
 */
final class GenMemo {

    private GenMemo() {}

    private static final int LIMIT = 262_144;
    private static final Long2LongLinkedOpenHashMap MAP = new Long2LongLinkedOpenHashMap();
    private static String key;
    static long hits;

    /** Ключ генератора; другой — забыть всё. */
    static void key(String k) {
        if (k == null ? key == null : k.equals(key)) return;
        key = k;
        MAP.clear();
    }

    static void put(long pos, long h) {
        MAP.putAndMoveToLast(pos, h);
        while (MAP.size() > LIMIT) MAP.removeFirstLong();
    }

    /** 0 — нет. */
    static long get(long pos) { return MAP.isEmpty() ? 0 : MAP.get(pos); }

    static void remove(long pos) { if (!MAP.isEmpty()) MAP.remove(pos); }

    static int size() { return MAP.size(); }

    static void clear() { MAP.clear(); key = null; }
}
