package ink.hoka.odyssey.server.tall;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Section;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** CubeGen (шаг 5): highest согласован с generateCube; hasBlocks=false — куб правда пустой; земля как прежде. */
class CubeGenTest {

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    private final CubeGen gen = FlatTallGen.INSTANCE;
    private final Map<Long, Section> cache = new HashMap<>();

    private Section cube(int cx, int cy, int cz) {
        long k = ((long) cx << 40) ^ ((long) (cy & 0xFFFFF) << 20) ^ (cz & 0xFFFFF);
        return cache.computeIfAbsent(k, kk -> gen.hasBlocks(cx, cy, cz) ? CubeGen.generate(gen, cx, cy, cz).section() : null);
    }

    /** То же, что дал бы HeightIndex по блокам generateCube: верх в [yFrom, yTo]. */
    private int scan(int x, int z, int yFrom, int yTo, HeightIndex.Kind k) {
        int best = HeightIndex.NONE;
        for (int y = yTo; y >= yFrom; y--) {
            Section s = cube(x >> 4, y >> 4, z >> 4);
            if (s == null || s.blockPalette().count() == 0) { y = (y >> 4) << 4; continue; }   // весь куб мимо
            int h = HeightIndex.contrib(HeightIndex.info(s.blockPalette().get(x & 15, y & 15, z & 15)), k, y);
            if (h != HeightIndex.NONE && h >= yFrom) { best = Math.max(best, h); if (h == y) break; }
        }
        return best;
    }

    @Test
    void highestMatchesGenerateCube() {
        Random r = new Random(42);
        int[][] cols = {{0, 0}, {4, -4}, {-4, 4}, {5, 0}, {-5, -5}, {100, 7}, {-1000, 33},
                {TallChunk.YARD_X0, 0}, {TallChunk.YARD_X0 - 1, 3}, {TallChunk.YARD_X0 + 100, -20}};
        int lo = TallWorld.MIN_Y, hi = TallWorld.MIN_Y + TallWorld.HEIGHT - 1;
        for (int t = 0; t < 40; t++) {
            int x, z;
            if (t < cols.length) { x = cols[t][0]; z = cols[t][1]; }
            else { x = r.nextInt(41) - 20; z = r.nextInt(41) - 20; }
            for (var k : HeightIndex.Kind.values()) {
                assertEquals(scan(x, z, lo, hi, k), gen.highest(x, z, lo, hi, k), "колонка " + x + " " + z + " " + k);
                for (int q = 0; q < 6; q++) {
                    int a = lo + r.nextInt(TallWorld.HEIGHT), b = lo + r.nextInt(TallWorld.HEIGHT);
                    if (q == 0) { a = 20; b = 39; }
                    if (q == 1) { a = 1999; b = 2000; }
                    int from = Math.min(a, b), to = Math.max(a, b);
                    assertEquals(scan(x, z, from, to, k), gen.highest(x, z, from, to, k), "колонка " + x + " " + z + " [" + from + ", " + to + "] " + k);
                }
            }
        }
    }

    @Test
    void emptyHintIsHonest() {
        Random r = new Random(7);
        for (int t = 0; t < 300; t++) {
            int cx = r.nextInt(5) - 2, cz = r.nextInt(5) - 2, cy = (TallWorld.MIN_Y >> 4) + r.nextInt(TallWorld.HEIGHT >> 4);
            if (t < 20) cy = r.nextInt(5) - 1;
            if (gen.hasBlocks(cx, cy, cz)) continue;
            assertTrue(CubeGen.generate(gen, cx, cy, cz).empty(), "куб " + cx + " " + cy + " " + cz);
        }
        assertFalse(gen.hasBlocks(TallChunk.YARD_X0 >> 4, 0, 0), "верфь пустая");
        assertTrue(CubeGen.generate(gen, TallChunk.YARD_X0 >> 4, 2, 0).empty());
    }

    /** Земля как в прежнем TallChunk.generated: бедрок/камень/земля/дёрн, площадки, светокамень в центре. */
    @Test
    void sameGroundAsBefore() {
        Section s0 = CubeGen.generate(gen, 0, 0, 0).section(), s2 = CubeGen.generate(gen, 3, 2, -2).section();
        assertEquals(net.minestom.server.instance.block.Block.BEDROCK.stateId(), s0.blockPalette().get(3, 0, 3));
        assertEquals(net.minestom.server.instance.block.Block.STONE.stateId(), s0.blockPalette().get(3, 15, 3));
        assertEquals(net.minestom.server.instance.block.Block.DIRT.stateId(), s2.blockPalette().get(0, 38 & 15, 0));
        assertEquals(net.minestom.server.instance.block.Block.GRASS_BLOCK.stateId(), s2.blockPalette().get(0, 40 & 15, 0));
        assertEquals(0, s2.blockPalette().get(0, 41 & 15, 0));
        assertEquals(16 * 16 * 9, s2.blockPalette().count());
        Section p = CubeGen.generate(gen, 0, 2000 >> 4, 0).section();
        assertEquals(net.minestom.server.instance.block.Block.GLOWSTONE.stateId(), p.blockPalette().get(0, 2000 & 15, 0));
        assertEquals(25, p.blockPalette().count());                      // 0…4 × 0…4 в столбце (0, 0)
        assertEquals(20, CubeGen.generate(gen, -1, 2000 >> 4, 0).section().blockPalette().count());   // −4…−1 × 0…4
    }
}
