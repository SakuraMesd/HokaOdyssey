package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Части модели гуманоида от модулей (ClientContext.onModelParts): в самом конце {@code HumanoidModel.setupAnim} —
 * после всех поз игры (PlayerModel.setupAnim зовёт его последним). Тем же путём расставляются модели брони, так что
 * броня идёт за частями тела. Без крючков — сразу как у игры.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidPartsMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void hokaOdyssey$parts(HumanoidRenderState s, CallbackInfo ci) {
        if (!Hooks.hasParts()) return;
        Hooks.parts((HumanoidModel<?>) (Object) this, s);
    }
}
