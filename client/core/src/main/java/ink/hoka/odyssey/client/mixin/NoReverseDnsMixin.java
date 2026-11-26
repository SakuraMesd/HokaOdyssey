package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.net.InetSocketAddress;

/**
 * Проверка адреса по чёрному списку Mojang (AddressCheck) берёт getHostName(): для адреса-IP это обратный DNS-запрос
 * (PTR). При медленном DNS (VPN-клиент с перехватом DNS) строка сервера в списке и вход висели до 15 с. Даём проверке
 * имя «как есть» (getHostString — без DNS): для домена это сам домен, для IP — IP; чёрный список проверяет оба.
 */
@Mixin(ServerNameResolver.class)
public abstract class NoReverseDnsMixin {

    @WrapOperation(method = "resolveAddress",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/resolver/AddressCheck;isAllowed(Lnet/minecraft/client/multiplayer/resolver/ResolvedServerAddress;)Z"),
            require = 0)
    private boolean hoka$noPtr(AddressCheck check, ResolvedServerAddress addr, Operation<Boolean> original) {
        return original.call(check, addr == null ? null : new ResolvedServerAddress() {
            @Override public String getHostName() { return addr.asInetSocketAddress().getHostString(); }
            @Override public String getHostIp() { return addr.getHostIp(); }
            @Override public int getPort() { return addr.getPort(); }
            @Override public InetSocketAddress asInetSocketAddress() { return addr.asInetSocketAddress(); }
        });
    }
}
