package ink.hoka.odyssey.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.49 (H): очередь применения пакетов кубов {@code hoka_odyssey:w} с бюджетом времени на кадр. Пачка кубов после
 * телепорта больше не даёт кадров по 5–40 мс: за кадр применяется столько, сколько влезает в бюджет (доля среднего
 * кадра), остальное — в следующих кадрах.
 * <p>Порядок как у пакетов: все op (2 куб, 4 выгрузка, 5 окно, 6 верфь, 11 свет) идут одной очередью FIFO. Ванильные
 * пакеты столбца (новый скелет) и Forget применяются сразу — поэтому у каждого куба в очереди есть «поколение» его
 * столбца на момент прихода пакета: столбец за это время заменили/забыли — куб (и его свет) пропускается, как если бы
 * его применили сразу, а скелет потом затёр. Ванильная правка блока (BlockUpdate и т.п.) сначала дожимает очередь —
 * старый куб не ляжет поверх новой правки. Только главный поток.</p>
 * <p>0.57 (задача 1): ванильные пакеты столбца (скелет 1876 секций) тоже идут сюда — запись «повтор пакета»
 * ({@link #defer}), применяется по тому же бюджету и в том же порядке, что кубы; правки блоков / Forget / биомы /
 * свет столбца, у которого что-то ждёт, — следом в очередь (PacketDeferMixin). Повтор вызывает packet.handle с
 * флагом {@link #REPLAY} — работают все чужие инъекции (Sodium, Fabric). Поколение столбца растёт при постановке
 * скелета/Forget в очередь, а не при повторе.</p>
 */
public final class CubeQueue {

    private CubeQueue() {}

    private static final class Entry {
        final ClientLevel level;
        final CubeReceiver.Parsed p;
        /** 0.57: отложенный ванильный пакет (иначе null); столбцы и секция, которые он держит. */
        Runnable replay;
        long[] cols;
        long sec = Long.MIN_VALUE;
        /** 0.57: выполнить и при смене мира (ChunkBatchFinished: без ответа сервер перестанет слать столбцы). */
        boolean always;
        final int[] gens;
        final long at;
        int next;
        boolean peeked;                          // 0.52: свет следом уже подсмотрен (lightAhead)
        Entry(ClientLevel level, CubeReceiver.Parsed p, int[] gens, long at) { this.level = level; this.p = p; this.gens = gens; this.at = at; }
    }

    private static final ArrayDeque<Entry> Q = new ArrayDeque<>();
    /** Поколение столбца (только пока очередь не пуста; отсутствие = 0). */
    private static final Long2IntOpenHashMap GEN = new Long2IntOpenHashMap();
    private static int counter;
    private static boolean draining;
    public static long stale, applied, maxLen;
    /** Кубов в очереди (для F3/лога). */
    private static int pending;
    /** 0.57: идёт повтор отложенного пакета (миксины не откладывают его снова). */
    public static boolean REPLAY;
    /** 0.57: ждущие в очереди: столбец → отложенных ванильных пакетов; секция → кубов и правок; всего ванильных. */
    private static final Long2IntOpenHashMap COLS = new Long2IntOpenHashMap(), SECS = new Long2IntOpenHashMap();
    private static int vanilla, replayFails;
    public static long deferred, replayed, replayNs, replayMaxNs;

    /** Пакет пришёл (главный поток). */
    static void add(ClientLevel level, CubeReceiver.Parsed p) {
        int[] gens = null;
        if (p.pos != null) {
            gens = new int[p.pos.length];
            if (!GEN.isEmpty())
                for (int i = 0; i < gens.length; i++) gens[i] = GEN.get(col(p.pos[i]));
            pending += p.pos.length;
            if (cube(p.op)) for (long q : p.pos) SECS.addTo(q, 1);
        }
        Q.add(new Entry(level, p, gens, System.nanoTime()));
        if (Q.size() > maxLen) maxLen = Q.size();
    }

    /** Операции «по кубу» (держат секцию, применяются по одному): 2, 4, 13 и 0.59: 21 (метка генерации). */
    private static boolean cube(int op) { return op == 2 || op == 4 || op == 13 || op == 21; }

    private static long col(long sec) { return ChunkPos.asLong(SectionPos.x(sec), SectionPos.z(sec)); }

    /** Столбец заменён новым пакетом / забыт (главный поток): ждущие кубы старого столбца станут устаревшими. */
    public static void columnChanged(int x, int z) {
        CubeCache.columnChanged(x, z);                                 // 0.51: ждать кубы старого столбца нечего
        GenPool.columnChanged(x, z);                                   // 0.59: генерировать для старого столбца — тоже
        if (Q.isEmpty()) return;
        GEN.put(ChunkPos.asLong(x, z), ++counter);
    }

    // ───────────── 0.57: отложенные ванильные пакеты ─────────────

    /** Есть ли что-то ждущее по столбцу (скелет, Forget, правка…). */
    public static boolean waitsColumn(int x, int z) { return !COLS.isEmpty() && COLS.get(ChunkPos.asLong(x, z)) > 0; }

    /** Ждёт ли столбец или куб/правка секции sec. */
    public static boolean waits(long sec) {
        return (!SECS.isEmpty() && SECS.get(sec) > 0) || waitsColumn(SectionPos.x(sec), SectionPos.z(sec));
    }

    public static boolean hasVanilla() { return vanilla > 0; }

    public static boolean isEmpty() { return Q.isEmpty(); }

    public static int size() { return pending + vanilla; }

    /**
     * Отложить ванильный пакет (главный поток): повтор r — в порядке очереди, по бюджету. cols — столбцы, sec —
     * секция правки (Long.MIN_VALUE — нет).
     */
    public static void defer(ClientLevel level, Runnable r, long[] cols, long sec) { defer(level, r, cols, sec, false); }

    /** always — повторить и если мир сменился (запись не выбрасывается). */
    public static void defer(ClientLevel level, Runnable r, long[] cols, long sec, boolean always) {
        CubeReceiver.Parsed p = new CubeReceiver.Parsed(-1, null);
        Entry e = new Entry(level, p, null, System.nanoTime());
        e.replay = r;
        e.cols = cols;
        e.sec = sec;
        e.always = always;
        if (cols != null) for (long c : cols) COLS.addTo(c, 1);
        if (sec != Long.MIN_VALUE) SECS.addTo(sec, 1);
        vanilla++;
        deferred++;
        Q.add(e);
        if (Q.size() > maxLen) maxLen = Q.size();
    }

    private static void release(Entry e) {
        if (e.replay == null) return;
        if (e.cols != null) for (long c : e.cols) dec(COLS, c);
        if (e.sec != Long.MIN_VALUE) dec(SECS, e.sec);
        vanilla--;
        e.replay = null;
    }

    private static void dec(Long2IntOpenHashMap m, long k) {
        int v = m.addTo(k, -1) - 1;
        if (v <= 0) m.remove(k);
    }

    /** Куб i записи e больше не ждёт (применён / пропущен / мир сменился). */
    private static void cubeDone(Entry e, int i) {
        CubeReceiver.Parsed p = e.p;
        if (p.pos != null && cube(p.op)) dec(SECS, p.pos[i]);
    }

    /** Каждый кадр: применять, пока не вышел бюджет. */
    public static void run() {
        ClientGen.frame();                                             // 0.59: генерация на клиенте — загрузка, пул, выборка
        if (Q.isEmpty()) { GenPool.apply(0); return; }
        long now = System.nanoTime();
        Entry head = Q.peek();
        // ждёт дольше 300 мс (поток пакетов больше бюджета) — бюджет больше: задержка не растёт без предела
        // ждёт дольше 2 с (окно не в фокусе, 1–30 FPS) — почти всё: задержка и память очереди ограничены
        long wait = now - head.at;
        // 0.57: в фокусе — не больше 8 мс (иначе снова всплески кадров); не в фокусе — почти всё
        boolean focus = Minecraft.getInstance().isWindowActive();
        long budget = wait > 2_000_000_000L ? (focus ? 8_000_000L : 50_000_000L)
                : wait > 1_000_000_000L ? 6_000_000L
                : wait > 300_000_000L ? 4_000_000L : FrameWatch.budgetNs(0.25, 300_000L, 1_500_000L);
        drain(now + budget, null);
        GenPool.apply(now + budget);                                   // 0.59: готовые кубы генерации — остаток бюджета
    }

    /** Дожать всё (смена мира и т.п.). */
    public static void drainAll() {
        if (!Q.isEmpty()) drain(Long.MAX_VALUE, null);
    }

    /**
     * Перед ванильной правкой блока в секции sec: дожать очередь только до последнего пакета, где есть куб этой секции
     * (op 2 / op 4) — иначе он лёг бы поверх правки. Всю очередь (после телепорта — десятки тысяч кубов) не трогаем:
     * ломание блока во время прогрузки не должно давать фриз в сотни мс.
     */
    public static void drainThrough(long sec) {
        if (Q.isEmpty()) return;
        Entry last = null;
        for (var it = Q.descendingIterator(); it.hasNext() && last == null; ) {
            Entry e = it.next();
            if (!cube(e.p.op) || e.p.pos == null) continue;
            long[] ps = e.p.pos;
            for (int i = e.next; i < ps.length; i++) if (ps[i] == sec) { last = e; break; }
        }
        if (last != null) drain(Long.MAX_VALUE, last);
    }

    /** 0.61: однородных меток сверх бюджета за кадр (каждая — десятки мкс: секция из одного значения, без света игры). */
    static final int CHEAP = 192;
    private static int cheapLeft;

    private static void drain(long end, Entry stop) {
        if (draining) return;                                          // повторный вход (правка блока изнутри применения)
        draining = true;
        cheapLeft = end == Long.MAX_VALUE ? 0 : CHEAP;
        try {
            Minecraft mc = Minecraft.getInstance();
            // сплошной камень дёшев только со светом на клиенте (SolidCubes); иначе — ванильный свет с соседями, по бюджету
            boolean solidCheap = mc.level != null && LocalLight.on(mc.level);
            boolean first = true;
            while (!Q.isEmpty()) {
                Entry e = Q.peek();
                if (e.level != mc.level) { drop(e); if (e == stop) return; continue; }   // мир сменился
                CubeReceiver.Parsed p = e.p;
                if (e.replay != null) {                                    // 0.57: отложенный ванильный пакет
                    Runnable r = e.replay;
                    Q.poll();
                    release(e);
                    long t0 = System.nanoTime();
                    REPLAY = true;
                    try {
                        r.run();
                    } catch (RuntimeException ex) {
                        if (replayFails++ < 5) org.slf4j.LoggerFactory.getLogger("HokaOdyssey").warn("отложенный пакет: сбой ({})", ex.toString(), ex);
                    } finally {
                        REPLAY = false;
                    }
                    long dt = System.nanoTime() - t0;
                    replayed++;
                    replayNs += dt;
                    if (dt > replayMaxNs) replayMaxNs = dt;
                    first = false;
                    if (e == stop || System.nanoTime() >= end) return;
                    continue;
                }
                switch (p.op) {
                    case 5, 6, 15, 16, 17, 18, 22, 23 -> { CubeReceiver.applySimple(e.level, p); Q.poll(); }
                    case 14 -> {                                       // 0.51: свет из кэша (как op 11)
                        List<Long> ps = new ArrayList<>(p.pos.length);
                        List<ServerLight.Cube> cs = new ArrayList<>(p.pos.length);
                        for (int i = 0; i < p.pos.length; i++) {
                            if (!current(e, i)) { stale++; continue; }
                            ServerLight.Cube c = CubeCache.takeLight(e.level, p.ns, p.pos[i], p.hash[i]);
                            if (c != null) { ps.add(p.pos[i]); cs.add(c); }
                        }
                        long[] pa = new long[ps.size()];
                        for (int i = 0; i < pa.length; i++) pa[i] = ps.get(i);
                        ServerLight.submitCached(e.level, pa, cs.toArray(new ServerLight.Cube[0]));
                        pending -= p.pos.length;
                        Q.poll();
                    }
                    case 11 -> {
                        boolean[] keep = null;
                        for (int i = 0; i < p.pos.length; i++) {
                            if (current(e, i)) continue;
                            if (keep == null) { keep = new boolean[p.pos.length]; java.util.Arrays.fill(keep, true); }
                            keep[i] = false;
                            stale++;
                        }
                        for (long ps : p.pos) CubeCache.gotLight(p.ns, ps);   // 0.51: кэшированный свет устарел
                        ServerLight.submit(e.level, p.light, keep);
                        pending -= p.pos.length;
                        Q.poll();
                    }
                    default -> {                                       // 2, 4, 13: по кубу, с проверкой времени
                        if (!e.peeked && p.op != 4 && p.op != 21) { e.peeked = true; lightAhead(e); }
                        int n = p.pos.length, from = e.next;
                        while (e.next < n) {
                            // 0.61: однородная метка (воздух / сплошной камень) дешёвая — сверх бюджета до CHEAP за кадр
                            int us = p.op == 21 ? p.uni[2 * e.next] : -1;
                            boolean cheap = cheapLeft > 0 && (us == 0 || us > 0 && solidCheap
                                    && SolidCubes.opaque(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(us)));   // вода — нет
                            if (cheap) cheapLeft--;
                            else if (!first && System.nanoTime() >= end) break;
                            first = false;
                            int i = e.next++;
                            cubeDone(e, i);
                            CubeReceiver.applyCube(e.level, p, i, !current(e, i));
                            pending--;
                            applied++;
                        }
                        if (p.op == 4 && e.next > from) unloadLight(e, from, e.next);
                        if (e.next < n) return;
                        Q.poll();
                    }
                }
                first = false;
                if (e == stop || System.nanoTime() >= end) return;
            }
        } finally {
            draining = false;
            if (Q.isEmpty()) { GEN.clear(); pending = 0; COLS.clear(); SECS.clear(); vanilla = 0; }
        }
    }

    /**
     * 0.52: свет (op 11 / op 14), идущий в очереди сразу за кубами e, — заранее в LATEST ServerLight: слой неба,
     * который движок заведёт кубу при его применении, сразу верный (было: ванильный 15 / копия ряда сверху, через
     * кадр-два свет сервера и вторая сборка; после кэша — всегда). Только подряд идущие 2/13/11/14, не дальше 16.
     */
    private static void lightAhead(Entry e) {
        boolean past = false;
        int seen = 0;
        for (Entry f : Q) {
            if (!past) { past = f == e; continue; }
            if (++seen > 16 || f.level != e.level) return;
            CubeReceiver.Parsed q = f.p;
            if (q.op == 2 || q.op == 13) continue;
            if (q.op == 11) {
                for (int i = 0; i < q.pos.length; i++) if (current(f, i)) ServerLight.preview(f.level, q.pos[i], q.light.cubes()[i]);
            } else if (q.op == 14) {
                for (int i = 0; i < q.pos.length; i++) {
                    if (!current(f, i)) continue;
                    ServerLight.Cube c = CubeCache.peekLight(q.ns, q.pos[i], q.hash[i]);
                    if (c != null) ServerLight.preview(f.level, q.pos[i], c);
                }
            } else return;
        }
    }

    /** op 4: свет выгруженных кубов отрезка — одной задачей в очередь света (как раньше весь пакет). */
    private static void unloadLight(Entry e, int from, int to) {
        long[] gone = new long[to - from];
        int k = 0;
        for (int i = from; i < to; i++) if (current(e, i)) gone[k++] = e.p.pos[i];
        if (k > 0) ServerLight.unload(e.level, k == gone.length ? gone : java.util.Arrays.copyOf(gone, k));
    }

    private static boolean current(Entry e, int i) {
        int g = e.gens == null ? 0 : e.gens[i];
        return GEN.isEmpty() ? g == 0 : GEN.get(col(e.p.pos[i])) == g;
    }

    private static void drop(Entry e) {
        Q.poll();
        Runnable r = e.always ? e.replay : null;
        release(e);
        if (r != null) {                                               // ответ серверу о пачке — всегда (Minestom ждёт его)
            REPLAY = true;
            try { r.run(); } catch (RuntimeException ignored) {} finally { REPLAY = false; }
        }
        if (e.p.pos != null) {
            pending -= e.p.pos.length - e.next;
            for (int i = e.next; i < e.p.pos.length; i++) cubeDone(e, i);
        }
    }

    /** Мир сменился / вышли. */
    public static void clear() {
        Q.clear();
        GEN.clear();
        COLS.clear();
        SECS.clear();
        vanilla = 0;
        pending = 0;
    }

    public static String stats() {
        return "очередь кубов " + pending + " (пакетов " + Q.size() + ", макс " + maxLen + "), устаревших " + stale
                + ", ванильных отложено " + deferred + " (ждут " + vanilla + ", повторено " + replayed
                + (replayed > 0 ? String.format(", в среднем %.3f мс, макс %.2f мс", replayNs / 1e6 / replayed, replayMaxNs / 1e6) : "") + ")";
    }
}
