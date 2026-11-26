package ink.hoka.odyssey.planet.vale;

import ink.hoka.odyssey.planet.vale.Shape.B;
import ink.hoka.odyssey.planet.vale.Shape.Rnd;

/**
 * Процедурные деревья, грибы и цветы vale v2: каждый строитель по случайности ячейки собирает {@link Shape} (якорь —
 * первый воздух над землёй). Только целые, Rnd, floor/abs и StrictMath.sqrt — одинаково на сервере и клиенте.
 * Корни и комли тянутся вниз до рельефа каждой колонки ({@link Env#g}). Лимит: |dx|,|dz| ≤ 64, dy ≤ 256.
 */
final class Flora {

    private Flora() {}

    /** Рельеф вокруг якоря: высота верхнего блока колонки (dx, dz) относительно якоря (якорь — g = −1). */
    interface Env {
        int g(int dx, int dz);
        boolean wet(int dx, int dz);
        /** Верх колонки — почва (трава, подзол, мох…): на ней держатся лепестки и прочие «на земле». */
        boolean soil(int dx, int dz);
        /** Колонка — разлом. */
        boolean rift(int dx, int dz);
    }

    /** Насколько глубоко комель/корни тянутся вниз до рельефа (дальше — обрыв, корень не ставится). */
    static final int DEEP_ROOT = 96;

    /** Состояния блоков (номера) — один раз на генератор. */
    static final class St {
        final int spruceWood, spruceWoodS, spruceLeaves, acaciaWood, acaciaWoodS, acaciaLeaves, acaciaLog, darkOakLog, darkOakLeaves,
                oakWood, oakLeaves, mangroveLeaves, cherryWood, cherryLeaves, jungleWood, jungleLeaves, mangroveLog, mangroveRoots,
                paleWood, paleLeaves, birchWood, birchLeaves, azaleaLeaves, floweringAzalea, stripOakLog, mushroomStem, redCap,
                brownCap, shroomlight, warpedStem, warpedWart, verdant, ochre, pearl, purpleTerra, magentaWool, greenConcrete,
                yellowWool, yellowConcrete, brownWool, honeycomb, greenTerra, limeTerra, limeConcrete, whiteConcrete, pinkWool,
                whiteWool, endRod, amethyst, mossCarpet, propagule, sporeBlossom, caveVine, caveVineTip, weeping, weepingTip,
                twisting, twistingTip, paleMoss, paleMossTip, shelf;
        final int[] vine = new int[4], cocoa = new int[4], resin = new int[4], petals = new int[4], tulip = new int[3];

        St(Pal p) {
            spruceWood = p.log("spruce_wood", 'y'); spruceWoodS = p.log("stripped_spruce_wood", 'y'); spruceLeaves = p.leaves("spruce");
            acaciaWood = p.log("acacia_wood", 'y'); acaciaWoodS = p.log("stripped_acacia_wood", 'y'); acaciaLeaves = p.leaves("acacia");
            acaciaLog = p.log("acacia_log", 'y'); darkOakLog = p.log("dark_oak_log", 'y'); darkOakLeaves = p.leaves("dark_oak");
            oakWood = p.log("oak_wood", 'y'); oakLeaves = p.leaves("oak"); mangroveLeaves = p.leaves("mangrove");
            cherryWood = p.log("cherry_wood", 'y'); cherryLeaves = p.leaves("cherry");
            jungleWood = p.log("jungle_wood", 'y'); jungleLeaves = p.leaves("jungle");
            mangroveLog = p.log("mangrove_log", 'y'); mangroveRoots = p.s("minecraft:mangrove_roots[waterlogged=false]");
            paleWood = p.any("minecraft:pale_oak_wood[axis=y]", "minecraft:dark_oak_wood[axis=y]");
            paleLeaves = p.any("minecraft:pale_oak_leaves[distance=1,persistent=true,waterlogged=false]",
                    "minecraft:dark_oak_leaves[distance=1,persistent=true,waterlogged=false]");
            birchWood = p.log("birch_wood", 'y'); birchLeaves = p.leaves("birch");
            azaleaLeaves = p.leaves("azalea"); floweringAzalea = p.leaves("flowering_azalea");
            stripOakLog = p.log("stripped_oak_log", 'y');
            mushroomStem = p.d("minecraft:mushroom_stem"); redCap = p.d("minecraft:red_mushroom_block");
            brownCap = p.d("minecraft:brown_mushroom_block"); shroomlight = p.d("minecraft:shroomlight");
            warpedStem = p.log("warped_stem", 'y'); warpedWart = p.d("minecraft:warped_wart_block");
            verdant = p.log("verdant_froglight", 'y'); ochre = p.log("ochre_froglight", 'y'); pearl = p.log("pearlescent_froglight", 'y');
            purpleTerra = p.d("minecraft:purple_terracotta"); magentaWool = p.d("minecraft:magenta_wool");
            greenConcrete = p.d("minecraft:green_concrete"); yellowWool = p.d("minecraft:yellow_wool");
            yellowConcrete = p.d("minecraft:yellow_concrete"); brownWool = p.d("minecraft:brown_wool");
            honeycomb = p.d("minecraft:honeycomb_block"); greenTerra = p.d("minecraft:green_terracotta");
            limeTerra = p.d("minecraft:lime_terracotta"); limeConcrete = p.d("minecraft:lime_concrete");
            whiteConcrete = p.d("minecraft:white_concrete"); pinkWool = p.d("minecraft:pink_wool"); whiteWool = p.d("minecraft:white_wool");
            endRod = p.s("minecraft:end_rod[facing=up]"); amethyst = p.s("minecraft:amethyst_cluster[facing=up,waterlogged=false]");
            mossCarpet = p.d("minecraft:moss_carpet");
            propagule = p.s("minecraft:mangrove_propagule[age=4,hanging=true,stage=0,waterlogged=false]");
            sporeBlossom = p.d("minecraft:spore_blossom");
            caveVine = p.s("minecraft:cave_vines_plant[berries=true]"); caveVineTip = p.s("minecraft:cave_vines[age=25,berries=true]");
            weeping = p.d("minecraft:weeping_vines_plant"); weepingTip = p.s("minecraft:weeping_vines[age=25]");
            twisting = p.d("minecraft:twisting_vines_plant"); twistingTip = p.s("minecraft:twisting_vines[age=25]");
            paleMoss = p.any("minecraft:pale_hanging_moss[tip=false]", "minecraft:weeping_vines_plant");
            paleMossTip = p.any("minecraft:pale_hanging_moss[tip=true]", "minecraft:weeping_vines[age=25]");
            shelf = p.s("minecraft:brown_mushroom_block[down=false,east=false,north=false,south=false,up=true,west=false]");
            String[] f = {"east", "west", "south", "north"};
            String[] opp = {"west", "east", "north", "south"};             // опора: блок с этой стороны
            for (int i = 0; i < 4; i++) {
                // лиана в (опора + сторона i): держится за блок со стороны opp[i]
                vine[i] = p.s("minecraft:vine[" + vineProps(opp[i]) + "]");
                cocoa[i] = p.s("minecraft:cocoa[age=2,facing=" + opp[i] + "]");
                resin[i] = p.any("minecraft:resin_clump[" + faceProps(opp[i]) + "]", "minecraft:air");
                petals[i] = p.any("minecraft:pink_petals[facing=" + f[i] + ",flower_amount=" + (i + 1) + "]", "minecraft:pink_tulip");
            }
            tulip[0] = p.d("minecraft:red_concrete"); tulip[1] = p.d("minecraft:orange_concrete"); tulip[2] = p.d("minecraft:pink_concrete");
        }

        private static String vineProps(String face) {
            return "east=" + face.equals("east") + ",north=" + face.equals("north") + ",south=" + face.equals("south") + ",up=false,west="
                    + face.equals("west");
        }

        private static String faceProps(String face) {
            return "down=false,east=" + face.equals("east") + ",north=" + face.equals("north") + ",south=" + face.equals("south")
                    + ",up=false,waterlogged=false,west=" + face.equals("west");
        }
    }

    static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};

    // ───────────── общие куски ─────────────

    /** Диск ствола радиуса rad на высоте y (put). */
    static void disc(B b, double cx, int y, double cz, double rad, int st) {
        int r = (int) Math.ceil(rad);
        for (int z = -r - 1; z <= r + 1; z++) for (int x = -r - 1; x <= r + 1; x++) {
            int bx = (int) Math.floor(cx + 0.5) + x, bz = (int) Math.floor(cz + 0.5) + z;
            double dx = bx - cx, dz = bz - cz;
            if (dx * dx + dz * dz <= rad * rad + 0.3) b.put(bx, y, bz, st);
        }
    }

    /** Комель: колонки ствола на нулевом слое тянутся вниз до рельефа. */
    static void footing(B b, Env e, int st) {
        for (int z = -8; z <= 8; z++) for (int x = -8; x <= 8; x++) {
            if (b.get(x, 0, z) == 0) continue;
            int g = e.g(x, z);
            if (g + 1 < -DEEP_ROOT) continue;                                 // под колонкой обрыв
            for (int y = g + 1; y < 0; y++) b.put(x, y, z, st);
        }
    }

    // ───────────── T1 секвойя-великан ─────────────

    static Shape sequoia(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        int H = r.range(70, 150);
        double r0 = r.range(1.5, 2.6) + (H - 70) / 160.0;
        double[] d = new double[2];
        // ствол сужается: r(y) = r0·(1 − y/H)^0.75
        for (int y = 0; y <= H; y++) {
            double u = 1 - (double) y / (H + 4);
            double rad = r0 * StrictMath.sqrt(u) * StrictMath.sqrt(StrictMath.sqrt(u)) + 0.35;
            disc(b, 0, y, 0, rad, s.spruceWood);
        }
        footing(b, e, s.spruceWood);
        // корни-контрфорсы
        int roots = r.range(4, 8);
        double t0 = r.next();
        for (int i = 0; i < roots; i++) {
            Shape.dir(t0 + (double) i / roots + r.range(-0.04, 0.04), d);
            int len = r.range(5, 9);
            for (int k = 1; k <= len + (int) r0; k++) {
                int x = (int) Math.floor(d[0] * (r0 + k * 0.9) + 0.5), z = (int) Math.floor(d[1] * (r0 + k * 0.9) + 0.5);
                int top = (int) Math.floor((1 - (double) k / (len + r0 + 1)) * (5 + r0 * 2));
                int g = e.g(x, z);
                if (g + 1 < -DEEP_ROOT) break;                                // корень упёрся в обрыв
                for (int y = g + 1; y <= top; y++) b.put(x, y, z, s.spruceWoodS);
            }
        }
        // грибы-полки M6 низко на стволе
        int shelves = r.range(0, 4);
        for (int i = 0; i < shelves; i++) {
            Shape.dir(r.next(), d);
            int y = r.range(3, 18);
            double rad = r0 * 1.05 + 0.9;
            b.add((int) Math.floor(d[0] * rad + 0.5), y, (int) Math.floor(d[1] * rad + 0.5), s.shelf);
        }
        // ветви и ярусы хвои — только верхняя треть
        int crownR = r.range(9, 14);
        int y0 = (int) (H * 0.62);
        double turn = r.next();
        for (int y = y0; y < H - 2; y += r.range(3, 5)) {
            double f = 1 - (double) (y - y0) / (H - y0);                     // 1 внизу кроны → 0 у макушки
            double L = 2.5 + crownR * (0.35 + 0.65 * f);
            int arms = 3 + r.nextInt(2);
            for (int a = 0; a < arms; a++) {
                turn += 0.618;
                Shape.dir(turn, d);
                double ex = d[0] * L, ez = d[1] * L, ey = y + 1 + r.range(-1, 2);
                b.limb(0, y, 0, ex * 0.8, ey, ez * 0.8, 0.9, 0.5, s.spruceWood);
                double cr = 2.2 + 1.6 * f;
                b.blob(ex * 0.75, ey + 0.5, ez * 0.75, cr + 0.8, 1.7, cr + 0.8, s.spruceLeaves, r, 0.35);
                b.blob(ex * 0.4, ey + 1, ez * 0.4, cr, 1.5, cr, s.spruceLeaves, r, 0.3);
            }
        }
        // макушка: конус
        for (int y = H - 6; y <= H + 3; y++) {
            double rad = Math.max(0.6, (H + 3 - y) * 0.55);
            for (int z = -4; z <= 4; z++) for (int x = -4; x <= 4; x++) if (x * x + z * z <= rad * rad) b.add(x, y, z, s.spruceLeaves);
        }
        // редкие лианы на стволе
        if (r.chance(0.35)) for (int i = 0; i < 6; i++) {
            Shape.dir(r.next(), d);
            int y = r.range(6, Math.max(7, y0 - 4));
            double rad = r0 * StrictMath.sqrt(1 - (double) y / (H + 4)) + 0.9;
            int x = (int) Math.floor(d[0] * rad + 0.5), z = (int) Math.floor(d[1] * rad + 0.5);
            int k = Math.abs(d[0]) > Math.abs(d[1]) ? (d[0] > 0 ? 0 : 1) : (d[1] > 0 ? 2 : 3);
            if (b.has(x, y, z) || !b.has(x - DX[k], y, z - DZ[k])) continue;
            int len = r.range(4, 12);
            for (int q = 0; q < len && !b.has(x, y - q, z) && y - q > e.g(x, z); q++) b.add(x, y - q, z, s.vine[k]);
        }
        return b.build();
    }

    // ───────────── T13 ель-пагода ─────────────

    static Shape pagoda(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        int H = r.range(35, 70);
        boolean thick = H > 55;
        for (int y = 0; y <= H; y++) {
            b.put(0, y, 0, s.spruceWood);
            if (thick && y < H * 0.7) { b.put(1, y, 0, s.spruceWood); b.put(0, y, 1, s.spruceWood); b.put(1, y, 1, s.spruceWood); }
        }
        footing(b, e, s.spruceWood);
        double Rm = r.range(5.0, 8.0);
        for (int y = r.range(6, 9); y < H - 2; y += r.range(4, 5)) {
            double u = 1 - (double) y / H;
            double R = Rm * StrictMath.sqrt(u) + 1.2;
            int ri = (int) Math.ceil(R);
            for (int z = -ri; z <= ri + 1; z++) for (int x = -ri; x <= ri + 1; x++) {
                double dx = x - (thick ? 0.5 : 0), dz = z - (thick ? 0.5 : 0);
                double dd = StrictMath.sqrt(dx * dx + dz * dz);
                if (dd > R) continue;
                if (dd > R - 1.2 && r.chance(0.3)) continue;
                int yy = dd > R - 1.8 ? y - 1 : dd < R * 0.45 ? y + 1 : y;    // юбка опущена по краям
                b.add(x, yy, z, s.spruceLeaves);
                if (dd < R * 0.45) b.add(x, y, z, s.spruceLeaves);
            }
        }
        for (int y = H - 2; y <= H + 2; y++) {
            b.add(0, y, 0, s.spruceLeaves);
            if (y < H + 1) for (int k = 0; k < 4; k++) b.add(DX[k], y, DZ[k], s.spruceLeaves);
        }
        return b.build();
    }

    // ───────────── T7 тропический гигант (+ иногда пара с лианой-мостом) ─────────────

    static Shape jungleGiant(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        double[] d = new double[2];
        int H = jungleTree(b, s, r, e, 0, 0, r.range(50, 95), d);
        if (r.chance(0.15)) {                                                  // пара: второе дерево и провисший мост
            Shape.dir(r.next(), d);
            int dist = r.range(24, 34);
            int ox = (int) Math.floor(d[0] * dist + 0.5), oz = (int) Math.floor(d[1] * dist + 0.5);
            boolean flat = !e.wet(ox, oz) && !e.rift(ox, oz) && Math.abs(e.g(ox, oz) + 1) < 12;
            for (int q = 0; q < 4 && flat; q++)                                // ровное место под второй комель
                flat = Math.abs(e.g(ox + (q < 2 ? -2 : 2), oz + ((q & 1) == 0 ? -2 : 2)) - e.g(ox, oz)) <= 3;
            if (flat) {
                int gy = e.g(ox, oz) + 1;
                int H2 = jungleTreeAt(b, s, r, e, ox, gy, oz, r.range(40, 60), d);
                int ya = (int) (H * 0.72), yb = gy + (int) (H2 * 0.72);
                int steps = dist * 2;
                for (int i = 0; i <= steps; i++) {
                    double t = (double) i / steps;
                    double sag = 4 * (4 + dist / 6.0) * t * (1 - t);            // парабола провиса
                    int x = (int) Math.floor(ox * t + 0.5), z = (int) Math.floor(oz * t + 0.5);
                    int y = (int) Math.floor(ya + (yb - ya) * t - sag + 0.5);
                    b.put(x, y, z, s.mangroveRoots);
                    b.add(x, y + 1, z, s.mossCarpet);
                    if (r.chance(0.25)) b.hang(x, y - 1, z, r.range(2, 6), s.caveVine, s.caveVineTip);
                }
            }
        }
        return b.build();
    }

    private static int jungleTree(B b, St s, Rnd r, Env e, int ox, int oz, int H, double[] d) {
        return jungleTreeAt(b, s, r, e, ox, 0, oz, H, d);
    }

    /** Дерево с основанием в (ox, oy, oz); ответ — высота. */
    private static int jungleTreeAt(B b, St s, Rnd r, Env e, int ox, int oy, int oz, int H, double[] d) {
        double r0 = H > 75 ? 1.5 : 1.0;
        for (int y = 0; y <= H; y++) disc(b, ox, oy + y, oz, r0 + (y < 6 ? (6 - y) * 0.15 : 0), s.jungleWood);
        for (int z = -3; z <= 3; z++) for (int x = -3; x <= 3; x++) {            // комель вниз
            if (b.get(ox + x, oy, oz + z) == 0) continue;
            int g = e.g(ox + x, oz + z);
            if (g + 1 < oy - DEEP_ROOT) continue;
            for (int y = g + 1; y < oy; y++) b.put(ox + x, y, oz + z, s.jungleWood);
        }
        // корни-доски
        int roots = r.range(4, 6);
        double t0 = r.next();
        for (int i = 0; i < roots; i++) {
            Shape.dir(t0 + (double) i / roots, d);
            int len = r.range(4, 7);
            for (int k = 1; k <= len; k++) {
                int x = ox + (int) Math.floor(d[0] * (r0 + k) + 0.5), z = oz + (int) Math.floor(d[1] * (r0 + k) + 0.5);
                int top = oy + (int) Math.floor(6.0 * (1 - (double) k / (len + 1)));
                int g = e.g(x, z);
                if (g + 1 < oy - DEEP_ROOT) break;
                for (int y = g + 1; y <= top; y++) b.put(x, y, z, s.jungleWood);
            }
        }
        // крона: 3–5 клякс у макушки, ветви к ним
        int blobs = r.range(3, 5);
        int cy = oy + (int) (H * 0.85);
        int[] cand = new int[0];
        for (int i = 0; i < blobs; i++) {
            Shape.dir(t0 + 0.13 + (double) i / blobs, d);
            double dist = r.range(4.0, 8.0), rad = r.range(4.0, 6.5);
            double bx = ox + d[0] * dist, bz = oz + d[1] * dist, by = cy + r.range(-2, 5);
            b.limb(ox, cy - 4, oz, bx, by - 1, bz, 1.0, 0.5, s.jungleWood);
            b.blob(bx, by, bz, rad, rad * 0.55, rad, s.jungleLeaves, r, 0.35);
        }
        b.blob(ox, oy + H + 1, oz, 4.5, 3, 4.5, s.jungleLeaves, r, 0.3);
        // лианы с краёв кроны почти до земли, какао и лианы на стволе
        for (int i = 0; i < 40; i++) {
            Shape.dir(r.next(), d);
            double dist = r.range(5.0, 12.0);
            int x = ox + (int) Math.floor(d[0] * dist + 0.5), z = oz + (int) Math.floor(d[1] * dist + 0.5);
            int y = cy + 6;
            while (y > cy - 8 && !b.has(x, y, z)) y--;
            if (!b.has(x, y, z)) continue;
            int k = r.nextInt(4), nx = x + DX[k], nz = z + DZ[k];
            int ground = e.g(nx, nz);
            int len = Math.min(r.range(6, 40), y - ground - 2);
            for (int q = 0; q < len && !b.has(nx, y - q, nz); q++) b.add(nx, y - q, nz, s.vine[k]);
        }
        for (int y = 2; y < H * 0.7; y++) for (int k = 0; k < 4; k++) {
            int x = ox + (int) Math.floor(DX[k] * (r0 + 1) + 0.5), z = oz + (int) Math.floor(DZ[k] * (r0 + 1) + 0.5);
            if (b.has(x, oy + y, z) || !b.has(x - DX[k], oy + y, z - DZ[k])) continue;
            double p = r.next();
            if (p < 0.22) b.add(x, oy + y, z, s.vine[k]);
            else if (p < 0.24 && y < 12) b.add(x, oy + y, z, s.cocoa[k]);
        }
        return H;
    }

    // ───────────── T2 баобаб ─────────────

    static Shape baobab(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        double[] d = new double[2];
        int H = r.range(18, 28);
        double Rm = r.range(2.5, 4.0);
        for (int y = 0; y <= H; y++) {
            double u = (double) y / H - 0.33;
            double rad = Rm * (0.7 + 0.3 * (1 - u * u * 4));
            disc(b, 0, y, 0, Math.max(1.0, rad), r.chance(0.15) ? s.acaciaWoodS : s.acaciaWood);
        }
        footing(b, e, s.acaciaWood);
        int arms = r.range(5, 8);
        double t0 = r.next();
        for (int i = 0; i < arms; i++) {
            Shape.dir(t0 + (double) i / arms + r.range(-0.03, 0.03), d);
            double L = r.range(5.0, 8.5), up = r.range(3.0, 6.0);
            double ex = d[0] * (L + Rm * 0.5), ez = d[1] * (L + Rm * 0.5), ey = H + up;
            b.limb(d[0] * Rm * 0.4, H - 1, d[1] * Rm * 0.4, ex, ey, ez, 1.3, 0.6, s.acaciaWood);
            b.blob(ex, ey + 1, ez, r.range(2.8, 3.8), 1.2, r.range(2.8, 3.8), s.acaciaLeaves, r, 0.3);
        }
        return b.build();
    }

    // ───────────── T3 зонтичное дерево ─────────────

    static Shape umbrella(Pal pal, St s, Rnd r, Env e, boolean acacia) {
        B b = new B(pal);
        double[] d = new double[2];
        int log = acacia ? s.acaciaLog : s.darkOakLog, leaf = acacia ? s.acaciaLeaves : s.oakLeaves;
        int H = r.range(16, 30);
        Shape.dir(r.next(), d);
        double lean = r.range(2.0, 6.0);
        double mx = d[0] * lean * 0.45, mz = d[1] * lean * 0.45, tx = d[0] * lean, tz = d[1] * lean;
        int ym = (int) (H * 0.55);
        b.limb(0, 0, 0, mx, ym, mz, 0.8, 0.7, log);
        b.limb(mx, ym, mz, tx, H, tz, 0.7, 0.6, log);
        b.put(0, 0, 0, log);
        footing(b, e, log);
        int arms = r.range(3, 5);
        double t0 = r.next();
        double R = r.range(8.0, 12.5);
        for (int i = 0; i < arms; i++) {
            Shape.dir(t0 + (double) i / arms, d);
            double L = R * 0.5;
            b.limb(tx, H, tz, tx + d[0] * L, H + 1.5, tz + d[1] * L, 0.6, 0.5, log);
        }
        // плоская крона в два слоя
        int ri = (int) Math.ceil(R);
        for (int z = -ri; z <= ri; z++) for (int x = -ri; x <= ri; x++) {
            double dd = x * x + z * z;
            int bx = (int) Math.floor(tx + 0.5) + x, bz = (int) Math.floor(tz + 0.5) + z;
            if (dd <= R * R && !(dd > (R - 1.5) * (R - 1.5) && r.chance(0.35))) b.add(bx, H + 2, bz, leaf);
            if (dd <= (R - 2.5) * (R - 2.5) && !(dd > (R - 4) * (R - 4) && r.chance(0.4))) b.add(bx, H + 3, bz, leaf);
            if (dd <= (R * 0.45) * (R * 0.45)) b.add(bx, H + 1, bz, leaf);
        }
        return b.build();
    }

    // ───────────── T12 драконово дерево ─────────────

    static Shape dragon(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        double[] d = new double[2];
        int H = r.range(10, 18);
        int h1 = H / 2;
        for (int y = 0; y <= h1; y++) b.put(0, y, 0, s.stripOakLog);
        footing(b, e, s.stripOakLog);
        // ветвление: 3 уровня, каждый раз на 2
        double[] px = {0}, py = {h1}, pz = {0};
        double t0 = r.next();
        double seg = (H - h1) / 3.0 + 0.5;
        for (int lev = 0; lev < 3; lev++) {
            int n = px.length;
            double[] nx = new double[n * 2], ny = new double[n * 2], nz = new double[n * 2];
            for (int i = 0; i < n; i++) for (int k = 0; k < 2; k++) {
                Shape.dir(t0 + (double) (i * 2 + k) / (n * 2) + lev * 0.11, d);
                double out = seg * (0.8 + lev * 0.15);
                int j = i * 2 + k;
                nx[j] = px[i] + d[0] * out; nz[j] = pz[i] + d[1] * out; ny[j] = py[i] + seg;
                b.limb(px[i], py[i], pz[i], nx[j], ny[j], nz[j], 0.7 - lev * 0.1, 0.5, s.stripOakLog);
            }
            px = nx; py = ny; pz = nz;
        }
        // плотный зонт-«гриб»
        double R = r.range(5.5, 8.0);
        double top = py[0] + 1;
        int ri = (int) Math.ceil(R);
        for (int z = -ri; z <= ri; z++) for (int x = -ri; x <= ri; x++) {
            double dd = (x * x + z * z) / (R * R);
            if (dd > 1) continue;
            int th = dd < 0.5 ? 3 : dd < 0.85 ? 2 : 1;
            int yb = (int) Math.floor(top + 0.5) + (dd < 0.3 ? 1 : 0) - (dd > 0.85 ? 1 : 0);
            for (int k = 0; k < th; k++) b.add(x, yb + k, z, s.darkOakLeaves);
        }
        return b.build();
    }

    // ───────────── T5 сакура-великан ─────────────

    static Shape sakura(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        double[] d = new double[2];
        int H = r.range(16, 28);
        int ht = (int) (H * 0.4);
        for (int y = 0; y <= ht; y++) disc(b, 0, y, 0, 1.0, s.cherryWood);
        footing(b, e, s.cherryWood);
        int arms = r.range(3, 5);
        double t0 = r.next();
        for (int i = 0; i < arms; i++) {
            Shape.dir(t0 + (double) i / arms + r.range(-0.05, 0.05), d);
            double L = r.range(8.0, 13.0), up = L * r.range(0.45, 0.9);
            double ex = d[0] * L, ez = d[1] * L, ey = ht + up;
            b.limb(0, ht, 0, ex, ey, ez, 1.0, 0.5, s.cherryWood);
            b.blob(ex, ey + 1.5, ez, r.range(4.0, 5.5), r.range(2.4, 3.2), r.range(4.0, 5.5), s.cherryLeaves, r, 0.3);
            b.blob(ex * 0.55, ey - up * 0.3 + 2, ez * 0.55, 3, 2, 3, s.cherryLeaves, r, 0.35);
            // свисающие «плети» листвы
            for (int k = 0; k < 6; k++) {
                int x = (int) Math.floor(ex + r.range(-4.0, 4.0) + 0.5), z = (int) Math.floor(ez + r.range(-4.0, 4.0) + 0.5);
                int y = (int) Math.floor(ey) + 3;
                while (y > ey - 4 && !b.has(x, y, z)) y--;
                if (b.has(x, y, z)) b.hang(x, y - 1, z, r.range(1, 3), s.cherryLeaves, s.cherryLeaves);
            }
        }
        b.blob(0, H, 0, 3.5, 2.5, 3.5, s.cherryLeaves, r, 0.3);
        // ковёр лепестков под кроной
        for (int z = -12; z <= 12; z++) for (int x = -12; x <= 12; x++) {
            if (x * x + z * z > 130 || !r.chance(0.35) || e.wet(x, z) || !e.soil(x, z)) continue;
            int g = e.g(x, z);
            if (Math.abs(g + 1) > 6) continue;
            b.add(x, g + 1, z, s.petals[r.nextInt(4)]);
        }
        return b.build();
    }

    // ───────────── T4 плакучая ива ─────────────

    static Shape willow(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        int H = r.range(7, 10);
        for (int y = 0; y <= H; y++) disc(b, 0.5, y, 0.5, y < 2 ? 1.6 : 1.0, s.oakWood);
        footing(b, e, s.oakWood);
        double[] d = new double[2];
        double t0 = r.next();
        for (int i = 0; i < 5; i++) {
            Shape.dir(t0 + i / 5.0, d);
            b.limb(0.5, H - 1, 0.5, 0.5 + d[0] * 5, H + 3, 0.5 + d[1] * 5, 0.7, 0.5, s.oakWood);
        }
        double R = r.range(8.0, 10.5), cy = H + 4;
        b.blob(0.5, cy, 0.5, R, 4, R, s.mangroveLeaves, r, 0.4);
        // «шторы»: столбики листвы по краю купола, под ними лианы
        int ri = (int) Math.ceil(R);
        for (int z = -ri; z <= ri + 1; z++) for (int x = -ri; x <= ri + 1; x++) {
            double dx = x - 0.5, dz = z - 0.5, dd = StrictMath.sqrt(dx * dx + dz * dz);
            if (dd < R - 3 || dd > R + 0.5 || !r.chance(0.55)) continue;
            int y = (int) cy + 2;
            while (y > cy - 5 && !b.has(x, y, z)) y--;
            if (!b.has(x, y, z)) continue;
            while (y > cy - 5 && b.has(x, y - 1, z)) y--;                     // низ купола в этой колонке
            int g = e.g(x, z);
            int len = Math.min(r.range(3, 10), y - g - 2);
            if (len <= 0) continue;
            if (b.hang(x, y - 1, z, len, s.mangroveLeaves, s.mangroveLeaves) < len) continue;
            int vl = Math.min(r.range(2, 5), y - len - g - 2);
            if (vl > 0) b.hang(x, y - len - 1, z, vl, s.paleMoss, s.paleMossTip);
        }
        return b.build();
    }

    // ───────────── T8 мангр на ходулях ─────────────

    static Shape mangrove(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        double[] d = new double[2];
        int S = r.range(6, 10), H2 = r.range(6, 10);
        int roots = r.range(5, 8);
        double t0 = r.next();
        for (int i = 0; i < roots; i++) {
            Shape.dir(t0 + (double) i / roots, d);
            double R = r.range(4.0, 6.5);
            int steps = 24;
            for (int k = 0; k <= steps; k++) {
                double t = (double) k / steps;
                int x = (int) Math.floor(d[0] * R * (1 - t) + 0.5), z = (int) Math.floor(d[1] * R * (1 - t) + 0.5);
                int g = e.g(x, z);
                double u = 1 - t;
                int y = (int) Math.floor(g + 1 + (S - g - 1) * (1 - u * u) + 0.5);
                b.put(x, y, z, s.mangroveRoots);
                if (k == 0) for (int q = g + 1; q < y; q++) b.put(x, q, z, s.mangroveRoots);
            }
        }
        for (int y = S; y <= S + H2; y++) b.put(0, y, 0, s.mangroveLog);
        double cr = r.range(4.0, 6.0);
        b.blob(0, S + H2, 0, cr, cr * 0.6, cr, s.mangroveLeaves, r, 0.3);
        b.blob(r.range(-2, 2), S + H2 - 2, r.range(-2, 2), cr * 0.7, 2, cr * 0.7, s.mangroveLeaves, r, 0.3);
        int ri = (int) Math.ceil(cr) + 1;
        for (int z = -ri; z <= ri; z++) for (int x = -ri; x <= ri; x++) {
            int y = S + H2 + 6;
            while (y > S && !b.has(x, y, z)) y--;
            if (y <= S) continue;
            if (b.get(x, y, z) == s.mangroveLeaves && r.chance(0.25)) b.add(x, y + 1, z, s.mossCarpet);
            int yb = S + 1;
            while (yb < y && !b.has(x, yb, z)) yb++;
            if (yb < y && b.get(x, yb, z) == s.mangroveLeaves && r.chance(0.12)) b.add(x, yb - 1, z, s.propagule);
        }
        return b.build();
    }

    // ───────────── T9 призрачный дуб-великан ─────────────

    static Shape ghostOak(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        double[] d = new double[2];
        int H = r.range(20, 35);
        double cx = 0, cz = 0;
        for (int y = 0; y <= H; y++) {
            disc(b, cx, y, cz, 1.4 + (y < 4 ? (4 - y) * 0.3 : 0), s.paleWood);
            if (r.chance(0.08)) b.blob(cx + r.range(-1.5, 1.5), y, cz + r.range(-1.5, 1.5), 1.6, 1.4, 1.6, s.paleWood, r, 0);
            cx += r.range(-0.3, 0.3); cz += r.range(-0.3, 0.3);
        }
        footing(b, e, s.paleWood);
        int arms = r.range(6, 9);
        double t0 = r.next();
        int[] tips = new int[arms * 3];
        for (int i = 0; i < arms; i++) {
            Shape.dir(t0 + (double) i / arms + r.range(-0.04, 0.04), d);
            double L = r.range(6.0, 11.0);
            double y0 = H * r.range(0.55, 0.95);
            double mx = cx * 0.7 + d[0] * L * 0.5, mz = cz * 0.7 + d[1] * L * 0.5, my = y0 + r.range(1, 4);
            double ex = cx * 0.7 + d[0] * L + r.range(-1.5, 1.5), ez = cz * 0.7 + d[1] * L + r.range(-1.5, 1.5), ey = my + r.range(-1, 4);
            b.limb(cx * 0.8, y0, cz * 0.8, mx, my, mz, 0.9, 0.6, s.paleWood);
            b.limb(mx, my, mz, ex, ey, ez, 0.6, 0.5, s.paleWood);
            b.blob(ex, ey + 1, ez, r.range(3.0, 5.0), r.range(2.0, 3.0), r.range(3.0, 5.0), s.paleLeaves, r, 0.5);
            tips[i * 3] = (int) Math.floor(ex + 0.5); tips[i * 3 + 1] = (int) Math.floor(ey + 0.5); tips[i * 3 + 2] = (int) Math.floor(ez + 0.5);
        }
        b.blob(cx, H + 1, cz, 4.5, 2.5, 4.5, s.paleLeaves, r, 0.5);
        // бороды мха до 8
        for (int i = 0; i < 45; i++) {
            int x = r.range(-14, 14), z = r.range(-14, 14);
            int y = H + 5;
            while (y > H * 0.4 && !b.has(x, y, z)) y--;
            if (!b.has(x, y, z) || b.get(x, y, z) != s.paleLeaves) continue;
            int yb = y;
            while (yb > H * 0.4 && b.has(x, yb - 1, z)) yb--;
            int g = e.g(x, z);
            int len = Math.min(r.range(2, 8), yb - g - 2);
            if (len > 0) b.hang(x, yb - 1, z, len, s.paleMoss, s.paleMossTip);
        }
        // смола и полки на коре
        for (int i = 0; i < 6; i++) {
            int y = r.range(2, Math.max(3, H / 2)), k = r.nextInt(4);
            int xx = 0, zz = 0;
            while (b.has(xx, y, zz) && Math.abs(xx) < 6 && Math.abs(zz) < 6) { xx += DX[k]; zz += DZ[k]; }
            if (b.has(xx, y, zz) || !b.has(xx - DX[k], y, zz - DZ[k])) continue;
            b.add(xx, y, zz, r.chance(0.5) ? s.resin[k] : s.shelf);
        }
        return b.build();
    }

    // ───────────── T10 «леденец»-спираль ─────────────

    static Shape spiral(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        double[] d = new double[2];
        int H = r.range(15, 30);
        double rc = r.range(2.0, 3.0), t0 = r.next();
        double px = 0, py = 0, pz = 0;
        for (int y = 1; y <= H; y++) {
            double u = (double) y / H;
            Shape.dir(t0 + 1.5 * u, d);
            double rr = rc * StrictMath.sqrt(u);
            double nx = d[0] * rr, nz = d[1] * rr;
            b.limb(px, py, pz, nx, y, nz, 0.75, 0.75, s.birchWood);
            px = nx; py = y; pz = nz;
        }
        b.put(0, 0, 0, s.birchWood);
        footing(b, e, s.birchWood);
        int[] cols = {s.birchLeaves, s.azaleaLeaves, s.cherryLeaves, s.floweringAzalea};
        int arms = r.range(3, 5);
        for (int i = 0; i < arms; i++) {
            Shape.dir(t0 + (double) i / arms, d);
            double L = r.range(3.0, 6.0);
            double ex = px + d[0] * L, ez = pz + d[1] * L, ey = py + r.range(1.0, 5.0);
            b.limb(px, py, pz, ex, ey, ez, 0.6, 0.5, s.birchWood);
            double rad = r.range(3.0, 4.8);
            b.blob(ex, ey + rad * 0.7, ez, rad, rad, rad, cols[(i + r.nextInt(4)) & 3], r, 0.15);
        }
        return b.build();
    }

    // ───────────── M1 колокол-светоносец ─────────────

    static Shape bellShroom(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        double[] d = new double[2];
        int H = r.range(15, 40);
        Shape.dir(r.next(), d);
        double bend = r.range(2.0, 6.0);
        double px = 0, py = 0, pz = 0;
        for (int i = 1; i <= H; i++) {
            double t = (double) i / H;
            double off = bend * t * t;                                        // изгиб ножки
            double nx = d[0] * off, nz = d[1] * off, rad = 2.2 - 1.0 * t;
            b.limb(px, py, pz, nx, i, nz, rad, rad, s.mushroomStem);
            px = nx; py = i; pz = nz;
        }
        footing(b, e, s.mushroomStem);
        double R = Math.min(12, r.range(6.0, 8.0) + H * 0.1), capH = R * 0.9;
        int ri = (int) Math.ceil(R) + 1;
        int cx = (int) Math.floor(px + 0.5), cz = (int) Math.floor(pz + 0.5);
        double base = py - capH * 0.35;
        for (int y = 0; y <= (int) Math.ceil(capH); y++) {
            double t = y / capH;                                              // 0 — край, 1 — макушка
            double rr = R * StrictMath.sqrt(Math.max(0, 1 - t * t)) * (1 + 0.15 * (1 - t) * (1 - t));
            int yy = (int) Math.floor(base + y + 0.5);
            for (int z = cz - ri; z <= cz + ri; z++) for (int x = cx - ri; x <= cx + ri; x++) {
                double dx = x - px, dz = z - pz, dd = StrictMath.sqrt(dx * dx + dz * dz);
                if (dd <= rr && dd > rr - 1.6) b.put(x, yy, z, s.redCap);
            }
        }
        // кольцо светильников и ягодные лианы под краем, спора в центре
        int yr = (int) Math.floor(base + 0.5);
        for (int z = cz - ri; z <= cz + ri; z++) for (int x = cx - ri; x <= cx + ri; x++) {
            double dx = x - px, dz = z - pz, dd = StrictMath.sqrt(dx * dx + dz * dz);
            if (dd <= R - 1 && dd > R - 2.6 && b.get(x, yr, z) == 0) b.put(x, yr + 1, z, s.shroomlight);
            if (dd <= R + 0.3 && dd > R - 2.0 && r.chance(0.25)) {           // под низом шляпки в этой колонке
                int y = yr - 1;
                while (y < yr + 4 && !b.has(x, y, z)) y++;
                if (b.has(x, y, z)) b.hang(x, y - 1, z, r.range(2, 7), s.caveVine, s.caveVineTip);
            }
        }
        for (int k = 0; k < 4; k++) {
            int x = cx + DX[k] * 2, z = cz + DZ[k] * 2;
            int y = (int) Math.floor(py + capH);
            while (y > py && !b.has(x, y, z)) y--;
            while (y > py - 3 && b.has(x, y, z)) y--;
            if (!b.has(x, y, z)) b.add(x, y, z, s.sporeBlossom);
        }
        return b.build();
    }

    // ───────────── M2 медузогриб ─────────────

    static Shape medusa(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        double[] d = new double[2];
        int H = r.range(10, 25);
        for (int y = 0; y <= H; y++) b.put(0, y, 0, s.warpedStem);
        footing(b, e, s.warpedStem);
        double R = r.range(5.0, 8.0), ry = R * 0.7;
        int ri = (int) Math.ceil(R);
        for (int y = 0; y <= (int) Math.ceil(ry); y++) for (int z = -ri; z <= ri; z++) for (int x = -ri; x <= ri; x++) {
            double q = (x * x + z * z) / (R * R) + (y * y) / (ry * ry);
            if (q > 1 || q < 0.55) continue;
            b.put(x, H + y, z, r.chance(0.06) ? s.verdant : s.warpedWart);
        }
        // щупальца с края
        double t0 = r.next();
        int n = (int) (R * 2.2);
        for (int i = 0; i < n; i++) {
            Shape.dir(t0 + (double) i / n, d);
            int x = (int) Math.floor(d[0] * (R - 0.6) + 0.5), z = (int) Math.floor(d[1] * (R - 0.6) + 0.5);
            int g = e.g(x, z);
            int len = Math.min(r.range(4, 12), H - 2 - g);
            if (len > 0) b.hang(x, H - 1, z, len, s.weeping, s.weepingTip);
        }
        for (int i = 0; i < 5; i++) {                                         // вьюны вверх у ножки
            int k = r.nextInt(4), x = DX[k] * r.range(1, 3), z = DZ[k] * r.range(1, 3);
            int g = e.g(x, z), len = r.range(2, 5);
            if (e.wet(x, z)) continue;
            for (int q = 1; q <= len && !b.has(x, g + q, z); q++) b.add(x, g + q, z, q == len ? s.twistingTip : s.twisting);
        }
        return b.build();
    }

    // ───────────── M3 гриб-пагода ─────────────

    static Shape pagodaShroom(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        int H = r.range(20, 45);
        double sr = H > 32 ? 1.5 : 1.0;
        for (int y = 0; y <= H; y++) disc(b, 0, y, 0, sr, s.mushroomStem);
        footing(b, e, s.mushroomStem);
        int caps = r.range(3, 5);
        for (int i = 0; i < caps; i++) {
            double f = (double) i / (caps - 1);
            int y = (int) Math.floor(H * (0.45 + 0.55 * f) + 0.5);
            double R = 8 - 5 * f + r.range(-0.5, 0.5);
            int ri = (int) Math.ceil(R);
            for (int z = -ri; z <= ri; z++) for (int x = -ri; x <= ri; x++) {
                double dd = StrictMath.sqrt(x * x + z * z);
                if (dd > R) continue;
                boolean rim = dd > R - 1.3;
                b.put(x, rim ? y - 1 : y, z, rim && ((x * 7 + z * 13) & 3) == 0 ? s.ochre : s.brownCap);
                if (dd < R * 0.4) b.put(x, y + 1, z, s.brownCap);
            }
        }
        return b.build();
    }

    // ───────────── M5 пузырь-аметист ─────────────

    static Shape amethystShroom(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        int H = r.range(12, 22);
        for (int y = 0; y <= H; y++) b.put(0, y, 0, s.mushroomStem);
        footing(b, e, s.mushroomStem);
        int ys = (int) (H * 0.6);
        for (int z = -3; z <= 3; z++) for (int x = -3; x <= 3; x++) {            // «юбка»
            double dd = x * x + z * z;
            if (dd >= 2 && dd <= 7) b.put(x, dd > 4.5 ? ys - 1 : ys, z, s.mushroomStem);
        }
        double R = r.range(4.0, 7.0), ry = R * 0.8;
        double cy = H + ry * 0.6;
        int ri = (int) Math.ceil(R);
        for (int y = (int) Math.floor(cy - ry); y <= (int) Math.ceil(cy + ry); y++)
            for (int z = -ri; z <= ri; z++) for (int x = -ri; x <= ri; x++) {
                double q = (x * x + z * z) / (R * R) + (y - cy) * (y - cy) / (ry * ry);
                if (q > 1 || q < 0.6) continue;
                b.put(x, y, z, r.chance(0.15) ? s.magentaWool : s.purpleTerra);
            }
        int topY = (int) Math.ceil(cy + ry);
        while (topY > cy && !b.has(0, topY, 0)) topY--;
        b.put(0, topY + 1, 0, s.amethyst);
        return b.build();
    }

    // ───────────── F1 подсолнух-великан ─────────────

    private static final double[][] TILT = {{0.3420201433256687, 0.9396926207859084}, {0.42261826174069944, 0.9063077870366499},
            {0.5, 0.8660254037844387}};

    static Shape sunflower(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        double[] d = new double[2];
        int H = r.range(12, 22);
        for (int y = 0; y <= H; y++) b.put(0, y, 0, s.greenConcrete);
        footing(b, e, s.greenConcrete);
        int leaves = r.range(2, 3);
        double t0 = r.next();
        for (int i = 0; i < leaves; i++) {
            Shape.dir(t0 + (double) i / leaves, d);
            int y = (int) (H * (0.3 + 0.2 * i));
            b.blob(d[0] * 2.5, y + 0.5, d[1] * 2.5, 2.6, 0.8, 2.6, s.azaleaLeaves, r, 0.2);
        }
        // диск наклонён к югу (+z) на 20–30°
        double[] tl = TILT[r.nextInt(3)];
        double sn = tl[0], cs = tl[1];
        double R = r.range(4.0, 6.0);
        double cy = H + 1;
        for (double v = -R; v <= R; v += 0.5) for (double u = -R; u <= R; u += 0.5) {
            double rr = u * u + v * v;
            if (rr > R * R) continue;
            double rad = StrictMath.sqrt(rr);
            // V = (0, cos, −sin); диск смотрит на юг и чуть вверх
            int x = (int) Math.floor(u + 0.5), y = (int) Math.floor(cy + v * cs + 0.5), z = (int) Math.floor(1 - v * sn + 0.5);
            int st;
            if (rad < 1.4) st = s.honeycomb;
            else if (rad < R * 0.45) st = s.brownWool;
            else {
                if (rad > R * 0.85 && ((x * 31 + y * 17) & 3) == 0) continue;     // зубчатый край
                st = ((x + y) & 1) == 0 ? s.yellowWool : s.yellowConcrete;
            }
            b.put(x, y, z, st);
            b.add(x, y, z - 1, s.greenConcrete);
        }
        return b.build();
    }

    // ───────────── F2 тюльпан-великан ─────────────

    static Shape tulip(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        double[] d = new double[2];
        int H = r.range(8, 14);
        int petal = s.tulip[r.nextInt(3)];
        for (int y = 0; y <= H; y++) b.put(0, y, 0, s.greenTerra);
        footing(b, e, s.greenTerra);
        int n = r.range(5, 6);
        double t0 = r.next();
        double[] px = new double[n], pz = new double[n];
        for (int i = 0; i < n; i++) { Shape.dir(t0 + (double) i / n, d); px[i] = d[0]; pz[i] = d[1]; }
        int cupH = r.range(5, 7);
        for (int y = 0; y <= cupH; y++) {
            double t = (double) y / cupH;
            double rr = 1.3 + 2.6 * StrictMath.sqrt(t) + (t > 0.7 ? (t - 0.7) * 3 : 0);
            int ri = (int) Math.ceil(rr) + 1;
            for (int z = -ri; z <= ri; z++) for (int x = -ri; x <= ri; x++) {
                double dd = StrictMath.sqrt(x * x + z * z);
                if (dd > rr || dd < rr - 1.3) continue;
                if (t > 0.45 && dd > 0.5) {                                   // щели между «ладонями» вверху
                    double best = -2;
                    for (int i = 0; i < n; i++) best = Math.max(best, (x * px[i] + z * pz[i]) / dd);
                    if (best < 0.93 - 0.2 * (1 - t)) continue;
                }
                b.put(x, H + 1 + y, z, petal);
            }
        }
        b.put(0, H + 1, 0, s.yellowWool);
        for (int k = 0; k < 4; k++) b.add(DX[k], H + 2, DZ[k], s.endRod);
        b.add(0, H + 2, 0, s.endRod);
        return b.build();
    }

    // ───────────── F3 ромашка-великан ─────────────

    static Shape daisy(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        double[] d = new double[2];
        int H = r.range(6, 12);
        for (int y = 0; y <= H; y++) b.put(0, y, 0, s.limeTerra);
        footing(b, e, s.limeTerra);
        int rays = r.range(12, 16);
        double t0 = r.next(), R = r.range(4.0, 6.0);
        double[] rx = new double[rays], rz = new double[rays];
        for (int i = 0; i < rays; i++) { Shape.dir(t0 + (double) i / rays, d); rx[i] = d[0]; rz[i] = d[1]; }
        int ri = (int) Math.ceil(R);
        for (int z = -ri; z <= ri; z++) for (int x = -ri; x <= ri; x++) {
            double dd = StrictMath.sqrt(x * x + z * z);
            if (dd > R) continue;
            if (dd < 1.6) { b.put(x, H + 1, z, s.yellowConcrete); continue; }
            double best = -2;
            for (int i = 0; i < rays; i++) best = Math.max(best, (x * rx[i] + z * rz[i]) / dd);
            double w = 0.55 / dd;                                              // ширина луча ~1 блок
            if (1 - best < w * w * 0.5 + 0.02) b.put(x, dd > R - 1 ? H : H + 1, z, s.whiteConcrete);
        }
        return b.build();
    }

    // ───────────── F6 кувшинка-великан (на воде) ─────────────

    static Shape lilyPad(Pal pal, St s, Rnd r, Env e) {
        B b = new B(pal);
        double[] d = new double[2];
        double R = r.range(4.0, 7.0);
        Shape.dir(r.next(), d);
        int ri = (int) Math.ceil(R);
        for (int z = -ri; z <= ri; z++) for (int x = -ri; x <= ri; x++) {
            double dd = StrictMath.sqrt(x * x + z * z);
            if (dd > R) continue;
            if (dd > 1.2 && (x * d[0] + z * d[1]) / dd > 0.92) continue;      // вырез-«клин»
            boolean rim = dd > R - 1;
            b.put(x, rim ? 1 : 0, z, rim ? s.limeConcrete : s.greenConcrete);
            if (rim) b.put(x, 0, z, s.greenConcrete);
        }
        b.put(0, 1, 0, s.pinkWool);
        for (int k = 0; k < 4; k++) b.put(DX[k], 1, DZ[k], r.chance(0.5) ? s.pinkWool : s.whiteWool);
        b.put(0, 2, 0, s.pinkWool);
        b.put(0, 3, 0, s.whiteWool);
        return b.build();
    }
}
