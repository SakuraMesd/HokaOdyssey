package ink.hoka.odyssey.client.vessels.hud;

/**
 * Силуэт корабля сверху: треугольники модели (система корабля: нос +Z, левый борт +X, верх +Y) проецируются на
 * плоскость XZ и заливаются в маску; на холст — бледная заливка и светлый контур (в будущем — для повреждений).
 * Нос — вправо (плашка широкая и низкая — так силуэт крупнее), левый борт — вверх, как на карте сверху. Вписывается
 * в рамку с отступом, пропорции сохраняются.
 */
public final class Silhouette {

    private Silhouette() {}

    /** Холст w×h (пиксели экрана) с силуэтом; tris — по 9 чисел. Пустая модель — пустой холст. */
    public static Canvas draw(float[] tris, int w, int h, float gs) {
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

        Canvas m = new Canvas(w, h);                                            // маска: треугольники сплошные, перекрытия — не гуще 1
        float[] xs = new float[3], ys = new float[3];
        for (int i = 0; i + 8 < tris.length; i += 9) {
            for (int v = 0; v < 3; v++) {
                xs[v] = ox + tris[i + v * 3 + 2] * s;
                ys[v] = oy - tris[i + v * 3] * s;
            }
            float area = (xs[1] - xs[0]) * (ys[2] - ys[0]) - (xs[2] - xs[0]) * (ys[1] - ys[0]);
            if (Math.abs(area) < 0.02f) continue;                               // ребром к нам (стенки) — не видно сверху
            m.polygon(xs, ys, 3, 0xFFFFFF, 1);
        }
        float[] cov = new float[w * h], edge = new float[w * h];
        for (int i = 0; i < cov.length; i++) cov[i] = m.a[i];
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
        out.mask(cov, Hud.GRAY, 0.22f);
        out.mask(edge, Hud.WHITE, 0.9f);
        out.touch();
        return out;
    }
}
