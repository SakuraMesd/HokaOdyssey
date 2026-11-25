package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ink.hoka.odyssey.client.ServerLight;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Свет от сервера (§3.3), L5: источники неба столбца не обновляем — их читает только SkyLightEngine, а он в этом
 * режиме выключен (L1). В высоком мире снятие верхнего блока иначе спускалось бы по блокам до низа мира.
 */
@Mixin(LevelChunk.class)
public abstract class ServerLightChunkMixin {

    @WrapOperation(method = "setBlockState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/ChunkSkyLightSources;update(Lnet/minecraft/world/level/BlockGetter;III)Z"))
    private boolean hoka$sources(ChunkSkyLightSources src, BlockGetter g, int x, int y, int z, Operation<Boolean> op) {
        LevelChunk self = (LevelChunk) (Object) this;
        var l = ServerLight.level;
        if (l != null && self.getLevel() == l && !ServerLight.yard(self.getPos().x)) return false;
        return op.call(src, g, x, y, z);
    }
}
