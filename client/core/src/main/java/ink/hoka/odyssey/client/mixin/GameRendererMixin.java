package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Модуль попросил не рисовать руку от первого лица (ClientContext.hideHand). */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void hoka$hand(float partialTick, boolean sleeping, Matrix4f projection, CallbackInfo ci) {
        if (Hooks.hideHand()) ci.cancel();
    }
}
