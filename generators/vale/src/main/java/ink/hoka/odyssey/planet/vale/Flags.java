package ink.hoka.odyssey.planet.vale;

import java.util.Set;

/**
 * Что блок даёт высотам колонки (как HeightIndex ядра): SKY — небо уже не полное (свет гасит или верх полный),
 * BELOW — только нижняя грань полная (небо «под ним»), MOTION — мешает движению. Любой не-воздух — SURFACE (всегда).
 * Таблица по id блока; сверка с ядром — ValeGenTest.flagsMatchCore (каждое состояние генератора и объектов).
 */
final class Flags {

    private Flags() {}

    static final int SKY = 1, BELOW = 2, MOTION = 4, FULL = SKY | MOTION;

    /** Растения и прочее «сквозное»: только SURFACE. */
    private static final Set<String> NONE = Set.of("vine", "cave_vines", "cave_vines_plant", "weeping_vines", "weeping_vines_plant",
            "twisting_vines", "twisting_vines_plant", "hanging_roots", "spore_blossom", "pale_hanging_moss", "glow_lichen", "pink_petals",
            "leaf_litter", "wildflowers", "bush", "firefly_bush", "short_dry_grass", "tall_dry_grass", "cactus_flower", "dead_bush",
            "mangrove_propagule", "pale_moss_carpet", "cocoa", "end_rod", "lily_pad", "short_grass", "fern", "tall_grass", "large_fern",
            "dandelion", "poppy", "oxeye_daisy", "azure_bluet", "cornflower", "allium", "lily_of_the_valley", "blue_orchid", "orange_tulip",
            "pink_tulip", "red_tulip", "white_tulip", "red_mushroom", "brown_mushroom", "closed_eyeblossom", "open_eyeblossom", "sunflower",
            "peony", "rose_bush", "lilac", "sweet_berry_bush", "torchflower", "pitcher_plant", "resin_clump", "air");
    /** Мешают движению, но небо пропускают. */
    private static final Set<String> MOTION_ONLY = Set.of("cactus", "amethyst_cluster", "bamboo");
    /** Ковры: небо «под ними» (нижняя грань полная). */
    private static final Set<String> BELOW_ONLY = Set.of("moss_carpet");

    /** Флаги состояния "minecraft:id[...]". */
    static int of(String state) {
        int b = state.indexOf('[');
        String id = b < 0 ? state : state.substring(0, b);
        if (id.startsWith("minecraft:")) id = id.substring(10);
        if (NONE.contains(id)) return 0;
        if (MOTION_ONLY.contains(id)) return MOTION;
        if (BELOW_ONLY.contains(id)) return BELOW;
        if (id.endsWith("_carpet")) return BELOW;
        int r = FULL;
        if (state.contains("waterlogged=true")) r |= MOTION;
        return r;
    }

    /** Вклад блока на Y в высоту вида k (0 SKY, 1 MOTION, 2 SURFACE) или NONE. */
    static int contrib(int f, int kind, int y, int none) {
        return switch (kind) {
            case 0 -> (f & SKY) != 0 ? y : (f & BELOW) != 0 ? y - 1 : none;
            case 1 -> (f & MOTION) != 0 ? y : none;
            default -> y;
        };
    }
}
