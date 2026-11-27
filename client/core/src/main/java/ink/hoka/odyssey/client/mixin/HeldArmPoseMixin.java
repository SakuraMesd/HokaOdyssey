package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.model.HeldModels;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 0.69 Поза рук от третьего лица с предметом-моделью (настройка «Руки» в окне /hoitem edit). */
@Mixin(HumanoidModel.class)
public abstract class HeldArmPoseMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void hoka$pose(HumanoidRenderState s, CallbackInfo ci) {
        HeldModels.pose((HumanoidModel<?>) (Object) this, s);
    }
}
