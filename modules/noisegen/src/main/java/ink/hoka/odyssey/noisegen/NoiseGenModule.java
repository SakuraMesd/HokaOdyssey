package ink.hoka.odyssey.noisegen;

import ink.hoka.odyssey.api.OdysseyModule;

/** Регистрирует генератор «noise-test». Модуль не выгрузить, пока карта с ним загружена: /homap unload → /homodule reload. */
public final class NoiseGenModule extends OdysseyModule {
    @Override
    protected void onEnable() {
        registerGenerator(new NoiseTestProvider());
    }
}
