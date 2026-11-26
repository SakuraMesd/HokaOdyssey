package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.CubeReceiver;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Кубовая карта (CUBIC-CHUNKS.md §3.7, P5): куб вне окна загрузки — не воздух, а «неизвестно». Сущность на клиенте
 * не уходит туда по вертикали: движение по Y обнуляется, пока сервер не пришлёт кубы (окно сдвигается за игроком).
 * По горизонтали ваниль и так не пускает в незагруженный столбец.
 */
@Mixin(Entity.class)
public abstract class CubeFreezeMixin {

    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
    private Vec3 hoka$freeze(Vec3 v, MoverType type) {
        Entity self = (Entity) (Object) this;
        if (v.y == 0 || !CubeReceiver.active(self.level()) || ink.hoka.odyssey.client.Hooks.soundFrame != null || self.getX() >= 399_000) return v;
        AABB b = self.getBoundingBox();
        double y = v.y > 0 ? b.maxY + v.y : b.minY + v.y;
        int sy = SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(y));
        // 0.51: куб, ждущий ответа на WANT (промах кэша), — тоже «неизвестно» (~1 RTT); 0.59: и ждущий генерации
        int sx = SectionPos.blockToSectionCoord(self.getBlockX()), sz = SectionPos.blockToSectionCoord(self.getBlockZ());
        if (CubeReceiver.unloadedY(self.level(), sy) || ink.hoka.odyssey.client.CubeCache.waiting(sx, sy, sz)
                || ink.hoka.odyssey.client.GenPool.waiting(sx, sy, sz)) {
            self.setDeltaMovement(self.getDeltaMovement().multiply(1, 0, 1));
            return new Vec3(v.x, 0, v.z);
        }
        return v;
    }
}
