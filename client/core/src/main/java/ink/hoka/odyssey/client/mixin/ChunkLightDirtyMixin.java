package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ink.hoka.odyssey.client.TallLight;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Пришёл столбец со светом — игра помечает к перестройке столбец и соседей по ВСЕЙ высоте мира. В высоком мире это
 * 1876 секций × 9 ≈ 17 тыс. пометок на столбец (при 32 чанках — десятки миллионов, лавина перестроек Sodium и
 * просадки FPS). Рисуется только окно по высоте вокруг камеры (SodiumTallMixin) — помечаем только его; с 0.49 ещё и
 * только слои с геометрией (ContentRange lo-1..hi+1): ~620 → ~10–30 пометок на столбец.
 * <p>0.49 (E): сам цикл updateSectionStatus по 1876 секциям — только по слоям, где у движка есть состояние (TallLight).</p>
 */
@Mixin(ClientPacketListener.class)
public abstract class ChunkLightDirtyMixin {

    @Shadow private ClientLevel level;

    @Inject(method = "enableChunkLight", at = @At("HEAD"), cancellable = true)
    private void hoka$fastEnable(LevelChunk chunk, int x, int z, CallbackInfo ci) {
        if (TallLight.enable(level, chunk, x, z)) ci.cancel();
    }

    @WrapOperation(method = "enableChunkLight", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;setSectionRangeDirty(IIIIII)V"))
    private void hoka$window(ClientLevel level, int x0, int y0, int z0, int x1, int y1, int z1, Operation<Void> op) {
        if (y1 - y0 <= 64) { op.call(level, x0, y0, z0, x1, y1, z1); return; }      // обычная высота — как у игры
        TallLight.dirty(level, x0, y0, z0, x1, y1, z1);
    }
}
