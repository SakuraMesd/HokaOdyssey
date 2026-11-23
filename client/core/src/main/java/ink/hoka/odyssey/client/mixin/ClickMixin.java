package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Клики игрока — сначала модулям (ClientContext.onClick): забрал — игра клик не обрабатывает. */
@Mixin(Minecraft.class)
public abstract class ClickMixin {

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$use(CallbackInfo ci) {
        if (Hooks.click(false, false)) ci.cancel();
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$attack(CallbackInfoReturnable<Boolean> cir) {
        if (Hooks.click(true, false)) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$holdAttack(boolean down, CallbackInfo ci) {
        if (down && Hooks.click(true, true)) ci.cancel();
    }
}
