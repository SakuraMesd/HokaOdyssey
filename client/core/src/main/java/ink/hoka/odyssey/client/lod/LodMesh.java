package ink.hoka.odyssey.client.lod;

import ink.hoka.odyssey.gen.GenBounds;
import ink.hoka.odyssey.gen.LodTile;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Сетка плитки дальней прорисовки: столбики-«пиксели» ячеек. Верх — прямоугольники с объединением соседей одной высоты
 * и цвета по рядам; бока — там, где сосед ниже (цвет боковой грани блока, темнее по стороне, как у ванильного света);
 * по краю плитки — «юбка» вниз, чтобы между плитками разной детальности не было щелей. Формат — POSITION_COLOR
 * (3 float + RGBA), координаты от угла плитки (x0, 0, z0). Строится в фоне, память — вне кучи (освободить {@link #free}).
 */
final class LodMesh {

    final ByteBuffer data;
    final int quads;
    final float yMin, yMax;

    private LodMesh(ByteBuffer data, int quads, float yMin, float yMax) {
        this.data = data;
        this.quads = quads;
        this.yMin = yMin;
        this.yMax = yMax;
    }

    void free() { if (data != null) MemoryUtil.memFree(data); }

    private static final float SHADE_NS = 0.80f, SHADE_EW = 0.62f, WATER_DROP = 0.125f;

    /** null — в плитке ничего нет. */
    static LodMesh build(LodTile t, Holder<Biome>[] biomes, GenBounds bounds) {
        int n = t.n, s = t.step;
        float[] y = new float[n * n];
        int[] top = new int[n * n], side = new int[n * n];
        boolean[] has = new boolean[n * n];
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            int k = j * n + i;
            if (bounds != null && !bounds.columnInside(t.cx(i), t.cz(j))) continue;
            boolean wet = t.water[k] != LodTile.NO_WATER && t.water[k] >= t.top[k];
            if (wet) {
                int w = LodColors.water(t.biome[k], biomes);
                top[k] = w;
                side[k] = LodColors.mul(w, 0.85f);
                y[k] = t.water[k] + 1 - WATER_DROP;
            } else {
                if (t.state[k] == 0) continue;
                long c = LodColors.of(t.state[k], t.biome[k], biomes);
                if (c == 0) continue;
                top[k] = (int) (c >>> 32);
                side[k] = (int) c;
                y[k] = t.top[k] + 1;
            }
            has[k] = true;
            lo = Math.min(lo, y[k]);
            hi = Math.max(hi, y[k]);
        }
        if (lo == Float.MAX_VALUE) return null;
        float skirt = s * 3 + 8;
        Out o = new Out(n * 8 + 64);
        // верх: жадно прямоугольниками (ширина по X, затем вниз по Z, пока ряд такой же) — равнины и вода одним куском
        boolean[] done = new boolean[n * n];
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            int k = j * n + i;
            if (!has[k] || done[k]) continue;
            int e = i + 1;
            while (e < n && same(has, done, y, top, k, j * n + e)) e++;
            int f = j + 1;
            grow:
            while (f < n) {
                for (int q = i; q < e; q++) if (!same(has, done, y, top, k, f * n + q)) break grow;
                f++;
            }
            for (int r = j; r < f; r++) for (int q = i; q < e; q++) done[r * n + q] = true;
            float x0 = i * s, x1 = e * s, z0 = j * s, z1 = f * s;
            o.quad(x0, y[k], z0, x0, y[k], z1, x1, y[k], z1, x1, y[k], z0, top[k]);
        }
        // бока: к соседу ниже (или к пустоте/краю плитки — юбка вниз); одинаковые подряд вдоль ребра — одним куском
        // восток (+X) и запад (−X): вдоль Z
        for (int dir = 0; dir < 2; dir++) for (int i = 0; i < n; i++) {
            int j = 0;
            while (j < n) {
                int k = j * n + i;
                float b = bottom(has, y, n, i, j, dir == 0 ? 1 : -1, 0, skirt);
                if (!has[k] || b >= y[k]) { j++; continue; }
                int c = LodColors.mul(side[k], SHADE_EW);
                int e = j + 1;
                while (e < n) {
                    int q = e * n + i;
                    if (!has[q] || y[q] != y[k] || side[q] != side[k] || bottom(has, y, n, i, e, dir == 0 ? 1 : -1, 0, skirt) != b) break;
                    e++;
                }
                float x = (dir == 0 ? i + 1 : i) * s, z0 = j * s, z1 = e * s, yk = y[k];
                if (dir == 0) o.quad(x, yk, z0, x, yk, z1, x, b, z1, x, b, z0, c);
                else o.quad(x, yk, z1, x, yk, z0, x, b, z0, x, b, z1, c);
                j = e;
            }
        }
        // юг (+Z) и север (−Z): вдоль X
        for (int dir = 0; dir < 2; dir++) for (int j = 0; j < n; j++) {
            int i = 0;
            while (i < n) {
                int k = j * n + i;
                float b = bottom(has, y, n, i, j, 0, dir == 0 ? 1 : -1, skirt);
                if (!has[k] || b >= y[k]) { i++; continue; }
                int c = LodColors.mul(side[k], SHADE_NS);
                int e = i + 1;
                while (e < n) {
                    int q = j * n + e;
                    if (!has[q] || y[q] != y[k] || side[q] != side[k] || bottom(has, y, n, e, j, 0, dir == 0 ? 1 : -1, skirt) != b) break;
                    e++;
                }
                float z = (dir == 0 ? j + 1 : j) * s, x0 = i * s, x1 = e * s, yk = y[k];
                if (dir == 0) o.quad(x1, yk, z, x0, yk, z, x0, b, z, x1, b, z, c);
                else o.quad(x0, yk, z, x1, yk, z, x1, b, z, x0, b, z, c);
                i = e;
            }
        }
        if (o.quads == 0) { o.free(); return null; }
        return new LodMesh(o.finish(), o.quads, lo - skirt, hi);
    }

    private static boolean same(boolean[] has, boolean[] done, float[] y, int[] top, int a, int b) {
        return has[b] && !done[b] && y[b] == y[a] && top[b] == top[a];
    }

    /** Низ боковой грани ячейки (i, j) в сторону (dx, dz): верх соседа, а за краем/у пустоты — юбка вниз. */
    private static float bottom(boolean[] has, float[] y, int n, int i, int j, int dx, int dz, float skirt) {
        int k = j * n + i;
        if (!has[k]) return Float.MAX_VALUE;
        int a = i + dx, b = j + dz;
        if (a < 0 || b < 0 || a >= n || b >= n || !has[b * n + a]) return y[k] - skirt;
        return y[b * n + a];
    }

    /** Вершины POSITION_COLOR (16 байт) в памяти вне кучи, растёт по мере надобности. */
    private static final class Out {
        ByteBuffer b;
        int quads;

        Out(int quadsHint) { b = MemoryUtil.memAlloc(Math.max(64, quadsHint) * 64); }

        void quad(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz, int argb) {
            if (b.remaining() < 64) {
                ByteBuffer nb = MemoryUtil.memRealloc(b, b.capacity() * 2);
                b = nb;
            }
            int abgr = argb & 0xFF00FF00 | (argb >> 16 & 0xFF) | (argb & 0xFF) << 16;   // в памяти: R, G, B, A
            v(ax, ay, az, abgr); v(bx, by, bz, abgr); v(cx, cy, cz, abgr); v(dx, dy, dz, abgr);
            quads++;
        }

        private void v(float x, float y, float z, int abgr) {
            b.putFloat(x).putFloat(y).putFloat(z).putInt(abgr);
        }

        ByteBuffer finish() {
            b.flip();
            return b;
        }

        void free() { MemoryUtil.memFree(b); }
    }
}
