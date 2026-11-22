package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.block.CustomBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Свой блок на листве-носителе: без падающих листьев и капель. */
@Mixin(LeavesBlock.class)
public class LeavesBlockMixin {

    @Inject(method = "animateTick", at = @At("HEAD"), cancellable = true)
    private void hoka$noLeaves(BlockState state, Level level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (CustomBlocks.isCustom(state)) ci.cancel();
    }
}
