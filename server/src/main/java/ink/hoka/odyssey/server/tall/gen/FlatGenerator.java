package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.worldgen.*;
import ink.hoka.odyssey.server.tall.TallWorld;
import net.minestom.server.instance.block.Block;

/**
 * Встроенный генератор «flat» v1 — перенос FlatTallGen один в один (тест FlatGeneratorTest сверяет): y 0 — бедрок,
 * 1…37 — камень, 38…39 — земля, 40 — дёрн; площадки 9×9 вокруг (0, 0) на высотах TallWorld.platforms (камень,
 * в центре светокамень). Верфь (X ≥ YARD_X0) — правило ядра (ModuleCubeGen), не генератора.
 */
public final class FlatGenerator implements WorldGenerator {

    static final int GROUND_TOP = 40;
    private static final int R = 4;
    private static final int[] PLATFORMS = TallWorld.platforms().clone();

    public static final GeneratorProvider PROVIDER = new GeneratorProvider() {
        @Override public String id() { return "flat"; }
        @Override public int version() { return 1; }
        @Override public Bounds defaultBounds() { return new Bounds(TallWorld.MIN_Y, TallWorld.MIN_Y + TallWorld.HEIGHT - 1); }
        @Override public WorldGenerator create(GenSettings s) { return new FlatGenerator(); }
    };

    private static Block ground(int y) {
        return y == 0 ? Block.BEDROCK : y < 38 ? Block.STONE : y < 40 ? Block.DIRT : Block.GRASS_BLOCK;
    }

    private static boolean platformCube(int cx, int cy, int cz) {
        if ((cx != 0 && cx != -1) || (cz != 0 && cz != -1)) return false;
        for (int py : PLATFORMS) if (py >> 4 == cy) return true;
        return false;
    }

    @Override
    public boolean hasBlocks(int cx, int cy, int cz) {
        if (cy >= 0 && cy <= GROUND_TOP >> 4) return true;
        return platformCube(cx, cy, cz);
    }

    @Override
    public Block uniform(int cx, int cy, int cz) {
        int y0 = cy << 4;
        if (y0 >= 1 && y0 + 15 < 38 && !platformCube(cx, cy, cz)) return Block.STONE;   // сплошной камень
        return null;
    }

    @Override
    public void generate(CubeBuffer out) {
        int cx = out.cx(), cy = out.cy(), cz = out.cz();
        if (!hasBlocks(cx, cy, cz)) return;
        int y0 = cy << 4;
        for (int y = Math.max(0, y0); y <= Math.min(GROUND_TOP, y0 + 15); y++) out.fillY(y - y0, y - y0, ground(y));
        for (int py : PLATFORMS) {
            if (py >> 4 != cy) continue;
            for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++)
                out.setAbs(x, py, z, x == 0 && z == 0 ? Block.GLOWSTONE : Block.STONE);
        }
    }

    @Override
    public int highest(int x, int z, int yFrom, int yTo, HeightKind kind) {
        if (yFrom > yTo) return NONE;
        int best = NONE;
        if (yTo >= 0 && yFrom <= GROUND_TOP) best = Math.min(yTo, GROUND_TOP);
        if (Math.abs(x) <= R && Math.abs(z) <= R)
            for (int py : PLATFORMS) if (py >= yFrom && py <= yTo && py > best) best = py;
        return best;
    }
}
