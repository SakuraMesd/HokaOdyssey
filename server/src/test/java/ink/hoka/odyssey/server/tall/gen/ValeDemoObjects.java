package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.gen.GenObject;
import ink.hoka.odyssey.gen.GenObjectCodec;
import ink.hoka.odyssey.gen.GenRandom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Свои объекты для карты «vale» (генератор vale@1): дубы, берёзы, ели, кусты, валуны — строятся кодом, детерминированно.
 * Якорь — центр-низ (основание ствола / низ камня), воздух не пишется, листва persistent=true (не опадает).
 * Категории: oak, birch, spruce, bushes, rocks (id объекта — «кат/имя», вложенных категорий нет).
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
        return r;
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
