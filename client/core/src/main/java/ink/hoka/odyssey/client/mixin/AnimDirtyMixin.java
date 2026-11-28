package ink.hoka.odyssey.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 0.90: секция перестраивается (сменился блок, пришёл чанк/куб) — поиску анимированных блоков (BlockAnims) перепроверить столбец. */
@Mixin(value = LevelRenderer.class, priority = 1500)   // позже Sodium: его @Overwrite этого метода иначе сотрёт наш вызов
public abstract class AnimDirtyMixin {
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void hokaOdyssey$animDirty(int x, int y, int z, boolean important, CallbackInfo ci) {
        ink.hoka.odyssey.client.model.BlockAnims.dirty(x, z);
    }
}
