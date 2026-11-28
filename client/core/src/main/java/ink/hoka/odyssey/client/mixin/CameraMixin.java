package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import ink.hoka.odyssey.client.api.CameraState;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Камера для модулей: после расчёта игрой — курс, наклон, положение и КРЕН от модулей. Крен подставляется в сам
 * поворот камеры (третий угол rotationYXZ), поэтому видимость (отсечение) и звук считаются уже с ним.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow protected abstract void setRotation(float yRot, float xRot);
    @Shadow protected abstract void setPosition(double x, double y, double z);
    @Shadow public abstract Vec3 position();
    @Shadow public abstract float yRot();
    @Shadow public abstract float xRot();

    @Inject(method = "setup", at = @At("TAIL"))
    private void hokaOdyssey$setup(Level level, Entity entity, boolean detached, boolean mirror, float partialTick, CallbackInfo ci) {
        if (!Hooks.hasCamera()) {
            if (Hooks.roll != 0 || Hooks.rotation != null) { Hooks.roll = 0; Hooks.rotation = null; setRotation(yRot(), xRot()); }
            Hooks.fov = 0;
            return;
        }
        CameraState s = new CameraState();
        Vec3 p = position();
        s.x = p.x;
        s.y = p.y;
        s.z = p.z;
        s.yaw = yRot();
        s.pitch = xRot();
        s.detached = detached;
        Hooks.camera(s, partialTick);
        Hooks.fov = s.fov > 0 ? Math.clamp(s.fov, 1f, 170f) : 0;              // 0.82: поле зрения от модуля
        if (s.rotation != null) {
            Hooks.rotation = new org.joml.Quaternionf(s.rotation);               // полный поворот на этот кадр
            Hooks.roll = 0;
        } else {
            Hooks.rotation = null;
            Hooks.roll = (float) Math.toRadians(s.roll);
        }
        setRotation(s.yaw, s.pitch);
        setPosition(s.x, s.y, s.z);
    }

    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private org.joml.Quaternionf rotation;
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private org.joml.Vector3f forwards;
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private org.joml.Vector3f up;
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private org.joml.Vector3f left;

    /** Полный поворот от модуля — поверх того, что игра насчитала из углов. */
    @Inject(method = "setRotation", at = @At("TAIL"))
    private void hokaOdyssey$rotation(float yRot, float xRot, CallbackInfo ci) {
        if (Hooks.rotation == null) return;
        rotation.set(Hooks.rotation);
        forwards.set(0, 0, -1).rotate(rotation);
        up.set(0, 1, 0).rotate(rotation);
        left.set(-1, 0, 0).rotate(rotation);
    }

    @ModifyArg(method = "setRotation", at = @At(value = "INVOKE",
            target = "Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;"), index = 2)
    private float hokaOdyssey$roll(float roll) {
        return Hooks.roll;
    }
}
