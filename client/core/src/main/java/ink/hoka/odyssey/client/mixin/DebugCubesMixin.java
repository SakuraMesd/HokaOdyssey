package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.ContentRange;
import net.minecraft.client.gui.components.debug.DebugEntrySectionPosition;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** F3 (высокий мир): строка «Hoka кубы: content lo..hi, окно Sodium a..b, кубов загружено N». Рядом с секцией игрока. */
@Mixin(DebugEntrySectionPosition.class)
public abstract class DebugCubesMixin {
    @Inject(method = "display", at = @At("RETURN"), require = 0)
    private void hoka$cubes(DebugScreenDisplayer out, Level level, LevelChunk a, LevelChunk b, CallbackInfo ci) {
        String s = ContentRange.debugLine();
        if (s != null) out.addLine(s);
        String f = ContentRange.feetLine();
        if (f != null) out.addLine(f);
        String k = ink.hoka.odyssey.client.CubeCache.debugLine();           // 0.51: кэш кубов
        if (k != null) out.addLine(k);
        String l = ink.hoka.odyssey.client.LocalLight.debugLine();         // 0.57: свет на клиенте
        if (l != null) out.addLine(l);
    }
}
