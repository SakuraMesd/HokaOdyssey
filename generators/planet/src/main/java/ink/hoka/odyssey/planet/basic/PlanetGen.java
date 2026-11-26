package ink.hoka.odyssey.planet.basic;

import ink.hoka.odyssey.gen.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Заглушка рельефа (нейтральная — чтобы проверить расстановку объектов): дно — бедрок, ниже моря−3 — камень, 3 слоя
 * земли, на уровне моря — трава, выше пусто; биом — равнина (в кубах целиком под землёй — 0: однородный камень). Объекты — сетки FeatureGrid из generator_options.features
 * (по умолчанию trees: cell 12, density 0.35, margin 2). Без изменяемых полей: вызывается параллельно.
 * <pre>
 * generator_options:
 *   sea_level: 300
 *   features:
 *     - {category: trees, cell: 12, density: 0.35, margin: 2}
 * </pre>
 */
final class PlanetGen implements PlanetGenerator {

    private final int bottom, sea;
    private final int bedrock, stone, dirt, grass, plains;
    private final GenSurface surface;
    private final FeatureGrid[] grids;

    PlanetGen(GenContext c) {
        GenBounds b = c.bounds();
        bottom = b.minY();
        sea = Math.max(bottom + 4, Math.min(b.maxY(), c.intOption("sea_level", PlanetProvider.SEA_LEVEL)));
        GenIds ids = c.ids();
        bedrock = ids.defaultState("minecraft:bedrock");
        stone = ids.defaultState("minecraft:stone");
        dirt = ids.defaultState("minecraft:dirt");
        grass = ids.state("minecraft:grass_block[snowy=false]");
        plains = ids.biome("minecraft:plains");
        surface = new StubSurface(sea);
        GenPlacer placer = GenPlacer.of(c);
        List<FeatureGrid> gs = new ArrayList<>();
        for (Map<?, ?> f : features(c)) {
            Object cat = f.get("category");
            if (cat == null) continue;
            FeatureGrid g = FeatureGrid.of(c, placer, cat.toString())
                    .cell(num(f.get("cell"), 12).intValue())
                    .density(num(f.get("density"), 0.35).doubleValue())
                    .margin(num(f.get("margin"), 2).intValue())
                    .salt(f.get("salt") == null ? "" : f.get("salt").toString())
                    .build();
            if (!g.isEmpty()) gs.add(g);
        }
        grids = gs.toArray(new FeatureGrid[0]);
    }

    private static Number num(Object v, Number def) { return v instanceof Number n ? n : def; }

    /** generator_options.features: список {category, cell, density, margin}; нет — trees по умолчанию. */
    private static List<Map<?, ?>> features(GenContext c) {
        Object v = c.options().get("features");
        List<Map<?, ?>> r = new ArrayList<>();
        if (v instanceof List<?> l) { for (Object o : l) if (o instanceof Map<?, ?> m) r.add(m); }
        else r.add(Map.of("category", "trees", "cell", 12, "density", 0.35, "margin", 2));
        return r;
    }

    private int layer(int y) {
        if (y < bottom || y > sea) return 0;
        if (y == bottom) return bedrock;
        if (y <= sea - 4) return stone;
        if (y < sea) return dirt;
        return grass;
    }

    private boolean anyGrid(int cx, int cy, int cz) {
        for (FeatureGrid g : grids) if (g.mayTouch(cx, cy, cz, sea, sea)) return true;
        return false;
    }

    @Override
    public void generate(GenCube out) {
        int y0 = out.y0();
        for (int ly = 0; ly < 16; ly++) {
            int s = layer(y0 + ly);
            if (s != 0) out.fillY(ly, ly, s);
        }
        if (y0 + 15 > sea - 4) out.fillBiome(plains);                       // под землёй биом 0 — камень идёт однородной меткой
        for (FeatureGrid g : grids) g.place(out, surface, sea, sea);
    }

    @Override
    public boolean hasBlocks(int cx, int cy, int cz) {
        int y0 = cy << 4;
        if (y0 + 15 < bottom) return false;
        return y0 <= sea || anyGrid(cx, cy, cz);
    }

    @Override
    public int uniform(int cx, int cy, int cz) {
        int y0 = cy << 4;
        return y0 > bottom && y0 + 15 <= sea - 4 && !anyGrid(cx, cy, cz) ? stone : -1;
    }

    @Override
    public int highest(int x, int z, int yFrom, int yTo, HeightKind k) {
        if (grids.length > 0) return UNKNOWN;                                // деревья — ядро возьмёт из индекса/кубов
        if (yTo < bottom || yFrom > yTo) return NONE;
        int top = Math.min(sea, yTo);
        return top >= Math.max(yFrom, bottom) ? top : NONE;
    }

    @Override public int columnBiome(int x, int z) { return plains; }

    @Override
    public boolean mayHaveSecrets(int cx, int cy, int cz) {
        for (FeatureGrid g : grids) if (g.hasData() && g.mayTouch(cx, cy, cz, sea, sea)) return true;
        return false;
    }
}
