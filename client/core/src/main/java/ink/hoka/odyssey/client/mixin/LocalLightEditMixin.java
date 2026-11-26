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

    /** 0.61: правка в сплошной куб без ванильного света (SolidCubes) — сначала вернуть ему бит «есть данные». */
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void hoka$solid(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        if (!LocalLight.active()) return;
        LevelChunk self = (LevelChunk) (Object) this;
        int idx = self.getSectionIndex(pos.getY());
        var secs = self.getSections();
        if (idx < 0 || idx >= secs.length) return;
        ink.hoka.odyssey.client.SolidCubes.beforeEdit(self.getLevel(), pos.getX(), pos.getY(), pos.getZ(), secs[idx], state);
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void hoka$edit(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        BlockState was = cir.getReturnValue();
        if (was == null || !LocalLight.active()) return;
        LevelChunk self = (LevelChunk) (Object) this;
        LocalLight.edit(self.getLevel(), pos, was, state);
    }
}
