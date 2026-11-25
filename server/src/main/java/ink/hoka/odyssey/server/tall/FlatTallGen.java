package ink.hoka.odyssey.server.tall;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.GenerationUnit;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;

/**
 * Тестовая земля высокой карты (как была в TallChunk.generated): y 0 — бедрок, 1…37 — камень, 38…39 — земля,
 * 40 — дёрн; площадки-ориентиры 9×9 вокруг (0, 0) на высотах {@link TallWorld#platforms()} (камень, в центре
 * светокамень); полоса верфи кораблей X ≥ {@link TallChunk#YARD_X0} — пустота. Биомы не пишет (как и раньше).
 * Все блоки — полные кубы, поэтому для любого вида высоты верх один и тот же.
 */
public final class FlatTallGen implements CubeGen {

    public static final FlatTallGen INSTANCE = new FlatTallGen();

    static final int GROUND_TOP = 40;
    private static final int R = 4;                          // площадка −4…4

    private FlatTallGen() {}

    private static Block ground(int y) {
        return y == 0 ? Block.BEDROCK : y < 38 ? Block.STONE : y < 40 ? Block.DIRT : Block.GRASS_BLOCK;
    }

    private static boolean yard(int x) { return x >= TallChunk.YARD_X0; }

    @Override
    public boolean hasBlocks(int cx, int cy, int cz) {
        if (yard(cx * 16)) return false;
        if (cy >= 0 && cy <= GROUND_TOP >> 4) return true;
        if ((cx != 0 && cx != -1) || (cz != 0 && cz != -1)) return false;
        for (int py : TallWorld.platforms()) if (py >> 4 == cy) return true;
        return false;
    }

    @Override
    public void generateCube(int cx, int cy, int cz, GenerationUnit unit) {
        if (!hasBlocks(cx, cy, cz)) return;
        var m = unit.modifier();
        int x0 = cx << 4, y0 = cy << 4, z0 = cz << 4;
        for (int y = Math.max(0, y0); y <= Math.min(GROUND_TOP, y0 + 15); y++) {
            Block b = ground(y);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) m.setBlock(x0 + x, y, z0 + z, b);
        }
        for (int py : TallWorld.platforms()) {
            if (py >> 4 != cy) continue;
            for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
                if (x >> 4 != cx || z >> 4 != cz) continue;
                m.setBlock(x, py, z, x == 0 && z == 0 ? Block.GLOWSTONE : Block.STONE);
            }
        }
    }

    @Override
    public int highest(int x, int z, int yFrom, int yTo, HeightIndex.Kind kind) {
        if (yard(x) || yFrom > yTo) return HeightIndex.NONE;
        int best = HeightIndex.NONE;
        if (yTo >= 0 && yFrom <= GROUND_TOP) best = Math.min(yTo, GROUND_TOP);
        if (Math.abs(x) <= R && Math.abs(z) <= R)
            for (int py : TallWorld.platforms()) if (py >= yFrom && py <= yTo && py > best) best = py;
        return best;
    }

    /** Биом, который лежит в палитрах (генератор их не трогает — id 0 реестра, как у пустой секции). */
    @Override
    public RegistryKey<Biome> biomeAt(int x, int z) {
        return MinecraftServer.getBiomeRegistry().getKey(0);
    }
}
