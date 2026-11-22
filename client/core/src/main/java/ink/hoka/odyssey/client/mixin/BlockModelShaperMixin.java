package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.block.CustomBlocks;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Свои блоки: у состояния-носителя модель — наша (чанки, частицы ломания). */
@Mixin(BlockModelShaper.class)
public class BlockModelShaperMixin {

    @Inject(method = "getBlockModel", at = @At("HEAD"), cancellable = true)
    private void hoka$model(BlockState state, CallbackInfoReturnable<BlockStateModel> cir) {
        BlockStateModel m = CustomBlocks.model(state);
        if (m != null) cir.setReturnValue(m);
    }

    @Inject(method = "getParticleIcon", at = @At("HEAD"), cancellable = true)
    private void hoka$particle(BlockState state, CallbackInfoReturnable<TextureAtlasSprite> cir) {
        BlockStateModel m = CustomBlocks.model(state);
        if (m != null) cir.setReturnValue(m.particleIcon());
    }
}
