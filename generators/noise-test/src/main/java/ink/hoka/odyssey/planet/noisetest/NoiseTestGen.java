package ink.hoka.odyssey.planet.noisetest;

import ink.hoka.odyssey.gen.*;

/**
 * «noise-test» v2 — переносимый (сервер и клиент): синусный рельеф h(x, z) = base + floor(amp·sin·cos) с фазой от сида;
 * ниже — земля 3 блока и камень до низа (бедрок на дне). v2: StrictMath вместо Math (v1 мог разойтись на 1 ulp между
 * JVM/платформами). hasBlocks/uniform/highest — точные, pregen даёт лёгкий индекс.
 * map.yml generator_options: base (64), amp (20), bottom (−64).
 */
public final class NoiseTestGen implements GenProvider {

    @Override public String id() { return "noise-test"; }
    @Override public int version() { return 2; }
    @Override public GenBounds defaultBounds() { return new GenBounds(-64, 255, 0); }
    @Override public PlanetGenerator create(GenContext ctx) { return new Gen(ctx); }

    /** Без изменяемых полей: вызывается параллельно. */
    static final class Gen implements PlanetGenerator {
        private final int base, amp, bottom;
        private final double px, pz;
        private final int bedrock, stone, dirt, grass;

        Gen(GenContext c) {
            base = c.intOption("base", 64);
            amp = Math.max(0, c.intOption("amp", 20));
            bottom = Math.max(c.bounds().minY(), c.intOption("bottom", -64));
            long seed = c.seed();
            px = (seed & 0xFFFF) / 65536.0 * StrictMath.PI * 2;
            pz = ((seed >>> 16) & 0xFFFF) / 65536.0 * StrictMath.PI * 2;
            GenIds ids = c.ids();
            bedrock = ids.defaultState("minecraft:bedrock");
            stone = ids.defaultState("minecraft:stone");
            dirt = ids.defaultState("minecraft:dirt");
            grass = ids.defaultState("minecraft:grass_block");
        }

        int surface(int x, int z) {
            return base + (int) StrictMath.floor(amp * StrictMath.sin(x / 40.0 + px) * StrictMath.cos(z / 50.0 + pz));
        }

        private int at(int y, int h) {
            if (y > h || y < bottom) return 0;
            if (y == bottom) return bedrock;
            if (y == h) return grass;
            return y >= h - 3 ? dirt : stone;
        }

        @Override
        public void generate(GenCube out) {
            int x0 = out.x0(), y0 = out.y0(), z0 = out.z0();
            for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                int h = surface(x0 + lx, z0 + lz);
                for (int ly = 0; ly < 16; ly++) {
                    int b = at(y0 + ly, h);
                    if (b != 0) out.set(lx, ly, lz, b);
                }
            }
        }

        @Override
        public boolean hasBlocks(int cx, int cy, int cz) {
            int y0 = cy << 4;
            return y0 <= base + amp && y0 + 15 >= bottom;
        }

        @Override
        public int uniform(int cx, int cy, int cz) {
            int y0 = cy << 4;
            return y0 > bottom && y0 + 15 < base - amp - 4 ? stone : -1;   // целиком в камне под самой низкой землёй
        }

        @Override
        public int highest(int x, int z, int yFrom, int yTo, HeightKind k) {
            int h = surface(x, z);                                         // все блоки полные — верх один для всех видов
            if (h < bottom || yTo < bottom) return NONE;
            int top = Math.min(h, yTo);
            return top >= yFrom ? top : NONE;
        }
    }
}
