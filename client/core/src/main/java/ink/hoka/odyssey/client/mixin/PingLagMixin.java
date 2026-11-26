package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.LagReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 0.57: замер задержки клиента по Ping сервера — первый вызов handlePing в сетевом потоке (пришёл), второй — на
 * главном (очередь пакетов дошла до него). Разница — сколько пакет ждал (LagReport, wc op 7).
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class PingLagMixin {

    @Inject(method = "handlePing", at = @At("HEAD"))
    private void hoka$ping(ClientboundPingPacket p, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) LagReport.handled(p.getId());
        else LagReport.arrived(p.getId());
    }
}
