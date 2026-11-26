package ink.hoka.odyssey.client.mixin.compat;

import ink.hoka.odyssey.client.ContentRange;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
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

    /**
     * 0.63.3: окно Sodium в высоком мире (±«Дальность по высоте»+гистерезис) — целиком в обходе графа, не только в
     * пределах дальности прорисовки по вертикали. У Sodium обход — цилиндр: секция дальше searchDistance по |dy| не
     * берётся. Окно же шире (V=10 + 4 = 14 кубов = 224 блока при дальности 12 = 192): ряды окна выше дальности граф не
     * брал, а проход полосы поверхности (SodiumBand) ряды окна пропускает как «дело графа» — горизонтальная дыра
     * через всю гору между низом (граф) и вершиной (полоса вне окна, по дереву без вертикального предела). Туман
     * дальности у игры цилиндрический — по вертикали он гору не прячет. По горизонтали предел прежний.
     */
    @ModifyReturnValue(method = "isWithinRenderDistance", at = @At("RETURN"), remap = false)
    private static boolean hoka$windowHeight(boolean ok, @Local(argsOnly = true) CameraTransform cam,
                                             @Local(argsOnly = true) RenderSection s, @Local(argsOnly = true) float max) {
        if (ok || !ContentRange.track) return ok;
        int y = s.getChunkY();
        if (y < ContentRange.winLo || y > ContentRange.winHi) return ok;  // вне окна (и окна нет: lo > hi) — как у Sodium
        int ox = s.getOriginX() - cam.intX, oz = s.getOriginZ() - cam.intZ;
        float dx = hoka$nearest(ox - 1, ox + 17) - cam.fracX;
        float dz = hoka$nearest(oz - 1, oz + 17) - cam.fracZ;
        return dx * dx + dz * dz < max * max;
    }

    @Unique
    private static int hoka$nearest(int min, int max) {
        return min > 0 ? min : max < 0 ? max : 0;
    }
}
