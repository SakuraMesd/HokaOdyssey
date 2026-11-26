package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.ServerLight;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.lighting.BlockLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 0.57, свет на клиенте (LocalLight): свет блоков считает наш движок — ванильный движок блоков не пересчитывает
 * (иначе его заливка спорила бы с нашей). Верфь кораблей — как в ванили.
 */
@Mixin(BlockLightEngine.class)
public abstract class BlockLightOffMixin {

    @Inject(method = "checkNode(J)V", at = @At("HEAD"), cancellable = true)
    private void hoka$noBlock(long pos, CallbackInfo ci) {
        if (!ServerLight.local() || ServerLight.yard(SectionPos.blockToSectionCoord(BlockPos.getX(pos)))) return;
        if (ServerLight.owns(((LightEngineAccessor) (Object) this).hoka$src())) ci.cancel();
    }
}
