package ink.hoka.odyssey.planet.vale;

import ink.hoka.odyssey.gen.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static ink.hoka.odyssey.planet.vale.Terrain.*;

/**
 * «vale» v2: рельеф {@link Terrain} (горы до ~1950, хребты, мезы, тепуи, разломы, реки, топи) и 24 зоны-биома.
 * Под верхом — почва зоны, слои пород на открытых стенах, лёд ледников; ниже 144 — глубинный сланец, на дне бедрок.
 * Объекты двух видов, в одном порядке (слой → gz → gx): процедурные ({@link Flora}: секвойи, тропические гиганты,
 * баобабы, сакуры, ивы, мангры, призрачные дубы, ели-пагоды, «леденцы», грибы, цветы-великаны) и .hobj из objects/.
 * Растения и лишайник — одиночными блоками. hasBlocks/uniform/highest — точно (флаги блоков {@link Flags}).
 * Без изменяемых полей (кроме кэшей, не зависящих от порядка): вызывается параллельно.
 */
final class ValeGen implements PlanetGenerator {

    /** Ниже — глубинный сланец (кратно 16: кубы целиком одного камня). */
    static final int DEEP = 144;
    private static final long PLANT_SALT = 0x9_1A47L, LAYER_SALT = 0x1A7E2L, THIN_SALT = 0x7E1EL, LICHEN_SALT = 0x11C4EL;

    private final Terrain t;
    private final Pal pal;
    private final Flora.St fs;
    private final int sea, bottom;
    private final int bedrock, stone, deepslate, dirt, coarse, mud, sand, sandstone, redSand, gravel, clay, snow, water, packedIce,
            blueIce, basalt, lilyPad;
    private final int[] topState = new int[TOP_ID.length];
    private final int[] biomes = new int[BIOME_ID.length];
    private final int[] bandStone, bandMesa, bandRift;
    private final int[] lichen = new int[4];
    private final int[] plantLo, plantHi;
    private final Layer[] layers;
    private final GenPlacer placer;
    private final boolean anyData;
    private final AtomicReferenceArray<ObjSpan> spans = new AtomicReferenceArray<>(1024);
    private final AtomicReferenceArray<Built> shapes = new AtomicReferenceArray<>(4096);
    private final Map<String, Shape[]> variants = new HashMap<>();             // только поиск по id (не обход)

    ValeGen(GenContext c) {
        GenBounds b = c.bounds();
        bottom = b.minY();
        sea = Math.max(bottom + 16, Math.min(b.maxY() - 64, c.intOption("sea_level", ValeProvider.SEA_LEVEL)));
        t = new Terrain(c.seed(), sea, bottom, b.maxY());
        pal = new Pal(c.ids());
        GenIds ids = c.ids();
        bedrock = pal.d("minecraft:bedrock"); stone = pal.d("minecraft:stone"); deepslate = pal.d("minecraft:deepslate");
        dirt = pal.d("minecraft:dirt"); coarse = pal.d("minecraft:coarse_dirt"); mud = pal.d("minecraft:mud");
        sand = pal.d("minecraft:sand"); sandstone = pal.d("minecraft:sandstone"); redSand = pal.d("minecraft:red_sand");
        gravel = pal.d("minecraft:gravel"); clay = pal.d("minecraft:clay"); snow = pal.d("minecraft:snow_block");
        water = pal.d("minecraft:water"); packedIce = pal.d("minecraft:packed_ice"); blueIce = pal.d("minecraft:blue_ice");
        basalt = pal.s("minecraft:basalt[axis=y]"); lilyPad = pal.d("minecraft:lily_pad");
        for (int i = 0; i < TOP_ID.length; i++) {
            String id = TOP_ID[i];
            topState[i] = switch (i) {
                case GRASS -> pal.s("minecraft:grass_block[snowy=false]");
                case PODZOL -> pal.s("minecraft:podzol[snowy=false]");
                case MYCELIUM -> pal.s("minecraft:mycelium[snowy=false]");
                case BASALT -> basalt;
                case LAVA -> pal.s("minecraft:lava[level=0]");
                case PALE_MOSS -> pal.any("minecraft:pale_moss_block", "minecraft:moss_block");
                default -> pal.d(id);
            };
        }
        for (int i = 0; i < BIOME_ID.length; i++) {
            int v;
            try { v = ids.biome(BIOME_ID[i]); } catch (RuntimeException e) { v = ids.biome("minecraft:plains"); }
            biomes[i] = v;
        }
        bandStone = new int[]{stone, stone, pal.d("minecraft:andesite"), pal.d("minecraft:tuff"), stone, pal.d("minecraft:calcite"),
                stone, pal.d("minecraft:dripstone_block")};
        bandMesa = new int[]{pal.d("minecraft:terracotta"), pal.d("minecraft:orange_terracotta"), pal.d("minecraft:terracotta"),
                pal.d("minecraft:yellow_terracotta"), pal.d("minecraft:red_terracotta"), pal.d("minecraft:white_terracotta"),
                pal.d("minecraft:brown_terracotta"), pal.d("minecraft:light_gray_terracotta"), pal.d("minecraft:red_terracotta"),
                pal.d("minecraft:orange_terracotta")};
        bandRift = new int[]{pal.d("minecraft:tuff"), pal.d("minecraft:deepslate"), pal.d("minecraft:tuff"), pal.d("minecraft:calcite"),
                pal.d("minecraft:deepslate"), pal.d("minecraft:smooth_basalt"), pal.d("minecraft:tuff"), basalt};
        String[] face = {"east", "west", "south", "north"};
        for (int i = 0; i < 4; i++) lichen[i] = pal.s("minecraft:glow_lichen[down=false,east=" + face[i].equals("east") + ",north="
                + face[i].equals("north") + ",south=" + face[i].equals("south") + ",up=false,waterlogged=false,west=" + face[i].equals("west") + "]");
        // растения (номер — индекс): см. plant()
        int sg = pal.d("minecraft:short_grass");
        plantLo = new int[]{sg, pal.d("minecraft:fern"), pal.s("minecraft:tall_grass[half=lower]"), pal.s("minecraft:large_fern[half=lower]"),
                pal.d("minecraft:dandelion"), pal.d("minecraft:poppy"), pal.d("minecraft:oxeye_daisy"), pal.d("minecraft:azure_bluet"),
                pal.d("minecraft:cornflower"), pal.d("minecraft:allium"), pal.d("minecraft:lily_of_the_valley"),
                pal.any("minecraft:leaf_litter[facing=north,segment_amount=3]", "minecraft:short_grass"),
                pal.any("minecraft:wildflowers[facing=east,flower_amount=3]", "minecraft:dandelion"),
                pal.any("minecraft:bush", "minecraft:fern"), pal.any("minecraft:firefly_bush", "minecraft:fern"),
                pal.any("minecraft:short_dry_grass", "minecraft:dead_bush"), pal.any("minecraft:tall_dry_grass", "minecraft:dead_bush"),
                pal.d("minecraft:dead_bush"), pal.d("minecraft:red_mushroom"), pal.d("minecraft:brown_mushroom"), pal.d("minecraft:moss_carpet"),
                pal.any("minecraft:pale_moss_carpet[bottom=true,east=none,north=none,south=none,west=none]", "minecraft:moss_carpet"),
                pal.any("minecraft:closed_eyeblossom", "minecraft:allium"), pal.d("minecraft:blue_orchid"),
                pal.s("minecraft:sunflower[half=lower]"), pal.s("minecraft:peony[half=lower]"), pal.s("minecraft:rose_bush[half=lower]"),
                pal.s("minecraft:lilac[half=lower]"), pal.s("minecraft:cactus[age=0]"), pal.d("minecraft:orange_tulip"),
                pal.d("minecraft:pink_tulip"), pal.any("minecraft:open_eyeblossom", "minecraft:oxeye_daisy")};
        plantHi = new int[plantLo.length];
        plantHi[P_TALL] = pal.s("minecraft:tall_grass[half=upper]");
        plantHi[P_LFERN] = pal.s("minecraft:large_fern[half=upper]");
        plantHi[P_SUN] = pal.s("minecraft:sunflower[half=upper]");
        plantHi[P_PEONY] = pal.s("minecraft:peony[half=upper]");
        plantHi[P_ROSE] = pal.s("minecraft:rose_bush[half=upper]");
        plantHi[P_LILAC] = pal.s("minecraft:lilac[half=upper]");
        plantHi[P_CACTUS] = pal.any("minecraft:cactus_flower", "minecraft:cactus[age=0]");
        fs = new Flora.St(pal);

        // ── слои объектов (порядок = порядок вставки) ──
        placer = GenPlacer.of(c);
        for (GenObject o : c.objects().all()) variants.put(o.id(), variantsOf(o, placer));
        double dens = Math.max(0, Math.min(2, c.doubleOption("tree_density", 1)));
        List<Layer> ls = new ArrayList<>();
        int land = bits(PLAINS, MEADOW, FLOWER, FOREST, BIRCH, TAIGA, CHERRY, GLOW, SAVANNA, JUNGLE, PALE);
        // великаны
        proc(ls, "sequoia", 40, 4, 20, 2, 330, 700, dens, K_SEQUOIA, z(REDWOOD, 0.45));
        proc(ls, "jungle_giant", 34, 4, 48, 3, 330, 750, dens, K_JUNGLE, z(JUNGLE, 0.5));
        proc(ls, "baobab", 30, 3, 14, 2, 320, 650, dens, K_BAOBAB, z(SAVANNA, 0.25));
        proc(ls, "ghost_oak", 22, 2, 20, 3, 320, 650, dens, K_GHOST, z(PALE, 0.5));
        proc(ls, "pagoda", 18, 2, 10, 3, 330, 1150, dens, K_PAGODA, z(REDWOOD, 0.6), z(TAIGA, 0.3), z(ALPINE, 0.3), z(GROVE, 0.2));
        proc(ls, "umbrella", 30, 3, 20, 2, 320, 900, dens, K_UMBRELLA, z(SAVANNA, 0.35), z(MESA, 0.1));
        proc(ls, "dragon", 30, 3, 10, 2, 320, 650, dens, K_DRAGON, z(SAVANNA, 0.15));
        proc(ls, "sakura", 20, 2, 20, 3, 320, 600, dens, K_SAKURA, z(CHERRY, 0.45));
        proc(ls, "willow", 16, 2, 12, 2, 298, 330, dens, K_WILLOW, z(SWAMP, 0.5));
        proc(ls, "mangrove", 14, 2, 8, 2, 290, 330, dens, K_MANGROVE, z(SWAMP, 0.4));
        proc(ls, "spiral", 64, 6, 16, 2, 300, 600, dens, K_SPIRAL, zs(land, 0.05));
        hobj(ls, c, placer, "glycine", 22, 0.2 * dens, 2, "", bits(CHERRY), 3, 320, 600, false);
        hobj(ls, c, placer, "azure", 40, 0.15 * dens, 3, "", bits(PALE), 3, 320, 650, false);
        hobj(ls, c, placer, "palm", 14, 0.35 * dens, 2, "", bits(BEACH), 2, 300, 330, false);
        // грибы и цветы
        proc(ls, "big_shroom", 26, 3, 22, 3, 300, 650, dens, K_SHROOM, z(GLOW, 0.5), z(PALE, 0.08));
        hobj(ls, c, placer, "glowfingers", 12, 0.4 * dens, 1, "", bits(GLOW), 3, 300, 650, false);
        hobj(ls, c, placer, "glowfingers", 12, 0.2 * dens, 1, "rift", bits(RIFT), 5, -500, 2000, false);
        proc(ls, "giant_flower", 48, 4, 10, 2, 300, 450, 1, K_FLOWER, z(FLOWER, 0.2), z(MEADOW, 0.2), z(CHERRY, 0.1));
        proc(ls, "lily", 30, 3, 8, 100, -500, 2000, 1, K_LILY, z(SWAMP, 0.3));
        hobj(ls, c, placer, "bellflowers", 40, 0.12, 3, "", bits(FLOWER, MEADOW, PALE), 2, 300, 650, false);
        hobj(ls, c, placer, "rafflesia", 56, 0.15, 4, "", bits(JUNGLE), 1, 300, 750, false);
        // обычный лес (×1.5 к v1): густо, края реже, поляны
        hobj(ls, c, placer, "oak", 6, 0.9 * dens, 1, "forest", bits(FOREST), 2, 300, 1150, true);
        hobj(ls, c, placer, "birch", 6, 0.9 * dens, 1, "forest", bits(BIRCH), 2, 300, 1150, true);
        hobj(ls, c, placer, "birch", 13, 0.45 * dens, 2, "mix", bits(FOREST), 2, 300, 1150, true);
        hobj(ls, c, placer, "oak", 13, 0.4 * dens, 2, "mix", bits(BIRCH, FLOWER), 2, 300, 1150, false);
        hobj(ls, c, placer, "oak", 23, 0.4 * dens, 3, "plains", bits(PLAINS, MEADOW), 2, 300, 1150, false);
        hobj(ls, c, placer, "birch", 31, 0.22 * dens, 3, "plains", bits(MEADOW), 2, 300, 1150, false);
        hobj(ls, c, placer, "spruce", 7, 0.8 * dens, 1, "taiga", bits(TAIGA), 3, 300, 1150, false);
        hobj(ls, c, placer, "spruce", 10, 0.35 * dens, 2, "redwood", bits(REDWOOD, ALPINE), 3, 300, 1150, false);
        hobj(ls, c, placer, "spruce", 14, 0.35 * dens, 2, "snow", bits(GROVE), 3, 300, 1150, false);
        hobj(ls, c, placer, "bushes", 9, 0.33 * dens, 1, "", bits(PLAINS, FOREST, BIRCH, MEADOW, FLOWER, JUNGLE, SAVANNA, CHERRY), 2, 300, 1150, false);
        hobj(ls, c, placer, "rocks", 27, 0.22, 2, "", land | bits(ALPINE, GROVE, REDWOOD), 5, 300, 1250, false);
        hobj(ls, c, placer, "rocks", 32, 0.3, 2, "high", bits(ALPINE, GROVE, REDWOOD), 6, 300, 1250, false);
        layers = ls.toArray(new Layer[0]);
        boolean data = false;
        for (Layer l : layers) if (l instanceof ObjLayer o) data |= o.g.hasData();
        anyData = data;
    }

    private static int bits(byte... zs) { int m = 0; for (byte z : zs) m |= 1 << z; return m; }

    private static double[] z(byte zone, double d) { return new double[]{zone, d}; }

    private static double[] zs(int mask, double d) { return new double[]{-1 - mask, d}; }

    /** Варианты .hobj (4 поворота × отражение) как Shape — для highest и охвата (вставка — GenPlacer). */
    private Shape[] variantsOf(GenObject o, GenPlacer placer) {
        Shape[] v = new Shape[8];
        for (int var = 0; var < 8; var++) {
            int rot = var & 3;
            boolean mir = var >= 4;
            Shape.B sb = new Shape.B(pal);
            for (int i = 0, n = o.count(); i < n; i++) {
                int st = placer.state(o, i, rot, mir);
                pal.note(st, rotated(o.palette().get(o.paletteIndex(i)), st, rot, mir));
                sb.put(GenObject.rx(o.dx(i), o.dz(i), rot, mir), o.dy(i), GenObject.rz(o.dx(i), o.dz(i), rot, mir), st);
            }
            v[var] = sb.build();
        }
        return v;
    }

    /** Строка повёрнутого состояния (для флагов и опор), если именно оно и стоит; иначе исходная. */
    private String rotated(String s, int st, int rot, boolean mir) {
        if (rot == 0 && !mir) return s;
        try {
            String r = GenStates.rotate(s, rot, mir);
            return pal.ids().state(r) == st ? r : s;
        } catch (RuntimeException e) { return s; }
    }

    // ───────────── слои объектов ─────────────

    /** Объект в мире: Shape (для охвата/высот), .hobj (o ≠ null — вставка через GenPlacer) и якорь. */
    record P(Shape sh, GenObject o, int x, int y, int z, int rot, boolean mir) {
        boolean covers(int x0, int z0, int x1, int z1) {
            return x + sh.maxDx >= x0 && x + sh.minDx <= x1 && z + sh.maxDz >= z0 && z + sh.minDz <= z1;
        }
    }

    /** Слой: объекты с якорем в [minX…maxX]×[minZ…maxZ] — по gz↑, gx↑. */
    private interface Layer {
        int reach();
        void collect(int minX, int minZ, int maxX, int maxZ, List<P> out);
    }

    private final class ObjLayer implements Layer {
        final FeatureGrid g;
        final Zone s;

        ObjLayer(FeatureGrid g, Zone s) { this.g = g; this.s = s; }

        @Override public int reach() { return g.reach(); }

        @Override
        public void collect(int minX, int minZ, int maxX, int maxZ, List<P> out) {
            for (FeatureGrid.Placement p : g.placements(minX, minZ, maxX, maxZ, s)) {
                Shape sh = variants.get(p.object().id())[(p.rot() & 3) + (p.mirror() ? 4 : 0)];
                out.add(new P(sh, p.object(), p.x(), p.ay(), p.z(), p.rot(), p.mirror()));
            }
        }
    }

    private void hobj(List<Layer> ls, GenContext c, GenPlacer placer, String cat, int cell, double density, int margin, String salt,
                      int zones, int maxSlope, int minY, int maxY, boolean forestEdge) {
        FeatureGrid g = FeatureGrid.of(c, placer, cat).cell(cell).density(Math.min(1, density)).margin(margin).salt(salt).build();
        if (!g.isEmpty()) ls.add(new ObjLayer(g, new Zone(zones, maxSlope, minY, maxY, forestEdge, salt.hashCode(), cat.equals("palm"))));
    }

    // виды процедурных объектов
    static final int K_SEQUOIA = 0, K_JUNGLE = 1, K_BAOBAB = 2, K_GHOST = 3, K_PAGODA = 4, K_UMBRELLA = 5, K_DRAGON = 6, K_SAKURA = 7,
            K_WILLOW = 8, K_MANGROVE = 9, K_SPIRAL = 10, K_SHROOM = 11, K_FLOWER = 12, K_LILY = 13;

    private record Built(int layer, int gx, int gz, Shape s) {}

    /** Процедурный слой: ячейки cell×cell, плотность по зоне, строитель Flora; Shape — в кэше по (слой, ячейка). */
    private final class ProcLayer implements Layer {
        final int idx, cell, margin, span, reach, maxSlope, minY, maxY, kind;
        final double[] dens = new double[BIOME_ID.length];
        final long seed;

        ProcLayer(int idx, String name, int cell, int margin, int reach, int maxSlope, int minY, int maxY, int kind, double mul, double[][] zd) {
            this.idx = idx;
            this.cell = cell;
            this.margin = margin;
            this.span = Math.max(1, cell - 2 * margin);
            this.reach = reach;
            this.maxSlope = maxSlope;
            this.minY = minY;
            this.maxY = maxY;
            this.kind = kind;
            for (double[] p : zd) {
                int zz = (int) p[0];
                if (zz >= 0) dens[zz] = Math.min(1, p[1] * mul);
                else for (int i = 0; i < dens.length; i++) if (((-1 - zz) & (1 << i)) != 0) dens[i] = Math.min(1, p[1] * mul);
            }
            this.seed = t.seed ^ GenHash.strings(List.of("vale-proc", name));
        }

        @Override public int reach() { return reach; }

        @Override
        public void collect(int minX, int minZ, int maxX, int maxZ, List<P> out) {
            for (int gz = Math.floorDiv(minZ, cell); gz <= Math.floorDiv(maxZ, cell); gz++)
                for (int gx = Math.floorDiv(minX, cell); gx <= Math.floorDiv(maxX, cell); gx++) {
                    P p = at(gx, gz);
                    if (p != null && p.x() >= minX && p.x() <= maxX && p.z() >= minZ && p.z() <= maxZ) out.add(p);
                }
        }

        /** Ячейка: объект или null. Порядок случайных чисел фиксирован. */
        P at(int gx, int gz) {
            GenRandom r = new GenRandom(GenRandom.hash(seed, gx, 0, gz));
            double p = r.nextDouble();
            int jx = r.nextInt(span), jz = r.nextInt(span);
            long bs = r.nextLong();
            int x = gx * cell + margin + jx, z = gz * cell + margin + jz;
            Terrain.Col c = t.col(x >> 4, z >> 4);
            int k = (z & 15) << 4 | (x & 15);
            byte zone = c.zone[k];
            if (p >= dens[zone]) return null;
            int h = c.h(x & 15, z & 15);
            boolean wet = h < sea && zone != RIFT;
            int ay;
            if (kind == K_LILY) {
                if (!wet || sea - h > 6) return null;
                ay = sea + 1;
            } else {
                if (c.slope[k] > maxSlope || h < minY || h > maxY) return null;
                if (wet && (kind != K_MANGROVE || sea - h > 3)) return null;
                if (kind != K_MANGROVE && !wet && c.top[k] != GRASS && c.top[k] != PODZOL && c.top[k] != COARSE && c.top[k] != MOSS
                        && c.top[k] != MYCELIUM && c.top[k] != PALE_MOSS && c.top[k] != MUD && c.top[k] != RED_SAND) return null;
                if (nearRift(x, z)) return null;
                if (kind == K_SEQUOIA) {                                       // ровное место под комель
                    for (int q = 0; q < 4; q++) if (Math.abs(t.height(x + (q < 2 ? -2 : 2), z + ((q & 1) == 0 ? -2 : 2)) - h) > 3) return null;
                }
                ay = h + 1;
            }
            int key = (int) (GenRandom.mix(((long) gx << 32) ^ (gz & 0xFFFFFFFFL) ^ ((long) idx << 56)) & (shapes.length() - 1));
            Built bt = shapes.get(key);
            if (bt == null || bt.layer() != idx || bt.gx() != gx || bt.gz() != gz) {
                bt = new Built(idx, gx, gz, build(new Shape.Rnd(bs), x, ay, z, zone).clip(reach, pal));
                shapes.set(key, bt);
            }
            return new P(bt.s(), null, x, ay, z, 0, false);
        }

        private Shape build(Shape.Rnd r, int x, int ay, int z, byte zone) {
            Flora.Env e = new Flora.Env() {
                @Override public int g(int dx, int dz) { return t.height(x + dx, z + dz) - ay; }
                @Override public boolean wet(int dx, int dz) { return t.wet(x + dx, z + dz); }
                @Override public boolean soil(int dx, int dz) {
                    byte tp = t.top(x + dx, z + dz);
                    return tp == GRASS || tp == PODZOL || tp == COARSE || tp == MOSS || tp == MYCELIUM || tp == PALE_MOSS || tp == MUD;
                }
                @Override public boolean rift(int dx, int dz) { return t.zone(x + dx, z + dz) == RIFT; }
            };
            return switch (kind) {
                case K_SEQUOIA -> Flora.sequoia(pal, fs, r, e);
                case K_JUNGLE -> Flora.jungleGiant(pal, fs, r, e);
                case K_BAOBAB -> Flora.baobab(pal, fs, r, e);
                case K_GHOST -> Flora.ghostOak(pal, fs, r, e);
                case K_PAGODA -> Flora.pagoda(pal, fs, r, e);
                case K_UMBRELLA -> Flora.umbrella(pal, fs, r, e, true);
                case K_DRAGON -> Flora.dragon(pal, fs, r, e);
                case K_SAKURA -> Flora.sakura(pal, fs, r, e);
                case K_WILLOW -> Flora.willow(pal, fs, r, e);
                case K_MANGROVE -> Flora.mangrove(pal, fs, r, e);
                case K_SPIRAL -> Flora.spiral(pal, fs, r, e);
                case K_SHROOM -> {
                    double q = r.next();
                    yield q < 0.35 ? Flora.bellShroom(pal, fs, r, e) : q < 0.6 ? Flora.medusa(pal, fs, r, e)
                            : q < 0.8 ? Flora.pagodaShroom(pal, fs, r, e) : Flora.amethystShroom(pal, fs, r, e);
                }
                case K_FLOWER -> {
                    double q = r.next();
                    yield q < 0.34 ? Flora.sunflower(pal, fs, r, e) : q < 0.67 ? Flora.tulip(pal, fs, r, e) : Flora.daisy(pal, fs, r, e);
                }
                default -> Flora.lilyPad(pal, fs, r, e);
            };
        }
    }

    private void proc(List<Layer> ls, String name, int cell, int margin, int reach, int maxSlope, int minY, int maxY, double mul, int kind,
                      double[]... zd) {
        if (mul <= 0) return;
        ls.add(new ProcLayer(ls.size(), name, cell, margin, reach, maxSlope, minY, maxY, kind, mul, zd));
    }

    /** В 8 блоках от якоря есть разлом (растительности там нет, кроме дна разлома). */
    private boolean nearRift(int x, int z) {
        return t.zone(x - 8, z - 8) == RIFT || t.zone(x + 8, z - 8) == RIFT || t.zone(x - 8, z + 8) == RIFT || t.zone(x + 8, z + 8) == RIFT
                || t.zone(x, z) == RIFT;
    }

    // ───────────── зона сетки .hobj (поверхность для FeatureGrid) ─────────────

    /** Поверхность только в своих зонах; вне — NONE (объект не ставится). */
    private final class Zone implements GenSurface {
        final int zones, maxSlope, minY, maxY;
        final boolean forestEdge, warmOnly, rift;
        final long salt;

        Zone(int zones, int maxSlope, int minY, int maxY, boolean forestEdge, int salt, boolean warmOnly) {
            this.zones = zones;
            this.maxSlope = maxSlope;
            this.minY = minY;
            this.maxY = maxY;
            this.forestEdge = forestEdge;
            this.salt = salt;
            this.warmOnly = warmOnly;
            this.rift = zones == 1 << RIFT;
        }

        @Override
        public int height(int x, int z) {
            Terrain.Col c = t.col(x >> 4, z >> 4);
            int k = (z & 15) << 4 | (x & 15);
            if ((zones & (1 << c.zone[k])) == 0 || c.slope[k] > maxSlope) return NONE;
            int h = c.h(x & 15, z & 15);
            if (h < minY || h > maxY) return NONE;
            if (h < sea && c.zone[k] != RIFT) return NONE;
            if (c.top[k] == LAVA) return NONE;                                // на лаву не ставим
            if (!rift && nearRift(x, z)) return NONE;
            if (warmOnly && !t.warm(x, z)) return NONE;
            if (forestEdge) {                                                 // края леса реже, внутри поляны
                if (t.glade(x, z)) return NONE;
                double edge = clamp01((t.forestness(x, z) - 0.08) / 0.12);
                if (new GenRandom(GenRandom.hash(t.seed ^ THIN_SALT ^ salt, x, 0, z)).nextDouble() >= 0.2 + 0.8 * edge) return NONE;
            }
            return h;
        }

        @Override public String block(int x, int z) {
            byte tp = t.top(x, z);
            return tp == GRASS || tp == PODZOL || tp == MOSS || tp == PALE_MOSS || tp == MUD || tp == COARSE || tp == MYCELIUM
                    ? "minecraft:grass_block" : TOP_ID[tp];
        }
        @Override public boolean water(int x, int z) { return t.wet(x, z); }
        @Override public String biome(int x, int z) { return BIOME_ID[t.zone(x, z)]; }
    }

    // ───────────── объекты колонки чанка (кэш) ─────────────

    /** Объекты, задевающие колонку чанка (по рамке), в порядке вставки; lo/hi — их Y. */
    private record ObjSpan(int cx, int cz, int lo, int hi, P[] list) {
        boolean any() { return lo <= hi; }
    }

    private ObjSpan span(int cx, int cz) {
        int i = (int) (GenRandom.mix(((long) cx << 32) ^ (cz & 0xFFFFFFFFL) ^ 0x51L) & (spans.length() - 1));
        ObjSpan s = spans.get(i);
        if (s != null && s.cx() == cx && s.cz() == cz) return s;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        int x0 = cx << 4, z0 = cz << 4;
        List<P> all = new ArrayList<>(), buf = new ArrayList<>();
        for (Layer l : layers) {
            int r = l.reach();
            buf.clear();
            l.collect(x0 - r, z0 - r, x0 + 15 + r, z0 + 15 + r, buf);
            for (P p : buf) {
                if (!p.covers(x0, z0, x0 + 15, z0 + 15) || p.sh().n == 0) continue;
                all.add(p);
                lo = Math.min(lo, p.y() + p.sh().minDy);
                hi = Math.max(hi, p.y() + p.sh().maxDy);
            }
        }
        s = new ObjSpan(cx, cz, lo, hi, all.toArray(new P[0]));
        spans.set(i, s);
        return s;
    }

    // ───────────── колонка ─────────────

    /** Состояние рельефа в (x, y, z) колонки k (без воды, растений и объектов). */
    private int ground(int x, int y, int z, int h, Terrain.Col c, int k) {
        if (y > h || y < bottom) return 0;
        if (y == bottom) return bedrock;
        byte top = c.top[k];
        if (y == h) return topState[top];
        int d = h - y;                                                        // 1… вглубь
        switch (top) {
            case GRASS, PODZOL, MYCELIUM, PALE_MOSS, MOSS -> { if (d <= 3 + (int) (GenRandom.hash(t.seed ^ LAYER_SALT, x, 0, z) & 1)) return dirt; }
            case COARSE -> { if (d <= 1) return coarse; if (d <= 3) return dirt; }
            case MUD -> { if (d <= 3) return mud; }
            case SAND -> { if (d <= 3) return sand; if (d <= 5) return sandstone; }
            case RED_SAND -> { if (d <= 1) return redSand; if (d <= 8) return bandMesa[Terrain.band(y, c.so[k], bandMesa.length)]; }
            case GRAVEL -> { if (d <= 2) return gravel; }
            case CLAY -> { if (d <= 2) return clay; if (d <= 3) return gravel; }
            case SNOW -> { if (d <= 1) return snow; }
            case ICE -> { if (d < c.ice[k]) return d >= 2 && t.blueIce(x, y, z) ? blueIce : packedIce; }
            case BASALT, MAGMA, LAVA, TUFF -> { if (d <= 2) return basalt; }
            default -> {}
        }
        if (y >= c.nb[k] - 1) {                                               // открытая стена: слои пород
            byte zn = c.zone[k];
            if (zn == MESA) return bandMesa[Terrain.band(y, c.so[k], bandMesa.length)];
            if (zn == RIFT) return bandRift[Terrain.band(y, c.so[k], bandRift.length)];
            return y < DEEP ? deepslate : bandStone[Terrain.band(y, c.so[k], bandStone.length)];
        }
        return y < DEEP ? deepslate : stone;
    }

    // растения: номера в plantLo
    static final int P_GRASS = 0, P_FERN = 1, P_TALL = 2, P_LFERN = 3, P_LITTER = 11, P_WILD = 12, P_BUSH = 13, P_FIREFLY = 14,
            P_DRY = 15, P_TDRY = 16, P_DEAD = 17, P_RMUSH = 18, P_BMUSH = 19, P_MOSS = 20, P_PMOSS = 21, P_EYE = 22, P_ORCHID = 23,
            P_SUN = 24, P_PEONY = 25, P_ROSE = 26, P_LILAC = 27, P_CACTUS = 28, P_OTULIP = 29, P_PTULIP = 30, P_OEYE = 31;
    private static final int[] FLOWERS = {4, 5, 6, 7, 8, 9, 12, 29, 30};
    private static final int[] ALPINE_FL = {7, 8, 9, 4};
    private static final int[] TALL_FL = {P_SUN, P_PEONY, P_ROSE, P_LILAC};
    /** Лепестки сакуры — отдельные номера (fs.petals). */
    private static final int PETALS = 1000;

    /** Растение колонки (номер; PETALS+i — лепестки) или −1. flat — соседи не выше (кактусу нужен воздух по бокам). */
    private int plant(int x, int z, byte zone, byte top, boolean flat) {
        GenRandom r = new GenRandom(GenRandom.hash(t.seed ^ PLANT_SALT, x, 0, z));
        double p = r.nextDouble(), k = r.nextDouble();
        int f = r.nextInt(36);
        boolean soil = top == GRASS || top == PODZOL || top == MOSS || top == COARSE || top == MUD || top == PALE_MOSS;
        switch (zone) {
            case PLAINS -> {
                if (top != GRASS) return -1;
                boolean patch = t.flowerPatch(x, z);
                if (p >= (patch ? 0.75 : 0.55)) return -1;
                if (k < (patch ? 0.35 : 0.68)) return P_GRASS;
                if (k < (patch ? 0.45 : 0.85)) return P_TALL;
                return patch && f < 6 ? P_WILD : FLOWERS[f % FLOWERS.length];
            }
            case MEADOW, FLOWER -> {
                if (top != GRASS) return -1;
                if (p >= (zone == FLOWER ? 0.82 : 0.72)) return -1;
                if (k < 0.32) return P_GRASS;
                if (k < 0.42) return P_TALL;
                if (k < 0.5) return P_BUSH;
                if (k < (zone == FLOWER ? 0.68 : 0.58)) return TALL_FL[f & 3];
                return f < 8 ? P_WILD : FLOWERS[f % FLOWERS.length];
            }
            case FOREST, BIRCH -> {
                if (top != GRASS) return -1;
                if (p >= 0.6) return -1;
                if (k < 0.3) return P_FERN;
                if (k < 0.58) return P_GRASS;
                if (k < 0.8) return P_LITTER;
                if (k < 0.88) return P_BUSH;
                return zone == BIRCH ? 10 : FLOWERS[f % FLOWERS.length];
            }
            case TAIGA -> {
                if (!soil) return -1;
                if (p >= 0.5) return -1;
                if (k < 0.45) return P_FERN;
                if (k < 0.65) return P_LFERN;
                if (k < 0.9) return P_GRASS;
                return P_BUSH;
            }
            case REDWOOD -> {
                if (!soil) return -1;
                if (top == MOSS && p < 0.3) return P_MOSS;
                if (p >= 0.65) return -1;
                if (k < 0.4) return P_FERN;
                if (k < 0.7) return P_LFERN;
                if (k < 0.92) return P_GRASS;
                return P_BMUSH;
            }
            case CHERRY -> {
                if (top != GRASS) return -1;
                if (p >= 0.62) return -1;
                if (k < 0.5) return PETALS + (f & 3);
                if (k < 0.82) return P_GRASS;
                if (k < 0.92) return P_TALL;
                return FLOWERS[f % FLOWERS.length];
            }
            case SWAMP -> {
                if (top != GRASS && top != MUD) return -1;
                if (p >= 0.5) return -1;
                if (k < 0.4) return P_GRASS;
                if (k < 0.62) return P_FERN;
                if (k < 0.77) return P_ORCHID;
                if (k < 0.88) return P_FIREFLY;
                return P_TALL;
            }
            case GLOW -> {
                if (top == MYCELIUM) return p < 0.22 ? (k < 0.5 ? P_RMUSH : P_BMUSH) : -1;
                if (top != MOSS && top != GRASS) return -1;
                if (p >= 0.45) return -1;
                if (k < 0.4) return P_FERN;
                if (k < 0.75) return P_GRASS;
                return P_FIREFLY;
            }
            case SAVANNA -> {
                if (top != GRASS && top != COARSE) return -1;
                if (p >= 0.55) return -1;
                if (k < 0.3) return P_DRY;
                if (k < 0.5) return P_TDRY;
                if (k < 0.8) return P_GRASS;
                if (k < 0.95) return P_TALL;
                return P_DEAD;
            }
            case MESA -> {
                if (top != RED_SAND) return -1;
                if (p >= 0.1) return -1;
                if (k < 0.4) return P_DEAD;
                if (k < 0.75) return P_DRY;
                // кактус ломается от соседа сбоку: только на ровном и через клетку (два кактуса не встанут рядом)
                return flat && ((x | z) & 1) == 0 ? P_CACTUS : P_DEAD;
            }
            case JUNGLE -> {
                if (top != GRASS && top != MOSS) return -1;
                if (p >= 0.7) return -1;
                if (k < 0.35) return P_FERN;
                if (k < 0.6) return P_LFERN;
                if (k < 0.8) return P_GRASS;
                if (k < 0.95) return P_BUSH;
                return P_TALL;
            }
            case PALE -> {
                if (top != PALE_MOSS && top != GRASS) return -1;
                if (p >= 0.5) return -1;
                if (k < 0.4) return top == PALE_MOSS ? P_PMOSS : P_GRASS;
                if (k < 0.65) return P_GRASS;
                if (k < 0.8) return P_EYE;
                if (k < 0.9) return P_FIREFLY;
                return P_OEYE;
            }
            case ALPINE, GROVE -> {
                if (top != GRASS) return -1;
                if (p >= (zone == ALPINE ? 0.5 : 0.3)) return -1;
                if (k < 0.55) return P_GRASS;
                if (k < 0.9) return ALPINE_FL[f & 3];
                return P_FERN;
            }
            default -> { return -1; }
        }
    }

    private int lo(int p) { return p >= PETALS ? fs.petals[p - PETALS] : plantLo[p]; }

    private int hi(int p) { return p >= PETALS ? 0 : plantHi[p]; }

    /** Кувшинка на воде топей (sea + 1). */
    private boolean lily(int x, int z) {
        return new GenRandom(GenRandom.hash(t.seed ^ PLANT_SALT ^ 0x111L, x, 0, z)).nextDouble() < 0.12;
    }

    /** Лишайник на стене разлома в (x, y, z): сторона (0 E, 1 W, 2 S, 3 N) или −1. */
    private int lichenAt(Terrain.Col c, int lx, int lz, int x, int y, int z) {
        if ((GenRandom.hash(t.seed ^ LICHEN_SALT, x, y, z) & 1023) >= 31) return -1;   // ~3 %
        if (c.h(lx + 1, lz) >= y) return 0;
        if (c.h(lx - 1, lz) >= y) return 1;
        if (c.h(lx, lz + 1) >= y) return 2;
        if (c.h(lx, lz - 1) >= y) return 3;
        return -1;
    }

    // ───────────── генерация ─────────────

    @Override
    public void generate(GenCube out) {
        int cx = out.cx(), cy = out.cy(), cz = out.cz();
        int uni = uniform(cx, cy, cz);
        if (uni >= 0) { out.fill(uni); return; }
        if (!hasBlocks(cx, cy, cz)) return;
        int x0 = out.x0(), y0 = out.y0(), z0 = out.z0(), y1 = y0 + 15;
        Terrain.Col c = t.col(cx, cz);
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int k = lz << 4 | lx, h = c.h(lx, lz);
            int x = x0 + lx, z = z0 + lz;
            int from = Math.max(y0, bottom), to = Math.min(y1, h);
            for (int y = from; y <= to; y++) out.set(lx, y - y0, lz, ground(x, y, z, h, c, k));
            if (c.zone[k] != RIFT) for (int y = Math.max(y0, h + 1); y <= Math.min(y1, sea); y++) out.set(lx, y - y0, lz, water);
        }
        if (y1 >= c.minH - SOFT - 1) for (int bz = 0; bz < 4; bz++) for (int bx = 0; bx < 4; bx++) {
            int b = biomes[c.zone[(bz * 4 + 1) << 4 | (bx * 4 + 1)]];
            for (int by = 0; by < 4; by++) out.biome(bx, by, bz, b);
        }
        ObjSpan s = span(cx, cz);
        if (s.any() && s.hi() >= y0 && s.lo() <= y1) {
            for (P p : s.list()) {
                if (p.y() + p.sh().maxDy < y0 || p.y() + p.sh().minDy > y1) continue;
                // .hobj с блок-сущностями — через GenPlacer (данные сундуков); остальное — Shape (повёрнутые состояния те же)
                if (p.o() != null && p.o().hasData()) placer.paste(out, p.o(), p.x(), p.y(), p.z(), p.rot(), p.mir());
                else p.sh().paste(out, p.x(), p.y(), p.z());
            }
        }
        // растения — в воздух над почвой (после объектов: ствол не затирается)
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int k = lz << 4 | lx, h = c.h(lx, lz);
            byte zn = c.zone[k];
            if (h < sea && zn != RIFT) {                                      // кувшинки топей
                int y = sea + 1;
                if (zn == SWAMP && y >= y0 && y <= y1 && sea - h <= 3 && lily(x0 + lx, z0 + lz) && out.get(lx, y - y0, lz) == 0)
                    out.set(lx, y - y0, lz, lilyPad);
                continue;
            }
            if (zn == RIFT) {                                                 // светящийся лишайник на стенах
                for (int y = Math.max(y0, h + 1); y <= Math.min(y1, c.nbMax[k]); y++) {
                    int f = lichenAt(c, lx, lz, x0 + lx, y, z0 + lz);
                    if (f >= 0 && out.get(lx, y - y0, lz) == 0) out.set(lx, y - y0, lz, lichen[f]);
                }
                continue;
            }
            int y = h + 1;
            if (y < y0 || y > y1) continue;
            int p = plant(x0 + lx, z0 + lz, zn, c.top[k], c.nbMax[k] <= h);
            if (p < 0 || out.get(lx, y - y0, lz) != 0) continue;
            if (hi(p) != 0) {
                if (y < y1 && out.get(lx, y + 1 - y0, lz) == 0) {
                    out.set(lx, y - y0, lz, lo(p));
                    out.set(lx, y + 1 - y0, lz, hi(p));
                } else out.set(lx, y - y0, lz, p == P_CACTUS ? lo(p) : plantLo[P_GRASS]);   // не влезло — пониже
            } else out.set(lx, y - y0, lz, lo(p));
        }
    }

    @Override
    public boolean hasBlocks(int cx, int cy, int cz) {
        int y0 = cy << 4;
        if (y0 + 15 < bottom) return false;
        Terrain.Col c = t.col(cx, cz);
        if (y0 <= Math.max(c.maxH18 + 2, sea + 1)) return true;
        ObjSpan s = span(cx, cz);
        return s.any() && y0 <= s.hi() && y0 + 15 >= s.lo();
    }

    @Override
    public int uniform(int cx, int cy, int cz) {
        int y0 = cy << 4, y1 = y0 + 15;
        if (y0 <= bottom) return -1;
        Terrain.Col c = t.col(cx, cz);
        if (y1 >= c.minH18 - SOFT) return -1;
        ObjSpan s = span(cx, cz);
        if (s.any() && s.lo() <= y1) return -1;
        if (y1 < DEEP) return deepslate;
        return y0 >= DEEP ? stone : -1;
    }

    @Override
    public int highest(int x, int z, int yFrom, int yTo, HeightKind kind) {
        if (yFrom > yTo || yTo < bottom) return NONE;
        Terrain.Col c = t.col(x >> 4, z >> 4);
        int lx = x & 15, lz = z & 15, ck = lz << 4 | lx;
        int h = c.h(lx, lz);
        byte zn = c.zone[ck];
        boolean wet = h < sea && zn != RIFT;
        int base = wet ? sea : h;                                             // верх рельефа с водой — всё полное
        int kd = kind.ordinal();
        // объекты: блоки этой колонки выше base, первый записавший на каждой высоте
        int n = 0;
        long[] e = null;
        ObjSpan s = span(x >> 4, z >> 4);
        if (s.any() && s.hi() > base) {
            int order = 0;
            for (P p : s.list()) {
                order++;
                int[] col = p.sh().column(x - p.x(), z - p.z());
                for (int i : col) {
                    int y = p.y() + Shape.dy(p.sh().pos[i]);
                    if (y <= base) break;                                     // дальше только ниже
                    if (e == null) e = new long[64];
                    if (n == e.length) e = java.util.Arrays.copyOf(e, n * 2);
                    // ключ: y по убыванию, затем порядок вставки по возрастанию
                    e[n++] = ((long) (0x7FFF - (y + 16384)) << 40) | ((long) order << 8) | (p.sh().fl[i] & 0xFF);
                }
            }
            if (n > 1) java.util.Arrays.sort(e, 0, n);
        }
        int bestPos = Integer.MIN_VALUE, bestVal = NONE;
        int prevY = Integer.MIN_VALUE;
        boolean occ1 = false, occ2 = false, occLily = false;
        for (int i = 0; i < n; i++) {
            int y = 0x7FFF - (int) (e[i] >>> 40) - 16384;
            if (y == prevY) continue;                                         // на этой высоте уже записал кто-то раньше
            prevY = y;
            if (y == h + 1) occ1 = true;
            if (y == h + 2) occ2 = true;
            if (y == sea + 1) occLily = true;
            if (bestPos != Integer.MIN_VALUE || y > yTo || y < yFrom) continue;
            int v = Flags.contrib((int) (e[i] & 0xFF), kd, y, NONE);
            if (v != NONE) { bestPos = y; bestVal = v; }
        }
        // растения / кувшинки / лишайник (только в воздух — позиции не совпадают с объектами)
        if (wet) {
            if (zn == SWAMP && sea - h <= 3 && !occLily && lily(x, z)) {
                int y = sea + 1;
                if (y >= yFrom && y <= yTo && y > bestPos) {
                    int v = Flags.contrib(pal.flags(lilyPad), kd, y, NONE);
                    if (v != NONE) { bestPos = y; bestVal = v; }
                }
            }
        } else if (zn == RIFT) {
            if (kd == 2) for (int y = Math.min(yTo, c.nbMax[ck]); y >= Math.max(yFrom, h + 1) && y > bestPos; y--) {
                if (lichenAt(c, lx, lz, x, y, z) >= 0 && !objAt(e, n, y)) { bestPos = y; bestVal = y; break; }
            }
        } else if (!occ1) {
            int p = plant(x, z, zn, c.top[ck], c.nbMax[ck] <= h);
            if (p >= 0) {
                int y = h + 1;
                int lo = lo(p), up = 0;
                if (hi(p) != 0) {
                    if (((h + 1) & 15) != 15 && !occ2) up = hi(p);
                    else if (p != P_CACTUS) lo = plantLo[P_GRASS];
                }
                if (up != 0 && y + 1 >= yFrom && y + 1 <= yTo && y + 1 > bestPos) {
                    int v = Flags.contrib(pal.flags(up), kd, y + 1, NONE);
                    if (v != NONE) { bestPos = y + 1; bestVal = v; }
                }
                if (y >= yFrom && y <= yTo && y > bestPos) {
                    int v = Flags.contrib(pal.flags(lo), kd, y, NONE);
                    if (v != NONE) { bestPos = y; bestVal = v; }
                }
            }
        }
        if (bestPos != Integer.MIN_VALUE) return bestVal;
        int top = Math.min(base, yTo);
        return top >= Math.max(yFrom, bottom) ? top : NONE;
    }

    private static boolean objAt(long[] e, int n, int y) {
        for (int i = 0; i < n; i++) if (0x7FFF - (int) (e[i] >>> 40) - 16384 == y) return true;
        return false;
    }

    @Override
    public int columnBiome(int x, int z) { return biomes[t.zone(x, z)]; }

    @Override
    public boolean mayHaveSecrets(int cx, int cy, int cz) {
        if (!anyData) return false;
        ObjSpan s = span(cx, cz);
        int y0 = cy << 4;
        return s.any() && s.hi() >= y0 && s.lo() <= y0 + 15;
    }

    // ───────────── для тестов (рефлексией) ─────────────

    /** Все состояния, которые генератор может поставить (строки) — сверка флагов с ядром. */
    List<String> statesUsed() { return pal.used(); }

    /** Флаги высот состояния (как их видит генератор). */
    static int flagsOf(String state) { return Flags.of(state); }

    /** Для тестов/спавна: высота рельефа и зона. */
    int surface(int x, int z) { return t.height(x, z); }
    String zoneName(int x, int z) { return ZONE_NAME[t.zone(x, z)]; }
}
