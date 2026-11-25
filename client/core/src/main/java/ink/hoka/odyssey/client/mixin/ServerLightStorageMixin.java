package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.ServerLight;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Свет от сервера (§3.3), небо: L2 — у куба нет слоя → значение из последнего op 11 (а не «подъём» к слою выше и
 * не 15 над верхним слоем); L3 — слой, который игра заводит сама, берём из op 11 вместо повтора нижнего ряда соседа.
 */
@Mixin(SkyLightSectionStorage.class)
public abstract class ServerLightStorageMixin {

    @Inject(method = "getLightValue(JZ)I", at = @At("HEAD"), cancellable = true)
    private void hoka$value(long pos, boolean updating, CallbackInfoReturnable<Integer> cir) {
        if (ServerLight.level == null) return;
        var st = (LightStorageAccessor) (Object) this;
        if (!ServerLight.owns(st.hoka$src())) return;
        long sec = SectionPos.blockToSection(pos);
        if ((updating ? st.hoka$updating() : st.hoka$visible()).getLayer(sec) != null) return;
        int v = ServerLight.sky(sec, BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos));
        if (v >= 0) cir.setReturnValue(v);
    }

    @Inject(method = "createDataLayer(J)Lnet/minecraft/world/level/chunk/DataLayer;", at = @At("HEAD"), cancellable = true)
    private void hoka$create(long sec, CallbackInfoReturnable<DataLayer> cir) {
        if (ServerLight.level == null) return;
        var st = (LightStorageAccessor) (Object) this;
        if (!ServerLight.owns(st.hoka$src()) || st.hoka$queued().get(sec) != null || ServerLight.yard(SectionPos.x(sec))) return;
        cir.setReturnValue(ServerLight.layer(sec, true));                // 0.52: всегда наш (см. hoka$removed)
    }

    /**
     * 0.52: удалили верхний слой столбца — ваниль ищет новый верх циклом вниз до самого нижнего Y мира (до ~1876
     * обращений на столбец, после телепорта — миллионы). В режиме сервера верх столбца не нужен: слой заводит
     * hoka$create (без подъёма к слою выше), значение без слоя — hoka$value / ограниченный цикл getLightValue.
     * Верх остаётся завышенным — это безопасно только пока hoka$create всегда отдаёт слой.
     */
    @Inject(method = "onNodeRemoved(J)V", at = @At("HEAD"), cancellable = true)
    private void hoka$removed(long sec, CallbackInfo ci) {
        if (ServerLight.level == null || ServerLight.yard(SectionPos.x(sec))) return;
        if (ServerLight.owns(((LightStorageAccessor) (Object) this).hoka$src())) ci.cancel();
    }
}
