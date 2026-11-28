package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.Octree;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Этап 6, V4 (CUBIC-CHUNKS.md §3.6): октодерево графа видимости. Ваниль строит его от низа мира, если степень двойки
 * от дальности не меньше sectionGridSizeY ViewArea; с кольцом по Y (ViewAreaRingMixin) sizeY маленький — дерево
 * встало бы у дна мира, и ни одна секция у камеры в него не попала бы (пустой экран). Высокий мир без Sodium: даём
 * «высоту» больше любого дерева — ваниль строит его вокруг камеры. Обычный мир — без изменений.
 */
@Mixin(targets = "net.minecraft.client.renderer.SectionOcclusionGraph$GraphStorage")
public abstract class OctreeHeightMixin {

    @WrapOperation(method = "<init>", at = @At(value = "NEW", target = "net/minecraft/client/renderer/Octree"))
    private Octree hoka$aroundCamera(SectionPos cam, int viewDistance, int sizeY, int minY, Operation<Octree> op) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (!ink.hoka.odyssey.client.VanillaView.SODIUM && level != null && sizeY < level.getSectionsCount()) sizeY = Integer.MAX_VALUE;
        return op.call(cam, viewDistance, sizeY, minY);
    }
}
