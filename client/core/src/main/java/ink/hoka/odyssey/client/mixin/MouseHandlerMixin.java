package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Движение мыши — сначала модулям (в градусах, с чувствительностью игрока, как считает сама игра).
 * Модуль забрал движение — игрок не поворачивается (свой прицел без задержки сервера).
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$turn(double movementTime, CallbackInfo ci) {
        if (!Hooks.hasMouse() || minecraft.player == null || !minecraft.isWindowActive()) return;
        double s = minecraft.options.sensitivity().get() * 0.6 + 0.2;
        double f = s * s * s * 8.0 * 0.15;                                      // как в игре: градусы поворота
        double dx = accumulatedDX * f * (minecraft.options.invertMouseX().get() ? -1 : 1);
        double dy = accumulatedDY * f * (minecraft.options.invertMouseY().get() ? -1 : 1);
        if (Hooks.mouse(dx, dy)) {
            accumulatedDX = 0;
            accumulatedDY = 0;
            ci.cancel();
        }
    }
}
