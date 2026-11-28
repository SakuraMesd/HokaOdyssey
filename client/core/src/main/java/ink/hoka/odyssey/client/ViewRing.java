package ink.hoka.odyssey.client;

/**
 * Этап 6: арифметика кольца ViewArea по Y без классов игры (для тестов). Использует mixin/ViewAreaRingMixin
 * через VanillaView.
 */
public final class ViewRing {

    private ViewRing() {}

    /** Мир выше этого числа секций — «высокий» (как признак высокого мира в остальном моде). */
    public static final int TALL_SECTIONS = 64;

    /**
     * Радиус кольца по Y (секции); 0 — ваниль (вся высота). Граф видимости (SectionOcclusionGraph.getRelativeFrom) и так
     * не уходит по Y дальше дальности прорисовки — больше неё не берём. Окно сервера — ±«Дальность по высоте»
     * (vertical), +1 — кольцо соседей для мешинга. Не меньше 2.
     */
    public static int radiusY(boolean sodium, int sections, int viewDistance, int vertical) {
        if (sodium || sections <= TALL_SECTIONS) return 0;
        int r = Math.max(2, Math.min(vertical + 1, viewDistance));
        return 2 * r + 1 >= sections ? 0 : r;
    }

    /** Нижняя секция кольца: ±r вокруг камеры, прижато к границам мира (секции вне мира не заводим). */
    public static int base(int camY, int r, int minSection, int maxSection) {
        int size = 2 * r + 1;
        int b = camY - r;
        if (b + size - 1 > maxSection) b = maxSection - size + 1;
        if (b < minSection) b = minSection;
        return b;
    }

    /** Какая секция Y лежит в слое j кольца с нижней секцией base (≡ j по модулю size). */
    public static int layerY(int j, int base, int size) {
        return base + Math.floorMod(j - base, size);
    }

    /** Слой кольца для секции Y. */
    public static int slot(int y, int size) {
        return Math.floorMod(y, size);
    }
}
