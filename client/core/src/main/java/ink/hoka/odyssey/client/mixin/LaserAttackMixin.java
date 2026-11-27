package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.model.Laser;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 0.71 С лазером в руке ЛКМ — луч (Laser), а не удар и не ванильное ломание блока. */
@Mixin(Minecraft.class)
public abstract class LaserAttackMixin {

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void hoka$laserStart(CallbackInfoReturnable<Boolean> cir) {
        if (Laser.holding()) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void hoka$laserHold(boolean held, CallbackInfo ci) {
        if (Laser.holding()) ci.cancel();
    }
}
