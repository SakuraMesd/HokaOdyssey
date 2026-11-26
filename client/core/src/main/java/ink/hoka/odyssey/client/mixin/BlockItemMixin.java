package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.block.CustomBlocks;
import ink.hoka.odyssey.client.block.CustomItems;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Предмет своего блока ставит у клиента сразу наш блок (как решит сервер) — без мелькания носителя. */
@Mixin(BlockItem.class)
public class BlockItemMixin {

    @Inject(method = "getPlacementState", at = @At("HEAD"), cancellable = true)
    private void hoka$place(BlockPlaceContext ctx, CallbackInfoReturnable<BlockState> cir) {
        if (ctx.getPlayer() == null || !ctx.getLevel().isClientSide()) return;
        String id = CustomItems.blockId(ctx.getItemInHand());
        if (id == null) return;
        BlockState st = CustomBlocks.placeState(id, ctx.getPlayer().getYRot());
        if (st != null) cir.setReturnValue(st);
    }

    /** Обычный предмет (листва, нотный блок, нить) не предсказывается в носитель своего блока — без мелькания «Ступки». */
    @ModifyReturnValue(method = "getPlacementState", at = @At("RETURN"))
    private BlockState hoka$notCarrier(BlockState st, BlockPlaceContext ctx) {
        if (st == null || !ctx.getLevel().isClientSide() || CustomItems.blockId(ctx.getItemInHand()) != null) return st;
        return CustomBlocks.foreign(st);
    }
}
