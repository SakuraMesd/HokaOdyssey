package ink.hoka.odyssey.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import ink.hoka.odyssey.client.Hooks;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Поворот модели сущности от модулей (ClientContext.onEntityTilt): при сборе состояния модели — запомнить поворот и
 * перевести углы тела/головы в его систему (взгляд в мире тот же); при отрисовке — повернуть модель вокруг ног.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class EntityTiltMixin {

    @Unique private static final RenderStateDataKey<Quaternionf> HOKA_TILT = RenderStateDataKey.create(() -> "hoka_odyssey:tilt");

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void hokaOdyssey$extract(LivingEntity e, LivingEntityRenderState s, float pt, CallbackInfo ci) {
        var pose = Hooks.pose(e, pt);
        if (pose != null) {                                                     // углы уже в системе поворота
            s.bodyRot = pose.bodyYaw();
            s.yRot = Mth.wrapDegrees(pose.headYaw() - pose.bodyYaw());
            s.xRot = pose.headPitch();
            s.setData(HOKA_TILT, pose.tilt() == null ? null : new Quaternionf(pose.tilt()));
            return;
        }
        var t = Hooks.tilt(e, pt);
        if (t == null) { s.setData(HOKA_TILT, null); return; }
        Quaternionf q = new Quaternionf(t);
        Quaternionf inv = new Quaternionf(q).conjugate();
        // тело: направление в мире → в системе поворота
        Vector3f b = inv.transform(dir(s.bodyRot, 0));
        float body = (float) Math.toDegrees(Math.atan2(-b.x, b.z));
        // голова: абсолютный взгляд в мире → в системе поворота
        Vector3f h = inv.transform(dir(s.bodyRot + s.yRot, s.xRot));
        float headYaw = (float) Math.toDegrees(Math.atan2(-h.x, h.z));
        float headPitch = (float) Math.toDegrees(Math.asin(Mth.clamp(-h.y, -1f, 1f)));
        s.bodyRot = body;
        s.yRot = Mth.wrapDegrees(headYaw - body);
        s.xRot = headPitch;
        s.setData(HOKA_TILT, q);
    }

    @Inject(method = "setupRotations", at = @At("HEAD"))
    private void hokaOdyssey$rotate(LivingEntityRenderState s, PoseStack pose, float bodyRot, float scale, CallbackInfo ci) {
        Quaternionf q = s.getData(HOKA_TILT);
        if (q != null) pose.mulPose(q);
    }

    @Unique
    private static Vector3f dir(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch), cp = Math.cos(p);
        return new Vector3f((float) (-Math.sin(y) * cp), (float) -Math.sin(p), (float) (Math.cos(y) * cp));
    }
}
