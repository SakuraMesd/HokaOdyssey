package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.block.CustomItems;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Свои предметы (метка hoka_item / hoka_block в custom_data) рисует мод — иконка или модель с сервера. */
@Mixin(ItemModelResolver.class)
public class ItemModelResolverMixin {

    @Inject(method = "appendItemLayers", at = @At("HEAD"), cancellable = true)
    private void hoka$item(ItemStackRenderState state, ItemStack stack, ItemDisplayContext ctx, Level level, ItemOwner owner, int seed, CallbackInfo ci) {
        if (CustomItems.update(state, stack, ctx)) ci.cancel();
    }
}
