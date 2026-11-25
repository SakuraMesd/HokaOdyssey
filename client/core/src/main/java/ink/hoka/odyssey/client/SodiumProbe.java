package ink.hoka.odyssey.client;

/** Отладка (F3): построена ли секция у Sodium. Классы Sodium трогаем, только если он стоит. */
final class SodiumProbe {

    private SodiumProbe() {}

    private static final boolean SODIUM = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");

    /** true/false — построена ли секция; null — Sodium нет или не готов. */
    static Boolean built(int x, int y, int z) {
        if (!SODIUM) return null;
        try {
            return Impl.built(x, y, z);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class Impl {
        static Boolean built(int x, int y, int z) {
            var r = net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.instanceNullable();
            return r == null ? null : r.isSectionReady(x, y, z);
        }
    }
}
