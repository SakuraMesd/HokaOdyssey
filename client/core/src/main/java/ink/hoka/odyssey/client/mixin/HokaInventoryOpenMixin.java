package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.inv.HokaInventory;
import ink.hoka.odyssey.client.inv.HokaInventoryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 0.67: на сервере с модулем survival вместо ванильного окна инвентаря — окно Hoka Odyssey (творческий — как был). */
@Mixin(Minecraft.class)
public abstract class HokaInventoryOpenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void hoka$inventory(Screen screen, CallbackInfo ci) {
        if (!(screen instanceof InventoryScreen) || !HokaInventory.enabled) return;
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.player == null || mc.player.isCreative()) return;
        ci.cancel();
        mc.setScreen(new HokaInventoryScreen(mc.player));
    }
}
