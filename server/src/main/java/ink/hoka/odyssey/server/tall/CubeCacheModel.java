package ink.hoka.odyssey.server.tall;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Кэш кубов клиента (ядро 0.51), модель на сервере: что, по мнению сервера, лежит у игрока в кэше — по картам
 * (mapKey) и столбцам, в порядке LRU. Блоки: куб → версия H (CubeHash), которую клиент унёс в кэш, когда куб ушёл от
 * него (op 4, Forget, смена карты). Свет: куб → L последней записи света, ушедшей клиенту (у него живая или в кэше).
 * {@link #UNKNOWN} — засеяно из HAVE (версия неизвестна: ссылку шлём с текущей, клиент сверит сам).
 * <p>Модель — только подсказка, что слать ссылкой: клиент сам сверяет хэш содержимого, промах стоит WANT (+1 RTT),
 * но устаревший блок показать не может. Живёт до выхода игрока (смена карты её не стирает). Доступ — synchronized.</p>
 */
final class CubeCacheModel {

    private CubeCacheModel() {}

    /** -Dhoka.cubes.cache=false — выключить (op 12 с ключом 0). */
    static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("hoka.cubes.cache", "true"));
    /** С какой версии ядро знает кэш (ops 12–15 S2C, 3–6 C2S). */
    static final int[] CORE = {0, 51, 0};
    /** Версия засеяна из HAVE (точная неизвестна). Настоящие хэши никогда не 0 (CubeHash.avalanche). */
    static final long UNKNOWN = 0;
    /** Предел записей модели на игрока и карту. */
    static final int MAX_ENTRIES = 300_000;

    static final class Col {
        final Int2LongOpenHashMap b = new Int2LongOpenHashMap(0), l = new Int2LongOpenHashMap(0);
        int size() { return b.size() + l.size(); }
    }

    static final class MapModel {
        final Long2ObjectLinkedOpenHashMap<Col> cols = new Long2ObjectLinkedOpenHashMap<>();
        int entries;
    }

    static final class P {
        /** Бюджет кэша клиента, МБ (HAVE); 0 — кэш выключен. */
        int budgetMb;
        /** Карты в порядке использования (предел записей — общий на игрока, первыми вытесняются давние карты). */
        final Map<Long, MapModel> maps = new java.util.LinkedHashMap<>(4, 0.75f, true);
        /** WANT клиента: {pos, what} — разбирает тик (CubeStreamer.wants). */
        final ConcurrentLinkedQueue<long[]> wants = new ConcurrentLinkedQueue<>();

        int limit() { return (int) Math.min(MAX_ENTRIES, Math.max(4096L, budgetMb * 512L)); }
    }

    private static final Map<UUID, P> PLAYERS = new ConcurrentHashMap<>();

    /** Отладка (/hocubes stats). */
    static volatile long refCubes, refBytesSaved, refLight, refLightSaved, wantBlocks, wantLight, wantIgnored,
            seededCols, evicted, hashChecks, hashBad;

    /** Есть хоть один игрок с кэшем — версии кубов стоит считать заранее (install/afterLoad). */
    static boolean anyActive() {
        if (!ENABLED || PLAYERS.isEmpty()) return false;
        for (P m : PLAYERS.values()) if (m.budgetMb > 0) return true;
        return false;
    }

    static P get(UUID u) { return PLAYERS.get(u); }

    static P getOrCreate(UUID u) { return PLAYERS.computeIfAbsent(u, k -> new P()); }

    static void remove(UUID u) { PLAYERS.remove(u); }

    /** Модель игрока, если кэш у него включён (HAVE с бюджетом), иначе null. */
    static P active(UUID u) {
        if (!ENABLED) return null;
        P m = PLAYERS.get(u);
        return m == null || m.budgetMb <= 0 ? null : m;
    }

    static long colKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    private static MapModel map(P m, long key, boolean create) {
        MapModel mm = m.maps.get(key);
        if (mm == null && create) m.maps.put(key, mm = new MapModel());
        return mm;
    }

    private static Col col(MapModel mm, long ck, boolean create) {
        Col c = mm.cols.getAndMoveToLast(ck);
        if (c == null && create) mm.cols.putAndMoveToLast(ck, c = new Col());
        return c;
    }

    // ───────────── блоки ─────────────

    /** В модели есть запись блоков куба (тогда стоит считать его хэш). */
    static boolean hasBlocks(P m, long key, int cx, int cz, int i) {
        synchronized (m) {
            MapModel mm = map(m, key, false);
            if (mm == null) return false;
            Col c = mm.cols.get(colKey(cx, cz));
            return c != null && c.b.containsKey(i);
        }
    }

    /** Слать ли куб ссылкой: у клиента в кэше та же версия (или засеяно — версия неизвестна, клиент сверит). */
    static boolean refBlocks(P m, long key, int cx, int cz, int i, long hNow) {
        if (hNow == 0) return false;
        synchronized (m) {
            MapModel mm = map(m, key, false);
            if (mm == null) return false;
            Col c = mm.cols.get(colKey(cx, cz));
            if (c == null || !c.b.containsKey(i)) return false;
            long h = c.b.get(i);
            return h == UNKNOWN || h == hNow;
        }
    }

    /** Куб ушёл клиенту (op 2 или op 13): из его кэша он вынут — из модели тоже. */
    static void sentBlocks(P m, long key, int cx, int cz, int i) {
        synchronized (m) {
            MapModel mm = map(m, key, false);
            if (mm == null) return;
            long ck = colKey(cx, cz);
            Col c = mm.cols.get(ck);
            if (c == null || !c.b.containsKey(i)) return;
            c.b.remove(i);
            mm.entries--;
            if (c.size() == 0) mm.cols.remove(ck);
        }
    }

    /** Куб ушёл от клиента в его кэш с версией h. */
    static void cachedBlocks(P m, long key, int cx, int cz, int i, long h) {
        if (h == 0) return;
        synchronized (m) {
            MapModel mm = map(m, key, true);
            Col c = col(mm, colKey(cx, cz), true);
            if (!c.b.containsKey(i)) mm.entries++;
            c.b.put(i, h);
            trim(m, mm);
        }
    }

    // ───────────── свет ─────────────

    /** Слать ли свет куба ссылкой: последняя запись света у клиента — та же L (или засеяно). */
    static boolean refLight(P m, long key, int cx, int cz, int i, long lNow) {
        synchronized (m) {
            MapModel mm = map(m, key, false);
            if (mm == null) return false;
            Col c = mm.cols.get(colKey(cx, cz));
            if (c == null || !c.l.containsKey(i)) return false;
            long l = c.l.get(i);
            return l == UNKNOWN || l == lNow;
        }
    }

    /** Свет куба ушёл клиенту (op 11 или op 14 — после ссылки у него та же L). */
    static void sentLight(P m, long key, int cx, int cz, int i, long l) {
        synchronized (m) {
            MapModel mm = map(m, key, true);
            Col c = col(mm, colKey(cx, cz), true);
            if (!c.l.containsKey(i)) mm.entries++;
            c.l.put(i, l);
            trim(m, mm);
        }
    }

    /** Свет куба ушёл короткой записью (ссылкой не пойдёт) — запись модели прочь. */
    static void forgetLight(P m, long key, int cx, int cz, int i) {
        synchronized (m) {
            MapModel mm = map(m, key, false);
            if (mm == null) return;
            long ck = colKey(cx, cz);
            Col c = mm.cols.get(ck);
            if (c == null || !c.l.containsKey(i)) return;
            c.l.remove(i);
            mm.entries--;
            if (c.size() == 0) mm.cols.remove(ck);
        }
    }

    // ───────────── сообщения клиента ─────────────

    /** EVICT: столбец вытеснен из кэша клиента. */
    static void evict(P m, long key, int cx, int cz) {
        synchronized (m) {
            MapModel mm = map(m, key, false);
            if (mm == null) return;
            Col c = mm.cols.remove(colKey(cx, cz));
            if (c != null) { mm.entries -= c.size(); evicted++; }
        }
    }

    /** HAVE: столбец в кэше клиента, кубы y0+k (маски блоков и света) — засеять «версия неизвестна», не затирая известное. */
    static void seed(P m, long key, int cx, int cz, int i0, byte[] blocks, byte[] light, int len) {
        synchronized (m) {
            MapModel mm = map(m, key, true);
            Col c = col(mm, colKey(cx, cz), true);
            for (int k = 0; k < len; k++) {
                int i = i0 + k;
                if (i < 0 || i >= TallWorld.HEIGHT >> 4) continue;
                if ((blocks[k >> 3] >> (k & 7) & 1) != 0 && !c.b.containsKey(i)) { c.b.put(i, UNKNOWN); mm.entries++; }
                if ((light[k >> 3] >> (k & 7) & 1) != 0 && !c.l.containsKey(i)) { c.l.put(i, UNKNOWN); mm.entries++; }
            }
            if (c.size() == 0) mm.cols.remove(colKey(cx, cz));
            seededCols++;
            trim(m, mm);
        }
    }

    /** Новый бюджет; 0 — кэш выключен, модель — прочь. */
    static void budget(P m, int mb) {
        synchronized (m) {
            m.budgetMb = Math.max(0, Math.min(1 << 16, mb));
            if (m.budgetMb == 0) m.maps.clear();
            else trim(m, null);
        }
    }

    /** Предел записей — на игрока по всем картам (копии/много карт не раздуют модель): сначала давние карты, потом LRU столбцов. */
    private static void trim(P m, MapModel mm) {
        int lim = m.limit();
        long total = 0;
        for (MapModel x : m.maps.values()) total += x.entries;
        if (total <= lim) return;
        for (var it = m.maps.values().iterator(); it.hasNext() && total > lim; ) {
            MapModel x = it.next();
            if (x == mm) continue;
            while (total > lim && !x.cols.isEmpty()) { int n = x.cols.removeFirst().size(); x.entries -= n; total -= n; }
            if (x.cols.isEmpty()) it.remove();
        }
        while (mm != null && total > lim && !mm.cols.isEmpty()) { int n = mm.cols.removeFirst().size(); mm.entries -= n; total -= n; }
    }

    // ───────────── отладка ─────────────

    static int players() {
        int n = 0;
        for (P m : PLAYERS.values()) if (m.budgetMb > 0) n++;
        return n;
    }

    static long entries() {
        long n = 0;
        for (P m : PLAYERS.values()) synchronized (m) { for (MapModel mm : m.maps.values()) n += mm.entries; }
        return n;
    }

    /** Записей модели игрока (для /hocubes player). */
    static String describe(UUID u) {
        P m = PLAYERS.get(u);
        if (m == null) return "кэш кубов: нет (ядро < 0.51 или HAVE не пришёл)";
        synchronized (m) {
            StringBuilder sb = new StringBuilder("кэш кубов: бюджет " + m.budgetMb + " МБ, карт " + m.maps.size());
            for (var e : m.maps.entrySet())
                sb.append("; ").append(Long.toHexString(e.getKey()), 0, Math.min(6, Long.toHexString(e.getKey()).length()))
                        .append("…: столбцов ").append(e.getValue().cols.size()).append(", записей ").append(e.getValue().entries);
            return sb.toString();
        }
    }

    static String stats() {
        return "кэш клиентов: игроков " + players() + ", кубов ссылкой " + refCubes + " (~" + (refBytesSaved >> 20)
                + " МБ не отправлено), света ссылкой " + refLight + " (" + (refLightSaved >> 10) + " КБ), WANT блоки "
                + wantBlocks + " / свет " + wantLight + " (пропущено " + wantIgnored + "), засеяно столбцов " + seededCols
                + ", вытеснено " + evicted + ", записей модели " + entries() + ", сверка хэшей " + hashChecks + " (расхождений " + hashBad + ")"
                + (ENABLED ? "" : " — ВЫКЛ (-Dhoka.cubes.cache=false)");
    }
}
