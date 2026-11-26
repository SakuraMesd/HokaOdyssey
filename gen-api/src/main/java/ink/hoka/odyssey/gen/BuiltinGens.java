package ink.hoka.odyssey.gen;

import java.util.List;

/**
 * Встроенные генераторы ядра — переносимые, код есть и в server.jar, и в ядре мода (jar не нужен: op 20 filePath
 * "builtin:&lt;id&gt;"). flat v1 — перенос FlatGenerator/FlatTallGen один в один; void v1 — пустота.
 */
public final class BuiltinGens {

    private BuiltinGens() {}

    /** Высокий мир ядра: −15008…15007. */
    public static final int WORLD_MIN_Y = -15008, WORLD_MAX_Y = 15007;
    /** Площадки-ориентиры flat 9×9 вокруг (0, 0) (как TallWorld.platforms). */
    public static final List<Integer> FLAT_PLATFORMS = List.of(-15000, -12000, -6000, -2000, 2000, 6000, 12000, 15000);
    public static final String PREFIX = "builtin:";

    public static GenProvider get(String id) {
        return switch (id) {
            case "flat" -> FLAT;
            case "void" -> VOID;
            default -> null;
        };
    }

    public static final GenProvider FLAT = new GenProvider() {
        @Override public String id() { return "flat"; }
        @Override public int version() { return 1; }
        @Override public GenBounds defaultBounds() { return new GenBounds(WORLD_MIN_Y, WORLD_MAX_Y, 0); }
        @Override public PlanetGenerator create(GenContext ctx) { return new Flat(ctx.ids()); }
    };

    public static final GenProvider VOID = new GenProvider() {
        @Override public String id() { return "void"; }
        @Override public int version() { return 1; }
        @Override public GenBounds defaultBounds() { return new GenBounds(0, 0, 0); }
        @Override public PlanetGenerator create(GenContext ctx) {
            return new PlanetGenerator() {
                @Override public void generate(GenCube out) { }
                @Override public boolean hasBlocks(int cx, int cy, int cz) { return false; }
                @Override public int highest(int x, int z, int yFrom, int yTo, HeightKind k) { return NONE; }
            };
        }
    };

    /** y 0 — бедрок, 1…37 — камень, 38…39 — земля, 40 — дёрн; площадки 9×9 (камень, в центре светокамень). */
    static final class Flat implements PlanetGenerator {
        static final int GROUND_TOP = 40, R = 4;
        private final int bedrock, stone, dirt, grass, glow;
        private final int[] platforms = FLAT_PLATFORMS.stream().mapToInt(Integer::intValue).toArray();

        Flat(GenIds ids) {
            bedrock = ids.defaultState("minecraft:bedrock");
            stone = ids.defaultState("minecraft:stone");
            dirt = ids.defaultState("minecraft:dirt");
            grass = ids.defaultState("minecraft:grass_block");
            glow = ids.defaultState("minecraft:glowstone");
        }

        private int ground(int y) { return y == 0 ? bedrock : y < 38 ? stone : y < 40 ? dirt : grass; }

        private boolean platformCube(int cx, int cy, int cz) {
            if ((cx != 0 && cx != -1) || (cz != 0 && cz != -1)) return false;
            for (int py : platforms) if (py >> 4 == cy) return true;
            return false;
        }

        @Override
        public boolean hasBlocks(int cx, int cy, int cz) {
            if (cy >= 0 && cy <= GROUND_TOP >> 4) return true;
            return platformCube(cx, cy, cz);
        }

        @Override
        public int uniform(int cx, int cy, int cz) {
            int y0 = cy << 4;
            return y0 >= 1 && y0 + 15 < 38 && !platformCube(cx, cy, cz) ? stone : -1;
        }

        @Override
        public void generate(GenCube out) {
            int cx = out.cx(), cy = out.cy(), cz = out.cz();
            if (!hasBlocks(cx, cy, cz)) return;
            int y0 = cy << 4;
            for (int y = Math.max(0, y0); y <= Math.min(GROUND_TOP, y0 + 15); y++) out.fillY(y - y0, y - y0, ground(y));
            for (int py : platforms) {
                if (py >> 4 != cy) continue;
                for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) out.setAbs(x, py, z, x == 0 && z == 0 ? glow : stone);
            }
        }

        @Override
        public int highest(int x, int z, int yFrom, int yTo, HeightKind kind) {
            if (yFrom > yTo) return NONE;
            int best = NONE;
            if (yTo >= 0 && yFrom <= GROUND_TOP) best = Math.min(yTo, GROUND_TOP);
            if (Math.abs(x) <= R && Math.abs(z) <= R)
                for (int py : platforms) if (py >= yFrom && py <= yTo && py > best) best = py;
            return best;
        }
    }
}
