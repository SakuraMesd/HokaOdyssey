package ink.hoka.odyssey.client.mixin;

import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Свет от сервера (ServerLight): хранилище и источник чанков движка. */
@Mixin(LightEngine.class)
public interface LightEngineAccessor {
    @Accessor("storage") LayerLightSectionStorage<?> hoka$storage();
    @Accessor("chunkSource") LightChunkGetter hoka$src();
}
