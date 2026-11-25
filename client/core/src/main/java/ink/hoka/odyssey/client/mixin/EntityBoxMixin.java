package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Рамка и глаза сущности от модулей (ClientContext.onEntityBox / onEntityEye), только в клиентском мире.
 * <p>
 * Рамка: {@code Entity.makeBoundingBox(Vec3)} — через него идут setPos (каждый шаг движения), reapplyPosition и
 * refreshDimensions, так что рамка модуля держится сама. Глаза: getEyePosition(), getEyePosition(pt), getEyeY() —
 * прицел (pick), луч взгляда, ломание/установка блоков, свет модели.
 * Без крючков — сразу как у игры (один пустой список).
 */
@Mixin(Entity.class)
public abstract class EntityBoxMixin {

    @Shadow public abstract Level level();
    @Shadow public abstract Vec3 position();
    @Shadow public double xo;
    @Shadow public double yo;
    @Shadow public double zo;

    @Inject(method = "makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;",
            at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$box(Vec3 pos, CallbackInfoReturnable<AABB> cir) {
        if (!Hooks.hasBox() || !hokaOdyssey$client()) return;
        AABB box = Hooks.box((Entity) (Object) this, pos);
        if (box != null) cir.setReturnValue(box);
    }

    @Inject(method = "getEyePosition()Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$eye(CallbackInfoReturnable<Vec3> cir) {
        Vec3 off = hokaOdyssey$eyeOffset();
        if (off != null) cir.setReturnValue(position().add(off));
    }

    @Inject(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$eyeAt(float pt, CallbackInfoReturnable<Vec3> cir) {
        Vec3 off = hokaOdyssey$eyeOffset();
        if (off == null) return;
        Vec3 p = position();
        cir.setReturnValue(new Vec3(Mth.lerp(pt, xo, p.x) + off.x, Mth.lerp(pt, yo, p.y) + off.y,
                Mth.lerp(pt, zo, p.z) + off.z));
    }

    @Inject(method = "getEyeY()D", at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$eyeY(CallbackInfoReturnable<Double> cir) {
        Vec3 off = hokaOdyssey$eyeOffset();
        if (off != null) cir.setReturnValue(position().y + off.y);
    }

    @Unique
    private Vec3 hokaOdyssey$eyeOffset() {
        if (!Hooks.hasEye() || !hokaOdyssey$client()) return null;
        return Hooks.eye((Entity) (Object) this);
    }

    /** Сущность клиентского мира (в одиночной игре те же классы — и у встроенного сервера: его не трогаем). */
    @Unique
    private boolean hokaOdyssey$client() {
        Level l = level();
        return l != null && l.isClientSide();
    }
}
