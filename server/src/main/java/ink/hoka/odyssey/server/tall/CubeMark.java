package ink.hoka.odyssey.server.tall;

import net.minestom.server.instance.palette.Palette;

import java.util.BitSet;
import java.util.function.IntUnaryOperator;

/**
 * Отметка высот куба (CUBIC-CHUNKS.md §4.6 п.5, этап 4 шаг 13) — поле int записи куба (CubeCodec):
 * <ul>
 *   <li>биты 0–1 — где был skyTop его колонок при сохранении относительно полосы вклада куба [y0−1, y0+15]
 *       (блок только с нижней гранью на дне куба даёт y0−1): {@link #BELOW} — у всех ниже, {@link #ABOVE} — у всех
 *       выше, {@link #INSIDE} — иначе (внутри или вперемешку); {@link #NONE} — отметки нет (запись до шага 13);</li>
 *   <li>биты 2–4 — в кубе нет блоков, дающих высоту вида SKY / MOTION / SURFACE: спуск по высотам такой
 *       невыгруженный куб пропускает, а не считает «верх куба» (HeightIndex.Unknown).</li>
 * </ul>
 */
final class CubeMark {

    private CubeMark() {}

    static final int NONE = 0, BELOW = 1, INSIDE = 2, ABOVE = 3;
    private static final int CLEAR0 = 2;
    /** Тег заголовка столбца: биты «нет вклада» кубов на диске (3 вида через «;», long-и hex через «,»). */
    static final String TAG = "hclear";

    /** Отметка skyTop колонок (top(c), c = z<<4|x) относительно куба с низом y0. */
    static int rel(int y0, IntUnaryOperator top) {
        boolean below = true, above = true;
        for (int c = 0; c < 256; c++) {
            int t = top.applyAsInt(c);
            if (t >= y0 - 1) below = false;
            if (t <= y0 + 15) above = false;
            if (!below && !above) return INSIDE;
        }
        return below ? BELOW : above ? ABOVE : INSIDE;
    }

    static int rel(int mark) { return mark & 3; }

    /** Биты «нет вклада» по палитре блоков куба (null — пустой). */
    static int clearBits(Palette p) {
        if (p == null || p.count() == 0) return 7 << CLEAR0;
        boolean[] has = new boolean[3];
        var kinds = HeightIndex.Kind.values();
        int sv = p.singleValue();
        if (sv >= 0) mark(sv, has, kinds);
        else {
            int[] seen = new int[1];                                         // повтор того же id — пропуск
            seen[0] = -1;
            p.getAll((x, y, z, id) -> { if (id != seen[0]) { seen[0] = id; mark(id, has, kinds); } });
        }
        int v = 0;
        for (int k = 0; k < 3; k++) if (!has[k]) v |= 1 << (CLEAR0 + k);
        return v;
    }

    private static void mark(int id, boolean[] has, HeightIndex.Kind[] kinds) {
        if (id == 0) return;
        int inf = HeightIndex.info(id);
        for (int k = 0; k < 3; k++) if (!has[k] && HeightIndex.contrib(inf, kinds[k], 0) != HeightIndex.NONE) has[k] = true;
    }

    /** Полная отметка: относительно skyTop + «нет вклада». */
    static int of(int y0, IntUnaryOperator top, Palette blocks) { return rel(y0, top) | clearBits(blocks); }

    /** В кубе нет блоков с высотой вида k (только у отметки шага 13; старая запись — false). */
    static boolean clear(int mark, HeightIndex.Kind k) {
        return rel(mark) != NONE && ((mark >> (CLEAR0 + k.ordinal())) & 1) != 0;
    }

    // ---- маска колонок неба (тег hskycol) ----

    /** Тег заголовка: по кубам на диске — какие колонки (z<<4|x) содержат блок с высотой SKY. */
    static final String COLS_TAG = "hskycol";
    /** Предел длины тега (writeUTF ≤ 65535 байт): лишние кубы не пишем — по ним спуск с запасом, как раньше. */
    private static final int COLS_TAG_MAX = 60_000;

    /** Маска колонок куба с блоком, дающим высоту SKY: 4 long по 64 колонки; null — пустой куб. */
    static long[] skyCols(Palette p) {
        if (p == null || p.count() == 0) return null;
        long[] m = new long[4];
        int sv = p.singleValue();
        if (sv >= 0) {
            if (sv != 0 && HeightIndex.contrib(HeightIndex.info(sv), HeightIndex.Kind.SKY, 0) != HeightIndex.NONE) java.util.Arrays.fill(m, -1L);
            return m;
        }
        int[] last = {-1, 0};                                                // повтор того же id — без поиска по реестру
        p.getAll((x, y, z, id) -> {
            if (id == 0) return;
            int c = z << 4 | x;
            if ((m[c >> 6] & (1L << c)) != 0) return;
            boolean has;
            if (id == last[0]) has = last[1] != 0;
            else {
                has = HeightIndex.contrib(HeightIndex.info(id), HeightIndex.Kind.SKY, 0) != HeightIndex.NONE;
                last[0] = id;
                last[1] = has ? 1 : 0;
            }
            if (has) m[c >> 6] |= 1L << c;
        });
        return m;
    }

    static boolean full(long[] m) { return m[0] == -1L && m[1] == -1L && m[2] == -1L && m[3] == -1L; }

    /** Тег по маскам (куб → маска); полные маски не пишем (как «нет сведений»). null — писать нечего. */
    static String encodeCols(java.util.Map<Integer, long[]> m, BitSet on) {
        StringBuilder sb = new StringBuilder();
        int[] keys = m.keySet().stream().mapToInt(Integer::intValue).toArray();
        java.util.Arrays.sort(keys);
        for (int i : keys) {
            if (!on.get(i)) continue;
            long[] a = m.get(i);
            if (a == null || full(a)) continue;
            int mark = sb.length();
            if (sb.length() > 0) sb.append(';');
            sb.append(Integer.toHexString(i)).append(':');
            for (int j = 0; j < 4; j++) { if (j > 0) sb.append(','); sb.append(Long.toHexString(a[j])); }
            if (sb.length() > COLS_TAG_MAX) { sb.setLength(mark); break; }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Разбор тега масок; битый — пусто (спуск по таким кубам — с запасом). */
    static java.util.Map<Integer, long[]> decodeCols(String s, int cubes) {
        var r = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<long[]>();
        if (s == null || s.isEmpty()) return r;
        try {
            for (String e : s.split(";")) {
                int p = e.indexOf(':');
                int i = Integer.parseInt(e.substring(0, p), 16);
                String[] w = e.substring(p + 1).split(",");
                if (i < 0 || i >= cubes || w.length != 4) { r.clear(); return r; }
                long[] a = new long[4];
                for (int j = 0; j < 4; j++) a[j] = Long.parseUnsignedLong(w[j], 16);
                r.put(i, a);
            }
        } catch (RuntimeException ex) { r.clear(); }
        return r;
    }

    // ---- тег заголовка ----

    static String encodeTag(BitSet[] b) {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (int k = 0; k < b.length; k++) {
            if (k > 0) sb.append(';');
            long[] l = b[k].toLongArray();
            for (int j = 0; j < l.length; j++) { if (j > 0) sb.append(','); sb.append(Long.toHexString(l[j])); }
            if (l.length > 0) any = true;
        }
        return any ? sb.toString() : null;
    }

    /** Разбор тега; битый — null (как будто сводок нет: спуск по таким кубам — с запасом). */
    static BitSet[] decodeTag(String s, int cubes) {
        if (s == null || s.isEmpty()) return null;
        try {
            String[] parts = s.split(";", -1);
            if (parts.length != 3) return null;
            BitSet[] r = new BitSet[3];
            for (int k = 0; k < 3; k++) {
                if (parts[k].isEmpty()) { r[k] = new BitSet(); continue; }
                String[] w = parts[k].split(",");
                if (w.length > (cubes + 63) / 64) return null;
                long[] l = new long[w.length];
                for (int j = 0; j < w.length; j++) l[j] = Long.parseUnsignedLong(w[j], 16);
                r[k] = BitSet.valueOf(l);
            }
            return r;
        } catch (RuntimeException e) { return null; }
    }
}
