package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ink.hoka.odyssey.client.ServerLight;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 0.61, высокий мир со светом на клиенте (LocalLight): движок света клиента работает только на главном
 * потоке, слои в карте не меняются на месте (копия при записи через changedSections), поэтому «видимая» карта слоёв
 * может быть той же, что «обновляемая». Ваниль в swapSectionMap на каждое изменение копировала ВСЮ карту (сотни
 * тысяч секций в толще камня — десятки мс на кадр, O(N²) за прогрузку). Верфь и обычные миры — как в ванили.
 * <p>Свет на клиенте: у куба без слоя (сплошной камень — SolidCubes, воздух без непустых соседей) Sodium брал
 * «небо 15» — светлые пещеры. Здесь отдаём слой из записи LocalLight (или F по верхам).</p>
 */
@Mixin(LayerLightSectionStorage.class)
public abstract class LightSwapMixin {

    @WrapOperation(method = "swapSectionMap", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/DataLayerStorageMap;copy()Lnet/minecraft/world/level/lighting/DataLayerStorageMap;"))
    private DataLayerStorageMap<?> hoka$noCopy(DataLayerStorageMap<?> map, Operation<DataLayerStorageMap<?>> op) {
        var st = (LightStorageAccessor) (Object) this;
        // только свет на клиенте: там ванильные движки не растекают (checkNode выкл), слои лишь заменяются целиком
        if (ServerLight.local() && ServerLight.owns(st.hoka$src()) && map == st.hoka$updating()) return map;
        return op.call(map);
    }

    @ModifyReturnValue(method = "getDataLayerData(J)Lnet/minecraft/world/level/chunk/DataLayer;", at = @At("RETURN"))
    private DataLayer hoka$fallback(DataLayer d, long sec) {
        if (d != null || !ServerLight.local()) return d;
        var st = (LightStorageAccessor) (Object) this;
        if (!ServerLight.owns(st.hoka$src()) || ServerLight.yard(SectionPos.x(sec))) return d;
        return ServerLight.layer(sec, (Object) this instanceof SkyLightSectionStorage);
    }
}
