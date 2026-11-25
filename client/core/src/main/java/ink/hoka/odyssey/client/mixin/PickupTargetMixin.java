package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.particle.ItemPickupParticle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Подбор: предмет летит в середину тела игрока — игра считает её по вертикали мира. Своему игроку на корабле (поза
 * модели от модуля) — по «верху» корабля, иначе на корабле на боку предмет улетал вбок.
 */
@Mixin(ItemPickupParticle.class)
public abstract class PickupTargetMixin {
    @Shadow @Final private Entity target;
    @Shadow protected double targetX;
    @Shadow protected double targetY;
    @Shadow protected double targetZ;

    @Inject(method = "updatePosition", at = @At("TAIL"))
    private void hoka$shipUp(CallbackInfo ci) {
        if (!(target instanceof LocalPlayer p)) return;
        var pose = Hooks.pose(p, 1f);
        if (pose == null || pose.tilt() == null) return;
        Vector3f up = pose.tilt().transform(new Vector3f(0, 1, 0), new Vector3f());
        double h = p.getEyeHeight() / 2.0;
        targetX = p.getX() + up.x * h;
        targetY = p.getY() + up.y * h;
        targetZ = p.getZ() + up.z * h;
    }
}
