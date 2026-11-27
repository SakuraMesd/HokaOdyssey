package ink.hoka.odyssey.planet.space;

import ink.hoka.odyssey.gen.GenContext;
import ink.hoka.odyssey.gen.GenCube;
import ink.hoka.odyssey.gen.GenIds;
import ink.hoka.odyssey.gen.GenNoise;
import ink.hoka.odyssey.gen.GenRandom;
import ink.hoka.odyssey.gen.PlanetGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * Космос. Пространство делится на клетки: астероид — клетка {@value #A} блоков (12×12×12 кубов), метеорит — {@value #B}
 * (4×4×4). В клетке не больше одного тела, и оно целиком внутри клетки — куб всегда в одной клетке каждого вида, поэтому
 * «есть ли тут что-то» — одна-две проверки (почти все кубы пусты и не генерируются). Скопления: клетка {@value #C},
 * в редких — шар радиусом 350–650, где астероиды встречаются в разы чаще (и мельче).
 * <p>Видимая часть (форма, порода, реголит, кратеры) — от сида карты, одинаково на сервере и клиенте. Руды, пустоты
 * тайников и сундуки с лутом — секреты от secret_seed (только сервер; игроку — вблизи).</p>
 * <p>generator_options: asteroid_chance (0.04), meteor_chance (0.006), cluster_chance (0.12), cluster_boost (0.45),
 * exclude ("x,y,z,r;…" — пусто вокруг планет), spawn_rock ("x,y,z,r" — гарантированный астероид у точки входа),
 * блоки: rock, regolith, meteor, ore_coal … ore_magnesium (состояния; по умолчанию — ванильные замены).</p>
 */
final class SpaceGen implements PlanetGenerator {

    static final int A = 192, B = 64, C = 1536;
    static final String[] ORE_NAMES = {"coal", "copper", "iron", "silicon", "nickel", "cobalt", "silver", "gold", "platinum", "uranium", "magnesium"};
    /** Частота руд (вес выбора вида в астероиде). */
    static final double[] ORE_W = {20, 16, 16, 12, 9, 6, 5, 3.5, 1.8, 1.2, 10};
    /** Металлические астероиды и метеориты — тяжёлые металлы чаще. */
    static final double[] ORE_W_METAL = {2, 10, 22, 4, 18, 12, 8, 6, 4, 2.5, 6};
    static final String[] ORE_FALLBACK = {"coal_ore", "copper_ore", "iron_ore", "amethyst_block", "raw_iron_block", "lapis_ore",
            "diamond_ore", "gold_ore", "emerald_ore", "glowstone", "calcite"};

    static final int ROCK = 0, ICE = 1, METAL = 2, METEOR = 3;

    private final long seed;
    private final boolean secretsOn;
    private final long secretSeed;
    private final GenNoise shapeN, shapeN2, veinN, pickN, veinPub, pickPub;
    /** Руды видны всем (без защиты от X-ray): от сида карты, в видимой части; false — секреты от secret_seed. */
    private final boolean oresVisible;
    private final int rock, regolith, meteor, ice, snow, chest;
    private final int[] ore = new int[ORE_NAMES.length];
    private final double chance, meteorChance, clusterChance, clusterBoost;
    private final double[][] exclude;
    private final Body spawn;

    SpaceGen(GenContext ctx) {
        seed = ctx.seed();
        secretsOn = ctx.server();
        secretSeed = secretsOn ? ctx.secretSeed() : 0;
        shapeN = new GenNoise(seed ^ 0x51A9E01L);
        shapeN2 = new GenNoise(seed ^ 0x51A9E02L);
        veinN = new GenNoise(secretSeed ^ 0x0E5E0E5EL);                // секретный шум — на клиенте бесполезен
        pickN = new GenNoise(secretSeed ^ 0x0E5E0B1CL);
        veinPub = new GenNoise(seed ^ 0x0E5E0E5EL);
        pickPub = new GenNoise(seed ^ 0x0E5E0B1CL);
        oresVisible = !"false".equalsIgnoreCase(ctx.stringOption("ores_visible", "true"));
        GenIds ids = ctx.ids();
        rock = st(ids, ctx.stringOption("rock", "minecraft:tuff"));
        regolith = st(ids, ctx.stringOption("regolith", "minecraft:gravel"));
        meteor = st(ids, ctx.stringOption("meteor", "minecraft:blackstone"));
        ice = st(ids, ctx.stringOption("ice", "minecraft:packed_ice"));
        snow = st(ids, ctx.stringOption("frost", "minecraft:snow_block"));
        chest = st(ids, "minecraft:chest[facing=north,type=single,waterlogged=false]");
        for (int i = 0; i < ORE_NAMES.length; i++)
            ore[i] = st(ids, ctx.stringOption("ore_" + ORE_NAMES[i], "minecraft:" + ORE_FALLBACK[i]));
        chance = ctx.doubleOption("asteroid_chance", 0.04);
        meteorChance = ctx.doubleOption("meteor_chance", 0.006);
        clusterChance = ctx.doubleOption("cluster_chance", 0.12);
        clusterBoost = ctx.doubleOption("cluster_boost", 0.45);
        List<double[]> ex = new ArrayList<>();
        for (String part : ctx.stringOption("exclude", "").split(";")) {
            double[] v = nums(part);
            if (v.length == 4) ex.add(v);
        }
        exclude = ex.toArray(new double[0][]);
        double[] sp = nums(ctx.stringOption("spawn_rock", ""));
        spawn = sp.length == 4 ? body(sp[0], sp[1], sp[2], sp[3], ROCK, new GenRandom(seed ^ 0x5BA4L), false) : null;
    }

    private static int st(GenIds ids, String s) { return ids.state(GenIds.normalize(s)); }

    private static double[] nums(String s) {
        if (s == null || s.isBlank()) return new double[0];
        String[] p = s.split(",");
        double[] v = new double[p.length];
        try { for (int i = 0; i < p.length; i++) v[i] = Double.parseDouble(p[i].trim()); } catch (NumberFormatException e) { return new double[0]; }
        return v;
    }

    // ───────────────────────── тела ─────────────────────────

    /** Тело: центр, радиус, оси эллипсоида, вид, сдвиг шума, кратеры, руды (виды), тайник. */
    static final class Body {
        double x, y, z, r, ax, ay, az, ext, ox, oy, oz;
        int kind;
        double[] craters = new double[0];            // по 4: x, y, z, радиус
        int[] ores = new int[0];
        double vein;                                 // порог жилы (меньше — богаче)
        boolean loot;
        int lx, ly, lz;                              // сундук тайника (мировые)

        boolean hits(int x0, int y0, int z0) {
            return x0 + 16 > x - ext && x0 < x + ext && y0 + 16 > y - ext && y0 < y + ext && z0 + 16 > z - ext && z0 < z + ext;
        }
    }

    private Body body(double x, double y, double z, double r, int kind, GenRandom g, boolean lootable) {
        Body b = new Body();
        b.x = x; b.y = y; b.z = z; b.r = r; b.kind = kind;
        b.ax = 0.75 + 0.5 * g.nextDouble();
        b.ay = 0.7 + 0.45 * g.nextDouble();
        b.az = 0.75 + 0.5 * g.nextDouble();
        b.ext = r * 1.4 * Math.max(b.ax, Math.max(b.ay, b.az)) + 1;
        b.ox = g.nextDouble() * 200; b.oy = g.nextDouble() * 200; b.oz = g.nextDouble() * 200;
        if (r >= 10 && kind != METEOR) {                                        // кратеры на поверхности
            int n = 1 + g.nextInt(r >= 24 ? 4 : 2);
            b.craters = new double[n * 4];
            for (int i = 0; i < n; i++) {
                double[] d = dir(g);
                double cr = r * (0.18 + 0.14 * g.nextDouble());
                b.craters[i * 4] = x + d[0] * r * b.ax * 1.02;
                b.craters[i * 4 + 1] = y + d[1] * r * b.ay * 1.02;
                b.craters[i * 4 + 2] = z + d[2] * r * b.az * 1.02;
                b.craters[i * 4 + 3] = cr;
            }
        }
        // руды: видов 1–3 (крупнее — больше), у металлических и метеоритов — тяжёлые металлы
        int n = kind == METEOR ? 2 : 1 + (r > 14 ? 1 : 0) + (r > 28 ? 1 : 0) + (kind == METAL ? 1 : 0);
        b.ores = new int[n];
        double[] w = kind == METAL || kind == METEOR ? ORE_W_METAL : ORE_W;
        for (int i = 0; i < n; i++) b.ores[i] = pick(w, g.nextDouble());
        b.vein = switch (kind) { case METAL, METEOR -> 0.22; case ICE -> 0.47; default -> 0.37; };
        if (lootable && r >= 15 && g.chance(0.28)) {
            double[] d = dir(g);
            double k = 0.2 + 0.25 * g.nextDouble();
            b.loot = true;
            b.lx = (int) StrictMath.floor(x + d[0] * r * k);
            b.ly = (int) StrictMath.floor(y + d[1] * r * k);
            b.lz = (int) StrictMath.floor(z + d[2] * r * k);
        }
        return b;
    }

    private static double[] dir(GenRandom g) {
        double u = g.nextDouble() * 2 - 1, t = g.nextDouble() * StrictMath.PI * 2, s = Math.sqrt(1 - u * u);
        return new double[]{s * StrictMath.cos(t), u, s * StrictMath.sin(t)};
    }

    private static int pick(double[] w, double u) {
        double sum = 0;
        for (double v : w) sum += v;
        double t = u * sum;
        for (int i = 0; i < w.length; i++) { t -= w[i]; if (t < 0) return i; }
        return w.length - 1;
    }

    private boolean excluded(double x, double y, double z, double ext) {
        for (double[] e : exclude) {
            double dx = x - e[0], dy = y - e[1], dz = z - e[2], rr = e[3] + ext;
            if (dx * dx + dy * dy + dz * dz < rr * rr) return true;
        }
        return false;
    }

    /** Астероид клетки (i, j, k) или null. */
    Body asteroid(int i, int j, int k) {
        GenRandom g = GenRandom.at(seed ^ 0xA57E401DL, i, j, k);
        double p = chance;
        boolean inCluster = false;
        double mx = (i + 0.5) * A, my = (j + 0.5) * A, mz = (k + 0.5) * A;
        int si = Math.floorDiv((int) mx, C), sj = Math.floorDiv((int) my, C), sk = Math.floorDiv((int) mz, C);
        GenRandom gc = GenRandom.at(seed ^ 0xC1057E2L, si, sj, sk);
        if (gc.chance(clusterChance)) {
            double ccx = (si + 0.2 + 0.6 * gc.nextDouble()) * C, ccy = (sj + 0.2 + 0.6 * gc.nextDouble()) * C, ccz = (sk + 0.2 + 0.6 * gc.nextDouble()) * C;
            double rad = 350 + 300 * gc.nextDouble();
            double dx = mx - ccx, dy = my - ccy, dz = mz - ccz;
            if (dx * dx + dy * dy + dz * dz < rad * rad) { p = clusterBoost; inCluster = true; }
        }
        if (!g.chance(p)) return null;
        double u = g.nextDouble();
        double r = inCluster ? 4 + 20 * u * u : 5 + 38 * u * u * u;
        double t = g.nextDouble();
        int kind = t < 0.12 ? ICE : t < 0.2 ? METAL : ROCK;
        double maxExt = r * 1.4 * 1.25 + 3;
        double x = i * A + maxExt + g.nextDouble() * (A - 2 * maxExt);
        double y = j * A + maxExt + g.nextDouble() * (A - 2 * maxExt);
        double z = k * A + maxExt + g.nextDouble() * (A - 2 * maxExt);
        if (excluded(x, y, z, maxExt)) return null;
        return body(x, y, z, r, kind, g, true);
    }

    /** Метеорит клетки (i, j, k) или null. */
    Body meteorite(int i, int j, int k) {
        GenRandom g = GenRandom.at(seed ^ 0x3E7E0A17L, i, j, k);
        if (!g.chance(meteorChance)) return null;
        double r = 1.6 + 3.2 * g.nextDouble();
        double m = r * 1.75 + 3;
        double x = i * B + m + g.nextDouble() * (B - 2 * m), y = j * B + m + g.nextDouble() * (B - 2 * m), z = k * B + m + g.nextDouble() * (B - 2 * m);
        if (excluded(x, y, z, m)) return null;
        return body(x, y, z, r, METEOR, g, false);
    }

    private List<Body> bodies(int cx, int cy, int cz) {
        int x0 = cx << 4, y0 = cy << 4, z0 = cz << 4;
        List<Body> l = new ArrayList<>(2);
        Body a = asteroid(Math.floorDiv(x0, A), Math.floorDiv(y0, A), Math.floorDiv(z0, A));
        if (a != null && a.hits(x0, y0, z0)) l.add(a);
        Body m = meteorite(Math.floorDiv(x0, B), Math.floorDiv(y0, B), Math.floorDiv(z0, B));
        if (m != null && m.hits(x0, y0, z0)) l.add(m);
        if (spawn != null && spawn.hits(x0, y0, z0)) l.add(spawn);
        return l;
    }

    @Override public boolean hasBlocks(int cx, int cy, int cz) { return !bodies(cx, cy, cz).isEmpty(); }

    @Override public boolean mayHaveSecrets(int cx, int cy, int cz) { return hasBlocks(cx, cy, cz); }

    /**
     * Верхний блок столбца в [yFrom, yTo] — без генерации кубов (свет сервера спрашивает его для каждого столбца; иначе
     * ядро перебирало бы все ~1500 кубов по высоте): тела, чья рамка накрывает (x, z), сверху вниз — та же проверка, что
     * в {@link #generate}.
     */
    @Override
    public int highest(int x, int z, int yFrom, int yTo, ink.hoka.odyssey.gen.HeightKind k) {
        int best = NONE;
        for (int j = Math.floorDiv(yTo, A); j >= Math.floorDiv(yFrom, A); j--) {
            if ((j + 1) * A - 1 < best) break;
            Body a = asteroid(Math.floorDiv(x, A), j, Math.floorDiv(z, A));
            if (a != null && covers(a, x, z)) best = Math.max(best, top(a, x, z, yFrom, yTo));
        }
        for (int j = Math.floorDiv(yTo, B); j >= Math.floorDiv(yFrom, B); j--) {
            if ((j + 1) * B - 1 < best) break;
            Body m = meteorite(Math.floorDiv(x, B), j, Math.floorDiv(z, B));
            if (m != null && covers(m, x, z)) best = Math.max(best, top(m, x, z, yFrom, yTo));
        }
        if (spawn != null && covers(spawn, x, z)) best = Math.max(best, top(spawn, x, z, yFrom, yTo));
        return best;
    }

    @Override
    public double[] lodBodies(int x0, int y0, int z0, int x1, int y1, int z1) {
        java.util.ArrayList<double[]> l = new java.util.ArrayList<>();
        int i0 = Math.floorDiv(x0, A), i1 = Math.floorDiv(x1, A), j0 = Math.floorDiv(y0, A), j1 = Math.floorDiv(y1, A);
        int k0 = Math.floorDiv(z0, A), k1 = Math.floorDiv(z1, A);
        long cells = (long) (i1 - i0 + 1) * (j1 - j0 + 1) * (k1 - k0 + 1);
        if (cells > 2_000_000) return new double[0];                         // слишком большая область — не тянем
        for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) for (int k = k0; k <= k1; k++) {
            Body b = asteroid(i, j, k);
            if (b != null) l.add(new double[]{b.x, b.y, b.z, b.ext});
        }
        if (spawn != null) l.add(new double[]{spawn.x, spawn.y, spawn.z, spawn.ext});
        double[] out = new double[l.size() * 4];
        for (int n = 0; n < l.size(); n++) System.arraycopy(l.get(n), 0, out, n * 4, 4);
        return out;
    }

    @Override
    public int lodSample(int x, int y, int z) {
        Body a = asteroid(Math.floorDiv(x, A), Math.floorDiv(y, A), Math.floorDiv(z, A));
        int s = a != null ? visible(a, x, y, z) : 0;
        if (s == 0 && spawn != null) s = visible(spawn, x, y, z);
        return s;
    }

    /** Видимое состояние тела в точке (как fill, без секретов) или 0. */
    private int visible(Body b, int x, int y, int z) {
        double px = x + 0.5, py = y + 0.5, pz = z + 0.5;
        double dx = (px - b.x) / b.ax, dy = (py - b.y) / b.ay, dz = (pz - b.z) / b.az;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz) / b.r;
        if (dist > 1.45) return 0;
        double surf = surface(b, px, py, pz);
        if (dist >= surf || inCrater(b, px, py, pz)) return 0;
        double depth = (surf - dist) * b.r;
        if (oresVisible) { int o = oreAt(b, px, py, pz, veinPub, pickPub); if (o >= 0) return o; }
        return switch (b.kind) {
            case ICE -> depth < 1.2 ? snow : ice;
            case METAL, METEOR -> meteor;
            default -> depth < 1.4 ? regolith : rock;
        };
    }

    private static boolean covers(Body b, int x, int z) {
        return x + 1 > b.x - b.ext && x < b.x + b.ext && z + 1 > b.z - b.ext && z < b.z + b.ext;
    }

    private int top(Body b, int x, int z, int yFrom, int yTo) {
        int hi = Math.min(yTo, (int) StrictMath.floor(b.y + b.ext)), lo = Math.max(yFrom, (int) StrictMath.floor(b.y - b.ext));
        for (int y = hi; y >= lo; y--) if (solid(b, x, y, z)) return y;
        return NONE;
    }

    /** Видимый блок тела в точке (форма, кратеры) — как в fill. */
    private boolean solid(Body b, int x, int y, int z) {
        double px = x + 0.5, py = y + 0.5, pz = z + 0.5;
        double dx = (px - b.x) / b.ax, dy = (py - b.y) / b.ay, dz = (pz - b.z) / b.az;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz) / b.r;
        if (dist > 1.45) return false;
        return dist < surface(b, px, py, pz) && !inCrater(b, px, py, pz);
    }

    private double surface(Body b, double px, double py, double pz) {
        double f = 1.0 / (b.r * 0.55 + 3), f2 = f * 2.7;
        if (b.kind == METEOR) return 1 + 0.22 * shapeN.noise(px * 0.45 + b.ox, py * 0.45 + b.oy, pz * 0.45 + b.oz);
        return 1 + 0.3 * shapeN.noise(px * f + b.ox, py * f + b.oy, pz * f + b.oz)
                + (b.kind == ICE ? 0.04 : 0.12) * shapeN2.noise(px * f2 + b.oz, py * f2 + b.ox, pz * f2 + b.oy);
    }

    // ───────────────────────── генерация ─────────────────────────

    @Override
    public void generate(GenCube out) {
        int x0 = out.x0(), y0 = out.y0(), z0 = out.z0();
        for (Body b : bodies(out.cx(), out.cy(), out.cz())) fill(out, b, x0, y0, z0);
    }

    private void fill(GenCube out, Body b, int x0, int y0, int z0) {
        int xa = Math.max(x0, (int) StrictMath.floor(b.x - b.ext)), xb = Math.min(x0 + 15, (int) StrictMath.floor(b.x + b.ext));
        int ya = Math.max(y0, (int) StrictMath.floor(b.y - b.ext)), yb = Math.min(y0 + 15, (int) StrictMath.floor(b.y + b.ext));
        int za = Math.max(z0, (int) StrictMath.floor(b.z - b.ext)), zb = Math.min(z0 + 15, (int) StrictMath.floor(b.z + b.ext));
        boolean sec = out.secrets() && secretsOn;
        for (int y = ya; y <= yb; y++) for (int z = za; z <= zb; z++) for (int x = xa; x <= xb; x++) {
            double px = x + 0.5, py = y + 0.5, pz = z + 0.5;
            double dx = (px - b.x) / b.ax, dy = (py - b.y) / b.ay, dz = (pz - b.z) / b.az;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz) / b.r;
            if (dist > 1.45) continue;
            double surf = surface(b, px, py, pz);
            if (dist >= surf) continue;
            if (inCrater(b, px, py, pz)) continue;
            double depth = (surf - dist) * b.r;                                 // примерно блоков до поверхности
            int st = switch (b.kind) {
                case ICE -> depth < 1.2 ? snow : ice;
                case METAL, METEOR -> meteor;
                default -> depth < 1.4 ? regolith : rock;
            };
            int vo = oresVisible ? oreAt(b, px, py, pz, veinPub, pickPub) : -1;
            out.set(x - x0, y - y0, z - z0, vo >= 0 ? vo : st);
            if (!sec) continue;
            // секреты: тайник (пустота + сундук), руды
            if (b.loot) {
                int qx = x - b.lx, qy = y - b.ly, qz = z - b.lz;
                if (qx == 0 && qy == 0 && qz == 0) {
                    out.secret(x - x0, y - y0, z - z0, chest);
                    out.data(x - x0, y - y0, z - z0, Loot.chest(GenRandom.at(secretSeed ^ 0x1007L, x, y, z)));
                    continue;
                }
                if (Math.abs(qx) <= 2 && Math.abs(qz) <= 2 && qy >= 0 && qy <= 2 && qx * qx + qz * qz + qy * qy <= 6) {
                    out.secret(x - x0, y - y0, z - z0, 0);                         // пустота тайника над сундуком
                    continue;
                }
            }
            if (oresVisible) continue;
            int so = oreAt(b, px, py, pz, veinN, pickN);
            if (so >= 0) out.secret(x - x0, y - y0, z - z0, so);
        }
    }

    /** Руда в точке тела (жила по шуму) или −1. */
    private int oreAt(Body b, double px, double py, double pz, GenNoise vein, GenNoise pick) {
        double v = vein.noise(px * 0.17 + b.ox, py * 0.17 + b.oy, pz * 0.17 + b.oz);
        if (v <= b.vein) return -1;
        int n = b.ores.length;
        int idx = n == 1 ? 0 : (int) Math.min(n - 1, (pick.noise(px * 0.05 + b.oz, py * 0.05, pz * 0.05 + b.ox) * 0.5 + 0.5) * n);
        return ore[b.ores[Math.max(0, idx)]];
    }

    private static boolean inCrater(Body b, double px, double py, double pz) {
        double[] c = b.craters;
        for (int i = 0; i < c.length; i += 4) {
            double dx = px - c[i], dy = py - c[i + 1], dz = pz - c[i + 2];
            if (dx * dx + dy * dy + dz * dz < c[i + 3] * c[i + 3]) return true;
        }
        return false;
    }
}
