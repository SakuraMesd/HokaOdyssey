package ink.hoka.odyssey.planet.vale;

import ink.hoka.odyssey.gen.GenBounds;
import ink.hoka.odyssey.gen.GenContext;
import ink.hoka.odyssey.gen.GenProvider;
import ink.hoka.odyssey.gen.PlanetGenerator;

/**
 * Генератор планеты «vale» v3: высоты −500…2000, море 300 (generator_options.sea_level); равнины и холмы 300–480,
 * пояса хребтов с пиками до ~1950, мезы, тепуи, разломы, реки, топи, 24 биома; процедурные деревья-великаны, грибы и
 * цветы + объекты категорий oak, birch, spruce, bushes, rocks, glycine, azure, palm, glowfingers, bellflowers, rafflesia.
 * v1 → v2: тот же id (одна карта, перегенерировать), клиенты перекачивают jar по версии.
 * v3: листва в мир — естественная (persistent=false, ванильная distance; persistent=true — носители своих блоков:
 * акациевая листва рисовалась «Ступкой»), рудные валуны (уголь, железо, медь). Рельеф тот же, объекты — нет: карта заново.
 * generator_options: sea_level (300), tree_density (1.0; 0…2).
 */
public final class ValeProvider implements GenProvider {

    public static final int MIN_Y = -500, MAX_Y = 2000, SEA_LEVEL = 300;

    @Override public String id() { return "vale"; }
    @Override public int version() { return 3; }
    @Override public GenBounds defaultBounds() { return new GenBounds(MIN_Y, MAX_Y, 0); }
    @Override public PlanetGenerator create(GenContext ctx) { return new ValeGen(ctx); }
}
