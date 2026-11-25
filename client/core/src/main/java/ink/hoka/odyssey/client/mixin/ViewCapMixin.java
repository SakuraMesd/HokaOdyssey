package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import ink.hoka.odyssey.client.TallWorld;
import net.minecraft.client.Options;
import net.minecraft.server.level.ClientInformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 0.48: на нашем сервере (TallWorld.net) сообщаем дальность не больше TallWorld.VIEW_CAP — см. там, зачем. Настройка
 * игрока не меняется, меняется только число в пакете ClientInformation (вход, смена настроек).
 */
@Mixin(Options.class)
public abstract class ViewCapMixin {
    @ModifyReturnValue(method = "buildPlayerInformation", at = @At("RETURN"))
    private ClientInformation hoka$viewCap(ClientInformation i) {
        int cap = TallWorld.VIEW_CAP;
        if (!TallWorld.net || cap <= 0 || i.viewDistance() <= cap) return i;
        return new ClientInformation(i.language(), cap, i.chatVisibility(), i.chatColors(), i.modelCustomisation(),
                i.mainHand(), i.textFilteringEnabled(), i.allowsListing(), i.particleStatus());
    }
}
