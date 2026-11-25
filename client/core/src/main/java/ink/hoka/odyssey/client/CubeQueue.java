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
 */
public final class CubeQueue {

    private CubeQueue() {}

    private static final class Entry {
        final ClientLevel level;
        final CubeReceiver.Parsed p;
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

    /** Пакет пришёл (главный поток). */
    static void add(ClientLevel level, CubeReceiver.Parsed p) {
        int[] gens = null;
        if (p.pos != null) {
            gens = new int[p.pos.length];
            if (!GEN.isEmpty())
                for (int i = 0; i < gens.length; i++) gens[i] = GEN.get(col(p.pos[i]));
            pending += p.pos.length;
        }
        Q.add(new Entry(level, p, gens, System.nanoTime()));
        if (Q.size() > maxLen) maxLen = Q.size();
    }

    private static long col(long sec) { return ChunkPos.asLong(SectionPos.x(sec), SectionPos.z(sec)); }

    /** Столбец заменён новым пакетом / забыт (главный поток): ждущие кубы старого столбца станут устаревшими. */
    public static void columnChanged(int x, int z) {
        CubeCache.columnChanged(x, z);                                 // 0.51: ждать кубы старого столбца нечего
        if (Q.isEmpty()) return;
        GEN.put(ChunkPos.asLong(x, z), ++counter);
    }

    /** Каждый кадр: применять, пока не вышел бюджет. */
    public static void run() {
        if (Q.isEmpty()) return;
        long now = System.nanoTime();
        Entry head = Q.peek();
        // ждёт дольше 300 мс (поток пакетов больше бюджета) — бюджет больше: задержка не растёт без предела
        // ждёт дольше 2 с (окно не в фокусе, 1–30 FPS) — почти всё: задержка и память очереди ограничены
        long wait = now - head.at;
        long budget = wait > 2_000_000_000L ? 50_000_000L
                : wait > 300_000_000L ? 4_000_000L : FrameWatch.budgetNs(0.25, 300_000L, 1_500_000L);
        drain(now + budget, null);
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
            if ((e.p.op != 2 && e.p.op != 4 && e.p.op != 13) || e.p.pos == null) continue;
            long[] ps = e.p.pos;
            for (int i = e.next; i < ps.length; i++) if (ps[i] == sec) { last = e; break; }
        }
        if (last != null) drain(Long.MAX_VALUE, last);
    }

    private static void drain(long end, Entry stop) {
        if (draining) return;                                          // повторный вход (правка блока изнутри применения)
        draining = true;
        try {
            Minecraft mc = Minecraft.getInstance();
            boolean first = true;
            while (!Q.isEmpty()) {
                Entry e = Q.peek();
                if (e.level != mc.level) { drop(e); if (e == stop) return; continue; }   // мир сменился
                CubeReceiver.Parsed p = e.p;
                switch (p.op) {
                    case 5, 6, 15, 16 -> { CubeReceiver.applySimple(e.level, p); Q.poll(); }
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
                        if (!e.peeked && p.op != 4) { e.peeked = true; lightAhead(e); }
                        int n = p.pos.length, from = e.next;
                        while (e.next < n) {
                            if (!first && System.nanoTime() >= end) break;
                            first = false;
                            int i = e.next++;
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
            if (Q.isEmpty()) { GEN.clear(); pending = 0; }
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
        if (e.p.pos != null) pending -= e.p.pos.length - e.next;
    }

    /** Мир сменился / вышли. */
    public static void clear() {
        Q.clear();
        GEN.clear();
        pending = 0;
    }

    public static String stats() {
        return "очередь кубов " + pending + " (пакетов " + Q.size() + ", макс " + maxLen + "), устаревших " + stale;
    }
}
