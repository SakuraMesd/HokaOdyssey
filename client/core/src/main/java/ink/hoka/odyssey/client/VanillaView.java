package ink.hoka.odyssey.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.LevelHeightAccessor;

/**
 * Этап 6 (CUBIC-CHUNKS.md §3.6): ванильная отрисовка высокого мира без Sodium. Ванильный ViewArea держит
 * RenderSection на всю высоту мира: у космоса (4094 секции) при дальности 12 это 25·25·4094 ≈ 2,5 млн секций — память
 * кончается. Здесь — радиус кольца по Y (mixin/ViewAreaRingMixin): окно ±rY секций вокруг камеры, как по X/Z.
 * С Sodium не участвует (у него своя отрисовка, SodiumTallMixin). Обычный мир (до 64 секций) — ваниль как есть.
 */
public final class VanillaView {

    private VanillaView() {}

    public static final boolean SODIUM = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
    /**
     * Радиус кольца по Y (в секциях) для ViewArea этого мира при дальности {@code viewDistance}; 0 — ваниль (вся высота).
     * Граф видимости (SectionOcclusionGraph.getRelativeFrom) и так не уходит по Y дальше дальности прорисовки,
     * поэтому больше неё не берём. Окно сервера — ±«Дальность по высоте» (VerticalView), +1 — кольцо для мешинга.
     */
    public static int radiusY(LevelHeightAccessor level, int viewDistance) {
        return ViewRing.radiusY(SODIUM, level == null ? 0 : level.getSectionsCount(), viewDistance, VerticalView.get());
    }

    /** Радиус кольца текущего ViewArea и его дальность (ставит ViewAreaRingMixin; F3 и смена ползунка). */
    public static volatile int current, currentView;

    /** Ползунок «Дальность по высоте» сменился: без Sodium пересоздать ViewArea, если кольцо стало другого размера. */
    public static void changed() {
        if (SODIUM) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.levelRenderer == null) return;
        if (radiusY(mc.level, currentView) != current) mc.levelRenderer.allChanged();
    }
}
