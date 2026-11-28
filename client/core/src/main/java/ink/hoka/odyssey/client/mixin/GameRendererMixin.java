package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

/** Модуль попросил не рисовать руку от первого лица (ClientContext.hideHand); поле зрения от модуля (CameraState.fov, 0.82). */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void hoka$hand(float partialTick, boolean sleeping, Matrix4f projection, CallbackInfo ci) {
        if (Hooks.hideHand()) ci.cancel();
    }

    /** Поле зрения мира (не руки): модуль задал своё в крючке камеры — оно. */
    @ModifyReturnValue(method = "getFov", at = @At("RETURN"))
    private float hoka$fov(float fov, net.minecraft.client.Camera camera, float partialTick, boolean useFovSetting) {
        float f = Hooks.fov;
        return f > 0 && useFovSetting ? f : fov;
    }
}
