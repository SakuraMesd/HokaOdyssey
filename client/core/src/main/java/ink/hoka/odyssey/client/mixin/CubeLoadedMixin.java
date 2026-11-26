package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.CubeReceiver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Кубовая карта: Level.isLoaded(pos) = false для куба вне окна загрузки (не «воздух», а «неизвестно»). Им пользуются
 * корабли (Pilot.hits: в незагруженное не летим) и ванильные проверки клиента.
 */
@Mixin(Level.class)
public abstract class CubeLoadedMixin {

    @Inject(method = "isLoaded", at = @At("HEAD"), cancellable = true)
    private void hoka$cube(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (pos.getX() >= 399_000) return;                                        // верфь кораблей — по своим правилам
        if (CubeReceiver.unloadedY(self, SectionPos.blockToSectionCoord(pos.getY()))) cir.setReturnValue(false);
        else if (self.isClientSide() && ink.hoka.odyssey.client.GenPool.waiting(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()), SectionPos.blockToSectionCoord(pos.getZ()))) cir.setReturnValue(false);   // 0.59: ждёт генерации
    }
}
