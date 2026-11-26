package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.ServerLight;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Свет от сервера: столбец забыт — его свет из ServerLight тоже (следом за ванильной очисткой света столбца). */
@Mixin(ClientPacketListener.class)
public abstract class ServerLightForgetMixin {

    @Shadow private ClientLevel level;

    /** 0.49 (E): в высоком мире своя задача забывания — только слои с состоянием (TallLight), дальше то же, что в TAIL. */
    @Inject(method = "queueLightRemoval", at = @At("HEAD"), cancellable = true)
    private void hoka$fastForget(ClientboundForgetLevelChunkPacket p, CallbackInfo ci) {
        if (!ink.hoka.odyssey.client.CubeQueue.REPLAY) ink.hoka.odyssey.client.CubeQueue.columnChanged(p.pos().x, p.pos().z);   // 0.57: отложенный — уже при постановке
        ink.hoka.odyssey.client.LocalLight.columnGone(level, p.pos().x, p.pos().z);
        ink.hoka.odyssey.client.ColumnHeal.forgot(p.pos().x, p.pos().z);   // 0.63.1: кубы следом — поздние, не потеря
        if (!ink.hoka.odyssey.client.TallLight.forget(level, p.pos())) return;
        hoka$after(p);
        ci.cancel();
    }

    @Inject(method = "queueLightRemoval", at = @At("TAIL"))
    private void hoka$forget(ClientboundForgetLevelChunkPacket p, CallbackInfo ci) {
        hoka$after(p);
    }

    @org.spongepowered.asm.mixin.Unique
    private void hoka$after(ClientboundForgetLevelChunkPacket p) {
        ServerLight.forget(level, p.pos());
        long k = p.pos().toLong();
        ink.hoka.odyssey.client.ContentRange.forgetColumn(level, net.minecraft.world.level.ChunkPos.getX(k), net.minecraft.world.level.ChunkPos.getZ(k));
    }
}
