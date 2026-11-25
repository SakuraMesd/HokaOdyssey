package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.QuadParticleRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Частица верфи корабля рисуется там, где корабль: её место (с плавностью кадра) — из верфи в мир. */
@Mixin(SingleQuadParticle.class)
public abstract class ParticleQuadYardMixin extends Particle {

    protected ParticleQuadYardMixin() { super(null, 0, 0, 0); }

    @Shadow
    protected abstract void extractRotatedQuad(QuadParticleRenderState state, Quaternionf q, float x, float y, float z, float pt);

    @Inject(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/QuadParticleRenderState;Lnet/minecraft/client/Camera;Lorg/joml/Quaternionf;F)V",
            at = @At("HEAD"), cancellable = true)
    private void hoka$yard(QuadParticleRenderState state, Camera camera, Quaternionf q, float pt, CallbackInfo ci) {
        if (this.x < 399_000) return;
        ci.cancel();
        double lx = Mth.lerp(pt, this.xo, this.x), ly = Mth.lerp(pt, this.yo, this.y), lz = Mth.lerp(pt, this.zo, this.z);
        var m = Hooks.yardToWorld(lx, ly, lz, pt);
        if (m == null) return;
        Vector3d w = m.transformPosition(new Vector3d(lx, ly, lz));
        Vec3 c = camera.position();
        extractRotatedQuad(state, q, (float) (w.x - c.x), (float) (w.y - c.y), (float) (w.z - c.z), pt);
    }
}
