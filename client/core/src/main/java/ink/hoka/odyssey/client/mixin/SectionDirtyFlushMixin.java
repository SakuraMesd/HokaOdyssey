package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.SectionDirty;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Пометки кубов и их света за кадр (SectionDirty) — в отрисовку одним махом: сразу после света мира (там применяется
 * свет кубов от сервера), до отбора секций. Необязательный (require 0): не нашёлся — сбросит начало кадра.
 */
@Mixin(LevelRenderer.class)
public abstract class SectionDirtyFlushMixin {
    @Inject(method = "renderLevel", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I", shift = At.Shift.AFTER))
    private void hoka$flushDirty(CallbackInfo ci) {
        SectionDirty.flush();
    }
}
