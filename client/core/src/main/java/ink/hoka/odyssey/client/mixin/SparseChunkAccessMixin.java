package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.SparseSections;
import ink.hoka.odyssey.client.TallWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/**
 * Высокий мир (C1, CUBIC-CHUNKS.md §3.1): новый столбец на клиенте — не 1876 новых секций, а общая заглушка
 * (секции тут же заменит пакет столбца, LevelChunkMixin). Только главный поток клиента и только на нашем сервере —
 * встроенный сервер одиночной игры не трогаем.
 */
@Mixin(ChunkAccess.class)
public abstract class SparseChunkAccessMixin {

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;replaceMissingSections(Lnet/minecraft/world/level/chunk/PalettedContainerFactory;[Lnet/minecraft/world/level/chunk/LevelChunkSection;)V"))
    private void hoka$fill(PalettedContainerFactory f, LevelChunkSection[] secs, Operation<Void> op) {
        Minecraft mc = TallWorld.net ? Minecraft.getInstance() : null;
        if (mc == null || !mc.isSameThread()) { op.call(f, secs); return; }
        for (int i = 0; i < secs.length; i++)
            if (secs[i] == null) secs[i] = SparseSections.fallback(f);
    }
}
