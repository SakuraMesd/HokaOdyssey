package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ink.hoka.odyssey.client.Smooth;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 0.90 «Плавность» (Smooth): ровная выдача кадров перед показом; ограничитель игры — когда она не действует. */
@Mixin(Minecraft.class)
public abstract class SmoothMixin {

    @Inject(method = "runTick", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay(Lcom/mojang/blaze3d/TracyFrameCapture;)V"))
    private void hokaOdyssey$pace(boolean advanceGameTime, CallbackInfo ci) {
        Smooth.beforePresent((Minecraft) (Object) this);
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;limitDisplayFPS(I)V"))
    private void hokaOdyssey$limit(int fps, Operation<Void> original) {
        if (Smooth.target() > 0) return;                                  // кадры уже выданы по сетке «Плавности»
        original.call(fps);
    }
}
