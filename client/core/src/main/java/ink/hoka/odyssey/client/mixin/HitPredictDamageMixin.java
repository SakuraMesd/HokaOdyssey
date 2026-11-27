package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.HitPredict;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 0.66: событие урона от сервера, уже показанное предсказанием, — не показывать второй раз (HitPredict). */
@Mixin(ClientPacketListener.class)
public abstract class HitPredictDamageMixin {
    @Inject(method = "handleDamageEvent", at = @At("HEAD"), cancellable = true)
    private void hoka$dedupe(ClientboundDamageEventPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;                // поток сети — только переадресация в главный
        if (HitPredict.suppress(packet.entityId())) ci.cancel();
    }
}
