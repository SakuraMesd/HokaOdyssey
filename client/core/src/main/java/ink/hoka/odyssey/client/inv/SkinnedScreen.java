package ink.hoka.odyssey.client.inv;

import net.minecraft.client.Minecraft;

/**
 * 0.80 Окно-контейнер с оформлением модуля (ClientContext.onContainer) — его реализует миксин
 * {@code ContainerSkinMixin} у всех AbstractContainerScreen.
 */
public interface SkinnedScreen {

    /** Чьё оформление у окна (id модуля) или null — окно как у игры. */
    String hoka$skinOwner();

    /** Своя рамка слота под мышью у модуля (рамку игры не рисовать). */
    boolean hoka$ownHighlight();

    /** Модуль выгружен — его окно закрыть (owner null — любое окно с оформлением). */
    static void closeOf(String owner) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof SkinnedScreen s && s.hoka$skinOwner() != null && (owner == null || owner.equals(s.hoka$skinOwner()))
                    && mc.player != null) mc.player.closeContainer();
        });
    }
}
