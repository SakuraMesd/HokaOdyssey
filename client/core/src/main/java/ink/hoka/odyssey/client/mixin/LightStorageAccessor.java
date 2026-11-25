package ink.hoka.odyssey.client.mixin;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Свет от сервера (ServerLight): внутренности хранилища слоёв (L2, L3, L6, L7). */
@Mixin(LayerLightSectionStorage.class)
public interface LightStorageAccessor {
    @Accessor("chunkSource") LightChunkGetter hoka$src();
    @Accessor("sectionStates") Long2ByteMap hoka$states();
    @Accessor("queuedSections") Long2ObjectMap<DataLayer> hoka$queued();
    @Accessor("updatingSectionData") DataLayerStorageMap<?> hoka$updating();
    @Accessor("visibleSectionData") DataLayerStorageMap<?> hoka$visible();
    @Accessor("changedSections") LongSet hoka$changed();
}
