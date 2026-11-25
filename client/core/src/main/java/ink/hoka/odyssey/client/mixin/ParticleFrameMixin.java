package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ParticleStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Частицы, рождённые, пока свой игрок в системе верфи корабля (бег, приземление, пузыри), остаются ЖИТЬ в верфи:
 * летят и падают к полу корабля, сталкиваются с его настоящими блоками, светятся его светом. Рисуются там, где корабль
 * (ParticleYardMixin). Игра отбросила бы их по дальности от камеры — поэтому создаём сами.
 */
@Mixin(ClientLevel.class)
public abstract class ParticleFrameMixin {

    @Inject(method = "doAddParticle", at = @At("HEAD"), cancellable = true)
    private void hoka$frame(ParticleOptions o, boolean force, boolean always, double x, double y, double z, double vx, double vy, double vz, CallbackInfo ci) {
        if (x < 399_000 || Hooks.soundFrame == null) return;                    // только частицы из полосы верфи, пока игрок там
        ci.cancel();
        Minecraft mc = Minecraft.getInstance();
        if (!force && mc.options.particles().get() == ParticleStatus.MINIMAL) return;
        mc.particleEngine.createParticle(o, x, y, z, vx, vy, vz);
    }
}
