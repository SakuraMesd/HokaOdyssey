package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.block.CustomBlocks;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Свои блоки: слой чанка (вырезка/полупрозрачность) — из описания блока, а не от носителя. */
@Mixin(ItemBlockRenderTypes.class)
public class ItemBlockRenderTypesMixin {

    @Inject(method = "getChunkRenderType", at = @At("HEAD"), cancellable = true)
    private static void hoka$layer(BlockState state, CallbackInfoReturnable<ChunkSectionLayer> cir) {
        ChunkSectionLayer l = CustomBlocks.layer(state);
        if (l != null) cir.setReturnValue(l);
    }
}
