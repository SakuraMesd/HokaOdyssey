package ink.hoka.odyssey.planet.vale;

import ink.hoka.odyssey.gen.GenNoise;
import ink.hoka.odyssey.gen.GenRandom;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Рельеф «vale» v2 — карта высот (без навесов): равнины и холмы 300–480, пояса хребтов (ridged multifractal) с пиками
 * до ~1950, террасы-обрывы, мезы, тепуи (столовые горы 760–1060), разломы глубиной 150–450, реки, озёра, топи.
 * Всё — чистые функции (x, z) от сида: только + − × /, floor, abs и StrictMath.sqrt. Кэш по колонкам чанков (высоты
 * 18×18 с рамкой): от порядка вызовов не зависит, гонка безвредна.
 */
final class Terrain {

    // верхний блок колонки
    static final byte GRASS = 0, SAND = 1, GRAVEL = 2, CLAY = 3, SNOW = 4, STONE = 5, PODZOL = 6, COARSE = 7, MYCELIUM = 8,
            PALE_MOSS = 9, MOSS = 10, MUD = 11, RED_SAND = 12, ICE = 13, BASALT = 14, MAGMA = 15, LAVA = 16, TUFF = 17;
    static final String[] TOP_ID = {"minecraft:grass_block", "minecraft:sand", "minecraft:gravel", "minecraft:clay",
            "minecraft:snow_block", "minecraft:stone", "minecraft:podzol", "minecraft:coarse_dirt", "minecraft:mycelium",
            "minecraft:pale_moss_block", "minecraft:moss_block", "minecraft:mud", "minecraft:red_sand", "minecraft:packed_ice",
            "minecraft:basalt", "minecraft:magma_block", "minecraft:lava", "minecraft:tuff"};

    // зона (она же биом)
    static final byte OCEAN = 0, BEACH = 1, RIVER = 2, LAKE = 3, PLAINS = 4, MEADOW = 5, FLOWER = 6, FOREST = 7, BIRCH = 8,
            TAIGA = 9, REDWOOD = 10, CHERRY = 11, SWAMP = 12, GLOW = 13, SAVANNA = 14, MESA = 15, JUNGLE = 16, PALE = 17,
            ALPINE = 18, GROVE = 19, JAGGED = 20, FROZEN = 21, STONY = 22, RIFT = 23;
    static final String[] BIOME_ID = {"minecraft:ocean", "minecraft:beach", "minecraft:river", "minecraft:river",
            "minecraft:plains", "minecraft:meadow", "minecraft:flower_forest", "minecraft:forest", "minecraft:birch_forest",
            "minecraft:taiga", "minecraft:old_growth_spruce_taiga", "minecraft:cherry_grove", "minecraft:mangrove_swamp",
            "minecraft:mushroom_fields", "minecraft:savanna_plateau", "minecraft:wooded_badlands", "minecraft:jungle",
            "minecraft:pale_garden", "minecraft:meadow", "minecraft:grove", "minecraft:jagged_peaks", "minecraft:frozen_peaks",
            "minecraft:stony_peaks", "minecraft:stony_shore"};
    static final String[] ZONE_NAME = {"ocean", "beach", "river", "lake", "plains", "meadow", "flower", "forest", "birch",
            "taiga", "redwood", "cherry", "swamp", "glow", "savanna", "mesa", "jungle", "pale", "alpine", "grove", "jagged",
            "frozen", "stony", "rift"};

    /**
     * Самая глубокая «мягкая» прослойка под верхом (лёд до 8, терракота мез до 8): ниже h − 9 — только камень.
     * Слои пород — только от «минимум соседей − 1» до h. Значит, ниже minH18 − SOFT колонка — сплошной камень.
     */
    static final int SOFT = 9;
    /** Снеговая линия (± шум 60), выше 1600 — снег везде, кроме стен. */
    static final int SNOW_LINE = 1150, GLACIER = 1250;

    private static final int CACHE = 4096;

    final int sea, bottom, top;
    final long seed;
    private final GenNoise cont, hill, hillAmp, det, lake, forest, birch, meadow, jitter, glade, warpA, warpB, beltN, ridgeN,
            cliffN, cliffT, plateauN, plateauP, riftN, riftW, riftM, riftD, riverN, temp, humid, magic, mesaN, cherryN, strataN,
            iceN;
    private final AtomicReferenceArray<Col> cache = new AtomicReferenceArray<>(CACHE);

    Terrain(long seed, int sea, int bottom, int top) {
        this.seed = seed;
        this.sea = sea;
        this.bottom = bottom;
        this.top = top;
        cont = noise(seed, 1); hill = noise(seed, 2); det = noise(seed, 3); hillAmp = noise(seed, 4);
        lake = noise(seed, 6); forest = noise(seed, 7); birch = noise(seed, 8); meadow = noise(seed, 9);
        jitter = noise(seed, 10); glade = noise(seed, 11);
        warpA = noise(seed, 12); warpB = noise(seed, 13); beltN = noise(seed, 14); ridgeN = noise(seed, 15);
        cliffN = noise(seed, 16); cliffT = noise(seed, 17); plateauN = noise(seed, 18); plateauP = noise(seed, 19);
        riftN = noise(seed, 20); riftW = noise(seed, 21); riftM = noise(seed, 22); riftD = noise(seed, 23);
        riverN = noise(seed, 24); temp = noise(seed, 25); humid = noise(seed, 26); magic = noise(seed, 27);
        mesaN = noise(seed, 28); cherryN = noise(seed, 29); strataN = noise(seed, 30); iceN = noise(seed, 31);
    }

    private static GenNoise noise(long seed, int k) { return new GenNoise(GenRandom.mix(seed + 0x5A1E000L * k + k)); }

    static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    static double smooth(double t) { return t * t * (3 - 2 * t); }

    // ───────────── точка рельефа ─────────────

    /** Всё о точке (x, z): высота и маски — для зоны, верха и объектов. */
    static final class S {
        double h, c, belt, r, rift, river, swamp, mesa, tepui, lakeT, tp, hu;
    }

    // ───────────── медленные поля: на сетке 4×4 блока, между узлами — билинейно ─────────────

    /** Шаг сетки медленных полей (узлы — в мировых координатах, кратных 4: одинаково из любого чанка). */
    static final int GS = 4;
    private static final int C = 0, TP = 1, HU = 2, HA = 3, WX = 4, WZ = 5, BN = 6, MESA_F = 7, CLIFF = 8, CLIFFT = 9, PLAT = 10,
            PLATP = 11, RIVER_F = 12, RIFTM = 13, RIFTN = 14, RIFTW = 15, RIFTD = 16, NF = 17;

    /** Медленные поля в узле (x, z). */
    private void slow(int x, int z, double[] f, int o) {
        f[o + C] = cont.fbm(x / 1100.0, z / 1100.0, 4, 0.5) * 1.7 + 0.2;
        f[o + TP] = temp.fbm(x / 2400.0, z / 2400.0, 2, 0.5) * 1.6;
        f[o + HU] = humid.fbm(x / 2200.0, z / 2200.0, 2, 0.5) * 1.6;
        f[o + HA] = 40 + 80 * smooth(clamp01(hillAmp.noise(x / 1800.0, z / 1800.0) + 0.5));
        double dx = 220 * warpA.fbm(x / 1400.0, z / 1400.0, 2, 0.5), dz = 220 * warpB.fbm(x / 1400.0, z / 1400.0, 2, 0.5);
        f[o + WX] = dx;
        f[o + WZ] = dz;
        double wx = x + dx, wz = z + dz;
        double rx = wx * 0.8660254037844387 - wz * 0.5, rz = wx * 0.5 + wz * 0.8660254037844387;
        f[o + BN] = beltN.fbm(rx / 5200.0, rz / 9000.0, 3, 0.5);
        f[o + MESA_F] = mesaN.fbm(x / 520.0, z / 520.0, 2, 0.5);
        f[o + CLIFF] = cliffN.noise(x / 900.0, z / 900.0);
        f[o + CLIFFT] = cliffT.noise(x / 600.0, z / 600.0);
        f[o + PLAT] = plateauN.noise(x / 750.0, z / 750.0);
        f[o + PLATP] = plateauP.noise(x / 2500.0, z / 2500.0);
        f[o + RIVER_F] = riverN.fbm(wx / 1300.0, wz / 1300.0, 3, 0.5);
        f[o + RIFTM] = riftM.noise(x / 2600.0, z / 2600.0);
        f[o + RIFTN] = riftN.fbm(wx / 1700.0, wz / 1700.0, 2, 0.5);
        f[o + RIFTW] = riftW.noise(x / 800.0, z / 800.0);
        f[o + RIFTD] = riftD.noise(x / 900.0, z / 900.0);
    }

    /** Узлы медленных полей, покрывающие [x0…x1]×[z0…z1]. */
    final class Grid {
        final int gx0, gz0, nx;
        final double[] f;

        Grid(int x0, int z0, int x1, int z1) {
            gx0 = Math.floorDiv(x0, GS);
            gz0 = Math.floorDiv(z0, GS);
            nx = Math.floorDiv(x1, GS) + 2 - gx0;
            int nz = Math.floorDiv(z1, GS) + 2 - gz0;
            f = new double[nx * nz * NF];
            for (int j = 0; j < nz; j++) for (int i = 0; i < nx; i++) slow((gx0 + i) * GS, (gz0 + j) * GS, f, (j * nx + i) * NF);
        }

        /** Все поля в (x, z) — билинейно. */
        void at(int x, int z, double[] out) {
            int fx = Math.floorDiv(x, GS), fz = Math.floorDiv(z, GS);
            int i = fx - gx0, j = fz - gz0;
            double u = (x - fx * GS) / (double) GS, v = (z - fz * GS) / (double) GS;
            int a = (j * nx + i) * NF, b = a + NF, c = a + nx * NF, d = c + NF;
            for (int k = 0; k < NF; k++) {
                double top = f[a + k] + (f[b + k] - f[a + k]) * u, bot = f[c + k] + (f[d + k] - f[c + k]) * u;
                out[k] = top + (bot - top) * v;
            }
        }
    }

    /** Высота и маски точки (поля сетки g). Порядок: база+холмы → хребты → мезы → террасы → тепуи → реки → озёра → топи → разломы → мелочь. */
    void sample(int x, int z, S s, Grid g, double[] f) {
        g.at(x, z, f);
        double c = f[C];
        double land = clamp01(c * 4);
        double h = c < 0 ? sea + 1 + c * 75 : sea + 1 + c * 32;
        double tp = f[TP], hu = f[HU];
        // холмы: амплитуда 40…120 пятнами
        double hl = hill.fbm(x / 260.0, z / 260.0, 4, 0.5);
        h += land * Math.max(0, hl + 0.12) * f[HA];
        // искажение координат и поворот на 30° (литералы) — хребты извилистые и не по осям
        double wx = x + f[WX], wz = z + f[WZ];
        double rx = wx * 0.8660254037844387 - wz * 0.5, rz = wx * 0.5 + wz * 0.8660254037844387;
        // пояс хребтов: окрестность нулевой изолинии шума — длинные извилистые полосы
        double belt = smooth(clamp01(1 - Math.abs(f[BN]) * 4.2)) * land;
        double r = 0;
        if (belt > 0.01) {
            // ridged multifractal (Масгрейв): гребни острые, в долинах мелкие октавы гаснут
            double sum = 0, w = 1, fq = 1 / 760.0, amp = 1;
            for (int i = 0; i < 5; i++) {
                double n = 1 - Math.abs(ridgeN.noise(rx * fq + i * 17.3, rz * fq));
                n *= n;
                n *= w;
                sum += n * amp;
                w = clamp01(n * 2.0);
                fq *= 2.03;
                amp *= 0.5;
            }
            r = clamp01(sum / 1.6);
            double peak = r * StrictMath.sqrt(r);
            h += belt * (180 + 1420 * peak * belt);
        }
        // мезы: жарко и сухо — столовые холмы с мелкими ступенями
        double mesa = smooth(clamp01((tp - 0.22) / 0.15)) * smooth(clamp01((-hu - 0.08) / 0.15)) * land * (1 - belt);
        if (mesa > 0) h += mesa * (40 + 280 * smooth(clamp01(f[MESA_F] * 1.6 + 0.35)));
        // террасы-обрывы: в горах и мезах
        double cliff = smooth(clamp01((f[CLIFF] + 0.1) / 0.35)) * smooth(clamp01((belt - 0.08) / 0.3));
        double ca = Math.max(cliff, mesa);
        if (ca > 0 && h > sea + 4) {
            double tn = f[CLIFFT];
            double T = mesa > cliff ? 8 + 2.5 * tn : 28 + 20 * tn;
            double t = (h - sea - 4) / T, fl = Math.floor(t), fr = t - fl;
            fr = fr * fr * fr * fr;                                           // плоская полка, потом резкий подъём
            h += ((fl + fr) * T + sea + 4 - h) * ca;
        }
        // тепуи: столовые горы с отвесными стенами
        double tep = 0;
        if (f[PLAT] > 0.36) {
            double pn = f[PLAT] + 0.05 * det.noise(x / 40.0, 2.5, z / 40.0);
            tep = smooth(clamp01((pn - 0.44) / 0.05)) * clamp01((c - 0.15) * 5) * (1 - belt) * (1 - mesa);
            if (tep > 0) {
                double P = 760 + 300 * (f[PLATP] + 1) * 0.5 + 4 * det.noise(x / 25.0, 8.5, z / 25.0);
                if (P > h) h += (P - h) * tep;
            }
        }
        // реки: узкая полоса до sea − 3, только на низинах (до 450)
        double river = 0;
        double rv2 = Math.abs(f[RIVER_F]);
        if (rv2 < 0.022) {
            double str = clamp01((450 - h) / 80) * clamp01((c - 0.02) * 10) * (1 - tep);
            if (str > 0) {
                double prof = smooth(clamp01((1 - rv2 / 0.022) / 0.6));
                river = prof * str;
                h += (sea - 3 - h) * river;
            }
        }
        // озёра: впадины ниже моря на равнинах
        double lakeT = 0;
        if (belt < 0.1 && tep < 0.1 && h < 352) {
            double l = lake.noise(x / 120.0, z / 120.0);
            if (l > 0.36) {
                lakeT = smooth(clamp01((l - 0.36) / 0.14)) * (1 - belt / 0.1) * clamp01((352 - h) / 25);
                h += (sea - 5 - h) * lakeT;
            }
        }
        // топи: влажно и тепло — почти у воды, лужи
        double sw = smooth(clamp01((hu - 0.25) / 0.12)) * smooth(clamp01((tp + 0.1) / 0.15)) * (1 - belt) * (1 - mesa)
                * (1 - tep) * clamp01((c - 0.02) * 8);
        if (sw > 0) {
            double target = sea + 1.2 + det.noise(x / 18.0, 7.7, z / 18.0) * 3.2;
            if (h > target) h += (target - h) * sw * 0.9;
        }
        // разломы: узкие извилистые трещины с плоским дном, сухие
        double rift = 0;
        double rm = smooth(clamp01((f[RIFTM] - 0.05) / 0.15)) * clamp01((c - 0.12) / 0.1) * (1 - lakeT) * (1 - sw) * (1 - river);
        if (rm > 0) {
            double rv = Math.abs(f[RIFTN]);
            double W = 0.009 + 0.007 * f[RIFTW];
            if (rv < W) {
                double tt = 1 - rv / W;
                double prof = tt > 0.4 ? 1 : smooth(tt / 0.4);
                double D = 150 + 300 * (f[RIFTD] + 1) * 0.5 + 250 * belt;
                double floorY = Math.max(sea - 60, h - D);
                rift = prof * rm;
                h += (floorY - h) * rift;
            }
        }
        h += det.fbm(x / 36.0, z / 36.0, 2, 0.5) * (1.5 + 3 * land) * (1 - rift);
        // мягкий потолок: к 1950 асимптотически
        if (h > 1780) { double e = h - 1780; h = 1780 + 170 * e / (e + 170); }
        s.h = h; s.c = c; s.belt = belt; s.r = r; s.rift = rift; s.river = river; s.swamp = sw; s.mesa = mesa; s.tepui = tep;
        s.lakeT = lakeT; s.tp = tp; s.hu = hu;
    }

    int clampH(double h) { return Math.max(bottom + 8, Math.min(top - 40, (int) Math.floor(h))); }

    double snowLine(int x, int z) { return SNOW_LINE + jitter.noise(x / 50.0, 3.3, z / 50.0) * 60; }

    /** Лесистость: > 0.08 — лес. */
    double forestness(int x, int z) { return forest.fbm(x / 280.0, z / 280.0, 3, 0.5); }

    /** Поляна в лесу. */
    boolean glade(int x, int z) { return glade.noise(x / 45.0, z / 45.0) > 0.42; }

    /** Пятно цветов. */
    boolean flowerPatch(int x, int z) { return jitter.noise(x / 14.0, 5.5, z / 14.0) > 0.38; }

    /** Жилы голубого льда в леднике. */
    boolean blueIce(int x, int y, int z) { return iceN.noise(x / 9.0, y / 9.0, z / 9.0) > 0.45; }

    /** Тепло (пальмы на пляже). */
    boolean warm(int x, int z) { return temp.fbm(x / 2400.0, z / 2400.0, 2, 0.5) * 1.6 > 0.1; }

    /** Номер слоя пород в (x, y, z): полосы по 7 блоков, сдвиг колонки so. */
    static int band(int y, int so, int len) { return Math.floorMod((int) Math.floor((y + so) / 7.0), len); }

    /** Зона по климату (без высоты). */
    private byte climate(int x, int z, S s) {
        if (s.swamp > 0.5) return SWAMP;
        if (s.mesa > 0.45) return MESA;
        if (magic.noise(x / 1300.0, z / 1300.0) > 0.40) return s.tp > -0.05 ? GLOW : PALE;
        if (s.tp > 0.22) return s.hu > 0.12 ? JUNGLE : SAVANNA;
        if (s.tp < -0.22) return s.hu > -0.1 ? REDWOOD : TAIGA;
        if (s.hu > 0.3 && s.tp < 0.05) return PALE;
        if (cherryN.noise(x / 700.0, z / 700.0) > 0.3 && s.hu > -0.15) return CHERRY;
        if (forestness(x, z) > 0.08) return birch.noise(x / 350.0, z / 350.0) > 0.18 ? BIRCH : FOREST;
        double m = meadow.noise(x / 200.0, z / 200.0);
        return m > 0.25 ? MEADOW : m < -0.33 ? FLOWER : PLAINS;
    }

    /** До какой высоты держится климатическая зона (выше — тайга/альпика). */
    private static int zoneTop(byte z) {
        return switch (z) {
            case MESA -> 1000;
            case JUNGLE -> 750;
            case REDWOOD, TAIGA -> 700;
            case PALE -> 650;
            case SAVANNA -> 650;
            case CHERRY -> 600;
            case GLOW -> 520;
            default -> 520;
        };
    }

    // ───────────── колонки чанка (кэш) ─────────────

    /**
     * Колонки чанка: высоты 18×18 (с рамкой), верх, зона, уклон, минимум соседей (слои пород), сдвиг слоёв, толщина льда;
     * min/max высоты 16×16 и minH18 — минимум по рамке 18×18 (честный uniform).
     */
    static final class Col {
        final int cx, cz;
        final short[] h = new short[18 * 18];
        final short[] nb = new short[256], nbMax = new short[256];
        final byte[] top = new byte[256], zone = new byte[256], slope = new byte[256], so = new byte[256], ice = new byte[256];
        int minH, maxH, minH18, maxH18;

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

    /** Вода над колонкой (море, реки, озёра, лужи топей; разломы сухие). */
    boolean wet(int x, int z) {
        Col c = col(x >> 4, z >> 4);
        int k = (z & 15) << 4 | (x & 15);
        return c.h(x & 15, z & 15) < sea && c.zone[k] != RIFT;
    }

    /** Колонка под водой (ниже моря и не разлом) — по сырым высотам. */
    private boolean wetRaw(int[] raw, boolean[] rf, int i) { return raw[i] < sea && !rf[i]; }

    private Col compute(int cx, int cz) {
        Col c = new Col(cx, cz);
        int x0 = cx << 4, z0 = cz << 4;
        S[] ss = new S[256];
        S tmp = new S();
        // сырые высоты 20×20 (рамка 2): сухой разлом ниже моря рядом с водой поднимаем до моря — иначе стена воды висит над
        // пустотой. Правило смотрит только на 4 соседей, поэтому рамка 18×18 в любом чанке считается одинаково.
        Grid grid = new Grid(x0 - 2, z0 - 2, x0 + 17, z0 + 17);
        double[] fv = new double[NF];
        int[] raw = new int[20 * 20];
        boolean[] rf = new boolean[20 * 20];
        for (int lz = -2; lz <= 17; lz++) for (int lx = -2; lx <= 17; lx++) {
            boolean in = lx >= 0 && lx < 16 && lz >= 0 && lz < 16;
            S s = in ? new S() : tmp;
            sample(x0 + lx, z0 + lz, s, grid, fv);
            int i = (lz + 2) * 20 + lx + 2;
            raw[i] = clampH(s.h);
            rf[i] = s.rift > 0;
            if (in) ss[lz << 4 | lx] = s;
        }
        int mn18 = Integer.MAX_VALUE, mx18 = Integer.MIN_VALUE;
        for (int lz = -1; lz <= 16; lz++) for (int lx = -1; lx <= 16; lx++) {
            int i = (lz + 2) * 20 + lx + 2;
            int h = raw[i];
            if (h < sea && rf[i] && (wetRaw(raw, rf, i + 1) || wetRaw(raw, rf, i - 1) || wetRaw(raw, rf, i + 20) || wetRaw(raw, rf, i - 20)))
                h = sea;
            c.h[(lz + 1) * 18 + lx + 1] = (short) h;
            mn18 = Math.min(mn18, h);
            mx18 = Math.max(mx18, h);
        }
        int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int x = x0 + lx, z = z0 + lz, k = lz << 4 | lx;
            S s = ss[k];
            int h = c.h(lx, lz);
            mn = Math.min(mn, h);
            mx = Math.max(mx, h);
            int e = c.h(lx + 1, lz), w = c.h(lx - 1, lz), so = c.h(lx, lz + 1), no = c.h(lx, lz - 1);
            int s1 = Math.max(Math.max(Math.abs(h - w), Math.abs(h - e)), Math.max(Math.abs(h - no), Math.abs(h - so)));
            c.slope[k] = (byte) Math.min(100, s1);
            c.nb[k] = (short) Math.min(Math.min(e, w), Math.min(so, no));
            c.nbMax[k] = (short) Math.max(Math.max(e, w), Math.max(so, no));
            c.so[k] = (byte) Math.floor(14 * strataN.noise(x / 90.0, 6.1, z / 90.0));
            byte t, zn;
            if (s.rift > 0.5 || (s.rift > 0 && h < sea)) {                    // дно и стены разлома (сухо, ниже моря тоже)
                zn = RIFT;
                double v = jitter.noise(x / 13.0, 2.2, z / 13.0);
                if (s1 >= 4) t = STONE;
                else if (v > 0.45 && c.nb[k] >= h && det.noise(x / 7.0, 9.9, z / 7.0) > 0.25) t = LAVA;   // лужа в ямке
                else t = v > 0.2 ? MAGMA : v < -0.3 ? TUFF : BASALT;
            } else if (h < sea) {
                int depth = sea - h;
                if (s.swamp > 0.5) t = MUD;
                else if (depth <= 2) t = SAND;
                else if (jitter.noise(x / 11.0, 9.3, z / 11.0) > 0.33) t = CLAY;
                else t = det.noise(x / 9.0, 4.1, z / 9.0) > 0 ? GRAVEL : SAND;
                zn = s.river > 0.5 ? RIVER : s.swamp > 0.5 ? SWAMP : s.c < 0.04 ? OCEAN : LAKE;
            } else if (s.swamp < 0.5 && h <= sea + 2 + (int) Math.floor(jitter.noise(x / 23.0, 1.1, z / 23.0) * 2)) {
                t = SAND;
                zn = s.river > 0.3 ? RIVER : BEACH;
            } else {
                double snow = snowLine(x, z);
                boolean steep = s1 >= 6 || (s1 >= 4 && h > 520);
                if (h >= snow) {
                    // ледник: «долина» гребня, пологий склон
                    boolean gl = h >= GLACIER && s.r < 0.35 && s1 <= 3;
                    if (gl) {
                        t = ICE;
                        c.ice[k] = (byte) (3 + (int) Math.floor((iceN.noise(x / 30.0, z / 30.0) + 1) * 2.6));
                    } else t = s1 >= 7 ? STONE : SNOW;
                    zn = gl || h >= 1600 ? FROZEN : s1 >= 5 ? JAGGED : STONY;
                } else {
                    byte cl = climate(x, z, s);
                    if (s.tepui > 0.5 && h > 650) zn = s.tp > 0 ? FLOWER : PALE;   // плоская вершина тепуи — свой сад
                    else if (h < zoneTop(cl) + (int) Math.floor(jitter.noise(x / 70.0, 7.7, z / 70.0) * 20)) zn = cl;
                    else if (h < 720 + jitter.noise(x / 60.0, 4.4, z / 60.0) * 40) zn = cl == SAVANNA || cl == MESA ? SAVANNA : TAIGA;
                    else zn = h < 950 ? ALPINE : GROVE;
                    if (zn == MESA) t = steep && s1 >= 8 ? STONE : RED_SAND;
                    else if (steep || (zn == GROVE && s1 >= 3)) t = STONE;
                    else {
                        double v = jitter.noise(x / 9.0, 6.6, z / 9.0);
                        t = switch (zn) {
                            case REDWOOD -> v > 0.3 ? COARSE : v < -0.35 ? MOSS : PODZOL;
                            case TAIGA -> v > 0.35 ? PODZOL : GRASS;
                            case GLOW -> v > 0.25 ? MOSS : MYCELIUM;
                            case PALE -> v > 0.3 ? GRASS : PALE_MOSS;
                            case SWAMP -> v > 0.2 ? MUD : GRASS;
                            case SAVANNA -> v > 0.35 ? COARSE : GRASS;
                            case JUNGLE -> v > 0.4 ? MOSS : GRASS;
                            case ALPINE, GROVE -> v > 0.45 ? STONE : GRASS;
                            default -> GRASS;
                        };
                    }
                }
            }
            c.top[k] = t;
            c.zone[k] = zn;
        }
        c.minH = mn;
        c.maxH = mx;
        c.minH18 = mn18;
        c.maxH18 = mx18;
        return c;
    }
}
