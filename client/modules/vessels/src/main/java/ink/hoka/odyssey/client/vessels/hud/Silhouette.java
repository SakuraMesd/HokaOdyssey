package ink.hoka.odyssey.client.vessels.hud;

/**
 * Силуэт корабля сверху: треугольники модели (система корабля: нос +Z, левый борт +X, верх +Y) проецируются на
 * плоскость XZ и заливаются в маску; на холст — бледная заливка и светлый контур; с повреждениями — по зонам
 * (белый → янтарный → красный).
 * Нос — вправо (плашка широкая и низкая — так силуэт крупнее), левый борт — вверх, как на карте сверху. Вписывается
 * в рамку с отступом, пропорции сохраняются.
 */
public final class Silhouette {

    private Silhouette() {}

    /** Холст w×h (пиксели экрана) с силуэтом; tris — по 9 чисел. Пустая модель — пустой холст. */
    public static Canvas draw(float[] tris, int w, int h, float gs) { return draw(tris, w, h, gs, null, null); }

    /** Цвет зоны по уровню {@link #level}: белый, янтарный, красный. */
    public static int levelColor(int level) { return level == 0 ? Hud.WHITE : level == 1 ? Hud.AMBER : Hud.RED; }

    /** Уровень целости (как Zones.level): 0 — цела (> 66%), 1 — повреждена, 2 — разбита (≤ 33%). */
    public static int level(double health) { return health > 0.66 ? 0 : health > 0.33 ? 1 : 2; }

    /** Цвет по доле целости. */
    public static int color(double health) { return levelColor(level(health)); }

    /**
     * Силуэт по зонам: triZone — зона каждого треугольника, health — целость зон 0…1 (null — без повреждений, как
     * раньше): заливка и контур зоны — белые → янтарные → красные.
     */
    public static Canvas draw(float[] tris, int w, int h, float gs, int[] triZone, float[] health) {
        Canvas out = new Canvas(w, h);
        if (tris == null || tris.length < 9) return out;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i + 2 < tris.length; i += 3) {
            minX = Math.min(minX, tris[i]); maxX = Math.max(maxX, tris[i]);
            minZ = Math.min(minZ, tris[i + 2]); maxZ = Math.max(maxZ, tris[i + 2]);
        }
        float pad = Math.max(2, 2 * gs), sx = maxX - minX, sz = maxZ - minZ;
        if (!(sx > 1e-4f) || !(sz > 1e-4f)) return out;
        float s = Math.min((w - 2 * pad) / sz, (h - 2 * pad) / sx);
        float ox = w / 2f - (minZ + maxZ) / 2 * s, oy = h / 2f + (minX + maxX) / 2 * s;   // x экрана = ox + Z·s, y = oy − X·s

        boolean zoned = triZone != null && health != null && triZone.length * 9 <= tris.length;
        int nz = zoned ? health.length : 1;
        Canvas[] m = new Canvas[nz];                                            // маски зон: треугольники сплошные, перекрытия — не гуще 1
        for (int z = 0; z < nz; z++) m[z] = new Canvas(w, h);
        float[] xs = new float[3], ys = new float[3];
        for (int i = 0; i + 8 < tris.length; i += 9) {
            for (int v = 0; v < 3; v++) {
                xs[v] = ox + tris[i + v * 3 + 2] * s;
                ys[v] = oy - tris[i + v * 3] * s;
            }
            float area = (xs[1] - xs[0]) * (ys[2] - ys[0]) - (xs[2] - xs[0]) * (ys[1] - ys[0]);
            if (Math.abs(area) < 0.02f) continue;                               // ребром к нам (стенки) — не видно сверху
            int z = zoned ? Math.clamp(triZone[i / 9], 0, nz - 1) : 0;
            m[z].polygon(xs, ys, 3, 0xFFFFFF, 1);
        }
        float[] cov = new float[w * h], edge = new float[w * h];
        int[] zoneAt = new int[w * h];
        for (int i = 0; i < cov.length; i++) {
            for (int z = 0; z < nz; z++) if (m[z].a[i] > cov[i]) { cov[i] = Math.min(1, m[z].a[i]); zoneAt[i] = z; }
        }
        int rad = Math.max(1, Math.round(gs * 0.8f));                           // контур ~1 пиксель интерфейса
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float c = cov[y * w + x];
            if (c <= 0.02f) continue;
            float lo = 1;
            for (int dy = -rad; dy <= rad; dy++) for (int dx = -rad; dx <= rad; dx++) {
                int xx = x + dx, yy = y + dy;
                lo = Math.min(lo, xx < 0 || yy < 0 || xx >= w || yy >= h ? 0 : cov[yy * w + xx]);
            }
            edge[y * w + x] = Math.min(c, 1 - lo);
        }
        for (int y = 0; y < h - 1; y++) for (int x = 0; x < w; x++) {          // мягкая тень вниз
            float c = cov[y * w + x];
            if (c > 0) out.put(x, y + 1, Hud.DARK, 0.45f * c);
        }
        if (!zoned) {
            out.mask(cov, Hud.GRAY, 0.22f);
            out.mask(edge, Hud.WHITE, 0.9f);
        } else {
            int[] lv = new int[nz];
            for (int z = 0; z < nz; z++) lv[z] = level(health[z]);
            for (int i = 0; i < cov.length; i++) {
                if (cov[i] <= 0) continue;
                int l = lv[zoneAt[i]], x = i % w, y = i / w;
                out.put(x, y, l == 0 ? Hud.GRAY : levelColor(l), cov[i] * (l == 0 ? 0.22f : l == 1 ? 0.42f : 0.55f));
            }
            for (int i = 0; i < cov.length; i++) {
                if (edge[i] <= 0) continue;
                out.put(i % w, i / w, levelColor(lv[zoneAt[i]]), edge[i] * 0.9f);
            }
        }
        out.touch();
        return out;
    }
}
