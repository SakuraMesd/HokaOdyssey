package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.worldgen.*;

/** Встроенный генератор «void» v1: пустота (площадку новой карты ставит Maps.create обычными блоками). */
public final class VoidGenerator implements WorldGenerator {

    public static final GeneratorProvider PROVIDER = new GeneratorProvider() {
        @Override public String id() { return "void"; }
        @Override public int version() { return 1; }
        @Override public Bounds defaultBounds() { return new Bounds(0, 0); }
        @Override public WorldGenerator create(GenSettings s) { return new VoidGenerator(); }
    };

    @Override public void generate(CubeBuffer out) { }
    @Override public boolean hasBlocks(int cx, int cy, int cz) { return false; }
    @Override public int highest(int x, int z, int yFrom, int yTo, HeightKind k) { return NONE; }
}
