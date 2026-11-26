package ink.hoka.odyssey.gen;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Объект-шаблон для генерации (дерево, камень, куст…): палитра канонических состояний + непустые блоки в рамке
 * sx×sy×sz, якорь внутри рамки, блок-сущности (SNBT) и метаданные. Неизменяемый. Файл — {@link GenObjectCodec}.
 * <p>Блок i: смещение от якоря {@link #dx}/{@link #dy}/{@link #dz}, состояние {@code palette().get(paletteIndex(i))}.
 * Блоки — по возрастанию индекса рамки (y·sz + z)·sx + x.</p>
 */
public final class GenObject {

    private final String id, category, name;
    private final int sx, sy, sz, ax, ay, az;
    private final boolean keepAir;
    private final List<String> palette;
    private final int[] index, pal, dx, dy, dz;
    private final SortedMap<Integer, String> data;
    private final GenObjectMeta meta;
    private final int reach, minDy, maxDy;
    private final boolean hasData;

    GenObject(String id, int sx, int sy, int sz, int ax, int ay, int az, boolean keepAir, List<String> palette,
              int[] index, int[] pal, SortedMap<Integer, String> data, Map<String, Object> meta) {
        this.id = id;
        int sl = id.indexOf('/');
        this.category = sl < 0 ? "" : id.substring(0, sl);
        this.name = sl < 0 ? id : id.substring(sl + 1);
        this.sx = sx; this.sy = sy; this.sz = sz;
        this.ax = ax; this.ay = ay; this.az = az;
        this.keepAir = keepAir;
        this.palette = List.copyOf(palette);
        this.index = index;
        this.pal = pal;
        int n = index.length;
        dx = new int[n]; dy = new int[n]; dz = new int[n];
        int r = 0, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int v = index[i];
            int x = v % sx, z = (v / sx) % sz, y = v / (sx * sz);
            dx[i] = x - ax; dy[i] = y - ay; dz[i] = z - az;
            r = Math.max(r, Math.max(Math.abs(dx[i]), Math.abs(dz[i])));
            lo = Math.min(lo, dy[i]);
            hi = Math.max(hi, dy[i]);
        }
        reach = r;
        minDy = n == 0 ? 0 : lo;
        maxDy = n == 0 ? 0 : hi;
        this.data = Collections.unmodifiableSortedMap(new TreeMap<>(data));
        this.hasData = !this.data.isEmpty();
        this.meta = GenObjectMeta.of(meta, keepAir);
    }

    /** "кат/имя". */
    public String id() { return id; }
    public String category() { return category; }
    public String name() { return name; }
    public int sizeX() { return sx; }
    public int sizeY() { return sy; }
    public int sizeZ() { return sz; }
    /** Якорь в координатах рамки (может быть вне рамки). */
    public int anchorX() { return ax; }
    public int anchorY() { return ay; }
    public int anchorZ() { return az; }
    /** Воздух записан (пещерные объекты: прорезать проход). */
    public boolean keepAir() { return keepAir; }
    /** Канонические состояния, по алфавиту. */
    public List<String> palette() { return palette; }
    /** Блоков. */
    public int count() { return index.length; }
    public int dx(int i) { return dx[i]; }
    public int dy(int i) { return dy[i]; }
    public int dz(int i) { return dz[i]; }
    public int paletteIndex(int i) { return pal[i]; }
    /** Индекс блока в рамке: (y·sz + z)·sx + x. */
    public int boxIndex(int i) { return index[i]; }
    /** Блок-сущности: номер блока (0…count−1) → SNBT. */
    public SortedMap<Integer, String> data() { return data; }
    public boolean hasData() { return hasData; }
    public GenObjectMeta meta() { return meta; }

    /** max(|dx|, |dz|) по блокам — не меняется при повороте/отражении. */
    public int reach() { return reach; }
    /** Наименьший/наибольший dy. */
    public int minDy() { return minDy; }
    public int maxDy() { return maxDy; }
    /** max dy (над якорем). */
    public int up() { return maxDy; }
    /** max(−dy) + sink (под поверхностью). */
    public int down() { return Math.max(0, -minDy) + meta.sink(); }

    /** Тот же объект с другим якорем (координаты рамки). */
    public GenObject withAnchor(int nax, int nay, int naz) {
        GenObjectCodec.checkAnchor(nax, nay, naz);
        return new GenObject(id, sx, sy, sz, nax, nay, naz, keepAir, palette, index, pal, data, meta.raw());
    }

    /** Тот же объект с другими метаданными. */
    public GenObject withMeta(Map<String, Object> m) {
        return new GenObject(id, sx, sy, sz, ax, ay, az, keepAir, palette, index, pal, data, m);
    }

    /** Тот же объект с другим id ("кат/имя"). */
    public GenObject withId(String nid) {
        return new GenObject(nid, sx, sy, sz, ax, ay, az, keepAir, palette, index, pal, data, meta.raw());
    }

    // ───────────── поворот ─────────────

    /** Смещение x после отражения (mir: dx → −dx) и поворота rot×90° по часовой: (dx, dz) → (−dz, dx). */
    public static int rx(int dx, int dz, int rot, boolean mir) {
        if (mir) dx = -dx;
        return switch (rot & 3) { case 0 -> dx; case 1 -> -dz; case 2 -> -dx; default -> dz; };
    }

    public static int rz(int dx, int dz, int rot, boolean mir) {
        if (mir) dx = -dx;
        return switch (rot & 3) { case 0 -> dz; case 1 -> dx; case 2 -> -dz; default -> -dx; };
    }

    @Override public String toString() { return "GenObject[" + id + " " + sx + "×" + sy + "×" + sz + ", " + count() + " блоков]"; }
}
