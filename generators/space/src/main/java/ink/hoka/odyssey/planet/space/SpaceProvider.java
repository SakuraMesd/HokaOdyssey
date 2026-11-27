package ink.hoka.odyssey.planet.space;

import ink.hoka.odyssey.gen.GenBounds;
import ink.hoka.odyssey.gen.GenContext;
import ink.hoka.odyssey.gen.GenProvider;
import ink.hoka.odyssey.gen.PlanetGenerator;

/**
 * Генератор «space» v1: открытый космос. Пусто почти везде (корабли летают свободно); изредка — астероиды (камень с
 * реголитом, ледяные, металлические), мелкие метеориты, редкие скопления астероидов. Руды (11 видов) и тайники с лутом —
 * секреты (видны только вблизи, против X-ray). Около планет и солнца (generator_options.exclude) — пусто.
 * generator_options — см. {@link SpaceGen}.
 */
public final class SpaceProvider implements GenProvider {

    @Override public String id() { return "space"; }
    @Override public int version() { return 1; }
    @Override public GenBounds defaultBounds() { return new GenBounds(-12000, 12000, 50000); }
    @Override public PlanetGenerator create(GenContext ctx) { return new SpaceGen(ctx); }
}
