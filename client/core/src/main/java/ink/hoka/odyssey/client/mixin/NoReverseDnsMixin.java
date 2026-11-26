package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * Без обратного DNS (PTR) для адреса-IP. Игра зовёт getHostName() у разрешённого адреса: в проверке по чёрному
 * списку Mojang (AddressCheck) и при входе — имя для первого пакета (ConnectScreen, ServerStatusPinger). Для IP это
 * PTR-запрос; при медленном DNS (VPN-клиент с перехватом DNS) он шёл 15 с: список серверов «искал» сервер, а вход
 * обрывался ретранслятором по таймауту первого пакета. Решение: адрес сразу знает своё имя (как записано — домен или
 * сам IP), getHostName() отвечает без DNS.
 */
@Mixin(ServerNameResolver.class)
public abstract class NoReverseDnsMixin {

    @WrapOperation(method = "resolveAddress",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/resolver/AddressCheck;isAllowed(Lnet/minecraft/client/multiplayer/resolver/ResolvedServerAddress;)Z"),
            require = 0)
    private boolean hoka$noPtr(AddressCheck check, ResolvedServerAddress addr, Operation<Boolean> original) {
        return original.call(check, addr == null ? null : hoka$named(addr));
    }

    /** Итог разрешения — с именем внутри: дальше ConnectScreen/пинг списка берут getHostName() без DNS. */
    @ModifyReturnValue(method = "resolveAddress", at = @At("RETURN"), require = 0)
    private Optional<ResolvedServerAddress> hoka$nameResult(Optional<ResolvedServerAddress> r) {
        return r == null ? null : r.map(NoReverseDnsMixin::hoka$named);
    }

    @Unique
    private static ResolvedServerAddress hoka$named(ResolvedServerAddress addr) {
        InetSocketAddress isa = addr.asInetSocketAddress();
        InetSocketAddress named = isa;
        try {
            if (isa.getAddress() != null)                                      // имя = как записано (без DNS)
                named = new InetSocketAddress(InetAddress.getByAddress(isa.getHostString(), isa.getAddress().getAddress()), isa.getPort());
        } catch (Exception ignored) { }
        InetSocketAddress fin = named;
        return new ResolvedServerAddress() {
            @Override public String getHostName() { return fin.getHostString(); }
            @Override public String getHostIp() { return fin.getAddress() == null ? fin.getHostString() : fin.getAddress().getHostAddress(); }
            @Override public int getPort() { return fin.getPort(); }
            @Override public InetSocketAddress asInetSocketAddress() { return fin; }
        };
    }
}
