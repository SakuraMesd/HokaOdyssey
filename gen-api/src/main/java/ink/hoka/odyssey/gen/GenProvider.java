package ink.hoka.odyssey.gen;

/**
 * Точка входа jar генератора планеты (класс указан в {@code hoka-gen.json}: {"id","version","provider"}). Один и тот же
 * jar работает на сервере и на клиенте: только классы {@code java.*} и этого пакета (GenClassLoader), никаких Minestom/MC.
 * Детерминизм обязателен: для чисел — только {@code StrictMath} (Math.sin/cos/pow/… запрещены, см. GenBytecodeCheck),
 * случайность — {@link GenRandom}/{@link GenNoise} от сида и координат.
 */
public interface GenProvider {

    /** a-z 0-9 _ - (до 32); то, что пишут в map.yml generator:. */
    String id();

    /** Версия вывода. Поменял, что генерируется, — увеличь (иначе клиенты со старым jar разойдутся с сервером). */
    int version();

    /** Где у планеты есть блоки (map.yml gen_min_y/gen_max_y/gen_size переопределяют). */
    default GenBounds defaultBounds() { return new GenBounds(-1024, 1023, 0); }

    /** Генератор карты (экземпляр на карту и на сторону — сервер и каждый клиент создают свой). */
    PlanetGenerator create(GenContext ctx);
}
