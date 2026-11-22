package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.AssetSync;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Вклеивает пак мода (ресурсы сервера) в клиентский список ресурсов — обязательный, сверху, его не выключить. */
@Mixin(PackRepository.class)
public abstract class PackRepositoryMixin {

    @Shadow @Final private Set<RepositorySource> sources;

    @Inject(method = "discoverAvailable", at = @At("RETURN"), cancellable = true)
    private void hokaOdyssey$addPack(CallbackInfoReturnable<Map<String, Pack>> cir) {
        if (!AssetSync.active()) return;
        boolean client = false;
        for (RepositorySource s : sources) {
            if (s instanceof ClientPackSource) { client = true; break; }
        }
        if (!client) return;
        Pack pack = AssetSync.createPack();
        if (pack == null) return;
        Map<String, Pack> map = new LinkedHashMap<>(cir.getReturnValue());
        map.put(AssetSync.PACK_ID, pack);
        cir.setReturnValue(map);
    }
}
