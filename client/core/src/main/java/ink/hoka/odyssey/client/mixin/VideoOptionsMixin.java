package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.VerticalView;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Без Sodium: ползунки «Дальность по высоте» и «Кэш кубов» сразу после «Дальности прорисовки» в настройках графики. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoOptionsMixin {

    @Inject(method = "qualityOptions", at = @At("RETURN"), cancellable = true)
    private static void hoka$vertical(Options options, CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        OptionInstance<?>[] a = cir.getReturnValue();
        int at = a.length;
        for (int i = 0; i < a.length; i++) if (a[i] == options.renderDistance()) { at = i + 1; break; }
        OptionInstance<?>[] r = new OptionInstance<?>[a.length + 2];
        System.arraycopy(a, 0, r, 0, at);
        r[at] = VerticalView.option();
        r[at + 1] = ink.hoka.odyssey.client.CubeCacheSetting.option();   // 0.51: кэш кубов, МБ
        System.arraycopy(a, at, r, at + 2, a.length - at);
        cir.setReturnValue(r);
    }
}
