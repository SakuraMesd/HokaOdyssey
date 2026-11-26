package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import ink.hoka.odyssey.client.ChunkRate;
import net.minecraft.client.multiplayer.ChunkBatchSizeCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 0.63: просьба о числе столбцов за тик — от регулятора ChunkRate (на кубовых картах), а не от часов пачки. */
@Mixin(ChunkBatchSizeCalculator.class)
public abstract class ChunkRateMixin {

    @ModifyReturnValue(method = "getDesiredChunksPerTick", at = @At("RETURN"))
    private float hoka$rate(float vanilla) { return ChunkRate.desired(vanilla); }
}
