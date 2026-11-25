package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.ServerLight;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Свет от сервера (§3.3), L3 для света блоков: слой, который игра заводит сама, — из последнего op 11. */
@Mixin(LayerLightSectionStorage.class)
public abstract class ServerLightBlockStorageMixin {

    @Inject(method = "createDataLayer(J)Lnet/minecraft/world/level/chunk/DataLayer;", at = @At("HEAD"), cancellable = true)
    private void hoka$create(long sec, CallbackInfoReturnable<DataLayer> cir) {
        if (ServerLight.level == null || (Object) this instanceof SkyLightSectionStorage) return;
        var st = (LightStorageAccessor) (Object) this;
        if (!ServerLight.owns(st.hoka$src()) || st.hoka$queued().get(sec) != null) return;
        DataLayer d = ServerLight.layer(sec, false);
        if (d != null) cir.setReturnValue(d);
    }
}
