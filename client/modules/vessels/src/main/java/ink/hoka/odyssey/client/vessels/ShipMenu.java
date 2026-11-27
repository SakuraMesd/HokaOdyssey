package ink.hoka.odyssey.client.vessels;

import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.vessels.hud.McGfx;
import ink.hoka.odyssey.client.vessels.hud.MenuArt;
import ink.hoka.odyssey.client.vessels.hud.MenuInfo;
import ink.hoka.odyssey.client.vessels.hud.MenuLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.List;
import java.util.Optional;

/**
 * Окно корабля (G) в стиле HUD — оформление окна-сундука сервера (ядро 0.80, ClientContext.onContainer). Слоты те же
 * (сервер — тот же инвентарь Minestom: роли слотов, Shift-клик, доступ), меняются только их места
 * ({@link MenuLayout}), фон и подписи ({@link MenuArt}). Сводка — из S2C MENU ({@link MenuInfo}), обновляется на лету.
 */
final class ShipMenu implements ClientContext.ContainerSkin {

    private final MenuInfo info;
    private final AbstractContainerMenu menu;
    private final MenuArt art = new MenuArt();
    private final McGfx gfx;
    private final Runnable onClose;

    ShipMenu(MenuInfo info, AbstractContainerMenu menu, McGfx gfx, Runnable onClose) {
        this.info = info;
        this.menu = menu;
        this.gfx = gfx;
        this.onClose = onClose;
    }

    @Override public int[] size(int screenW, int screenH) { return new int[]{MenuLayout.W, MenuLayout.H}; }

    @Override public int[] slot(int index) { return index < MenuLayout.SLOTS ? MenuLayout.slot(index) : null; }

    @Override
    public void background(GuiGraphics g, int left, int top, int mouseX, int mouseY, float partialTick) {
        art.background(gfx.frame(g), left, top);
    }

    @Override
    public void labels(GuiGraphics g, int mouseX, int mouseY, int hovered) {
        boolean[] empty = new boolean[Math.min(MenuLayout.SLOTS, menu.slots.size())];
        for (int i = 0; i < empty.length; i++) empty[i] = !menu.slots.get(i).hasItem();
        art.labels(gfx.frame(g), 0, 0, info, empty, hovered);
        if (hovered >= 0 && hovered < empty.length && empty[hovered] && menu.getCarried().isEmpty()) {   // что класть в пустой слот
            List<Component> tip = switch (MenuLayout.kind(hovered)) {
                case UPGRADE -> List.of(Component.literal("Улучшение"),
                        Component.literal("Двигатель+, Бак+, Орудие+, Варп-катушка").withStyle(net.minecraft.ChatFormatting.GRAY),
                        Component.literal("По одному в слот, одинаковые складываются").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
                case FUEL -> List.of(Component.literal("Топливо"),
                        Component.literal("Бак — 4 слота, варп тратит отсюда").withStyle(net.minecraft.ChatFormatting.GRAY));
                default -> null;
            };
            if (tip != null) g.setTooltipForNextFrame(Minecraft.getInstance().font, tip, Optional.empty(), mouseX, mouseY);
        }
    }

    @Override public boolean vanillaHighlight() { return false; }

    @Override public void removed() { onClose.run(); }
}
