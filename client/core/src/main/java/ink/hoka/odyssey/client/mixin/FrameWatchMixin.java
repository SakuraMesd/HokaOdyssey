package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.FrameWatch;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Начало каждого кадра — сторожу кадров (FrameWatch). */
@Mixin(Minecraft.class)
public abstract class FrameWatchMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void hokaOdyssey$frame(boolean advanceGameTime, CallbackInfo ci) {
        FrameWatch.frameStart();
        ink.hoka.odyssey.client.CubeQueue.run();                       // 0.49: кубы по бюджету кадра (до сброса пометок)
        ink.hoka.odyssey.client.LocalLight.frame();                    // 0.57: свет движка клиента (основное — перед runLightUpdates)
        ink.hoka.odyssey.client.LagReport.frame();                     // 0.57: задержка клиента → серверу (op 7)
        ink.hoka.odyssey.client.CubeCache.frame();                     // 0.51: кэш кубов — смена мира, WANT, EVICT
        ink.hoka.odyssey.client.SectionDirty.flush();                  // запасной сброс пометок (без Sodium / до отрисовки)
    }
}
