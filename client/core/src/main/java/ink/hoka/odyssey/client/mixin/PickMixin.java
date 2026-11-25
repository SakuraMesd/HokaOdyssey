package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Колёсико мыши («выбрать блок») — сначала модулям: блок под прицелом может быть блоком корабля, а не мира. */
@Mixin(Minecraft.class)
public abstract class PickMixin {
    @Inject(method = "pickBlock", at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$pick(CallbackInfo ci) {
        if (Hooks.pick(Minecraft.getInstance().hasControlDown())) ci.cancel();
    }
}
