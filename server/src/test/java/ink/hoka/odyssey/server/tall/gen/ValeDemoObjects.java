package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.gen.GenObject;
import ink.hoka.odyssey.gen.GenObjectCodec;
import ink.hoka.odyssey.gen.GenRandom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Свои объекты для карты «vale» (генератор vale@2): дубы, берёзы, ели, кусты, валуны и (v2) глицинии,
 * лазурные деревья, пальмы, пальцы-светляки, колокольчики-гирлянды, раффлезии; великанов vale строит сам, процедурно.
 * Строятся кодом, детерминированно.
 * Якорь — центр-низ (основание ствола / низ камня), воздух не пишется, листва persistent=true (не опадает).
 * Категории: oak, birch, spruce, bushes, rocks, glycine, azure, palm, glowfingers, bellflowers, rafflesia (id объекта — «кат/имя», вложенных категорий нет).
 */
final class ValeDemoObjects {

    private ValeDemoObjects() {}

    static final String OAK_LOG = "minecraft:oak_log[axis=y]", BIRCH_LOG = "minecraft:birch_log[axis=y]",
            SPRUCE_LOG = "minecraft:spruce_log[axis=y]";

    static String leaves(String wood) { return "minecraft:" + wood + "_leaves[distance=1,persistent=true,waterlogged=false]"; }

    /** Сборщик с «мягким» добавлением: листва не затирает ствол. */
    private static final class B {
        final GenObjectCodec.Builder b = new GenObjectCodec.Builder(false);
        final Map<Long, String> m = new LinkedHashMap<>();

        static long k(int x, int y, int z) { return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512); }

        void log(int x, int y, int z, String s) { m.put(k(x, y, z), s); }

        void leaf(int x, int y, int z, String s) { m.putIfAbsent(k(x, y, z), s); }

        /** Эллипсоид листвы с рваным краем (доля дыр на краю — holes). */
        void blob(double cx, double cy, double cz, double rx, double ry, double rz, String s, GenRandom r, double holes) {
            for (int y = (int) Math.floor(cy - ry); y <= (int) Math.ceil(cy + ry); y++)
                for (int x = (int) Math.floor(cx - rx); x <= (int) Math.ceil(cx + rx); x++)
                    for (int z = (int) Math.floor(cz - rz); z <= (int) Math.ceil(cz + rz); z++) {
                        double dx = (x - cx) / rx, dy = (y - cy) / ry, dz = (z - cz) / rz;
                        double d = dx * dx + dy * dy + dz * dz;
                        if (d > 1) continue;
                        if (d > 0.62 && r.nextDouble() < holes) continue;
                        leaf(x, y, z, s);
                    }
        }

        GenObject build(String id, Map<String, Object> meta) {
            for (var e : m.entrySet()) {
                long k = e.getKey();
                b.add((int) (k >> 40) - 512, (int) ((k >> 20) & 0xFFFFF) - 512, (int) (k & 0xFFFFF) - 512, e.getValue());
            }
            b.anchor(0, 0, 0);
            return b.build(id, meta);
        }
    }

    static Map<String, Object> meta(double weight, List<String> on, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("weight", weight);
        m.put("on", on);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static final List<String> GRASS = List.of("minecraft:grass_block");

    static List<GenObject> all() {
        List<GenObject> r = new ArrayList<>();
        // ── дубы ──
        {   // маленький: ствол 4, классическая шапка
            B t = new B(); GenRandom g = new GenRandom(101);
            for (int y = 0; y < 5; y++) t.log(0, y, 0, OAK_LOG);
            String L = leaves("oak");
            for (int y = 3; y <= 4; y++) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
                if (!(Math.abs(x) == 2 && Math.abs(z) == 2 && g.nextDouble() < 0.7)) t.leaf(x, y, z, L);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                if (!(Math.abs(x) == 1 && Math.abs(z) == 1 && g.nextDouble() < 0.5)) t.leaf(x, 5, z, L);
            t.leaf(0, 6, 0, L); t.leaf(1, 6, 0, L); t.leaf(-1, 6, 0, L); t.leaf(0, 6, 1, L); t.leaf(0, 6, -1, L);
            r.add(t.build("oak/oak_small", meta(3, GRASS)));
        }
        {   // круглый: ствол 6, шар r≈3.2
            B t = new B(); GenRandom g = new GenRandom(102);
            for (int y = 0; y < 7; y++) t.log(0, y, 0, OAK_LOG);
            t.blob(0, 6.5, 0, 3.3, 2.8, 3.3, leaves("oak"), g, 0.35);
            r.add(t.build("oak/oak_round", meta(3, GRASS)));
        }
        {   // высокий: ствол 10, две шапки, сучок
            B t = new B(); GenRandom g = new GenRandom(103);
            for (int y = 0; y < 11; y++) t.log(0, y, 0, OAK_LOG);
            t.log(1, 6, 0, "minecraft:oak_log[axis=x]"); t.log(2, 6, 0, "minecraft:oak_log[axis=x]");
            t.blob(0, 10.5, 0, 2.7, 2.6, 2.7, leaves("oak"), g, 0.3);
            t.blob(2.5, 7, 0.3, 1.9, 1.4, 1.9, leaves("oak"), g, 0.3);
            r.add(t.build("oak/oak_tall", meta(2, GRASS)));
        }
        {   // раскидистый: ствол 5, ветви на 4 стороны, плоская широкая крона
            B t = new B(); GenRandom g = new GenRandom(104);
            for (int y = 0; y < 7; y++) t.log(0, y, 0, OAK_LOG);
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : dirs) {
                String ax = d[0] != 0 ? "minecraft:oak_log[axis=x]" : "minecraft:oak_log[axis=z]";
                t.log(d[0], 4, d[1], ax);
                t.log(2 * d[0], 5, 2 * d[1], ax);
                t.log(3 * d[0], 5, 3 * d[1], ax);
            }
            t.blob(0, 7, 0, 4.6, 1.9, 4.6, leaves("oak"), g, 0.4);
            for (int[] d : dirs) t.blob(3 * d[0], 6, 3 * d[1], 1.8, 1.3, 1.8, leaves("oak"), g, 0.3);
            r.add(t.build("oak/oak_wide", meta(1, GRASS)));
        }
        // ── берёзы ──
        {
            B t = new B(); GenRandom g = new GenRandom(201);
            for (int y = 0; y < 7; y++) t.log(0, y, 0, BIRCH_LOG);
            String L = leaves("birch");
            for (int y = 4; y <= 5; y++) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
                if (!(Math.abs(x) == 2 && Math.abs(z) == 2) && !(Math.abs(x) + Math.abs(z) == 3 && g.nextDouble() < 0.4)) t.leaf(x, y, z, L);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) if (!(Math.abs(x) == 1 && Math.abs(z) == 1 && g.nextDouble() < 0.5)) t.leaf(x, 6, z, L);
            t.leaf(0, 7, 0, L); t.leaf(1, 7, 0, L); t.leaf(-1, 7, 0, L); t.leaf(0, 7, 1, L); t.leaf(0, 7, -1, L);
            r.add(t.build("birch/birch_1", meta(3, GRASS)));
        }
        {   // высокая стройная
            B t = new B(); GenRandom g = new GenRandom(202);
            for (int y = 0; y < 10; y++) t.log(0, y, 0, BIRCH_LOG);
            t.blob(0, 8.5, 0, 1.9, 3.2, 1.9, leaves("birch"), g, 0.25);
            t.leaf(0, 10, 0, leaves("birch")); t.leaf(0, 11, 0, leaves("birch"));
            r.add(t.build("birch/birch_2", meta(2, GRASS)));
        }
        // ── ели (конусы) ──
        List<String> spruceOn = List.of("minecraft:grass_block", "minecraft:snow_block");
        r.add(spruce("spruce/spruce_small", 7, 2, 2, 301, meta(3, spruceOn)));
        r.add(spruce("spruce/spruce_mid", 11, 3, 2, 302, meta(3, spruceOn)));
        r.add(spruce("spruce/spruce_tall", 16, 4, 3, 303, meta(2, spruceOn)));
        // ── кусты ──
        {
            B t = new B(); GenRandom g = new GenRandom(401);
            t.log(0, 0, 0, OAK_LOG);
            t.blob(0, 0.6, 0, 1.8, 1.3, 1.6, leaves("oak"), g, 0.3);
            r.add(t.build("bushes/bush_oak", meta(3, GRASS)));
        }
        {
            B t = new B(); GenRandom g = new GenRandom(402);
            t.log(0, 0, 0, OAK_LOG);
            for (int y = 0; y <= 2; y++) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                double d = x * x / 4.4 + (y - 0.4) * (y - 0.4) / 2.2 + z * z / 3.4;
                if (d > 1 || (d > 0.6 && g.nextDouble() < 0.3)) continue;
                t.leaf(x, y, z, g.nextDouble() < 0.35 ? leaves("flowering_azalea") : leaves("azalea"));
            }
            r.add(t.build("bushes/bush_azalea", meta(2, GRASS)));
        }
        // ── камни ──
        List<String> rockOn = List.of("minecraft:grass_block", "minecraft:stone", "minecraft:snow_block", "minecraft:gravel");
        r.add(rock("rocks/boulder_mossy", 2.4, 1.8, 2.0, 501,
                new String[]{"minecraft:mossy_cobblestone", "minecraft:mossy_cobblestone", "minecraft:cobblestone", "minecraft:stone"}, meta(2, rockOn, "sink", 1)));
        r.add(rock("rocks/boulder_andesite", 2.0, 1.7, 1.7, 502,
                new String[]{"minecraft:andesite", "minecraft:andesite", "minecraft:stone", "minecraft:tuff"}, meta(2, rockOn, "sink", 1)));
        r.add(rock("rocks/rock_small", 1.3, 1.1, 1.2, 503,
                new String[]{"minecraft:cobblestone", "minecraft:mossy_cobblestone", "minecraft:andesite"}, meta(3, rockOn, "sink", 1)));
        for (int i = 0; i < 8; i++) r.add(glycine(i));
        for (int i = 0; i < 6; i++) r.add(azure(i));
        for (int i = 0; i < 6; i++) r.add(palm(i));
        for (int i = 0; i < 6; i++) r.add(glowfingers(i));
        for (int i = 0; i < 6; i++) r.add(bellflowers(i));
        for (int i = 0; i < 5; i++) r.add(rafflesia(i));
        return r;
    }

    static final String STEM = "minecraft:mushroom_stem[down=true,east=true,north=true,south=true,up=true,west=true]";

    /** T6 глициния: кручёный ствол, плоская шапка, свисают грозди (цветущая азалия/сакура). */
    static GenObject glycine(int v) {
        B t = new B(); GenRandom g = new GenRandom(600 + v);
        int h = 10 + g.nextInt(7);
        double turn = g.nextDouble() * 6.283185307179586;
        for (int y = 0; y < h; y++) {
            double a = turn + y * 0.55;
            t.log((int) Math.round(Math.cos(a) * 1.2), y, (int) Math.round(Math.sin(a) * 1.2), "minecraft:dark_oak_log[axis=y]");
            t.log((int) Math.round(Math.cos(a + 3.14) * 0.6), y, (int) Math.round(Math.sin(a + 3.14) * 0.6), "minecraft:dark_oak_log[axis=y]");
        }
        int R = 8 + g.nextInt(3);
        String L = leaves("azalea");
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
            double d = Math.sqrt(x * x + z * z);
            if (d > R + 0.3 || (d > R - 1.5 && g.nextDouble() < 0.35)) continue;
            t.leaf(x, h, z, L);
            if (d < R - 2) t.leaf(x, h + 1, z, g.nextDouble() < 0.25 ? leaves("flowering_azalea") : L);
            if (d > 2 && d < R - 0.5 && g.nextDouble() < 0.2) {                 // гроздь
                int len = 3 + g.nextInt(5);
                String c = g.nextDouble() < 0.6 ? leaves("flowering_azalea") : leaves("cherry");
                for (int k = 1; k <= len; k++) t.leaf(x, h - k, z, c);
                g.nextDouble();     // ягодные лозы под листвой не держатся (листва — не прочная опора), генератор их снимет — не ставим
            }
        }
        return t.build("glycine/glycine_" + (v + 1), meta(1, GRASS));
    }

    /** T11 лазурное дерево: бирюзовый ствол, ветви-рога вверх, шапки, светящиеся «плоды» на стеблях, вьюны у корней. */
    static GenObject azure(int v) {
        B t = new B(); GenRandom g = new GenRandom(700 + v);
        int h = 18 + g.nextInt(13);
        String stem = "minecraft:warped_stem[axis=y]";
        for (int y = 0; y < h; y++) { t.log(0, y, 0, stem); if (y < 4) { t.log(1, y, 0, stem); t.log(0, y, 1, stem); } }
        int horns = 2 + g.nextInt(3);
        double a0 = g.nextDouble() * 6.283185307179586;
        for (int i = 0; i < horns; i++) {
            double a = a0 + i * 6.283185307179586 / horns;
            int y0 = (int) (h * (0.45 + 0.3 * g.nextDouble()));
            double x = 0, z = 0;
            int len = 6 + g.nextInt(5);
            for (int k = 1; k <= len; k++) {
                double out = Math.min(1.0, 2.0 / k);
                x += Math.cos(a) * out; z += Math.sin(a) * out;
                t.log((int) Math.round(x), y0 + k, (int) Math.round(z), stem);
            }
            int ex = (int) Math.round(x), ez = (int) Math.round(z), ey = y0 + len;
            t.blob(ex, ey + 1.5, ez, 2.6, 1.6, 2.6, "minecraft:warped_wart_block", g, 0.3);
            t.blob(ex, ey + 2.5, ez, 2.0, 1.2, 2.0, leaves("oak"), g, 0.3);
            for (int f = 0; f < 2; f++) {                                        // плоды на стебле
                int fx = ex + g.nextInt(5) - 2, fz = ez + g.nextInt(5) - 2;
                int s = 1 + g.nextInt(3);
                for (int k = 0; k < s; k++) t.leaf(fx, ey - k, fz, "minecraft:weeping_vines_plant");
                t.leaf(fx, ey - s, fz, "minecraft:shroomlight");
            }
        }
        t.blob(0, h + 1.5, 0, 3.4, 2.0, 3.4, "minecraft:warped_wart_block", g, 0.3);
        t.blob(0, h + 3, 0, 2.6, 1.4, 2.6, leaves("oak"), g, 0.3);
        for (int i = 0; i < 6; i++) {                                           // вьюны вверх от корней
            int x = g.nextInt(7) - 3, z = g.nextInt(7) - 3;
            if (Math.abs(x) < 2 && Math.abs(z) < 2) continue;
            int len = 2 + g.nextInt(4);
            for (int k = 0; k < len; k++) t.leaf(x, k, z, k == len - 1 ? "minecraft:twisting_vines[age=25]" : "minecraft:twisting_vines_plant");
        }
        return t.build("azure/azure_" + (v + 1), meta(1, GRASS));
    }

    /** T14 пальма: ствол изогнут параболой, 6–8 листьев-перьев с изгибом вниз, кокосы. */
    static GenObject palm(int v) {
        B t = new B(); GenRandom g = new GenRandom(800 + v);
        int h = 10 + g.nextInt(9);
        double k = (2.5 + g.nextDouble() * 3) / (h * h);
        int tx = 0;
        for (int y = 0; y < h; y++) { tx = (int) Math.round(k * y * y); t.log(tx, y, 0, "minecraft:jungle_log[axis=y]"); }
        String L = leaves("jungle");
        int fr = 6 + g.nextInt(3);
        double a0 = g.nextDouble() * 6.283185307179586;
        t.leaf(tx, h, 0, L);
        for (int i = 0; i < fr; i++) {
            double a = a0 + i * 6.283185307179586 / fr;
            int len = 4 + g.nextInt(3);
            for (int s = 1; s <= len + 1; s++) {
                int x = tx + (int) Math.round(Math.cos(a) * s), z = (int) Math.round(Math.sin(a) * s);
                int y = h + (s <= 2 ? 1 : s <= 4 ? 0 : -(s - 4));
                t.leaf(x, y, z, L);
                if (s >= 3 && s % 2 == 1) t.leaf(x, y - 1, z, L);
            }
        }
        String[] face = {"west", "east", "north", "south"};
        int[][] off = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int i = 0; i < 4; i++)
            if (g.nextDouble() < 0.5) t.leaf(tx + off[i][0], h - 2, off[i][1], "minecraft:cocoa[age=2,facing=" + face[i] + "]");
        return t.build("palm/palm_" + (v + 1), meta(1, List.of("minecraft:sand")));
    }

    /** M4 пальцы-светляки: куст тонких ножек со светящимися шариками. */
    static GenObject glowfingers(int v) {
        B t = new B(); GenRandom g = new GenRandom(900 + v);
        int n = 5 + g.nextInt(8);
        t.log(0, 0, 0, "minecraft:nether_wart_block");
        for (int i = 0; i < n; i++) {
            int x = g.nextInt(7) - 3, z = g.nextInt(7) - 3;
            int h = 4 + g.nextInt(7);
            for (int y = 0; y < h; y++) t.leaf(x, y, z, STEM);
            t.leaf(x, h, z, "minecraft:pearlescent_froglight[axis=y]");
            if (g.nextDouble() < 0.5) t.leaf(x + 1, 0, z, "minecraft:nether_wart_block");
        }
        return t.build("glowfingers/glowfingers_" + (v + 1), meta(1, List.of()));
    }

    /** F4 колокольчики-гирлянды: дуга-стебель, с неё свисают колокольчики с «язычком»-фонарём. */
    static GenObject bellflowers(int v) {
        B t = new B(); GenRandom g = new GenRandom(1000 + v);
        int h = 8 + g.nextInt(7), span = 5 + g.nextInt(4);
        String stem = "minecraft:green_concrete";
        int[] topY = new int[span + 2];
        java.util.Arrays.fill(topY, -1);
        for (int s = 0; s <= 40; s++) {
            double u = s / 40.0;
            double y = h * Math.sin(Math.PI * 0.5 * Math.min(1, u * 1.6)) - (u > 0.62 ? (u - 0.62) * h * 0.9 : 0);
            int x = (int) Math.round(span * u), yy = (int) Math.round(y);
            t.log(x, yy, 0, stem);
            topY[x] = Math.max(topY[x], yy);
        }
        int bells = 3 + g.nextInt(4);
        for (int b = 0; b < bells; b++) {
            int bx = 1 + (int) Math.round((span - 1) * (b + 0.5) / bells);
            int by = topY[bx];
            if (by < 6) continue;
            int drop = 1 + g.nextInt(3);
            for (int k = 1; k <= drop; k++) t.leaf(bx, by - k, 0, stem);
            int top = by - drop - 1;
            String c = g.nextDouble() < 0.5 ? "minecraft:light_blue_wool" : "minecraft:blue_concrete";
            t.leaf(bx, top, 0, c);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) t.leaf(bx + dx, top - 1, dz, c);
            t.leaf(bx, top - 1, 0, "minecraft:sea_lantern");
        }
        return t.build("bellflowers/bellflowers_" + (v + 1), meta(1, GRASS));
    }

    /** F5 раффлезия: пять мясистых лепестков с белыми пятнами, в центре чаша с водой. */
    static GenObject rafflesia(int v) {
        B t = new B(); GenRandom g = new GenRandom(1100 + v);
        double R = 5 + g.nextInt(4);
        double a0 = g.nextDouble() * 6.283185307179586;
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
            double d = Math.sqrt(x * x + z * z);
            if (d > R || d < 2.4) continue;
            double a = Math.atan2(z, x) - a0;
            double lobe = Math.abs(Math.cos(a * 2.5));                          // 5 лепестков
            if (d > 2.6 + (R - 2.6) * (0.35 + 0.65 * lobe)) continue;
            t.log(x, d > R - 1.6 ? 1 : 0, z, g.nextDouble() < 0.18 ? STEM : "minecraft:red_terracotta");
        }
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            double d = Math.sqrt(x * x + z * z);
            if (d > 2.3) continue;
            t.log(x, 0, z, "minecraft:brown_terracotta");
            t.log(x, 1, z, d > 1.2 ? "minecraft:brown_terracotta" : "minecraft:water[level=0]");
            if (d > 1.2) t.log(x, 2, z, "minecraft:brown_terracotta");
        }
        return t.build("rafflesia/rafflesia_" + (v + 1), meta(1, GRASS));
    }

    /** Ель: ствол h, кольца лапника от низа к верху, радиус убывает с «зубцами», макушка. */
    static GenObject spruce(String id, int h, int rMax, int start, long seed, Map<String, Object> meta) {
        B t = new B(); GenRandom g = new GenRandom(seed);
        String L = leaves("spruce");
        for (int y = 0; y < h; y++) t.log(0, y, 0, SPRUCE_LOG);
        int top = h + 1;
        for (int y = start; y <= top; y++) {
            double f = (double) (top - y) / (top - start);                   // 1 внизу → 0 на макушке
            int r = (int) Math.round(f * rMax);
            if (((top - y) & 1) == 1 && r > 1) r--;                          // зубцы: через ряд уже
            if (y >= h) r = 0;
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
                double d = x * x + z * z;
                if (d > r * r + 0.6) continue;
                if (d > (r - 0.5) * (r - 0.5) && r >= 2 && g.nextDouble() < 0.25) continue;
                t.leaf(x, y, z, L);
            }
        }
        t.leaf(0, top, 0, L);
        return t.build(id, meta);
    }

    /** Валун: эллипсоид из смеси камней, низ плоский. */
    static GenObject rock(String id, double rx, double ry, double rz, long seed, String[] mix, Map<String, Object> meta) {
        B t = new B(); GenRandom g = new GenRandom(seed);
        double cy = ry * 0.45;
        for (int y = 0; y <= (int) Math.ceil(cy + ry); y++)
            for (int x = (int) -Math.ceil(rx); x <= Math.ceil(rx); x++)
                for (int z = (int) -Math.ceil(rz); z <= Math.ceil(rz); z++) {
                    double dx = x / rx, dy = (y - cy) / ry, dz = z / rz;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > 1 || (d > 0.7 && g.nextDouble() < 0.3)) continue;
                    t.log(x, y, z, mix[g.nextInt(mix.length)]);
                }
        t.log(0, 0, 0, mix[0]);
        return t.build(id, meta);
    }
}
