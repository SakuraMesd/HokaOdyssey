package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.sound.CustomSounds;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Файлы своих звуков (.ogg с сервера) — из кэша мода, мимо ресурсов игры. */
@Mixin(SoundBufferLibrary.class)
public class SoundBufferLibraryMixin {

    @Shadow @Final @Mutable private ResourceProvider resourceManager;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void hoka$wrap(ResourceProvider provider, CallbackInfo ci) {
        resourceManager = CustomSounds.wrap(resourceManager);
    }
}
