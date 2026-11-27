package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ink.hoka.odyssey.client.Hooks;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.inv.HokaInventoryScreen;
import ink.hoka.odyssey.client.inv.SkinnedScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 0.80 Оформление окна-контейнера модулем (ClientContext.onContainer): при раскладке окна — свой размер и места
 * слотов (на закрытии — вернуть), фон и надписи — модуля. Модуль спрашивается один раз, при первой раскладке окна.
 * Слоты те же — клики, Shift, перетаскивание и обмен с сервером не меняются.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerSkinMixin implements SkinnedScreen {

    @Shadow protected int imageWidth;
    @Shadow protected int imageHeight;
    @Shadow @Final protected AbstractContainerMenu menu;
    @Shadow protected Slot hoveredSlot;

    @Unique private Hooks.Owned<ClientContext.ContainerSkin> hoka$skin;
    @Unique private boolean hoka$asked;
    @Unique private int[][] hoka$orig;

    @Override public String hoka$skinOwner() { return hoka$skin != null ? hoka$skin.owner() : null; }

    @Override public boolean hoka$ownHighlight() { return hoka$skin != null && !hoka$skin.hook().vanillaHighlight(); }

    @Inject(method = "init", at = @At("HEAD"))
    private void hoka$skinSize(CallbackInfo ci) {
        Object self = this;
        if (self instanceof HokaInventoryScreen) return;
        if (!hoka$asked) {
            hoka$asked = true;
            hoka$skin = Hooks.containerSkin((AbstractContainerScreen<?>) self, menu.containerId);
        }
        var s = hoka$skin;
        if (s == null) return;
        Screen scr = (Screen) self;
        int[][] size = {null};
        if (!Hooks.skinSafe(s.owner(), () -> size[0] = s.hook().size(scr.width, scr.height)) || size[0] == null || size[0].length < 2) {
            hoka$skin = null;
            return;
        }
        imageWidth = Math.max(1, size[0][0]);
        imageHeight = Math.max(1, size[0][1]);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void hoka$skinSlots(CallbackInfo ci) {
        var s = hoka$skin;
        if (s == null) return;
        var slots = menu.slots;
        if (hoka$orig == null) {
            hoka$orig = new int[slots.size()][];
            for (int i = 0; i < slots.size(); i++) hoka$orig[i] = new int[]{slots.get(i).x, slots.get(i).y};
        }
        for (int i = 0; i < slots.size(); i++) {
            int idx = i;
            int[][] at = {null};
            if (!Hooks.skinSafe(s.owner(), () -> at[0] = s.hook().slot(idx))) { hoka$restore(); hoka$skin = null; return; }
            int[] p = at[0] != null && at[0].length >= 2 ? at[0] : i < hoka$orig.length ? hoka$orig[i] : null;
            if (p == null) continue;
            SlotAccessor a = (SlotAccessor) slots.get(i);
            a.hoka$setX(p[0]);
            a.hoka$setY(p[1]);
        }
    }

    @Unique
    private void hoka$restore() {
        if (hoka$orig == null) return;
        var slots = menu.slots;
        for (int i = 0; i < slots.size() && i < hoka$orig.length; i++) {
            SlotAccessor a = (SlotAccessor) slots.get(i);
            a.hoka$setX(hoka$orig[i][0]);
            a.hoka$setY(hoka$orig[i][1]);
        }
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void hoka$skinRemoved(CallbackInfo ci) {
        var s = hoka$skin;
        if (s == null) return;
        hoka$restore();
        Hooks.skinSafe(s.owner(), () -> s.hook().removed());
    }

    @WrapOperation(method = "renderBackground", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderBg(Lnet/minecraft/client/gui/GuiGraphics;FII)V"))
    private void hoka$skinBg(AbstractContainerScreen<?> self, GuiGraphics g, float pt, int mx, int my, Operation<Void> op) {
        var s = hoka$skin;
        if (s == null) { op.call(self, g, pt, mx, my); return; }
        AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) self;
        Hooks.skinSafe(s.owner(), () -> s.hook().background(g, a.hoka$leftPos(), a.hoka$topPos(), mx, my, pt));
    }

    @Inject(method = "renderLabels", at = @At("HEAD"), cancellable = true)
    private void hoka$skinLabels(GuiGraphics g, int mx, int my, CallbackInfo ci) {
        var s = hoka$skin;
        if (s == null) return;
        ci.cancel();
        int hovered = hoveredSlot != null && hoveredSlot.isActive() ? menu.slots.indexOf(hoveredSlot) : -1;
        Hooks.skinSafe(s.owner(), () -> s.hook().labels(g, mx, my, hovered));
    }
}
