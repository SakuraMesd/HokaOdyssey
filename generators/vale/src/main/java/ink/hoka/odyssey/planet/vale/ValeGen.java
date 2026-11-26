package ink.hoka.odyssey.planet.vale;

import ink.hoka.odyssey.gen.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static ink.hoka.odyssey.planet.vale.Terrain.*;

/**
 * «vale» v1: долина у моря. Рельеф {@link Terrain}; под верхом — земля/песок/гравий/глина, камень, ниже 144 — глубинный
 * сланец, на дне −500 бедрок; вода до уровня моря; растения одиночными блоками; объекты категорий oak, birch, spruce,
 * bushes, rocks — сетками FeatureGrid по зонам (лес гуще, поляны, ели в горах). hasBlocks/uniform/highest — аналитически.
 * Без изменяемых полей (кроме кэшей, не зависящих от порядка): вызывается параллельно.
 */
final class ValeGen implements PlanetGenerator {

    /** Ниже — глубинный сланец (кратно 16: кубы целиком одного камня). */
    static final int DEEP = 144;
    private static final int[] FLOWERS = {4, 5, 6, 7, 8, 9, 12};
    private static final long PLANT_SALT = 0x9_1A47L, LAYER_SALT = 0x1A7E2L, THIN_SALT = 0x7E1EL;

    private final Terrain t;
    private final int sea, bottom;
    private final int bedrock, stone, deepslate, dirt, sand, sandstone, gravel, clay, snow, water;
    private final int[] topState = new int[6];
    private final int[] biomes = new int[BIOME_ID.length];
    private final int[] plantLo, plantHi;
    private final Grid[] grids;
    /** Все блоки объектов «полные» (стволы, листва, камень) — для неба/движения считаются как есть. */
    private final boolean simpleObjects;
    private final AtomicReferenceArray<ObjSpan> spans = new AtomicReferenceArray<>(1024);

    /** Сетка объектов и её зона. */
    private record Grid(FeatureGrid g, Zone s) {}

    ValeGen(GenContext c) {
        GenBounds b = c.bounds();
        bottom = b.minY();
        sea = Math.max(bottom + 16, Math.min(b.maxY() - 64, c.intOption("sea_level", ValeProvider.SEA_LEVEL)));
        t = new Terrain(c.seed(), sea, bottom, b.maxY());
        GenIds ids = c.ids();
        bedrock = ids.defaultState("minecraft:bedrock");
        stone = ids.defaultState("minecraft:stone");
        deepslate = ids.defaultState("minecraft:deepslate");
        dirt = ids.defaultState("minecraft:dirt");
        sand = ids.defaultState("minecraft:sand");
        sandstone = ids.defaultState("minecraft:sandstone");
        gravel = ids.defaultState("minecraft:gravel");
        clay = ids.defaultState("minecraft:clay");
        snow = ids.defaultState("minecraft:snow_block");
        water = ids.defaultState("minecraft:water");
        int grass = ids.state("minecraft:grass_block[snowy=false]");
        topState[GRASS] = grass; topState[SAND] = sand; topState[GRAVEL] = gravel;
        topState[CLAY] = clay; topState[SNOW] = snow; topState[STONE] = stone;
        for (int i = 0; i < BIOME_ID.length; i++) biomes[i] = ids.biome(BIOME_ID[i]);
        // растения: 0 трава, 1 папоротник, 2 высокая трава, 3 большой папоротник, 4… цветы, 11 опавшие листья, 12 полевые цветы
        int sg = ids.defaultState("minecraft:short_grass");
        plantLo = new int[]{sg, ids.defaultState("minecraft:fern"), ids.state("minecraft:tall_grass[half=lower]"),
                ids.state("minecraft:large_fern[half=lower]"), ids.defaultState("minecraft:dandelion"), ids.defaultState("minecraft:poppy"),
                ids.defaultState("minecraft:oxeye_daisy"), ids.defaultState("minecraft:azure_bluet"), ids.defaultState("minecraft:cornflower"),
                ids.defaultState("minecraft:allium"), ids.defaultState("minecraft:lily_of_the_valley"), opt(ids, "minecraft:leaf_litter", sg),
                opt(ids, "minecraft:wildflowers", ids.defaultState("minecraft:dandelion"))};
        plantHi = new int[plantLo.length];
        plantHi[2] = ids.state("minecraft:tall_grass[half=upper]");
        plantHi[3] = ids.state("minecraft:large_fern[half=upper]");

        GenPlacer placer = GenPlacer.of(c);
        double dens = Math.max(0, Math.min(2, c.doubleOption("tree_density", 1)));
        int land = bit(PLAINS) | bit(FOREST) | bit(BIRCH) | bit(MEADOW) | bit(TAIGA) | bit(SNOWY);
        List<Grid> gs = new ArrayList<>();
        // лес: густо, края реже, поляны
        grid(gs, c, placer, "oak", 6, 0.6 * dens, 1, "forest", bit(FOREST), 2, true);
        grid(gs, c, placer, "birch", 6, 0.6 * dens, 1, "forest", bit(BIRCH), 2, true);
        grid(gs, c, placer, "birch", 13, 0.3 * dens, 2, "mix", bit(FOREST), 2, true);
        grid(gs, c, placer, "oak", 13, 0.25 * dens, 2, "mix", bit(BIRCH), 2, true);
        // равнины: редкие одинокие деревья
        grid(gs, c, placer, "oak", 23, 0.28 * dens, 3, "plains", bit(PLAINS) | bit(MEADOW), 2, false);
        grid(gs, c, placer, "birch", 31, 0.15 * dens, 3, "plains", bit(MEADOW), 2, false);
        // горы: ельник, выше — редкие ели на снегу
        grid(gs, c, placer, "spruce", 7, 0.55 * dens, 1, "taiga", bit(TAIGA), 3, false);
        grid(gs, c, placer, "spruce", 14, 0.25 * dens, 2, "snow", bit(SNOWY), 3, false);
        grid(gs, c, placer, "bushes", 9, 0.22 * dens, 1, "", bit(PLAINS) | bit(FOREST) | bit(BIRCH) | bit(MEADOW), 2, false);
        grid(gs, c, placer, "rocks", 27, 0.22, 2, "", land, 5, false);
        grids = gs.toArray(new Grid[0]);

        boolean simple = true;
        for (Grid g : grids) for (GenObject o : c.objects().category(g.g().category()))
            for (String s : o.palette()) simple &= fullBlock(s);
        simpleObjects = simple;
    }

    private static int bit(byte z) { return 1 << z; }

    private static int opt(GenIds ids, String id, int def) {
        try { return ids.defaultState(id); } catch (RuntimeException e) { return def; }
    }

    private void grid(List<Grid> gs, GenContext c, GenPlacer placer, String cat, int cell, double density, int margin,
                      String salt, int zones, int maxSlope, boolean forestEdge) {
        FeatureGrid g = FeatureGrid.of(c, placer, cat).cell(cell).density(Math.min(1, density)).margin(margin).salt(salt).build();
        if (!g.isEmpty()) gs.add(new Grid(g, new Zone(zones, maxSlope, forestEdge, salt.hashCode())));
    }

    private static final Set<String> FULL = Set.of("minecraft:cobblestone", "minecraft:mossy_cobblestone", "minecraft:stone",
            "minecraft:andesite", "minecraft:tuff", "minecraft:diorite", "minecraft:granite", "minecraft:moss_block",
            "minecraft:mossy_stone_bricks", "minecraft:cobbled_deepslate", "minecraft:mud_bricks");

    /** Блок мешает небу и движению и не воздух (стволы, листва, камень). */
    static boolean fullBlock(String state) {
        int b = state.indexOf('[');
        String id = b < 0 ? state : state.substring(0, b);
        return id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_leaves") || FULL.contains(id);
    }

    // ───────────── зона сетки (поверхность для FeatureGrid) ─────────────

    /** Поверхность только в своих зонах; вне — NONE (объект не ставится). */
    private final class Zone implements GenSurface {
        final int zones, maxSlope;
        final boolean forestEdge;
        final long salt;

        Zone(int zones, int maxSlope, boolean forestEdge, int salt) {
            this.zones = zones;
            this.maxSlope = maxSlope;
            this.forestEdge = forestEdge;
            this.salt = salt;
        }

        @Override
        public int height(int x, int z) {
            Col c = t.col(x >> 4, z >> 4);
            int k = (z & 15) << 4 | (x & 15);
            if ((zones & (1 << c.zone[k])) == 0 || c.slope[k] > maxSlope) return NONE;
            int h = c.h(x & 15, z & 15);
            if (h < sea) return NONE;
            if (forestEdge) {                                                 // края леса реже, внутри поляны
                if (t.glade(x, z)) return NONE;
                double edge = clamp01((t.forestness(x, z) - 0.08) / 0.12);
                if (new GenRandom(GenRandom.hash(t.seed ^ THIN_SALT ^ salt, x, 0, z)).nextDouble() >= 0.2 + 0.8 * edge) return NONE;
            }
            return h;
        }

        @Override public String block(int x, int z) { return TOP_ID[t.top(x, z)]; }
        @Override public boolean water(int x, int z) { return t.height(x, z) < sea; }
        @Override public String biome(int x, int z) { return BIOME_ID[t.zone(x, z)]; }
    }

    // ───────────── охват объектов по высоте на колонку чанка (кэш) ─────────────

    private record ObjSpan(int cx, int cz, int lo, int hi) {
        boolean any() { return lo <= hi; }
    }

    /** Наименьший/наибольший Y блоков объектов, задевающих колонку чанка (консервативно: по рамке объекта). */
    private ObjSpan span(int cx, int cz) {
        int i = (int) (GenRandom.mix(((long) cx << 32) ^ (cz & 0xFFFFFFFFL) ^ 0x51L) & (spans.length() - 1));
        ObjSpan s = spans.get(i);
        if (s != null && s.cx() == cx && s.cz() == cz) return s;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        int x0 = cx << 4, z0 = cz << 4;
        for (Grid g : grids) {
            int r = g.g().reach();
            for (FeatureGrid.Placement p : g.g().placements(x0 - r, z0 - r, x0 + 15 + r, z0 + 15 + r, g.s())) {
                GenObject o = p.object();
                int or = o.reach();
                if (p.x() + or < x0 || p.x() - or > x0 + 15 || p.z() + or < z0 || p.z() - or > z0 + 15) continue;
                lo = Math.min(lo, p.ay() + o.minDy());
                hi = Math.max(hi, p.ay() + o.maxDy());
            }
        }
        s = new ObjSpan(cx, cz, lo, hi);
        spans.set(i, s);
        return s;
    }

    // ───────────── колонка ─────────────

    /** Состояние рельефа в (x, y, z) колонки с высотой h и верхом top (без воды, растений и объектов). */
    private int ground(int x, int y, int z, int h, byte top) {
        if (y > h || y < bottom) return 0;
        if (y == bottom) return bedrock;
        if (y == h) return topState[top];
        int d = h - y;                                                        // 1… вглубь
        switch (top) {
            case GRASS -> { if (d <= 3 + (int) (GenRandom.hash(t.seed ^ LAYER_SALT, x, 0, z) & 1)) return dirt; }
            case SAND -> { if (d <= 3) return sand; if (d <= 5) return sandstone; }
            case GRAVEL -> { if (d <= 2) return gravel; }
            case CLAY -> { if (d <= 2) return clay; if (d <= 3) return gravel; }
            case SNOW -> { if (d <= 1) return snow; }
            default -> {}
        }
        return y < DEEP ? deepslate : stone;
    }

    /** Растение колонки (номер в plantLo) или −1. Только на траве. */
    private int plant(int x, int z, byte zone) {
        GenRandom r = new GenRandom(GenRandom.hash(t.seed ^ PLANT_SALT, x, 0, z));
        double p = r.nextDouble(), k = r.nextDouble();
        int f = r.nextInt(7);
        switch (zone) {
            case FOREST, BIRCH -> {
                if (p >= 0.32) return -1;
                if (k < 0.35) return 1;
                if (k < 0.75) return 0;
                if (k < 0.90) return 11;
                return zone == BIRCH ? 10 : 5;
            }
            case PLAINS, MEADOW -> {
                boolean patch = zone == MEADOW || t.flowerPatch(x, z);
                if (p >= (patch ? 0.6 : 0.4)) return -1;
                if (k < (patch ? 0.40 : 0.72)) return 0;
                if (k < (patch ? 0.50 : 0.86)) return 2;
                return FLOWERS[f];
            }
            case TAIGA -> {
                if (p >= 0.26) return -1;
                if (k < 0.55) return 1;
                if (k < 0.72) return 3;
                return 0;
            }
            default -> { return -1; }
        }
    }

    // ───────────── генерация ─────────────

    @Override
    public void generate(GenCube out) {
        int cx = out.cx(), cy = out.cy(), cz = out.cz();
        int uni = uniform(cx, cy, cz);
        if (uni >= 0) { out.fill(uni); return; }
        if (!hasBlocks(cx, cy, cz)) return;
        int x0 = out.x0(), y0 = out.y0(), z0 = out.z0(), y1 = y0 + 15;
        Col c = t.col(cx, cz);
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int k = lz << 4 | lx, h = c.h(lx, lz);
            int x = x0 + lx, z = z0 + lz;
            int from = Math.max(y0, bottom), to = Math.min(y1, h);
            for (int y = from; y <= to; y++) out.set(lx, y - y0, lz, ground(x, y, z, h, c.top[k]));
            for (int y = Math.max(y0, h + 1); y <= Math.min(y1, sea); y++) out.set(lx, y - y0, lz, water);
        }
        boolean surfaceCube = y1 >= c.minH - SOFT - 1;
        if (surfaceCube) for (int bz = 0; bz < 4; bz++) for (int bx = 0; bx < 4; bx++) {
            int b = biomes[c.zone[(bz * 4 + 1) << 4 | (bx * 4 + 1)]];
            for (int by = 0; by < 4; by++) out.biome(bx, by, bz, b);
        }
        ObjSpan s = span(cx, cz);
        if (s.any() && s.hi() >= y0 && s.lo() <= y1) for (Grid g : grids) g.g().place(out, g.s());
        // растения — в воздух над травой (после объектов: ствол не затирается)
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int k = lz << 4 | lx, h = c.h(lx, lz), y = h + 1;
            if (c.top[k] != GRASS || h < sea) continue;
            if (y < y0 || y > y1 + 1) continue;
            int p = plant(x0 + lx, z0 + lz, c.zone[k]);
            if (p < 0) continue;
            if (y <= y1) {
                if (out.get(lx, y - y0, lz) != 0) continue;
                if (plantHi[p] != 0) {
                    if (y < y1 && out.get(lx, y + 1 - y0, lz) == 0) {
                        out.set(lx, y - y0, lz, plantLo[p]);
                        out.set(lx, y + 1 - y0, lz, plantHi[p]);
                    } else out.set(lx, y - y0, lz, plantLo[0]);           // высокое не влезло — короткая трава
                } else out.set(lx, y - y0, lz, plantLo[p]);
            }
            // y == y1 + 1: нижняя половина в кубе выше — верх тоже там (высокие только внутри одного куба)
        }
    }

    @Override
    public boolean hasBlocks(int cx, int cy, int cz) {
        int y0 = cy << 4;
        if (y0 + 15 < bottom) return false;
        Col c = t.col(cx, cz);
        if (y0 <= Math.max(c.maxH + 2, sea)) return true;
        ObjSpan s = span(cx, cz);
        return s.any() && y0 <= s.hi() && y0 + 15 >= s.lo();
    }

    @Override
    public int uniform(int cx, int cy, int cz) {
        int y0 = cy << 4, y1 = y0 + 15;
        if (y0 <= bottom) return -1;
        Col c = t.col(cx, cz);
        if (y1 >= c.minH - SOFT) return -1;
        ObjSpan s = span(cx, cz);
        if (s.any() && s.lo() <= y1) return -1;
        if (y1 < DEEP) return deepslate;
        return y0 >= DEEP ? stone : -1;
    }

    @Override
    public int highest(int x, int z, int yFrom, int yTo, HeightKind k) {
        if (yFrom > yTo || yTo < bottom) return NONE;
        Col c = t.col(x >> 4, z >> 4);
        int lx = x & 15, lz = z & 15, ck = lz << 4 | lx;
        int h = c.h(lx, lz);
        int best = NONE;
        int lo = Math.max(yFrom, bottom);
        if (Math.min(h, yTo) >= lo) best = Math.min(h, yTo);
        if (h < sea && Math.min(sea, yTo) >= Math.max(yFrom, h + 1)) best = Math.max(best, Math.min(sea, yTo));
        // объекты: блоки в этой колонке
        boolean occ1 = false, occ2 = false;
        ObjSpan s = span(x >> 4, z >> 4);
        // занятость h+1/h+2 нужна растениям и вне окна (окно [h+2..] при объекте на h+1) — охват по объединению
        if (s.any() && s.hi() >= Math.min(yFrom, h + 1) && s.lo() <= Math.max(yTo, h + 2)) {
            for (Grid g : grids) {
                int r = g.g().reach();
                for (FeatureGrid.Placement p : g.g().placements(x - r, z - r, x + r, z + r, g.s())) {
                    GenObject o = p.object();
                    int ddx = x - p.x(), ddz = z - p.z();
                    if (Math.abs(ddx) > o.reach() || Math.abs(ddz) > o.reach()) continue;
                    for (int i = 0, n = o.count(); i < n; i++) {
                        if (GenObject.rx(o.dx(i), o.dz(i), p.rot(), p.mirror()) != ddx
                                || GenObject.rz(o.dx(i), o.dz(i), p.rot(), p.mirror()) != ddz) continue;
                        int y = p.ay() + o.dy(i);
                        if (y == h + 1) occ1 = true;
                        if (y == h + 2) occ2 = true;
                        if (y < yFrom || y > yTo || y <= best) continue;
                        if (k != HeightKind.SURFACE && !simpleObjects) return UNKNOWN;
                        best = y;
                    }
                }
            }
        }
        // растения — только «любой не-воздух»
        if (k == HeightKind.SURFACE && c.top[ck] == GRASS && h >= sea && !occ1) {
            int p = plant(x, z, c.zone[ck]);
            if (p >= 0) {
                int topY = h + 1;
                if (plantHi[p] != 0 && ((h + 1) & 15) != 15 && !occ2) topY = h + 2;
                for (int y = topY; y >= h + 1; y--) if (y >= yFrom && y <= yTo) { best = Math.max(best, y); break; }
            }
        }
        return best;
    }

    @Override
    public int columnBiome(int x, int z) { return biomes[t.zone(x, z)]; }

    @Override
    public boolean mayHaveSecrets(int cx, int cy, int cz) {
        boolean data = false;
        for (Grid g : grids) data |= g.g().hasData();
        if (!data) return false;
        ObjSpan s = span(cx, cz);
        int y0 = cy << 4;
        return s.any() && s.hi() >= y0 && s.lo() <= y0 + 15;
    }

    /** Для тестов/спавна: высота рельефа. */
    int surface(int x, int z) { return t.height(x, z); }
    byte zoneAt(int x, int z) { return t.zone(x, z); }
}
