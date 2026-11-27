package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ink.hoka.odyssey.client.inv.HokaInventoryScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 0.67.1: в окне инвентаря Hoka Odyssey — без ванильных иконок пустых слотов (шлем, щит…: свои — в текстуре окна) и
 * без ванильной рамки выделения (своя — скруглённая голубая, рисует HokaInventoryScreen).
 */
@Mixin(AbstractContainerScreen.class)
public abstract class HokaInventoryLookMixin {

    @WrapOperation(method = "renderSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/Slot;getNoItemIcon()Lnet/minecraft/resources/Identifier;"), require = 0)
    private Identifier hoka$noIcon(Slot slot, Operation<Identifier> op) {
        return (Object) this instanceof HokaInventoryScreen ? null : op.call(slot);
    }

    @Inject(method = "renderSlotHighlightBack", at = @At("HEAD"), cancellable = true, require = 0)
    private void hoka$noBack(GuiGraphics g, CallbackInfo ci) {
        if ((Object) this instanceof HokaInventoryScreen) ci.cancel();
    }

    @Inject(method = "renderSlotHighlightFront", at = @At("HEAD"), cancellable = true, require = 0)
    private void hoka$noFront(GuiGraphics g, CallbackInfo ci) {
        if ((Object) this instanceof HokaInventoryScreen) ci.cancel();
    }
}
