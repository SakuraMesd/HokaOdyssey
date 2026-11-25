package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.YardColumns;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Столбцы верфи кораблей (далеко от игрока) — из своей карты YardColumns, а не из кольца вокруг игрока. */
@Mixin(ClientChunkCache.class)
public abstract class YardChunkCacheMixin {

    @Shadow @Final ClientLevel level;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At("HEAD"), cancellable = true)
    private void hoka$yard(int x, int z, ChunkStatus status, boolean create, CallbackInfoReturnable<LevelChunk> cir) {
        if (!YardColumns.isYard(x)) return;
        LevelChunk c = YardColumns.get(level, x, z);
        if (c != null) cir.setReturnValue(c);
    }
}
