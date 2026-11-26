package ink.hoka.odyssey.client;

/** Отладка (F3): построена ли секция у Sodium. Классы Sodium трогаем, только если он стоит. */
public final class SodiumProbe {

    private SodiumProbe() {}

    /** 0.63.5, F3 «под прицелом»: описание секции у Sodium (реализует SodiumTallMixin). Без классов Sodium в сигнатуре. */
    public interface Probe {
        String hoka$describe(int x, int y, int z);
    }

    /** Текущий RenderSectionManager высокого мира (ставится каждый кадр); null — Sodium не рисует высокий мир. */
    public static volatile Probe probe;
    /** До какого времени (мс) копить отладочный учёт полосы — пока открыт F3 (читает SodiumBand). */
    public static volatile long diagUntil;

    static String describe(Probe p, int x, int y, int z) {
        try {
            return p.hoka$describe(x, y, z);
        } catch (Throwable t) {
            return "Sodium: ошибка " + t;
        }
    }

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
