package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.HitPredict;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 0.66: свой удар по существу — вспышка и звук сразу, не через пинг (HitPredict). */
@Mixin(MultiPlayerGameMode.class)
public abstract class HitPredictAttackMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void hoka$predict(Player player, Entity target, CallbackInfo ci) {
        if (player instanceof net.minecraft.client.player.LocalPlayer lp) HitPredict.attack(lp, target);
    }
}
