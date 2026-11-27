package ink.hoka.odyssey.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import ink.hoka.odyssey.client.model.HeldModels;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 0.69 Предмет-модель в руке от первого лица: модель с анимацией и свои руки вместо ванильной руки с предметом. */
@Mixin(ItemInHandRenderer.class)
public abstract class HeldFirstPersonMixin {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void hoka$held(AbstractClientPlayer player, float pt, float pitch, InteractionHand hand, float swing, ItemStack stack,
                           float equip, PoseStack ps, SubmitNodeCollector snc, int light, CallbackInfo ci) {
        HeldModels.Def d = HeldModels.find(player.getMainHandItem());
        if (d == null || player.isScoping()) return;
        if (hand == InteractionHand.MAIN_HAND) {
            HeldModels.firstPerson(player, d, equip, ps, snc, light);
            ci.cancel();
        } else if ("both".equals(d.arms)) ci.cancel();          // вторая рука занята моделью
    }
}
