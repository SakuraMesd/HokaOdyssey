package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Игрок со своей рамкой от модуля (ClientContext.onEntityBox): позу по «влезает ли рамка стоя / присев» игра не
 * выбирает — она примеряет ровную рамку 0.6×1.8 от ног вверх, а у игрока, стоящего на стене, рамка лежит вдоль
 * нормали стены: игра «посадила» бы его или «положила плыть». Поза остаётся какой была.
 */
@Mixin(Player.class)
public abstract class PlayerPoseMixin {
    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$keepPose(CallbackInfo ci) {
        if (!Hooks.hasBox()) return;
        Player self = (Player) (Object) this;
        if (self.level() == null || !self.level().isClientSide()) return;
        if (Hooks.box(self, self.position()) != null) ci.cancel();
    }
}
