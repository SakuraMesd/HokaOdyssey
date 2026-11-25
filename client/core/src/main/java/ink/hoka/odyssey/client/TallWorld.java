package ink.hoka.odyssey.client;

/**
 * Высокий мир (до ±32 752 по высоте). Внутри игры координата блока упаковывается в 64 бита: у ванили X/Z по 26 бит,
 * Y 12 бит (отсюда предел высоты −2032…2031). Мод перекладывает так: X/Z по 24 бита (±8 388 607), Y 16 бит —
 * см. mixin/BlockPosPackingMixin; лимиты измерения игра выводит из этого сама.
 * <p>
 * В СЕТИ формат зависит от сервера: наш сервер (сигнал «hoka_odyssey:tall» на этапе входа) шлёт позиции в новом
 * формате, любой другой — в ванильном (mixin/BlockPosNetMixin переводит).
 */
public final class TallWorld {

    private TallWorld() {}

    public static final int HORIZONTAL_BITS = 24, Y_BITS = 16;

    /** Сервер сказал, что мир высокий: позиции блоков в пакетах — в нашем формате. */
    public static volatile boolean net;

    /**
     * 0.48: дальность, которую клиент сообщает нашему серверу (ClientInformation.viewDistance), не больше этой
     * (-Dhoka.view-cap, 0 — не ограничивать). Minestom шлёт столбцы до min(клиент, сервер) + 1 = 33, а «зрителями»
     * столбца (Chunk.getViewers, радиус 32) игрок на кольце 33 не считается: кубы, загрузившиеся там, сервер никому не
     * досылает, и при шаге вперёд это кольцо становится видимой полосой без земли (на сервере блоки есть — застреваешь).
     * С 31 сервер шлёт до 32 — все отправленные столбцы в радиусе зрителей. Цена — одно кольцо дальности прорисовки.
     * Временная мера до правки сервера (CubeStreamer: получатели по st.sent вместо getViewers).
     */
    public static final int VIEW_CAP = Integer.getInteger("hoka.view-cap", 0);   // сервер 25ноя уже чинит кольцо 33 — не режем

    // ── ванильный сетевой формат: X 26 бит (сдвиг 38), Z 26 бит (сдвиг 12), Y 12 бит ──

    public static long vanillaPack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    public static int vanillaX(long l) { return (int) (l >> 38); }

    public static int vanillaY(long l) { return (int) (l << 52 >> 52); }

    public static int vanillaZ(long l) { return (int) (l << 26 >> 38); }

    // ── наш формат (= внутренняя упаковка мода): X 24 бита (сдвиг 40), Z 24 бита (сдвиг 16), Y 16 бит ──

    public static long tallPack(int x, int y, int z) {
        return ((long) x & 0xFFFFFFL) << 40 | ((long) z & 0xFFFFFFL) << 16 | ((long) y & 0xFFFFL);
    }

    public static int tallX(long l) { return (int) (l >> 40); }

    public static int tallY(long l) { return (int) (l << 48 >> 48); }

    public static int tallZ(long l) { return (int) (l << 24 >> 40); }
}
