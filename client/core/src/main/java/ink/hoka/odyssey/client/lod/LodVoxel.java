package ink.hoka.odyssey.client.lod;

import ink.hoka.odyssey.client.GenPool;
import ink.hoka.odyssey.gen.GenArrayCube;
import ink.hoka.odyssey.gen.GenBounds;
import ink.hoka.odyssey.gen.PlanetGenerator;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Ближнее кольцо дальней прорисовки (уровень 0): плитка 32×32 блока из НАСТОЯЩИХ кубов генератора — те же блоки, что
 * игрок увидит, подойдя (деревья, валуны, обрывы): поэтому граница с обычной прорисовкой не бросается в глаза. Кубы
 * берутся сверху вниз до поверхности каждой колонки и ещё немного ниже самой низкой (стенки обрывов); сгенерированные
 * кладутся в дисковый кэш генерации — подойдёшь, они уже готовы.
 * <p>Сетка — грани блоков, открытые воздуху (листва с листвой склеивается, мелкие растения не рисуются), жадно
 * склеенные в прямоугольники одного блока и биома; текстура повторяется по блокам в шейдере. Вода — отдельный
 * полупрозрачный слой (только верх).</p>
 */
final class LodVoxel {

    private LodVoxel() {}

    static final int W = 32, CH = 2;                                          // плитка 32×32 = 2×2 столбца чанков
    private static final int MAX_CUBES = 40;                                  // на столбец чанка (горы — до 640 блоков)

    /** Кубы плитки: [столбец chunk 0..3][cy - cyLo] → блоки или null (воздух). */
    private static final class Vox {
        int cyLo, cyHi;
        final int[][][] cubes = new int[CH * CH][][];
        final int[][][] bio = new int[CH * CH][][];

        int get(int x, int y, int z) {
            if (x < 0 || z < 0 || x >= W || z >= W) return -1;
            int cy = y >> 4;
            if (cy < cyLo || cy > cyHi) return 0;
            int[][] col = cubes[(z >> 4) * CH + (x >> 4)];
            int[] c = col[cy - cyLo];
            return c == null ? 0 : c[GenArrayCube.index(x & 15, y & 15, z & 15)];
        }

        int biome(int x, int y, int z) {
            int[][] col = bio[(z >> 4) * CH + (x >> 4)];
            int cy = Math.max(cyLo, Math.min(cyHi, y >> 4));
            int[] b = col[cy - cyLo];
            if (b == null) return -1;
            return b[((y & 15) >> 2) << 4 | ((z & 15) >> 2) << 2 | ((x & 15) >> 2)];
        }
    }

    /** null — пусто. */
    static LodMesh build(PlanetGenerator g, GenBounds b, long seed, int x0, int z0, Holder<Biome>[] biomes) {
        Vox v = gather(g, b, seed, x0, z0);
        if (v == null) return null;
        return mesh(v, biomes);
    }

    private static Vox gather(PlanetGenerator g, GenBounds b, long seed, int x0, int z0) {
        int top = b.maxY() >> 4, bot = b.minY() >> 4;
        GenArrayCube c = new GenArrayCube(false);
        int[][][] tmp = new int[CH * CH][][];
        int[][][] tb = new int[CH * CH][][];
        int lowSurface = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        int[] lowCy = new int[CH * CH];
        // 1) сверху вниз до поверхности каждой колонки
        for (int q = 0; q < CH * CH; q++) {
            int cx = (x0 >> 4) + (q % CH), cz = (z0 >> 4) + (q / CH);
            tmp[q] = new int[top - bot + 1][];
            tb[q] = new int[top - bot + 1][];
            boolean[] found = new boolean[256];
            int left = 256, n = 0;
            lowCy[q] = top + 1;
            for (int cy = top; cy >= bot && left > 0 && n < MAX_CUBES; cy--) {
                if (!GenPool.lodCube(g, b, seed, cx, cy, cz, c)) {
                    if (n > 0 && left == 0) break;
                    continue;
                }
                n++;
                tmp[q][top - cy] = c.blocks.clone();
                tb[q][top - cy] = c.biomes.clone();
                hi = Math.max(hi, cy);
                lowCy[q] = cy;
                for (int k = 0; k < 256; k++) {
                    if (found[k]) continue;
                    for (int ly = 15; ly >= 0; ly--) {
                        int s = c.blocks[ly << 8 | k];
                        if (s != 0 && LodBlocks.info(s).kind == LodBlocks.SOLID) {
                            found[k] = true;
                            left--;
                            lowSurface = Math.min(lowSurface, (cy << 4) + ly);
                            break;
                        }
                    }
                }
            }
        }
        if (hi == Integer.MIN_VALUE) return null;
        // 2) все столбцы — до самой низкой поверхности плитки и ещё куб ниже (стенки обрывов между колонками)
        int floorCy = lowSurface == Integer.MAX_VALUE ? hi : Math.max(bot, (lowSurface >> 4) - 1);
        for (int q = 0; q < CH * CH; q++) {
            int cx = (x0 >> 4) + (q % CH), cz = (z0 >> 4) + (q / CH);
            int n = 0;
            for (int i = 0; i <= top - bot; i++) if (tmp[q][i] != null) n++;
            for (int cy = lowCy[q] - 1; cy >= floorCy && n < MAX_CUBES; cy--) {
                if (tmp[q][top - cy] != null) continue;
                if (!GenPool.lodCube(g, b, seed, cx, cy, cz, c)) continue;
                tmp[q][top - cy] = c.blocks.clone();
                tb[q][top - cy] = c.biomes.clone();
                n++;
            }
        }
        Vox v = new Vox();
        v.cyHi = hi;
        v.cyLo = floorCy;
        for (int q = 0; q < CH * CH; q++) {
            v.cubes[q] = new int[hi - floorCy + 1][];
            v.bio[q] = new int[hi - floorCy + 1][];
            for (int cy = floorCy; cy <= hi; cy++) {
                v.cubes[q][cy - floorCy] = tmp[q][top - cy];
                v.bio[q][cy - floorCy] = tb[q][top - cy];
            }
        }
        return v;
    }

    private static byte kind(int s) { return s <= 0 ? LodBlocks.SKIP : LodBlocks.info(s).kind; }

    private static LodMesh mesh(Vox v, Holder<Biome>[] biomes) {
        LodQuads o = new LodQuads(512), w = new LodQuads(32);
        int yLo = v.cyLo << 4, yHi = (v.cyHi << 4) + 15;
        int[] mask = new int[W * Math.max(W, yHi - yLo + 1)], bio = new int[mask.length];
        // грани по направлениям: срез за срезом, маска ключей (состояние × биом) → прямоугольники
        for (int face = 0; face < 6; face++) {
            int ax = face <= 1 ? 1 : face <= 3 ? 2 : 0;                        // ось нормали: 1 — Y, 2 — Z, 0 — X
            int dir = (face & 1) == 1 ? 1 : -1;                              // UP, SOUTH, EAST — «+»
            int lo = ax == 1 ? yLo : 0, hi = ax == 1 ? yHi : W - 1;
            for (int sl = lo; sl <= hi; sl++) {
                boolean any = false;
                // плоскость: для Y — (x, z); для Z — (x, y); для X — (z, y)
                int du = W, dv = ax == 1 ? W : (yHi - yLo + 1);
                for (int vv = 0; vv < dv; vv++) for (int uu = 0; uu < du; uu++) {
                    int x, y, z;
                    if (ax == 1) { x = uu; z = vv; y = sl; }
                    else if (ax == 2) { x = uu; y = yLo + vv; z = sl; }
                    else { z = uu; y = yLo + vv; x = sl; }
                    int s = v.get(x, y, z), key = 0;
                    byte k = kind(s);
                    int nx = x + (ax == 0 ? dir : 0), ny = y + (ax == 1 ? dir : 0), nz = z + (ax == 2 ? dir : 0);
                    int ns = v.get(nx, ny, nz);
                    if (k == LodBlocks.SOLID) {
                        if (ns < 0) key = s;                                  // край плитки — грань наружу
                        else if (kind(ns) != LodBlocks.SOLID) key = (ny < yLo) ? 0 : s;   // низ среза генерации не рисуем
                    } else if (k == LodBlocks.WATER && face == 1 && ns >= 0 && kind(ns) == LodBlocks.SKIP) key = -2;
                    if (key != 0) {
                        int bioId = v.biome(x, y, z);
                        key = key == -2 ? (0x40000000 | (bioId & 0xFFFF)) : key;
                        mask[vv * du + uu] = key;
                        bio[vv * du + uu] = bioId;
                        any = true;
                    } else mask[vv * du + uu] = 0;
                }
                if (!any) continue;
                emit(mask, bio, du, dv, face, ax, sl, yLo, o, w, biomes);
            }
        }
        LodMesh m = new LodMesh(o, w);
        return m.empty() ? null : m;
    }

    private static void emit(int[] mask, int[] bio, int du, int dv, int face, int ax, int sl, int yLo, LodQuads o, LodQuads w, Holder<Biome>[] biomes) {
        float shade = LodQuads.SHADE[face];
        for (int vv = 0; vv < dv; vv++) for (int uu = 0; uu < du; uu++) {
            int i = vv * du + uu, key = mask[i];
            if (key == 0) continue;
            int bi = bio[i];
            LodBlocks.Face f;
            boolean water = (key & 0x40000000) != 0;
            if (water) f = LodBlocks.info(LodMesh.waterState()).faces == null ? null : LodBlocks.info(LodMesh.waterState()).faces[1];
            else f = LodBlocks.info(key).faces[face];
            boolean tinted = f != null && f.tint() != LodBlocks.TINT_NONE;
            // ширина: тот же ключ (и биом, если оттенок)
            int e = uu + 1;
            while (e < du && mask[vv * du + e] == key && (!tinted || bio[vv * du + e] == bi)) e++;
            int h = vv + 1;
            grow:
            while (h < dv) {
                for (int q = uu; q < e; q++) {
                    int k2 = h * du + q;
                    if (mask[k2] != key || (tinted && bio[k2] != bi)) break grow;
                }
                h++;
            }
            for (int r = vv; r < h; r++) for (int q = uu; q < e; q++) mask[r * du + q] = 0;
            if (f == null) continue;
            int tint = LodBlocks.tintColor(water ? LodBlocks.TINT_WATER : f.tint(), bi, biomes);
            float[] m = LodQuads.mul(tint, water ? 1f : shade, 1, 1, 1);
            float u0 = uu, u1 = e, v0 = vv, v1 = h;
            LodQuads out = water ? w : o;
            switch (ax) {
                case 1 -> {                                                     // Y: (x = u, z = v)
                    float y = face == 1 ? sl + 1 - (water ? 0.125f : 0) : sl;
                    if (face == 1) out.quad(u0, y, v0, u0, y, v1, u1, y, v1, u1, y, v0, f, face, m[0], m[1], m[2], 1);
                    else out.quad(u0, y, v1, u0, y, v0, u1, y, v0, u1, y, v1, f, face, m[0], m[1], m[2], 1);
                }
                case 2 -> {                                                     // Z: (x = u, y = yLo + v)
                    float z = face == 3 ? sl + 1 : sl, ya = yLo + v0, yb = yLo + v1;
                    if (face == 3) out.quad(u1, yb, z, u0, yb, z, u0, ya, z, u1, ya, z, f, face, m[0], m[1], m[2], 1);
                    else out.quad(u0, yb, z, u1, yb, z, u1, ya, z, u0, ya, z, f, face, m[0], m[1], m[2], 1);
                }
                default -> {                                                    // X: (z = u, y = yLo + v)
                    float x = face == 5 ? sl + 1 : sl, ya = yLo + v0, yb = yLo + v1;
                    if (face == 5) out.quad(x, yb, u0, x, yb, u1, x, ya, u1, x, ya, u0, f, face, m[0], m[1], m[2], 1);
                    else out.quad(x, yb, u1, x, yb, u0, x, ya, u0, x, ya, u1, f, face, m[0], m[1], m[2], 1);
                }
            }
        }
    }
}
