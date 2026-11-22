package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.sound.CustomSounds;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Свои звуки hoka:<id> — из описания с сервера, без sounds.json в паке. */
@Mixin(SoundManager.class)
public class SoundManagerMixin {

    @Inject(method = "getSoundEvent", at = @At("HEAD"), cancellable = true)
    private void hoka$event(Identifier id, CallbackInfoReturnable<WeighedSoundEvents> cir) {
        WeighedSoundEvents e = CustomSounds.event(id);
        if (e != null) cir.setReturnValue(e);
    }
}
