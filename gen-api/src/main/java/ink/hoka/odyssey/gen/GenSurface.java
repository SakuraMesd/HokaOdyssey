package ink.hoka.odyssey.gen;

/**
 * Поверхность для расстановки объектов (FeatureGrid): чистая функция колонки — кубы НЕ читает, от порядка генерации не
 * зависит. Рельеф генератора реализует её сам (дёшево: зовут на каждую ячейку для каждого задетого куба).
 */
public interface GenSurface {

    int NONE = PlanetGenerator.NONE;

    /** Y верхнего твёрдого блока колонки или {@link #NONE}. */
    int height(int x, int z);

    /** id верхнего блока ("minecraft:grass_block") или null — неизвестно (фильтр on не применяется). */
    default String block(int x, int z) { return null; }

    /** Над поверхностью вода. */
    default boolean water(int x, int z) { return false; }

    /** Биом колонки ("minecraft:plains") или null — неизвестно (фильтр biomes не применяется). */
    default String biome(int x, int z) { return null; }
}
