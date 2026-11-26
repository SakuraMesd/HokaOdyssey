package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.block.CustomBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Свой блок на листве-носителе: без падающих листьев и капель; обычная листва не встаёт в носитель. */
@Mixin(LeavesBlock.class)
public class LeavesBlockMixin {

    @Inject(method = "animateTick", at = @At("HEAD"), cancellable = true)
    private void hoka$noLeaves(BlockState state, Level level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (CustomBlocks.isCustom(state)) ci.cancel();
    }

    /**
     * Ваниль ставит листву persistent=true — а это носители своих блоков. Наш предмет сюда не доходит (BlockItemMixin
     * отвечает раньше), так что здесь только обычная листва (рука, Axiom и т.п.) → persistent=false, как на сервере.
     */
    @ModifyReturnValue(method = "getStateForPlacement", at = @At("RETURN"))
    private BlockState hoka$notCarrier(BlockState st, BlockPlaceContext ctx) {
        return ctx.getLevel().isClientSide() ? CustomBlocks.foreign(st) : st;
    }
}
