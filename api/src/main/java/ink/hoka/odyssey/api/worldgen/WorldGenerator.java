package ink.hoka.odyssey.api.worldgen;

import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;

/**
 * Генератор одной карты-планеты (экземпляр на карту — {@link GeneratorProvider#create}). Ядро вызывает его
 * ПАРАЛЛЕЛЬНО из многих потоков: без общего изменяемого состояния (кэши — только потокобезопасные и такие, что
 * результат не зависит от порядка), без Math.random/ThreadLocalRandom — случайность только от сида и координат.
 * Проверка: /hocubes gentest &lt;карта&gt;.
 */
public interface WorldGenerator {

    /** «В колонке ничего нет» (как HeightIndex ядра: MIN_Y − 1). */
    int NONE = -15009;
    /** highest не умеет — ядро возьмёт из индекса pregen или посчитает по кубам. */
    int UNKNOWN = Integer.MIN_VALUE;

    /** Заполнить куб out.cx/cy/cz. Только свой куб; детерминированно (если провайдер deterministic). */
    void generate(CubeBuffer out);

    /** false — куб точно пуст (generate не зовём). Вне Bounds ядро и так считает пустым. */
    default boolean hasBlocks(int cx, int cy, int cz) { return true; }

    /** Весь куб — один блок (недра планеты), без биомов и блоков с данными; null — неизвестно (позовут generate). */
    default Block uniform(int cx, int cy, int cz) { return null; }

    /**
     * Самый верхний Y в [yFrom, yTo] колонки (x, z), который дал бы generate для вида k, или {@link #NONE};
     * {@link #UNKNOWN} — не умею (по умолчанию). Ошибка здесь портит свет — не уверен, отдавай UNKNOWN.
     */
    default int highest(int x, int z, int yFrom, int yTo, HeightKind k) { return UNKNOWN; }

    /** Биом столбца (для заголовка); null — первый из реестра. Биомы куба пишет generate (out.biome). */
    default RegistryKey<Biome> columnBiome(int x, int z) { return null; }

    /** Ядро больше не будет звать генератор (выход сервера). */
    default void close() {}
}
