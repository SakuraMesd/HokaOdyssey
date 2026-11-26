package ink.hoka.odyssey.client.mixin.compat;

import ink.hoka.odyssey.client.CubeCacheSetting;
import ink.hoka.odyssey.client.VerticalView;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium заменяет экран графики: ползунок «Дальность по высоте» — своей группой на странице «Общие», где «Дальность
 * прорисовки». Опция строится обычным config API Sodium (ConfigBuilder). Необязательный (@Pseudo, require 0).
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder", remap = false)
public abstract class SodiumOptionsMixin {

    @Inject(method = "buildGeneralPage", at = @At("RETURN"), remap = false)
    private void hoka$vertical(ConfigBuilder builder, CallbackInfoReturnable<OptionPageBuilder> cir) {
        OptionPageBuilder page = cir.getReturnValue();
        if (page == null) return;
        page.addOptionGroup(builder.createOptionGroup()
                .setName(Component.literal("Hoka Odyssey"))
                .addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath("hoka_odyssey", "vertical_distance"))
                        .setName(Component.translatable("options.hoka_odyssey.vertical"))
                        .setTooltip(Component.translatable("options.hoka_odyssey.vertical.tooltip"))
                        .setRange(VerticalView.MIN, VerticalView.MAX, 1)
                        .setDefaultValue(VerticalView.DEFAULT)
                        .setValueFormatter(v -> Component.translatable("options.hoka_odyssey.vertical.value", v))
                        .setImpact(OptionImpact.HIGH)
                        .setBinding(VerticalView::set, VerticalView::get)
                        .setStorageHandler(VerticalView::save))
                .addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath("hoka_odyssey", "surface_view"))   // 0.62
                        .setName(Component.translatable("options.hoka_odyssey.surface"))
                        .setTooltip(Component.translatable("options.hoka_odyssey.surface.tooltip"))
                        .setRange(0, VerticalView.SURF_MAX, VerticalView.SURF_STEP)
                        .setDefaultValue(VerticalView.SURF_DEFAULT)
                        .setValueFormatter(VerticalView::surfaceValue)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding(VerticalView::setSurface, VerticalView::surface)
                        .setStorageHandler(VerticalView::saveSurface))
                .addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath("hoka_odyssey", "cube_cache"))   // 0.51
                        .setName(Component.translatable("options.hoka_odyssey.cube_cache"))
                        .setTooltip(Component.translatable("options.hoka_odyssey.cube_cache.tooltip"))
                        .setRange(0, CubeCacheSetting.MAX, CubeCacheSetting.STEP)
                        .setDefaultValue(Math.round(CubeCacheSetting.DEFAULT / (float) CubeCacheSetting.STEP) * CubeCacheSetting.STEP)
                        .setValueFormatter(v -> v == 0 ? Component.translatable("options.hoka_odyssey.cube_cache.off")
                                : Component.translatable("options.hoka_odyssey.cube_cache.value", v))
                        .setImpact(OptionImpact.LOW)
                        .setBinding(CubeCacheSetting::set, CubeCacheSetting::get)
                        .setStorageHandler(CubeCacheSetting::save))
                .addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath("hoka_odyssey", "client_gen"))   // 0.59
                        .setName(Component.translatable("options.hoka_odyssey.client_gen"))
                        .setTooltip(Component.translatable("options.hoka_odyssey.client_gen.tooltip"))
                        .setRange(0, ink.hoka.odyssey.client.GenSetting.AUTO, 1)
                        .setDefaultValue(ink.hoka.odyssey.client.GenSetting.AUTO)
                        .setValueFormatter(ink.hoka.odyssey.client.GenSetting::valueText)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding(ink.hoka.odyssey.client.GenSetting::set, ink.hoka.odyssey.client.GenSetting::get)
                        .setStorageHandler(ink.hoka.odyssey.client.GenSetting::save))
                .addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath("hoka_odyssey", "gen_disk"))   // 0.63
                        .setName(Component.translatable("options.hoka_odyssey.gen_disk"))
                        .setTooltip(Component.translatable("options.hoka_odyssey.gen_disk.tooltip"))
                        .setRange(0, ink.hoka.odyssey.client.GenSetting.DISK_MAX, ink.hoka.odyssey.client.GenSetting.DISK_STEP)
                        .setDefaultValue(ink.hoka.odyssey.client.GenSetting.DISK_DEFAULT)
                        .setValueFormatter(ink.hoka.odyssey.client.GenSetting::diskText)
                        .setImpact(OptionImpact.LOW)
                        .setBinding(ink.hoka.odyssey.client.GenSetting::setDisk, ink.hoka.odyssey.client.GenSetting::diskMb)
                        .setStorageHandler(ink.hoka.odyssey.client.GenSetting::saveDisk)));
    }
}
