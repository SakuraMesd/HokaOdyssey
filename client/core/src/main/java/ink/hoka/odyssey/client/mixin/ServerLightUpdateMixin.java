package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.ServerLight;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 0.52, свет от сервера: пометки перестройки от слоёв неба движка не нужны. Слой неба в этом режиме меняет только
 * ServerLight.apply (помечает сам, по отличиям), сетку нового куба помечает CubeReceiver.put; а заведение слоя неба
 * (куб пришёл — у соседей завелись слои) помечало до 5×5×5 секций в обход SectionDirty. Свет блоков не трогаем —
 * там предсказание клиента. Верфь кораблей — как в ванили.
 */
@Mixin(ClientChunkCache.class)
public abstract class ServerLightUpdateMixin {

    @Inject(method = "onLightUpdate", at = @At("HEAD"), cancellable = true)
    private void hoka$skyUpdate(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        if (layer != LightLayer.SKY || ServerLight.level == null || ServerLight.yard(pos.x())) return;
        if (ServerLight.owns((ClientChunkCache) (Object) this)) ci.cancel();
    }
}
