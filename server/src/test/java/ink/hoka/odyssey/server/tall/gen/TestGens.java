package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.worldgen.*;
import net.minestom.server.instance.block.Block;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Генераторы для тестов: синусный рельеф (highest — по желанию UNKNOWN), заведомо недетерминированный. */
final class TestGens {

    private TestGens() {}

    /** Сколько раз звали generate (проверка «вне границ модуль не зовут»). */
    static final AtomicInteger GENERATE_CALLS = new AtomicInteger();

    static GeneratorProvider sine(String id, boolean analyticHighest) {
        return new GeneratorProvider() {
            @Override public String id() { return id; }
            @Override public int version() { return 1; }
            @Override public Bounds defaultBounds() { return new Bounds(-64, 255); }
            @Override public WorldGenerator create(GenSettings s) { return new Sine(s.seed(), analyticHighest); }
        };
    }

    static final class Sine implements WorldGenerator {
        final double ph;
        final boolean analytic;
        Sine(long seed, boolean analytic) { this.ph = (seed & 0xFFFF) / 65536.0 * 6.28; this.analytic = analytic; }
        int h(int x, int z) { return 64 + (int) Math.floor(20 * Math.sin(x / 13.0 + ph) * Math.cos(z / 17.0)); }

        @Override public void generate(CubeBuffer out) {
            GENERATE_CALLS.incrementAndGet();
            for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                int h = h(out.x0() + lx, out.z0() + lz);
                for (int ly = 0; ly < 16; ly++) {
                    int y = out.y0() + ly;
                    if (y > h || y < -64) continue;
                    out.set(lx, ly, lz, y == h ? Block.GRASS_BLOCK : y == -64 ? Block.BEDROCK : Block.STONE);
                    if (y == h && torch(out.x0() + lx, out.z0() + lz, h) && ly < 15) out.set(lx, ly + 1, lz, Block.TORCH);
                }
            }
        }
        @Override public boolean hasBlocks(int cx, int cy, int cz) { return (cy << 4) <= 85 && (cy << 4) + 15 >= -64; }
        @Override public Block uniform(int cx, int cy, int cz) { return (cy << 4) > -64 && (cy << 4) + 15 < 40 ? Block.STONE : null; }
        static boolean torch(int x, int z, int h) { return (x * 31 + z) % 97 == 0 && (h & 15) != 15; }
        @Override public int highest(int x, int z, int yFrom, int yTo, HeightKind k) {
            if (!analytic) return UNKNOWN;
            int h = h(x, z);
            int top = k == HeightKind.SURFACE && torch(x, z, h) ? h + 1 : h;   // факел: не небо и не движение
            if (yTo < -64) return NONE;
            int r = Math.min(top, yTo);
            return r >= yFrom ? r : NONE;
        }
    }

    /** Недетерминированный: зависит от счётчика вызовов. */
    static GeneratorProvider bad(String id) {
        AtomicInteger n = new AtomicInteger();
        return new GeneratorProvider() {
            @Override public String id() { return id; }
            @Override public int version() { return 1; }
            @Override public Bounds defaultBounds() { return new Bounds(0, 31); }
            @Override public WorldGenerator create(GenSettings s) {
                return out -> out.set(0, 0, 0, n.incrementAndGet() % 2 == 0 ? Block.STONE : Block.DIRT);
            }
        };
    }

    static void reg(GeneratorProvider p) {
        if (GeneratorRegistry.get(p.id()) == null) GeneratorRegistry.register("test", p);
    }

    static ModuleCubeGen gen(GeneratorProvider p, long seed) {
        return new ModuleCubeGen(p, new GenSettings("t", seed, p.defaultBounds(), Map.of()));
    }
}
