package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Модуль попросил спрятать экран загрузки мира (ClientContext.hideLoadingScreen): вместо него чёрный экран. */
@Mixin(LevelLoadingScreen.class)
public abstract class LoadingScreenMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void hoka$black(GuiGraphics g, int mx, int my, float pt, CallbackInfo ci) {
        if (!Hooks.hideLoading()) return;
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xFF000000);
        ci.cancel();
    }

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void hoka$blackBg(GuiGraphics g, int mx, int my, float pt, CallbackInfo ci) {
        if (Hooks.hideLoading()) ci.cancel();
    }
}
