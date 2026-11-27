package ink.hoka.odyssey.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import ink.hoka.odyssey.client.model.HeldModels;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 0.69 Предмет-модель в руке от третьего лица (и у других игроков): модель с анимацией у кисти. */
@Mixin(ItemInHandLayer.class)
public abstract class HeldThirdPersonMixin {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void hoka$held(ArmedEntityRenderState s, ItemStackRenderState st, ItemStack stack, HumanoidArm arm, PoseStack ps,
                           SubmitNodeCollector snc, int light, CallbackInfo ci) {
        HeldModels.Def d = HeldModels.find(s.getMainHandItemStack());
        if (d == null) return;
        if (arm != s.mainArm) {
            if ("both".equals(d.arms)) ci.cancel();
            return;
        }
        ps.pushPose();
        ((ArmedModel) ((ItemInHandLayer) (Object) this).getParentModel()).translateToHand(s, arm, ps);
        boolean left = arm == HumanoidArm.LEFT;      // поворот и сдвиг к кисти — в HeldModels.thirdPerson (учёт растяжения руки)
        HeldModels.thirdPerson(d, left, ps, snc, light,
                s instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState a ? a.id : -1);
        ps.popPose();
        ci.cancel();
    }
}
