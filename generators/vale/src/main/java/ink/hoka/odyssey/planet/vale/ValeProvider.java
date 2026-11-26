package ink.hoka.odyssey.planet.vale;

import ink.hoka.odyssey.gen.GenBounds;
import ink.hoka.odyssey.gen.GenContext;
import ink.hoka.odyssey.gen.GenProvider;
import ink.hoka.odyssey.gen.PlanetGenerator;

/**
 * Генератор планеты «vale» v1: высоты −500…2000, море 300 (generator_options.sea_level), рельеф из шума — равнины и
 * холмы 300–380, редкие горы до ~520 со снегом, пляжи, озёра; объекты категорий oak, birch, spruce, bushes, rocks.
 * generator_options: sea_level (300), tree_density (1.0; 0…2).
 */
public final class ValeProvider implements GenProvider {

    public static final int MIN_Y = -500, MAX_Y = 2000, SEA_LEVEL = 300;

    @Override public String id() { return "vale"; }
    @Override public int version() { return 1; }
    @Override public GenBounds defaultBounds() { return new GenBounds(MIN_Y, MAX_Y, 0); }
    @Override public PlanetGenerator create(GenContext ctx) { return new ValeGen(ctx); }
}
