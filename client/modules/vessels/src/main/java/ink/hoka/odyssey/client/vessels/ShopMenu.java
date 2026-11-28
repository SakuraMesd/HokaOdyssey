package ink.hoka.odyssey.client.vessels;

import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.vessels.hud.McGfx;
import ink.hoka.odyssey.client.vessels.hud.ShopArt;
import ink.hoka.odyssey.client.vessels.hud.ShopInfo;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Окно мастерской корабля в стиле HUD — оформление окна-сундука сервера (как {@link ShipMenu}): слоты те же (клики
 * считает сервер), меняются их места ({@link ShopArt}), фон и подписи; что нужно и сколько есть — S2C SHOP.
 */
final class ShopMenu implements ClientContext.ContainerSkin {

    private final ShopInfo info;
    private final ShopArt art = new ShopArt();
    private final McGfx gfx;

    ShopMenu(ShopInfo info, McGfx gfx) {
        this.info = info;
        this.gfx = gfx;
    }

    @Override public int[] size(int screenW, int screenH) { return new int[]{ShopArt.W, ShopArt.H}; }

    @Override public int[] slot(int index) { return index < ShopArt.SLOTS ? ShopArt.slot(index) : null; }

    @Override
    public void background(GuiGraphics g, int left, int top, int mouseX, int mouseY, float partialTick) {
        art.background(gfx.frame(g), left, top);
    }

    @Override
    public void labels(GuiGraphics g, int mouseX, int mouseY, int hovered) {
        art.labels(gfx.frame(g), 0, 0, info, hovered);
    }

    @Override public boolean vanillaHighlight() { return false; }
}
