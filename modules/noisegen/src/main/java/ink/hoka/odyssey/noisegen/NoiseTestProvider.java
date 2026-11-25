package ink.hoka.odyssey.noisegen;

import ink.hoka.odyssey.api.worldgen.*;
import net.minestom.server.instance.block.Block;

/**
 * «noise-test» — синусный рельеф: поверхность h(x, z) = base + amp·sin·cos с фазой от сида; ниже — земля 3 блока
 * и камень до низа планеты (bedrock на самом дне). Всё считается формулой: hasBlocks/uniform/highest — точно,
 * поэтому pregen даёт лёгкий индекс, а при входе игрока генерируются только кубы у поверхности.
 * map.yml generator_options: base (64), amp (20), bottom (−64).
 */
public final class NoiseTestProvider implements GeneratorProvider {

    @Override public String id() { return "noise-test"; }
    @Override public int version() { return 1; }                     // поменял формулу — увеличь
    @Override public Bounds defaultBounds() { return new Bounds(-64, 255); }
    @Override public WorldGenerator create(GenSettings s) { return new Gen(s); }

    /** Без изменяемых полей: вызывается параллельно. */
    static final class Gen implements WorldGenerator {
        private final int base, amp, bottom;
        private final double px, pz;

        Gen(GenSettings s) {
            base = s.intOption("base", 64);
            amp = Math.max(0, s.intOption("amp", 20));
            bottom = Math.max(s.bounds().minY(), s.intOption("bottom", -64));
            long seed = s.seed();                                         // фаза — от сида (никакого Math.random)
            px = (seed & 0xFFFF) / 65536.0 * Math.PI * 2;
            pz = ((seed >>> 16) & 0xFFFF) / 65536.0 * Math.PI * 2;
        }

        int surface(int x, int z) {
            return base + (int) Math.floor(amp * Math.sin(x / 40.0 + px) * Math.cos(z / 50.0 + pz));
        }

        private Block at(int y, int h) {
            if (y > h || y < bottom) return Block.AIR;
            if (y == bottom) return Block.BEDROCK;
            if (y == h) return Block.GRASS_BLOCK;
            return y >= h - 3 ? Block.DIRT : Block.STONE;
        }

        @Override
        public void generate(CubeBuffer out) {
            int x0 = out.x0(), y0 = out.y0(), z0 = out.z0();
            for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                int h = surface(x0 + lx, z0 + lz);
                for (int ly = 0; ly < 16; ly++) {
                    Block b = at(y0 + ly, h);
                    if (!b.isAir()) out.set(lx, ly, lz, b);
                }
            }
        }

        @Override
        public boolean hasBlocks(int cx, int cy, int cz) {
            int y0 = cy << 4;
            return y0 <= base + amp && y0 + 15 >= bottom;
        }

        @Override
        public Block uniform(int cx, int cy, int cz) {
            int y0 = cy << 4;
            if (y0 > bottom && y0 + 15 < base - amp - 4) return Block.STONE;   // целиком в камне под самой низкой землёй
            return null;
        }

        @Override
        public int highest(int x, int z, int yFrom, int yTo, HeightKind k) {
            int h = surface(x, z);                                        // все блоки полные — верх один для всех видов
            if (h < bottom || yTo < bottom) return NONE;
            int top = Math.min(h, yTo);
            return top >= yFrom ? top : NONE;
        }
    }
}
