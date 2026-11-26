package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.HokaPreparsed;
import ink.hoka.odyssey.client.SparseSections;
import ink.hoka.odyssey.client.TallWorld;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 0.57 (задача 1а): секции скелета столбца высокого мира (1876 штук) разбираются в сетевом потоке при декодировании
 * пакета — SparseSections.read потокобезопасен. Главный поток (SparseLevelChunkMixin) берёт готовое. Не вышло
 * (мир не тот, сбой) — разберёт как раньше.
 */
@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ChunkPacketPreparseMixin implements HokaPreparsed {

    @Shadow @Final private byte[] buffer;

    @Unique private volatile LevelChunkSection[] hoka$secs;
    @Unique private volatile PalettedContainerFactory hoka$f;

    @Inject(method = "<init>(Lnet/minecraft/network/RegistryFriendlyByteBuf;II)V", at = @At("TAIL"))
    private void hoka$preparse(RegistryFriendlyByteBuf in, int x, int z, CallbackInfo ci) {
        if (!TallWorld.net) return;
        ClientLevel l = Minecraft.getInstance().level;
        if (l == null) return;
        int n = l.getSectionsCount();
        if (n <= 64) return;
        try {
            PalettedContainerFactory f = l.palettedContainerFactory();
            FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.wrappedBuffer(buffer));
            LevelChunkSection[] s = new LevelChunkSection[n];
            for (int i = 0; i < n; i++) s[i] = SparseSections.read(f, b);
            hoka$f = f;
            hoka$secs = s;
            SparseSections.PREPARSED.incrementAndGet();
        } catch (Throwable t) {
            hoka$secs = null;
        }
    }

    @Override
    public LevelChunkSection[] hoka$take(PalettedContainerFactory f, int count) {
        LevelChunkSection[] s = hoka$secs;
        hoka$secs = null;
        return s != null && hoka$f == f && s.length == count ? s : null;
    }
}
