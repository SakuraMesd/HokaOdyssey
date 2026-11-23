package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Перед движением своего игрока — модулям (ClientContext.onLocalTravel): сила работает в этом же тике. */
@Mixin(LivingEntity.class)
public abstract class LocalTravelMixin {
    @Inject(method = "travel", at = @At("HEAD"))
    private void hokaOdyssey$travel(Vec3 input, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer p) Hooks.travel(p);
    }
}
