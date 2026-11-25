package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Покачивание руки от первого лица = (взгляд − отстающий «след» xBob/yBob) × 0.1. Если модуль держит взгляд в своей
 * системе (корабль: курс/наклон относительно палубы, след — там же), берём взгляд оттуда: на перевёрнутом корабле
 * мировой курс при повороте мыши едет в обратную сторону, и рука тряслась.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class HandSwayMixin {

    @WrapOperation(method = "renderHandsWithItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getViewXRot(F)F"))
    private float hoka$viewX(LocalPlayer p, float pt, Operation<Float> op) {
        float[] v = Hooks.localView(pt);
        return v != null ? v[1] : op.call(p, pt);
    }

    @WrapOperation(method = "renderHandsWithItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getViewYRot(F)F"))
    private float hoka$viewY(LocalPlayer p, float pt, Operation<Float> op) {
        float[] v = Hooks.localView(pt);
        return v != null ? v[0] : op.call(p, pt);
    }
}
