package ink.hoka.odyssey.client.lod;

import ink.hoka.odyssey.gen.GenBounds;
import ink.hoka.odyssey.gen.LodTile;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Сетка плитки дальней прорисовки: непрозрачная часть (земля, кроны, склоны) и вода (полупрозрачная, отдельным
 * проходом — сквозь мелководье видно дно, как у настоящей воды). Формат {@link LodQuads}. Память вне кучи
 * ({@link #free}).
 * <p>Дальние ячейки (2…64 блока, {@link #heightmap}): столбики с текстурой верхнего блока, повторённой по каждому блоку.
 * Лес издалека — смесь земли и крон по доле площади под кронами (из генератора): цвет ячейки = средний цвет такой
 * смеси (как его видит игра в мипмапах вдали), а при густом лесе ячейка поднимается до крон.</p>
 */
final class LodMesh {

    final ByteBuffer opaque, water;
    final int opaqueQuads, waterQuads;
    final float yMin, yMax;

    LodMesh(LodQuads o, LodQuads w) {
        opaqueQuads = o.quads;
        waterQuads = w.quads;
        float lo = Math.min(o.yMin, w.yMin), hi = Math.max(o.yMax, w.yMax);
        yMin = lo == Float.MAX_VALUE ? 0 : lo;
        yMax = hi == -Float.MAX_VALUE ? 0 : hi;
        opaque = o.finish();
        water = w.finish();
    }

    boolean empty() { return opaque == null && water == null; }

    void free() {
        if (opaque != null) MemoryUtil.memFree(opaque);
        if (water != null) MemoryUtil.memFree(water);
    }

    private static final float WATER_DROP = 0.125f;

    /** null — в плитке ничего нет. */
    static LodMesh heightmap(LodTile t, Holder<Biome>[] biomes, GenBounds bounds) {
        int n = t.n, s = t.step, nn = n * n;
        float[] y = new float[nn];
        LodBlocks.Face[] top = new LodBlocks.Face[nn], side = new LodBlocks.Face[nn];
        int[] col = new int[nn], sideCol = new int[nn];                     // RGB множители ×128 (0…2 → 0…255)
        boolean[] has = new boolean[nn];
        float[] wy = new float[nn];
        int[] wcol = new int[nn];
        LodBlocks.Face waterFace = LodBlocks.info(waterState()).faces == null ? null : LodBlocks.info(waterState()).faces[1];
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            int k = j * n + i;
            wy[k] = Float.NaN;
            if (bounds != null && !bounds.columnInside(t.cx(i), t.cz(j))) continue;
            LodBlocks.Info g = LodBlocks.info(t.state[k]);
            if (g.kind != LodBlocks.SOLID) continue;
            int bio = t.biome[k];
            LodBlocks.Face gt = g.faces[1], gs = g.faces[2];
            int gTint = LodBlocks.tintColor(gt.tint(), bio, biomes), gsTint = LodBlocks.tintColor(gs.tint(), bio, biomes);
            int gAvg = LodBlocks.mulRgb(gt.avg(), gTint);
            double c = t.cover(k);
            float h = t.top[k] + 1;
            LodBlocks.Face ft = gt, fs = gs;
            int tint = gTint, sTint = gsTint, baseAvg = gAvg, sideAvg = LodBlocks.mulRgb(gs.avg(), gsTint), target = gAvg;
            if (c > 0.02 && t.canopyState[k] != 0) {
                LodBlocks.Info l = LodBlocks.info(t.canopyState[k]);
                if (l.kind == LodBlocks.SOLID) {
                    int lTint = LodBlocks.tintColor(l.faces[1].tint(), bio, biomes);
                    int lAvg = LodBlocks.mulRgb(l.faces[1].avg(), lTint);
                    target = mix(gAvg, lAvg, (float) c);
                    if (c >= 0.45 && t.canopy[k] > t.top[k]) {               // густо — ячейка до крон, цвет — смесь
                        h = t.top[k] + 1 + Math.round((t.canopy[k] - t.top[k]) * 0.85f);
                        ft = l.faces[1];
                        fs = l.faces[2];
                        tint = lTint;
                        sTint = LodBlocks.tintColor(fs.tint(), bio, biomes);
                        baseAvg = lAvg;
                        sideAvg = LodBlocks.mulRgb(fs.avg(), sTint);
                    }
                }
            }
            top[k] = ft;
            side[k] = fs;
            col[k] = pack(tint, ratio(target, baseAvg));
            sideCol[k] = pack(sTint, ratio(target == gAvg ? sideAvg : mix(sideAvg, target, 0.5f), sideAvg));
            y[k] = h;
            has[k] = true;
            if (t.water[k] != LodTile.NO_WATER && t.water[k] >= t.top[k] && waterFace != null) {
                wy[k] = t.water[k] + 1 - WATER_DROP;
                wcol[k] = LodBlocks.tintColor(LodBlocks.TINT_WATER, bio, biomes);
            }
        }
        LodQuads o = new LodQuads(n * 6), w = new LodQuads(16);
        float skirt = s * 3 + 8;
        // верх: жадно прямоугольниками одинаковой высоты, текстуры и цвета
        boolean[] done = new boolean[nn];
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            int k = j * n + i;
            if (!has[k] || done[k]) continue;
            int e = i + 1;
            while (e < n && same(has, done, y, top, col, k, j * n + e)) e++;
            int f = j + 1;
            grow:
            while (f < n) {
                for (int q = i; q < e; q++) if (!same(has, done, y, top, col, k, f * n + q)) break grow;
                f++;
            }
            for (int r = j; r < f; r++) for (int q = i; q < e; q++) done[r * n + q] = true;
            float x0 = i * s, x1 = e * s, z0 = j * s, z1 = f * s, yk = y[k];
            float[] m = unpack(col[k], 1f);
            o.quad(x0, yk, z0, x0, yk, z1, x1, yk, z1, x1, yk, z0, top[k], 1, m[0], m[1], m[2], 1);
        }
        // бока: к соседу ниже (у пустоты и края плитки — юбка вниз)
        for (int k = 0; k < nn; k++) {
            if (!has[k]) continue;
            int i = k % n, j = k / n;
            float yk = y[k], x0 = i * s, x1 = (i + 1) * s, z0 = j * s, z1 = (j + 1) * s;
            float b;
            b = i + 1 < n && has[k + 1] ? y[k + 1] : yk - skirt;
            if (b < yk) { float[] m = unpack(sideCol[k], LodQuads.SHADE[5]); o.quad(x1, yk, z0, x1, yk, z1, x1, b, z1, x1, b, z0, side[k], 5, m[0], m[1], m[2], 1); }
            b = i > 0 && has[k - 1] ? y[k - 1] : yk - skirt;
            if (b < yk) { float[] m = unpack(sideCol[k], LodQuads.SHADE[4]); o.quad(x0, yk, z1, x0, yk, z0, x0, b, z0, x0, b, z1, side[k], 4, m[0], m[1], m[2], 1); }
            b = j + 1 < n && has[k + n] ? y[k + n] : yk - skirt;
            if (b < yk) { float[] m = unpack(sideCol[k], LodQuads.SHADE[3]); o.quad(x1, yk, z1, x0, yk, z1, x0, b, z1, x1, b, z1, side[k], 3, m[0], m[1], m[2], 1); }
            b = j > 0 && has[k - n] ? y[k - n] : yk - skirt;
            if (b < yk) { float[] m = unpack(sideCol[k], LodQuads.SHADE[2]); o.quad(x0, yk, z0, x1, yk, z0, x1, b, z0, x0, b, z0, side[k], 2, m[0], m[1], m[2], 1); }
        }
        // вода: по рядам одинакового уровня и цвета
        for (int j = 0; j < n; j++) {
            int i = 0;
            while (i < n) {
                int k = j * n + i;
                if (Float.isNaN(wy[k])) { i++; continue; }
                int e = i + 1;
                while (e < n && wy[j * n + e] == wy[k] && wcol[j * n + e] == wcol[k]) e++;
                float[] m = LodQuads.mul(wcol[k], 1f, 1, 1, 1);
                w.quad(i * s, wy[k], j * s, i * s, wy[k], (j + 1) * s, e * s, wy[k], (j + 1) * s, e * s, wy[k], j * s, waterFace, 1, m[0], m[1], m[2], 1);
                i = e;
            }
        }
        LodMesh mesh = new LodMesh(o, w);
        if (mesh.empty()) return null;
        return mesh;
    }

    private static int waterState = -1;

    static int waterState() {
        if (waterState < 0) waterState = net.minecraft.world.level.block.Block.getId(net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        return waterState;
    }

    private static boolean same(boolean[] has, boolean[] done, float[] y, LodBlocks.Face[] top, int[] col, int a, int b) {
        return has[b] && !done[b] && y[b] == y[a] && top[b] == top[a] && col[b] == col[a];
    }

    /** Смесь цветов RGB: a → b на долю t. */
    static int mix(int a, int b, float t) {
        int r = Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    /** Во сколько раз поменять цвет base, чтобы в среднем вышел target: по каналам, 0…2 (×64 → 0…128 в байте). */
    private static int ratio(int target, int base) {
        int r = q((target >> 16 & 255) / (float) Math.max(8, base >> 16 & 255));
        int g = q((target >> 8 & 255) / (float) Math.max(8, base >> 8 & 255));
        int b = q((target & 255) / (float) Math.max(8, base & 255));
        return r << 16 | g << 8 | b;
    }

    private static int q(float v) { return Math.max(0, Math.min(255, Math.round(Math.min(2f, v) * 64 / 4) * 4)); }

    /** Оттенок × смешивание в одно число (ключ склейки): оттенок RGB24 и смешивание RGB по 8 бит — храним как пару. */
    private static int pack(int tint, int ratio) {
        // оттенок и смешивание перемножаем сразу (0…2 на канал, шаг 1/64) — склеиваются ячейки с одинаковым итогом
        int r = Math.min(255, Math.round((tint >> 16 & 255) / 255f * (ratio >> 16 & 255)));
        int g = Math.min(255, Math.round((tint >> 8 & 255) / 255f * (ratio >> 8 & 255)));
        int b = Math.min(255, Math.round((tint & 255) / 255f * (ratio & 255)));
        return r << 16 | g << 8 | b;
    }

    /** Ключ цвета → множители 0…2 с учётом света грани. */
    private static float[] unpack(int c, float shade) {
        return new float[]{(c >> 16 & 255) / 64f * shade, (c >> 8 & 255) / 64f * shade, (c & 255) / 64f * shade};
    }
}
