package ink.hoka.odyssey.server.tall;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.GenerationUnit;
import net.minestom.server.instance.generator.GeneratorImpl;
import net.minestom.server.instance.generator.UnitModifier;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;

import java.util.function.Consumer;

/**
 * Генератор по кубам 16×16×16 (CUBIC-CHUNKS.md §4.5, этап 4 шаг 5). Обязательные требования к генератору кубовой карты:
 * <ul>
 *   <li>{@link #generateCube} пишет только в свой куб (границы Minestom не проверяет), без форков, детерминированно;</li>
 *   <li>{@link #highest} отвечает аналитически, не генерируя кубы (§4.4: верх колонки при выгруженных кубах) — и
 *       обязан совпадать с тем, что поставил бы generateCube (юнит-тест CubeGenTest);</li>
 *   <li>{@link #biomeAt} — биом столбца.</li>
 * </ul>
 * GeneratorImpl.section/GenSection — @Internal Minestom: версия закреплена (2026.05.17), при обновлении — проверить.
 */
public interface CubeGen {

    /** Заполнить куб (cx, cy, cz) — в абсолютных координатах блоков unit. */
    void generateCube(int cx, int cy, int cz, GenerationUnit unit);

    /**
     * Самый верхний Y в [yFrom, yTo], который дал бы индекс высот (HeightIndex.contrib) по блокам generateCube для вида
     * kind, или {@link HeightIndex#NONE}.
     */
    int highest(int x, int z, int yFrom, int yTo, HeightIndex.Kind kind);

    /** Биом столбца (x, z — блоки). */
    RegistryKey<Biome> biomeAt(int x, int z);

    /** Подсказка: в кубе может что-то быть (false — точно пусто, generateCube не зовём). */
    default boolean hasBlocks(int cx, int cy, int cz) { return true; }

    /** Один и тот же куб всегда одинаковый (иначе сгенерированное обязано лечь на диск). */
    default boolean deterministic() { return true; }

    /** id@версия генератора — тег gen в заголовке столбца, generator_seen в map.yml. */
    default String tag() { return "flat@1"; }

    /** Сгенерировать куб в новую секцию (по умолчанию — через GenerationUnit Minestom; ModuleCubeGen — напрямую в палитры). */
    default Cube cube(int cx, int cy, int cz) {
        Section sec = new Section();
        var gs = new GeneratorImpl.GenSection(sec.blockPalette(), sec.biomePalette());
        GenerationUnit unit = GeneratorImpl.section(MinecraftServer.getBiomeRegistry(), gs, cx, cy, cz);
        generateCube(cx, cy, cz, new NoFork(unit));
        return new Cube(sec, gs.specials());
    }

    /** Результат генерации куба: секция и блоки с данными/обработчиком (их ставить своим setBlock). */
    record Cube(Section section, Int2ObjectMap<Block> specials) {
        public boolean empty() { return section.blockPalette().count() == 0 && specials.isEmpty(); }
    }

    /** Сгенерировать куб в новую секцию (форки запрещены — бросят исключение). */
    static Cube generate(CubeGen gen, int cx, int cy, int cz) { return gen.cube(cx, cy, cz); }

    /** Обёртка unit без форков: генератор кубов пишет только в свой куб. */
    record NoFork(GenerationUnit u) implements GenerationUnit {
        @Override public UnitModifier modifier() { return u.modifier(); }
        @Override public Point size() { return u.size(); }
        @Override public Point absoluteStart() { return u.absoluteStart(); }
        @Override public Point absoluteEnd() { return u.absoluteEnd(); }
        @Override public GenerationUnit fork(Point start, Point end) { throw new IllegalStateException("CubeGen: форки запрещены"); }
        @Override public void fork(Consumer<Block.Setter> setter) { throw new IllegalStateException("CubeGen: форки запрещены"); }
    }
}
