package ink.hoka.odyssey.client.mixin;

import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Свет от сервера (ServerLight): движки неба и блоков мира. */
@Mixin(LevelLightEngine.class)
public interface LevelLightEngineAccessor {
    @Accessor("skyEngine") LightEngine<?, ?> hoka$sky();
    @Accessor("blockEngine") LightEngine<?, ?> hoka$block();
}
