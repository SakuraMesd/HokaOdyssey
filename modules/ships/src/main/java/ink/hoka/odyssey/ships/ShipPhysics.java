package ink.hoka.odyssey.ships;

import ink.hoka.odyssey.api.ModuleConfig;
import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Тело корабля (SHIP-PHYSICS.md §1, этап 0): масса из блоков (материал × форма), центр масс, тензор инерции,
 * двигатели-блоки. Идеи — как у мода Sable, код свой (обычные формулы твёрдого тела).
 * Оси — как у Flight.parts: от сиденья, {@code r = (dx, dy + 0.5 − seatH, dz)}.
 */
final class ShipPhysics {

    /** Двигатель из конфига physics.engines. kind: main (ход), lift (подъём), gyro (момент). */
    record EngineSpec(String kind, double thrust, double torque, double mass, boolean boost) {}

    /** Двигатель в корабле: место (оси корабля), направление тяги (единичный), тяга т·б/с². */
    record Engine(String kind, double px, double py, double pz, double dx, double dy, double dz, double thrust, boolean boost) {}

    /** Результат расчёта. I — тензор относительно центра масс: xx, yy, zz, xy, xz, yz (уже со сжатием массы). */
    record Body(int blocks, double mass, double massEff, double[] com, double[] inertia, List<Engine> engines,
                double thrustForward, double thrustBack, double thrustUp, double thrustDown) {}

    // ───────────────────────── конфиг ─────────────────────────

    static final class Config {
        final double def, hardnessK, min, hardnessMax, refMass, massExp;
        final Map<String, Double> blocks = new LinkedHashMap<>(), masks = new LinkedHashMap<>(), shape = new LinkedHashMap<>();
        final Map<String, EngineSpec> engines = new LinkedHashMap<>();
        private final Map<Integer, Double> cache = new ConcurrentHashMap<>();

        Config(ModuleConfig c) {
            def = c.getDouble("physics.mass.default", 1.0);
            hardnessK = c.getDouble("physics.mass.hardness-k", 0.5);
            min = c.getDouble("physics.mass.min", 0.05);
            hardnessMax = c.getDouble("physics.mass.hardness-max", 4.0);
            refMass = c.getDouble("physics.reference-mass", 300);
            massExp = c.getDouble("physics.mass-exp", 0.5);
            for (String k : c.keys("physics.mass.blocks")) blocks.put(k, c.getDouble("physics.mass.blocks." + k, def));
            for (String k : c.keys("physics.mass.masks")) masks.put(k, c.getDouble("physics.mass.masks." + k, def));
            for (String k : c.keys("physics.mass.shape")) shape.put(k, c.getDouble("physics.mass.shape." + k, 1));
            for (String k : c.keys("physics.engines")) {
                String p = "physics.engines." + k + ".";
                engines.put(k, new EngineSpec(c.getString(p + "kind", "main"), c.getDouble(p + "thrust", 0),
                        c.getDouble(p + "torque", 0), c.getDouble(p + "mass", def), c.getBoolean(p + "boost", false)));
            }
        }

        /** Ключ без «minecraft:» — так проще писать маски. */
        private static String name(Block b) {
            String k = b.key().asString();
            return k.startsWith("minecraft:") ? k.substring(10) : k;
        }

        double massOf(Block b) {
            return cache.computeIfAbsent(b.stateId(), id -> compute(b));
        }

        private double compute(Block b) {
            if (b.isAir()) return 0;
            String full = b.key().asString(), n = name(b);
            EngineSpec e = engines.get(full);
            if (e == null) e = engines.get(n);
            if (e != null) return e.mass();
            Double m = blocks.get(full);
            if (m == null) m = blocks.get(n);
            if (m != null) return m;
            if (!b.registry().isSolid() && b.registry().isReplaceable()) return 0;   // трава, вода и т.п.
            double base = -1;
            for (var en : masks.entrySet()) if (glob(en.getKey(), n)) { base = en.getValue(); break; }
            if (base < 0) {
                double h = b.registry().hardness();
                base = h > 0 ? Math.max(min, Math.min(hardnessMax, hardnessK * h)) : def;
            }
            double k = 1;
            for (var en : shape.entrySet()) if (glob(en.getKey(), n)) { k = en.getValue(); break; }
            return Math.max(min, base * k);
        }

        EngineSpec engine(Block b) {
            EngineSpec e = engines.get(b.key().asString());
            return e != null ? e : engines.get(name(b));
        }

        /** Маска со звёздочками: «*_planks», «*glass*», «stone». */
        static boolean glob(String mask, String s) {
            if (!mask.contains("*")) return mask.equals(s);
            String[] parts = mask.split("\\*", -1);
            int pos = 0;
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (p.isEmpty()) continue;
                int at = s.indexOf(p, pos);
                if (at < 0 || (i == 0 && at != 0)) return false;
                pos = at + p.length();
            }
            String last = parts[parts.length - 1];
            return last.isEmpty() || s.endsWith(last);
        }
    }

    // ───────────────────────── расчёт ─────────────────────────

    /** Клетка корабля: смещение от сиденья и блок. */
    record Cell(int dx, int dy, int dz, Block block) {}

    static Body compute(List<Cell> cells, double seatH, Config cfg) {
        double M = 0, cx = 0, cy = 0, cz = 0;
        int n = 0;
        double[] ms = new double[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            double m = cfg.massOf(c.block());
            ms[i] = m;
            if (m <= 0) continue;
            n++;
            M += m;
            cx += m * c.dx(); cy += m * (c.dy() + 0.5 - seatH); cz += m * c.dz();
        }
        double[] com = M > 0 ? new double[]{cx / M, cy / M, cz / M} : new double[3];
        double meff = M <= 0 ? 0 : cfg.refMass * Math.pow(M / cfg.refMass, cfg.massExp);
        double s = M > 0 ? meff / M : 1;
        double xx = 0, yy = 0, zz = 0, xy = 0, xz = 0, yz = 0;
        List<Engine> engines = new ArrayList<>();
        double tf = 0, tb = 0, tu = 0, td = 0;
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            double m = ms[i];
            double rx = c.dx(), ry = c.dy() + 0.5 - seatH, rz = c.dz();
            if (m > 0) {
                double dx = rx - com[0], dy = ry - com[1], dz = rz - com[2];
                double cube = m / 6;                                       // собственная инерция кубика 1×1×1
                xx += m * (dy * dy + dz * dz) + cube;
                yy += m * (dx * dx + dz * dz) + cube;
                zz += m * (dx * dx + dy * dy) + cube;
                xy -= m * dx * dy; xz -= m * dx * dz; yz -= m * dy * dz;
            }
            EngineSpec e = cfg.engine(c.block());
            if (e == null || "gyro".equals(e.kind())) continue;
            double[] d = thrustDir(c.block(), e.kind());
            engines.add(new Engine(e.kind(), rx, ry, rz, d[0], d[1], d[2], e.thrust(), e.boost()));
            tf += e.thrust() * Math.max(0, d[2]); tb += e.thrust() * Math.max(0, -d[2]);
            tu += e.thrust() * Math.max(0, d[1]); td += e.thrust() * Math.max(0, -d[1]);
        }
        double[] I = {xx * s, yy * s, zz * s, xy * s, xz * s, yz * s};
        return new Body(n, M, meff, com, I, engines, tf, tb, tu, td);
    }

    /** Тяга против «facing» (сопло смотрит назад); без facing — main по +Z, lift по +Y. */
    static double[] thrustDir(Block b, String kind) {
        String f = b.getProperty("facing");
        if (f != null) return switch (f) {
            case "north" -> new double[]{0, 0, 1};
            case "south" -> new double[]{0, 0, -1};
            case "west" -> new double[]{1, 0, 0};
            case "east" -> new double[]{-1, 0, 0};
            case "up" -> new double[]{0, -1, 0};
            case "down" -> new double[]{0, 1, 0};
            default -> new double[]{0, 0, 1};
        };
        return "lift".equals(kind) ? new double[]{0, 1, 0} : new double[]{0, 0, 1};
    }
}
