package ink.hoka.odyssey.client.mixin;

import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 0.56, TallHeight: высота колонки карты высот (x, z — 0..15; h — верхний блок + 1). */
@Mixin(Heightmap.class)
public interface HeightmapAccessor {
    @Invoker("setHeight") void hoka$setHeight(int x, int z, int h);
}
