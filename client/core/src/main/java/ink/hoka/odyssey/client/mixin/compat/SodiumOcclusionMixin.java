package ink.hoka.odyssey.client.mixin.compat;

import ink.hoka.odyssey.client.ContentRange;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

/**
 * План, шаг 1 (А1): обход видимости Sodium в высоком мире — только по слоям с геометрией (ContentRange). Из секций
 * на уровне hi+1 и выше (и не ниже камеры) не идём вверх, из lo-1 и ниже (и не выше камеры) — вниз. Обход Sodium
 * идёт только «от камеры», поэтому путь к любой секции lo..hi по высоте не выходит за слой hi+1 / lo-1 — отсечение
 * пещер не страдает, а сотни тысяч пустых секций неба не обходятся. Сущности в отрезанном небе видны: см.
 * SodiumTallMixin.isSectionVisible. Пока непустых кубов нет (ContentRange.cut = false) — ничего не меняем.
 * Необязательный (@Pseudo, require 0).
 * <p>0.49: @ModifyReturnValue вместо @Inject(RETURN, cancellable) — без CallbackInfoReturnable на каждую секцию графа
 * в каждом обходе (было 36% мусора главного потока).</p>
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller", remap = false)
public abstract class SodiumOcclusionMixin {

    /** Биты направлений Sodium (GraphDirectionSet): DOWN = 1, UP = 2. */
    @Unique private static final int HOKA_DOWN = 1, HOKA_UP = 2;

    @ModifyReturnValue(method = "getOutwardDirections", at = @At("RETURN"), remap = false)
    private static int hoka$cutAir(int d, @Local(argsOnly = true) SectionPos cam, @Local(argsOnly = true) RenderSection s) {
        if (!ContentRange.cut) return d;
        int y = s.getChunkY(), cy = cam.y();
        if (y >= Math.max(cy, ContentRange.hi + 1)) d &= ~HOKA_UP;
        if (y <= Math.min(cy, ContentRange.lo - 1)) d &= ~HOKA_DOWN;
        return d;
    }
}
