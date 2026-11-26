package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ink.hoka.odyssey.client.TallWorld;
import net.minecraft.client.multiplayer.ClientChunkCache;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 0.49: «Ignoring chunk since it's not in the view range» на нашем сервере — устаревшие столбцы из очереди отправки
 * Minestom (тысячи за прогрузку, до ~50 строк в секунду с главного потока). Вместо строки на столбец — счётчик и одна
 * сводка раз в 10 с (с последним столбцом — для отладки полос).
 */
@Mixin(ClientChunkCache.class)
public abstract class IgnoredChunkLogMixin {

    @Unique private static long hoka$ignored, hoka$lastLog;
    @Unique private static int hoka$lastX, hoka$lastZ;

    @WrapOperation(method = "replaceWithPacketData", at = @At(value = "INVOKE",
            target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", remap = false))
    private void hoka$quiet(Logger log, String msg, Object a, Object b, Operation<Void> op) {
        if (!TallWorld.net) { op.call(log, msg, a, b); return; }
        hoka$ignored++;
        if (a instanceof Integer ix && b instanceof Integer iz) ink.hoka.odyssey.client.ColumnHeal.forgot(ix, iz);   // 0.63.1: вне обзора — не потеря
        if (a instanceof Integer x) hoka$lastX = x;
        if (b instanceof Integer z) hoka$lastZ = z;
        long now = System.currentTimeMillis();
        if (now - hoka$lastLog < 10_000) return;
        hoka$lastLog = now;
        log.warn("Hoka: столбцы вне зоны обзора клиента отброшены, всего {} (последний {}, {})", hoka$ignored, hoka$lastX, hoka$lastZ);
    }
}
