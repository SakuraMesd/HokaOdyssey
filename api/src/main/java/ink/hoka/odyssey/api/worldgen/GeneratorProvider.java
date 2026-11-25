package ink.hoka.odyssey.api.worldgen;

/**
 * Генератор планеты, который регистрирует модуль ({@code registerGenerator(new MarsProvider())}); в map.yml карты —
 * {@code generator: <id>}. Только для высоких карт (tall: true, store: cubes).
 *
 * <pre>
 * public final class MarsProvider implements GeneratorProvider {
 *     public String id() { return "mars"; }
 *     public int version() { return 1; }          // поменял логику — увеличь
 *     public Bounds defaultBounds() { return new Bounds(-1000, 1000); }
 *     public WorldGenerator create(GenSettings s) { return new MarsGen(s); }
 * }
 * </pre>
 */
public interface GeneratorProvider {

    /** a-z 0-9 _ - ; то, что пишут в map.yml generator:. */
    String id();

    /**
     * Версия логики. Поменял генерацию — увеличь: правки игроков на диске останутся, нетронутое сгенерируется
     * заново новой версией, индекс pregen старой версии перестанет использоваться (нужен новый pregen).
     */
    int version();

    /**
     * true — один и тот же куб всегда одинаковый (по сиду и координатам). Тогда на диск пишутся только правки
     * игроков — карту 100k×100k можно держать. false — каждый сгенерированный куб обязан лечь на диск (только
     * маленькие карты).
     */
    default boolean deterministic() { return true; }

    /** Где у планеты есть блоки (map.yml gen_min_y/gen_max_y/gen_size переопределяют). */
    default Bounds defaultBounds() { return new Bounds(-1024, 1023); }

    /** Генератор для карты (на каждую карту свой экземпляр). */
    WorldGenerator create(GenSettings s);
}
