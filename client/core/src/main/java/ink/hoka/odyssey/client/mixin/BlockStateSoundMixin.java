package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.block.CustomBlocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Звуки своего блока (шаги, ломание, установка, удар, падение) — из описания блока, а не от носителя. */
@Mixin(BlockBehaviour.BlockStateBase.class)
public class BlockStateSoundMixin {

    @Inject(method = "getSoundType", at = @At("HEAD"), cancellable = true)
    private void hoka$sound(CallbackInfoReturnable<SoundType> cir) {
        SoundType t = CustomBlocks.soundType((BlockState) (Object) this);
        if (t != null) cir.setReturnValue(t);
    }
}
