package ink.hoka.odyssey.gen;

/**
 * Генератор одной карты-планеты. Вызывается ПАРАЛЛЕЛЬНО из многих потоков: без изменяемого общего состояния (кэши —
 * только такие, что результат не зависит от порядка), без Math.random/ThreadLocalRandom/времени.
 * Номера состояний блоков и биомов — из {@link GenContext#ids()} (сервер и клиент строят свои таблицы, номера совпадают).
 * <p>Анти-X-ray: руды и тайники — только через {@link GenCube#secret} и только от {@link GenContext#secretSeed()}
 * (на клиенте его нет: там секреты не генерируются вовсе, их присылает сервер, когда игрок рядом).</p>
 */
public interface PlanetGenerator {

    /** «В колонке ничего нет» (как HeightIndex ядра: MIN_Y − 1). */
    int NONE = -15009;
    /** highest не умеет. */
    int UNKNOWN = Integer.MIN_VALUE;

    /** Заполнить куб out.cx/cy/cz (видимую часть; секреты — out.secret, они пишутся, только если out.secrets()). */
    void generate(GenCube out);

    /** false — куб точно пуст (generate не зовут). */
    default boolean hasBlocks(int cx, int cy, int cz) { return true; }

    /** Весь куб — одно состояние, биомы 0, секретов нет: номер состояния или −1 (неизвестно — позовут generate). */
    default int uniform(int cx, int cy, int cz) { return -1; }

    /** Самый верхний Y в [yFrom, yTo] колонки, который дал бы generate для вида k, или NONE; UNKNOWN — не умею. */
    default int highest(int x, int z, int yFrom, int yTo, HeightKind k) { return UNKNOWN; }

    /** Номер биома столбца (заголовок) или −1 — первый из реестра. */
    default int columnBiome(int x, int z) { return -1; }

    /** В кубе могут быть секреты (руды, тайники) — сервер пришлёт их клиенту вблизи (op 22). */
    default boolean mayHaveSecrets(int cx, int cy, int cz) { return false; }

    /**
     * Дальняя прорисовка: заполнить плитку {@link LodTile} (верх ячеек — высота, блок, вода, биом). Должно быть дёшево —
     * зовут для тысяч ячеек далеко от игрока (точка рельефа без объектов и пещер; кроны лесов — по желанию). Секреты не
     * показывать. false — не умею (тогда вдали видно только то, что игрок уже видел вблизи).
     */
    default boolean lod(LodTile t) { return false; }

    /** Генератор больше не нужен. */
    default void close() {}
}
