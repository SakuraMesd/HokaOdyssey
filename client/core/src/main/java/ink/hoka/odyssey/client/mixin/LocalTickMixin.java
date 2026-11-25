package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Весь тик своего игрока — модулям (ClientContext.onLocalTick): в начале Player.tick и в самом его конце (до
 * LocalPlayer.sendPosition, который идёт уже после super.tick()). Так корабль переводит игрока в систему верфи
 * на ВЕСЬ тик — ходьба, анимация ног, поворот тела, частицы бега считаются игрой по настоящим блокам.
 */
@Mixin(Player.class)
public abstract class LocalTickMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void hokaOdyssey$tickEnter(CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer p) Hooks.tickEnter(p);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void hokaOdyssey$tickExit(CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer p) Hooks.tickExit(p);
    }
}
