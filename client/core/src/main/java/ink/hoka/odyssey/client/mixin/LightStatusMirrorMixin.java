package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.TallLight;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 0.49 (E): зеркало бита «есть данные» движков света клиента по столбцам (TallLight). Поведение не меняет. */
@Mixin(LevelLightEngine.class)
public abstract class LightStatusMirrorMixin {
    @Inject(method = "updateSectionStatus", at = @At("HEAD"))
    private void hoka$mirror(SectionPos pos, boolean empty, CallbackInfo ci) {
        TallLight.status((LevelLightEngine) (Object) this, pos, empty);
    }
}
