package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Пока движение своего игрока считается в системе верфи корабля (Hooks.soundFrame), его звуки (шаги, прыжок,
 * падение) игра ставит в координаты верфи — далеко. Переносим их туда, где игрок на самом деле (мир).
 */
@Mixin(ClientLevel.class)
public abstract class SoundFrameMixin {

    @Unique private static boolean hoka$inside;

    @Inject(method = "playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V", at = @At("HEAD"), cancellable = true)
    private void hoka$frame(double x, double y, double z, SoundEvent ev, SoundSource src, float vol, float pitch, boolean delay, CallbackInfo ci) {
        if (hoka$inside || x < 399_000) return;                                   // только звуки из полосы верфи
        var m = Hooks.soundFrame;
        if (m == null) m = Hooks.yardToWorld(x, y, z, 1f);                       // вне тика игрока (подбор предмета палубы и т.п.)
        if (m == null) return;
        Vector3d w = m.transformPosition(new Vector3d(x, y, z));
        hoka$inside = true;
        try { ((ClientLevel) (Object) this).playLocalSound(w.x, w.y, w.z, ev, src, vol, pitch, delay); }
        finally { hoka$inside = false; }
        ci.cancel();
    }
}
