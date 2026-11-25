package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Подбор предмета (ванильная анимация «втягивания»): начало полёта — место предмета. У предмета палубы корабля оно
 * в верфи (далеко) — переводим на корабль в мире, иначе предмет летел бы к игроку через полмира.
 */
@Mixin(ClientPacketListener.class)
public abstract class PickupYardMixin {
    @WrapOperation(method = "handleTakeItemEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
    private EntityRenderState hoka$yard(EntityRenderDispatcher d, Entity e, float pt, Operation<EntityRenderState> op) {
        EntityRenderState s = op.call(d, e, pt);
        if (s != null && s.x >= 399_000) {
            var m = Hooks.yardToWorld(s.x, s.y, s.z, 1f);
            if (m != null) {
                Vector3d w = m.transformPosition(new Vector3d(s.x, s.y, s.z));
                s.x = w.x; s.y = w.y; s.z = w.z;
            }
        }
        return s;
    }
}
