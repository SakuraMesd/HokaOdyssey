package ink.hoka.odyssey.client.inv;

import ink.hoka.odyssey.client.mixin.SlotAccessor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 0.67 Окно инвентаря выживания: игрок в центре, вокруг кольцом броня (слева), скафандр и джетпак, 2 слота «прочее»
 * (справа), вторая рука снизу; под ним инвентарь 3×9 и быстрая панель. Крафта нет (слоты крафта уводятся за экран).
 * Фон — текстура gui/inventory.png (рисует gui-art/inventory_space/gen.py; раскладка — константы ниже, те же).
 * Слоты игры те же (player.inventoryMenu) — просто стоят в других местах; на закрытии места возвращаются.
 */
public final class HokaInventoryScreen extends AbstractContainerScreen<InventoryMenu> {

    private static final Identifier BG = Identifier.fromNamespaceAndPath("hoka_odyssey", "textures/gui/inventory.png");
    static final int W = 190, H = 238, CX = 95, CY = 75;
    /** Левый верх предмета (16×16) в окне: броня (шлем, грудь, ноги, ботинки), вторая рука, доп. слоты. */
    private static final int[][] ARMOR = {{69, 18}, {42, 41}, {36, 76}, {54, 107}};
    private static final int[] OFFHAND = {87, 119};
    static final int[][] EXTRA = {{105, 18}, {132, 41}, {138, 76}, {120, 107}};
    private static final int GRID_X = 15, GRID_Y = 153, HOT_Y = 216;
    private static final String[] EXTRA_NAME = {"Скафандр", "Джетпак", "Прочее", "Прочее"};

    private final int[] ox = new int[46], oy = new int[46];
    private final Player player;

    public HokaInventoryScreen(Player player) {
        super(player.inventoryMenu, player.getInventory(), Component.translatable("container.inventory"));
        this.player = player;
        imageWidth = W;
        imageHeight = H;
    }

    @Override
    protected void init() {
        super.init();
        var slots = menu.slots;
        for (int i = 0; i < slots.size() && i < 46; i++) { ox[i] = slots.get(i).x; oy[i] = slots.get(i).y; }
        for (int i = 0; i <= 4; i++) move(i, -10000, -10000);                 // результат и 2×2 крафта — убраны
        for (int i = 0; i < 4; i++) move(5 + i, ARMOR[i][0], ARMOR[i][1]);
        for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) move(9 + r * 9 + c, GRID_X + c * 18, GRID_Y + r * 18);
        for (int c = 0; c < 9; c++) move(36 + c, GRID_X + c * 18, HOT_Y);
        move(45, OFFHAND[0], OFFHAND[1]);
    }

    private void move(int i, int x, int y) {
        if (i >= menu.slots.size()) return;
        SlotAccessor a = (SlotAccessor) menu.slots.get(i);
        a.hoka$setX(x);
        a.hoka$setY(y);
    }

    @Override
    public void removed() {
        for (int i = 0; i < menu.slots.size() && i < 46; i++) move(i, ox[i], oy[i]);   // ванильные места — творческому и др.
        super.removed();
    }

    @Override
    protected void renderBg(GuiGraphics g, float pt, int mx, int my) {
        g.blit(RenderPipelines.GUI_TEXTURED, BG, leftPos, topPos, 0, 0, W, H, 256, 256);
        InventoryScreen.renderEntityInInventoryFollowsMouse(g, leftPos + CX - 26, topPos + CY - 44, leftPos + CX + 26, topPos + CY + 34,
                30, 0.0625f, mx, my, player);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {}                // без надписей «Инвентарь» / «Создание»

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        for (int i = 0; i < HokaInventory.EXTRA; i++) {
            int x = leftPos + EXTRA[i][0], y = topPos + EXTRA[i][1];
            ItemStack it = HokaInventory.EXTRAS[i];
            if (!it.isEmpty()) {
                g.renderItem(it, x, y);
                g.renderItemDecorations(font, it, x, y);
            }
            if (over(i, mx, my)) {
                g.fill(x, y, x + 16, y + 16, 0x80FFFFFF);
                if (menu.getCarried().isEmpty()) {
                    if (!it.isEmpty()) g.setTooltipForNextFrame(font, it, mx, my);
                    else g.setTooltipForNextFrame(font, Component.literal(EXTRA_NAME[i]), mx, my);
                }
            }
        }
    }

    private boolean over(int i, double mx, double my) {
        double x = mx - leftPos - EXTRA[i][0], y = my - topPos - EXTRA[i][1];
        return x >= -1 && y >= -1 && x < 17 && y < 17;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        for (int i = 0; i < HokaInventory.EXTRA; i++) {
            if (!over(i, e.x(), e.y())) continue;
            int b = e.button();
            if (b != 0 && b != 1) return true;
            boolean shift = hasShiftDown();
            predict(i, b, shift);
            HokaInventory.click(i, b, shift);
            return true;
        }
        return super.mouseClicked(e, dbl);
    }

    /** Сразу показать итог клика (сервер пришлёт свой — совпадёт). Те же правила, что у сервера (SurvivalModule). */
    private void predict(int i, int button, boolean shift) {
        ItemStack slot = HokaInventory.EXTRAS[i], cur = menu.getCarried();
        if (shift) {
            if (!slot.isEmpty() && player.getInventory().add(slot.copy())) HokaInventory.EXTRAS[i] = ItemStack.EMPTY;
            return;
        }
        if (button == 0) {
            if (!cur.isEmpty() && !slot.isEmpty() && ItemStack.isSameItemSameComponents(cur, slot)) {
                int room = slot.getMaxStackSize() - slot.getCount(), n = Math.min(room, cur.getCount());
                slot = slot.copyWithCount(slot.getCount() + n);
                cur = cur.getCount() - n <= 0 ? ItemStack.EMPTY : cur.copyWithCount(cur.getCount() - n);
            } else { ItemStack t = slot; slot = cur; cur = t; }
        } else {
            if (cur.isEmpty()) {
                if (!slot.isEmpty()) {
                    int take = (slot.getCount() + 1) / 2;
                    cur = slot.copyWithCount(take);
                    slot = slot.getCount() - take <= 0 ? ItemStack.EMPTY : slot.copyWithCount(slot.getCount() - take);
                }
            } else if (slot.isEmpty() || (ItemStack.isSameItemSameComponents(cur, slot) && slot.getCount() < slot.getMaxStackSize())) {
                slot = slot.isEmpty() ? cur.copyWithCount(1) : slot.copyWithCount(slot.getCount() + 1);
                cur = cur.getCount() - 1 <= 0 ? ItemStack.EMPTY : cur.copyWithCount(cur.getCount() - 1);
            }
        }
        HokaInventory.EXTRAS[i] = slot;
        menu.setCarried(cur);
    }

    private static boolean hasShiftDown() {
        var w = net.minecraft.client.Minecraft.getInstance().getWindow();
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(w, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(w, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    @SuppressWarnings("unused")
    private static Slot none() { return null; }
}
