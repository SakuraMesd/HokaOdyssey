package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ink.hoka.odyssey.client.ServerLight;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.SkyLightEngine;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Свет от сервера (§3.3): L1 — клиент небо не пересчитывает (правка блока небо не трогает, ждём op 11 сервера);
 * setLightEnabled не заливает «пустые» слои 15 (наш слой «всё 0» тоже пустой) и не трогает слои вообще (0.52). Верфь кораблей — как в ванили.
 * L4 не нужен: без checkNode циклы неба по столбцу не запускаются.
 */
@Mixin(SkyLightEngine.class)
public abstract class ServerLightEngineMixin {

    @Unique private boolean hoka$noFill;

    @Unique
    private boolean hoka$server() { return ServerLight.owns(((LightEngineAccessor) (Object) this).hoka$src()); }

    @Inject(method = "checkNode(J)V", at = @At("HEAD"), cancellable = true)
    private void hoka$noSky(long pos, CallbackInfo ci) {
        if (ServerLight.level == null || ServerLight.yard(SectionPos.blockToSectionCoord(BlockPos.getX(pos)))) return;
        if (hoka$server()) ci.cancel();
    }

    @Inject(method = "setLightEnabled(Lnet/minecraft/world/level/ChunkPos;Z)V", at = @At("HEAD"))
    private void hoka$enable(ChunkPos pos, boolean on, CallbackInfo ci) {
        hoka$noFill = ServerLight.level != null && !ServerLight.yard(pos.x) && hoka$server();
    }

    /**
     * 0.52: в режиме сервера слой не берём вовсе — заливки нет, а getDataLayerToWrite копировал каждый слой столбца
     * в changedSections (и swapSectionMap потом клонировал всю карту).
     */
    @WrapOperation(method = "setLightEnabled(Lnet/minecraft/world/level/ChunkPos;Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/SkyLightSectionStorage;getDataLayerToWrite(J)Lnet/minecraft/world/level/chunk/DataLayer;"))
    private DataLayer hoka$write(SkyLightSectionStorage st, long sec, Operation<DataLayer> op) {
        return hoka$noFill ? null : op.call(st, sec);
    }
}
