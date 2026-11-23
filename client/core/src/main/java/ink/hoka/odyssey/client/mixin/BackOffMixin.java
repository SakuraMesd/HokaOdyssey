package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Присед у края: игра проверяет «есть ли под ногами блок» только по блокам мира — на корабле (теле модуля) под
 * ногами для неё пустота, и она гасит весь ход на корточках. Рядом с телами модулей эту проверку не делаем.
 */
@Mixin(Player.class)
public abstract class BackOffMixin {
    @Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$backOff(Vec3 movement, MoverType type, CallbackInfoReturnable<Vec3> cir) {
        Player self = (Player) (Object) this;
        if (self.level().isClientSide() && Hooks.nearBodies(self)) cir.setReturnValue(movement);
    }
}
