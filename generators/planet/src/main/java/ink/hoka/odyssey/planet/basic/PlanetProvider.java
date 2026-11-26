package ink.hoka.odyssey.planet.basic;

import ink.hoka.odyssey.gen.GenBounds;
import ink.hoka.odyssey.gen.GenContext;
import ink.hoka.odyssey.gen.GenProvider;
import ink.hoka.odyssey.gen.PlanetGenerator;

/**
 * Генератор планеты «planet» v1: высоты −500…2000, уровень моря 300 (map.yml generator_options.sea_level).
 * Рельеф — пока заглушка (PlanetGen: плоская трава на уровне моря), объекты — из библиотеки карты (objects/).
 */
public final class PlanetProvider implements GenProvider {

    public static final int MIN_Y = -500, MAX_Y = 2000, SEA_LEVEL = 300;

    @Override public String id() { return "planet"; }
    @Override public int version() { return 1; }
    @Override public GenBounds defaultBounds() { return new GenBounds(MIN_Y, MAX_Y, 0); }
    @Override public PlanetGenerator create(GenContext ctx) { return new PlanetGen(ctx); }
}
