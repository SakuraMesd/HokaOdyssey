package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.VerticalView;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Без Sodium: ползунки «Дальность по высоте», «Кэш кубов» и «Генерация мира» сразу после «Дальности прорисовки» в настройках графики. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoOptionsMixin {

    @Inject(method = "qualityOptions", at = @At("RETURN"), cancellable = true)
    private static void hoka$vertical(Options options, CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        OptionInstance<?>[] a = cir.getReturnValue();
        int at = a.length;
        for (int i = 0; i < a.length; i++) if (a[i] == options.renderDistance()) { at = i + 1; break; }
        OptionInstance<?>[] r = new OptionInstance<?>[a.length + 10];
        System.arraycopy(a, 0, r, 0, at);
        r[at] = VerticalView.option();
        r[at + 1] = VerticalView.surfaceOption();                         // 0.62: горы целиком
        r[at + 2] = ink.hoka.odyssey.client.CubeCacheSetting.option();   // 0.51: кэш кубов, МБ
        r[at + 3] = ink.hoka.odyssey.client.GenSetting.option();         // 0.59: генерация мира на клиенте
        r[at + 4] = ink.hoka.odyssey.client.GenSetting.diskOption();     // 0.63: кэш генерации на диске
        r[at + 5] = ink.hoka.odyssey.client.LodSetting.distanceOption(); // 0.64: дальняя прорисовка
        r[at + 6] = ink.hoka.odyssey.client.LodSetting.qualityOption();
        r[at + 7] = ink.hoka.odyssey.client.LodSetting.bodyDistanceOption();   // 0.76: астероиды в космосе
        r[at + 8] = ink.hoka.odyssey.client.LodSetting.bodyQualityOption();
        r[at + 9] = ink.hoka.odyssey.client.Smooth.option();             // 0.90: плавность
        System.arraycopy(a, at, r, at + 10, a.length - at);
        cir.setReturnValue(r);
    }
}
