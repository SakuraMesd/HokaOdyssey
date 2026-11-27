package ink.hoka.odyssey.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Рамка выделения от модулей (ClientContext.onOutline) — там же, где игра рисует свою: тот же проход и вид линий
 * (иначе линии выходят светлыми и полупрозрачными). Модуль нарисовал — рамку блока мира в этом кадре не рисуем.
 */
@Mixin(LevelRenderer.class)
public abstract class OutlineMixin {

    @Inject(method = "renderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$outline(MultiBufferSource.BufferSource buffers, PoseStack pose, boolean translucent, LevelRenderState state, CallbackInfo ci) {
        if (translucent) {                                                      // второй вызов кадра (прозрачная рамка)
            if (Hooks.outlineDrawn) ci.cancel();
            return;
        }
        Hooks.outlineDrawn = false;
        if (state.cameraRenderState == null || state.cameraRenderState.pos == null) return;
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        if (ink.hoka.odyssey.client.model.Laser.outline(pose, buffers, state.cameraRenderState.pos)
                || Hooks.outline(pose, buffers, state.cameraRenderState.pos, pt)) {
            buffers.endBatch(RenderTypes.lines());
            Hooks.outlineDrawn = true;
            ci.cancel();
        }
    }
}
