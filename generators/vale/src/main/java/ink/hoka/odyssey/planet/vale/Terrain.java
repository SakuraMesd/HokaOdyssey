package ink.hoka.odyssey.planet.vale;

import ink.hoka.odyssey.gen.GenNoise;
import ink.hoka.odyssey.gen.GenRandom;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Рельеф «vale»: высота колонки, верхний блок, биом — чистые функции (x, z) от сида (только + − × / и floor).
 * Кэш по колонкам чанков (16×16, высоты с рамкой 1 для уклона): результат от порядка вызовов не зависит, гонка безвредна.
 */
final class Terrain {

    // верхний блок колонки
    static final byte GRASS = 0, SAND = 1, GRAVEL = 2, CLAY = 3, SNOW = 4, STONE = 5;
    static final String[] TOP_ID = {"minecraft:grass_block", "minecraft:sand", "minecraft:gravel", "minecraft:clay",
            "minecraft:snow_block", "minecraft:stone"};
    // зона (она же биом)
    static final byte OCEAN = 0, BEACH = 1, PLAINS = 2, FOREST = 3, BIRCH = 4, TAIGA = 5, SNOWY = 6, MEADOW = 7, LAKE = 8;
    static final String[] BIOME_ID = {"minecraft:ocean", "minecraft:beach", "minecraft:plains", "minecraft:forest",
            "minecraft:birch_forest", "minecraft:taiga", "minecraft:snowy_slopes", "minecraft:meadow", "minecraft:river"};
    /** Самая глубокая «мягкая» прослойка под верхом (песок 3 + песчаник 2): ниже h − 6 — только камень. */
    static final int SOFT = 6;

    private static final int CACHE = 2048;

    final int sea, bottom, top;
    final long seed;
    private final GenNoise cont, hill, det, mount, ridge, lake, forest, birch, meadow, jitter, glade;
    private final AtomicReferenceArray<Col> cache = new AtomicReferenceArray<>(CACHE);

    Terrain(long seed, int sea, int bottom, int top) {
        this.seed = seed;
        this.sea = sea;
        this.bottom = bottom;
        this.top = top;
        cont = noise(seed, 1); hill = noise(seed, 2); det = noise(seed, 3); mount = noise(seed, 4); ridge = noise(seed, 5);
        lake = noise(seed, 6); forest = noise(seed, 7); birch = noise(seed, 8); meadow = noise(seed, 9);
        jitter = noise(seed, 10); glade = noise(seed, 11);
    }

    private static GenNoise noise(long seed, int k) { return new GenNoise(GenRandom.mix(seed + 0x5A1E000L * k + k)); }

    static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    static double smooth(double t) { return t * t * (3 - 2 * t); }

    /** «Континентальность»: < 0 — море, дальше от 0 — глубже в сушу. */
    double continent(int x, int z) { return cont.fbm(x / 1100.0, z / 1100.0, 4, 0.5) * 1.7 + 0.2; }

    /** Высота рельефа (дробная) при известной континентальности c. */
    double heightD(int x, int z, double c) {
        double h = c < 0 ? sea + 1 + c * 75 : sea + 1 + c * 32;
        double land = clamp01(c * 4);
        double hl = hill.fbm(x / 230.0, z / 230.0, 4, 0.5);
        h += land * Math.max(0, hl + 0.12) * 62;
        double m = mount.fbm(x / 1500.0, z / 1500.0, 3, 0.5);
        double mm = smooth(clamp01((m - 0.1) * 3.5)) * land;
        if (mm > 0) {
            double r = clamp01(1 - Math.abs(ridge.fbm(x / 300.0, z / 300.0, 4, 0.5)) * 1.8);
            h += mm * (30 + 150 * r * r);
        }
        h += det.fbm(x / 36.0, z / 36.0, 2, 0.5) * (1.5 + 3 * land);
        double l = lake.noise(x / 120.0, z / 120.0);                     // озёра: впадины ниже моря на равнинах
        if (l > 0.36 && mm < 0.3) {
            double t = smooth(clamp01((l - 0.36) / 0.14)) * (1 - mm / 0.3) * clamp01((352 - h) / 25);
            h += (sea - 5 - h) * t;
        }
        return h;
    }

    int heightAt(int x, int z) {
        int h = (int) Math.floor(heightD(x, z, continent(x, z)));
        return Math.max(bottom + 8, Math.min(top - 40, h));
    }

    double snowLine(int x, int z) { return 455 + jitter.noise(x / 50.0, 3.3, z / 50.0) * 10; }

    double taigaLine(int x, int z) { return 385 + jitter.noise(x / 70.0, 7.7, z / 70.0) * 10; }

    /** Лесистость: > 0.08 — лес. */
    double forestness(int x, int z) { return forest.fbm(x / 280.0, z / 280.0, 3, 0.5); }

    /** Поляна в лесу. */
    boolean glade(int x, int z) { return glade.noise(x / 45.0, z / 45.0) > 0.42; }

    /** Пятно цветов на равнине. */
    boolean flowerPatch(int x, int z) { return jitter.noise(x / 14.0, 5.5, z / 14.0) > 0.38; }

    // ───────────── колонки чанка (кэш) ─────────────

    /** Колонки чанка: высоты 18×18 (с рамкой), верх, зона, уклон; min/max высоты 16×16. */
    static final class Col {
        final int cx, cz;
        final short[] h = new short[18 * 18];
        final byte[] top = new byte[256], zone = new byte[256], slope = new byte[256];
        int minH, maxH;

        Col(int cx, int cz) { this.cx = cx; this.cz = cz; }

        int h(int lx, int lz) { return h[(lz + 1) * 18 + lx + 1]; }
    }

    Col col(int cx, int cz) {
        int i = (int) (GenRandom.mix(((long) cx << 32) ^ (cz & 0xFFFFFFFFL)) & (CACHE - 1));
        Col c = cache.get(i);
        if (c != null && c.cx == cx && c.cz == cz) return c;
        c = compute(cx, cz);
        cache.set(i, c);
        return c;
    }

    int height(int x, int z) { return col(x >> 4, z >> 4).h(x & 15, z & 15); }

    byte top(int x, int z) { return col(x >> 4, z >> 4).top[(z & 15) << 4 | (x & 15)]; }

    byte zone(int x, int z) { return col(x >> 4, z >> 4).zone[(z & 15) << 4 | (x & 15)]; }

    int slope(int x, int z) { return col(x >> 4, z >> 4).slope[(z & 15) << 4 | (x & 15)]; }

    private Col compute(int cx, int cz) {
        Col c = new Col(cx, cz);
        int x0 = cx << 4, z0 = cz << 4;
        double[] cont = new double[256];
        for (int lz = -1; lz <= 16; lz++) for (int lx = -1; lx <= 16; lx++) {
            int x = x0 + lx, z = z0 + lz;
            double cc = continent(x, z);
            int h = (int) Math.floor(heightD(x, z, cc));
            h = Math.max(bottom + 8, Math.min(top - 40, h));
            c.h[(lz + 1) * 18 + lx + 1] = (short) h;
            if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16) cont[lz << 4 | lx] = cc;
        }
        int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int x = x0 + lx, z = z0 + lz, k = lz << 4 | lx;
            int h = c.h(lx, lz);
            mn = Math.min(mn, h);
            mx = Math.max(mx, h);
            int s = Math.max(Math.max(Math.abs(h - c.h(lx - 1, lz)), Math.abs(h - c.h(lx + 1, lz))),
                    Math.max(Math.abs(h - c.h(lx, lz - 1)), Math.abs(h - c.h(lx, lz + 1))));
            c.slope[k] = (byte) Math.min(100, s);
            byte t, zn;
            if (h < sea) {
                int depth = sea - h;
                if (depth <= 2) t = SAND;
                else if (jitter.noise(x / 11.0, 9.3, z / 11.0) > 0.33) t = CLAY;
                else t = det.noise(x / 9.0, 4.1, z / 9.0) > 0 ? GRAVEL : SAND;
                zn = cont[k] < 0.04 ? OCEAN : LAKE;
            } else if (h <= sea + 2 + (int) Math.floor(jitter.noise(x / 23.0, 1.1, z / 23.0) * 2)) {
                t = SAND;
                zn = BEACH;
            } else {
                double snow = snowLine(x, z);
                if (h >= snow) { t = s >= 7 ? STONE : SNOW; zn = SNOWY; }
                else {
                    if (s >= 6 || (s >= 4 && h > 345) || (s >= 3 && h > 430)) t = STONE;
                    else t = GRASS;
                    if (h >= taigaLine(x, z)) zn = TAIGA;
                    else if (forestness(x, z) > 0.08) zn = birch.noise(x / 350.0, z / 350.0) > 0.18 ? BIRCH : FOREST;
                    else zn = meadow.noise(x / 200.0, z / 200.0) > 0.25 ? MEADOW : PLAINS;
                }
            }
            c.top[k] = t;
            c.zone[k] = zn;
        }
        c.minH = mn;
        c.maxH = mx;
        return c;
    }
}
