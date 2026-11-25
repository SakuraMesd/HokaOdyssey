package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Частицы верфи корабля: видимость в кадре — по точке на корабле в мире, а не в далёкой верфи. */
@Mixin(QuadParticleGroup.class)
public abstract class ParticleYardMixin {
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
    private boolean hoka$visible(Frustum f, double x, double y, double z, Operation<Boolean> op) {
        if (x < 399_000) return op.call(f, x, y, z);
        var m = Hooks.yardToWorld(x, y, z, 1f);
        if (m == null) return false;                                            // корабля нет рядом — не рисовать
        Vector3d w = m.transformPosition(new Vector3d(x, y, z));
        return op.call(f, w.x, w.y, w.z);
    }
}
