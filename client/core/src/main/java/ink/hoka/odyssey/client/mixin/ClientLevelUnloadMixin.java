package ink.hoka.odyssey.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Кэш кубов (0.51): столбец уходит из мира клиента (Forget — ClientChunkCache.drop, или молча при вытеснении из
 * хранилища — Storage.replace): его кубы и последний свет — в CubeCache. HEAD — секции и свет ещё на месте.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelUnloadMixin {
    @Inject(method = "unload", at = @At("HEAD"))
    private void hoka$cache(LevelChunk chunk, CallbackInfo ci) {
        try { ink.hoka.odyssey.client.CubeCache.captureColumn((ClientLevel) (Object) this, chunk); }
        catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger("HokaOdyssey").warn("кэш кубов: столбец не унесён ({})", e.toString()); }
    }
}
