package ink.hoka.odyssey.planet.basic;

import ink.hoka.odyssey.gen.GenSurface;

/** Поверхность заглушки: везде трава на уровне моря, равнина. Заменится настоящим рельефом. */
final class StubSurface implements GenSurface {

    private final int sea;

    StubSurface(int sea) { this.sea = sea; }

    @Override public int height(int x, int z) { return sea; }
    @Override public String block(int x, int z) { return "minecraft:grass_block"; }
    @Override public boolean water(int x, int z) { return false; }
    @Override public String biome(int x, int z) { return "minecraft:plains"; }
}
