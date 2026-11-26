package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.LocalLight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 0.57, свет на клиенте: блок сменился (своя правка, BlockUpdate сервера) — событие движку света, если от этого
 * свет может измениться (lightBlock / грани / свечение). Тень — в этом же кадре (LocalLight.frame).
 */
@Mixin(LevelChunk.class)
public abstract class LocalLightEditMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void hoka$edit(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        BlockState was = cir.getReturnValue();
        if (was == null || !LocalLight.active()) return;
        LevelChunk self = (LevelChunk) (Object) this;
        LocalLight.edit(self.getLevel(), pos, was, state);
    }
}
