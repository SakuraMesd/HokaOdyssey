package ink.hoka.odyssey.server.tall;

import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;

import java.util.Arrays;
import java.util.List;

/**
 * Высоты колонок столбца (CUBIC-CHUNKS.md §4.4, этап 4 шаг 4): на каждую из 256 колонок три значения —
 * <ul>
 *   <li>{@link Kind#SKY} ({@code skyTop}) — самый верхний Y, где небо уже не полное 15 (выше — свет неба 15);</li>
 *   <li>{@link Kind#MOTION} — верх блока, мешающего движению (как MotionBlockingHeightmap Minestom);</li>
 *   <li>{@link Kind#SURFACE} — верх любого не-воздуха (как WorldSurfaceHeightmap).</li>
 * </ul>
 * Значения — Y блока (не «Y+1», как у карт высот Minestom), {@link #NONE} — в колонке ничего нет.
 * Карты высот Minestom (motionBlocking/worldSurface) живут как раньше — это отдельный индекс для света и будущих
 * выгруженных кубов.
 * <p>
 * Предикат неба — как у игры (ChunkSkyLightSources 1.21): блок с lightBlock &gt; 0 (вода, листва, лёд, плиты…)
 * или с полной верхней гранью формы затенения — сам уже не источник ({@code skyTop = y}); блок только с полной
 * нижней гранью (снег-слой, грядка) — источник сам, но под ним темно ({@code skyTop = y − 1}).
 */
public final class HeightIndex {

    public enum Kind { SKY, MOTION, SURFACE }

    /** Нет блока в колонке. */
    public static final int NONE = TallWorld.MIN_Y - 1;

    private final short[] sky = new short[256], motion = new short[256], surface = new short[256];
    /** Счётчик правок высот MOTION (update/fix): сменился — разослать op 17 (CubeStreamer.heightsChanged). */
    int motionVer;

    public HeightIndex() {
        Arrays.fill(sky, (short) NONE);
        Arrays.fill(motion, (short) NONE);
        Arrays.fill(surface, (short) NONE);
    }

    private short[] arr(Kind k) { return k == Kind.SKY ? sky : k == Kind.MOTION ? motion : surface; }

    public int get(Kind k, int x, int z) { return arr(k)[(z & 15) << 4 | (x & 15)]; }

    /** skyTop колонки по индексу z<<4|x — для света. */
    int skyTop(int col) { return sky[col]; }

    // ---- предикаты по stateId (кэш: реестр Minestom на каждом блоке дорог) ----

    private static final int DONE = 1 << 31;
    private static final int LB_MASK = 0xF;                 // lightBlock 0…15
    private static final int SKY_SELF = 1 << 4, SKY_BELOW = 1 << 5, MOTION = 1 << 6, SURFACE = 1 << 7;
    private static final int FACE0 = 8;                     // биты 8…13 — грань формы затенения полная (BlockFace.ordinal)
    private static volatile int[] INFO = new int[1 << 15];

    /** Сведения о состоянии блока (lightBlock, предикаты, полные грани). */
    public static int info(int stateId) {
        int[] a = INFO;
        if (stateId >= 0 && stateId < a.length) {
            int v = a[stateId];
            if (v != 0) return v;
        }
        int v = compute(stateId);
        if (stateId >= 0) {
            if (stateId >= a.length) { a = Arrays.copyOf(a, Integer.highestOneBit(stateId) << 1); INFO = a; }
            a[stateId] = v;                                  // гонка безвредна: значение одно и то же
        }
        return v;
    }

    private static int compute(int stateId) {
        Block b = stateId == 0 ? Block.AIR : Block.fromStateId(stateId);
        if (b == null || b.isAir()) return DONE;
        var r = b.registry();
        int lb = Math.max(0, Math.min(15, r.lightBlocked()));
        int v = DONE | lb | SURFACE;
        boolean occ = r.occludes();
        if (occ) {
            var shape = r.occlusionShape();
            for (BlockFace f : BlockFace.values()) if (shape.isFaceFull(f)) v |= 1 << (FACE0 + f.ordinal());
        }
        if (lb > 0 || full(v, BlockFace.TOP)) v |= SKY_SELF;
        else if (full(v, BlockFace.BOTTOM)) v |= SKY_BELOW;
        // как MotionBlockingHeightmap.checkBlock Minestom
        if ((b.isSolid() && !b.compare(Block.COBWEB) && !b.compare(Block.BAMBOO_SAPLING)) || b.isLiquid()
                || "true".equals(b.getProperty("waterlogged"))) v |= MOTION;
        return v;
    }

    static int lightBlock(int info) { return info & LB_MASK; }

    /** Грань формы затенения полная (свет через неё не идёт). */
    static boolean full(int info, BlockFace f) { return (info & (1 << (FACE0 + f.ordinal()))) != 0; }

    /** Какую высоту даёт блок на Y для вида k: Y, Y−1 (небо: только нижняя грань) или NONE. */
    public static int contrib(int info, Kind k, int y) {
        return switch (k) {
            case SKY -> (info & SKY_SELF) != 0 ? y : (info & SKY_BELOW) != 0 ? y - 1 : NONE;
            case MOTION -> (info & MOTION) != 0 ? y : NONE;
            case SURFACE -> (info & SURFACE) != 0 ? y : NONE;
        };
    }

    // ---- расчёт ----

    /** Все колонки заново по палитрам (после загрузки/генерации). */
    public void recompute(List<Section> secs, int minSection) {
        Arrays.fill(sky, (short) NONE);
        Arrays.fill(motion, (short) NONE);
        Arrays.fill(surface, (short) NONE);
        int left = 3 * 256;
        boolean[] done = new boolean[3 * 256];
        for (int i = secs.size() - 1; i >= 0 && left > 0; i--) {
            var p = secs.get(i).blockPalette();
            if (p.count() == 0) continue;
            int base = (i + minSection) << 4;
            for (int ly = 15; ly >= 0 && left > 0; ly--) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int id = p.get(x, ly, z);
                if (id == 0) continue;
                int inf = info(id), c = z << 4 | x;
                for (Kind k : Kind.values()) {
                    int d = k.ordinal() * 256 + c;
                    if (done[d]) continue;
                    int h = contrib(inf, k, base + ly);
                    if (h == NONE) continue;
                    arr(k)[c] = (short) h;
                    done[d] = true;
                    left--;
                }
            }
        }
    }

    /**
     * Кубы, которых нет в памяти (шаг 10, CubeColumn с ленивой загрузкой): при спуске их палитру не смотрим, а
     * спрашиваем верх у столбца (генератор аналитически или «верх куба» для куба с диска — с запасом, уточнится
     * при загрузке куба, см. {@link #fix}).
     */
    interface Unknown {
        boolean unknown(int i);
        /** Верх колонки (x, z — 0…15) для вида k внутри куба i не выше fromY или NONE — дальше вниз. */
        int top(int i, int x, int z, int fromY, Kind k);
    }

    /** Высоты целиком (из заголовка столбца региона): [вид][z*16+x]. */
    void load(short[][] h) {
        for (Kind k : Kind.values()) System.arraycopy(h[k.ordinal()], 0, arr(k), 0, 256);
    }

    /** Высоты по генератору (столбец не на диске, кубы ещё не сгенерированы): cx, cz — столбец. */
    void fromGen(CubeGen g, int cx, int cz, int minY, int maxY) {
        for (Kind k : Kind.values()) {
            short[] a = arr(k);
            for (int c = 0; c < 256; c++) a[c] = (short) g.highest(cx * 16 + (c & 15), cz * 16 + (c >> 4), minY, maxY, k);
        }
    }

    /** Чего нет в вкладе куба i для вида k (CubeMark: «нет блоков с высотой этого вида»). */
    interface Clear {
        boolean clear(Kind k, int i);
        /** То же по одной колонке c (маска колонок неба); по умолчанию — как куб целиком. */
        default boolean clear(Kind k, int i, int c) { return clear(k, i); }
    }

    /**
     * Высоты по генератору, а кубы с диска не в памяти: верх колонки не ниже верха самого высокого такого куба
     * (с вкладом в вид k) — с запасом, как Unknown.top; точное значение даст fix при загрузке куба.
     */
    void raiseOver(java.util.BitSet disk, int minSection, Clear clear) {
        for (Kind k : Kind.values()) {
            short[] a = arr(k);
            for (int c = 0; c < 256; c++)                                    // по колонке: маска неба пропускает лишние
                for (int i = disk.length() - 1; i >= 0; i = disk.previousSetBit(i - 1)) {
                    if (!disk.get(i) || clear.clear(k, i, c)) continue;
                    int top = ((i + minSection) << 4) + 15;
                    if (a[c] < top) a[c] = (short) top;
                    break;
                }
        }
    }

    /** Блок на (x, y, z) сменился: рост — сразу, сломали верх — спуск по палитрам. */
    void update(List<Section> secs, int minSection, int x, int y, int z, int stateId) { update(secs, minSection, x, y, z, stateId, null); }

    void update(List<Section> secs, int minSection, int x, int y, int z, int stateId, Unknown u) {
        int inf = info(stateId), c = (z & 15) << 4 | (x & 15);
        for (Kind k : Kind.values()) {
            short[] a = arr(k);
            int cur = a[c], cand = contrib(inf, k, y);
            if (cand > cur) a[c] = (short) cand;
            else if (cand < cur && (cur == y || cur == y - 1)) a[c] = (short) descend(secs, minSection, x & 15, y + 1, z & 15, k, u);
            if (k == Kind.MOTION && a[c] != cur) motionVer++;
        }
    }

    /**
     * Куб i только что загружен (шаг 10): высоты, которые он задевает, — точно по его палитре. Блок куба выше
     * текущего верха — верх поднимается; верх лежал в кубе (с запасом или из заголовка) — спуск от куба заново.
     * true — что-то поменялось.
     */
    boolean fix(List<Section> secs, int minSection, int i, Unknown u) {
        int y0 = (i + minSection) << 4;
        var p = secs.get(i).blockPalette();
        boolean ch = false;
        for (Kind k : Kind.values()) {
            short[] a = arr(k);
            for (int c = 0; c < 256; c++) {
                int cur = a[c];
                if (cur > y0 + 15) continue;                                  // верх выше куба — куб его не трогает
                int t = p.count() == 0 ? NONE : inCube(p, c & 15, c >> 4, y0, k);
                int nv;
                if (t != NONE) nv = t;                                        // в кубе есть блок не ниже верха — он и верх
                else if (cur >= y0) nv = descend(secs, minSection, c & 15, y0 - 1, c >> 4, k, u);   // верх был в кубе (с запасом) — ниже
                else continue;
                if (nv != cur) { a[c] = (short) nv; ch = true; if (k == Kind.MOTION) motionVer++; }
            }
        }
        return ch;
    }

    /** Верх колонки в одной палитре куба (base — Y низа куба) или NONE. */
    private static int inCube(net.minestom.server.instance.palette.Palette p, int x, int z, int base, Kind k) {
        for (int ly = 15; ly >= 0; ly--) {
            int id = p.get(x, ly, z);
            if (id == 0) continue;
            int h = contrib(info(id), k, base + ly);
            if (h != NONE) return h;
        }
        return NONE;
    }

    /** Верх колонки для вида k, начиная с fromY вниз (пустые секции — целиком мимо). */
    static int descend(List<Section> secs, int minSection, int x, int fromY, int z, Kind k) { return descend(secs, minSection, x, fromY, z, k, null); }

    static int descend(List<Section> secs, int minSection, int x, int fromY, int z, Kind k, Unknown u) {
        int top = Math.min(fromY, ((minSection + secs.size()) << 4) - 1);
        for (int i = (top >> 4) - minSection; i >= 0; i--) {
            if (u != null && u.unknown(i)) {
                int h = u.top(i, x, z, top, k);
                if (h != NONE) return h;
                continue;
            }
            var p = secs.get(i).blockPalette();
            if (p.count() == 0) continue;
            int base = (i + minSection) << 4;
            for (int ly = Math.min(15, top - base); ly >= 0; ly--) {
                int id = p.get(x, ly, z);
                if (id == 0) continue;
                int h = contrib(info(id), k, base + ly);
                if (h != NONE) return h;
            }
        }
        return NONE;
    }
}
