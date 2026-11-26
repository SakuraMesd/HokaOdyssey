package ink.hoka.odyssey.gen;

/**
 * Уровень API генераторов. hoka-gen.json {@code "api"}: 1 — до объектов (ядро 0.59), 2 — объекты (GenObjects, GenPlacer,
 * FeatureGrid; ядро 0.60). Jar с api выше {@link #LEVEL} не грузится («нужно ядро новее»).
 */
public final class GenApi {
    private GenApi() {}

    public static final int LEVEL = 2;
}
