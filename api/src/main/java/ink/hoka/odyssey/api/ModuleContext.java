package ink.hoka.odyssey.api;

import net.minestom.server.command.builder.Command;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Optional;

/** Всё, что ядро даёт модулю. Получить: {@link OdysseyModule#context()}. */
public interface ModuleContext {

    ModuleDescriptor descriptor();

    /** Логгер с именем модуля: [ships] ... */
    Logger logger();

    /** Узел событий модуля (дочерний к глобальному). Снимается целиком при выключении. */
    EventNode<Event> events();

    /** Зарегистрировать команду. При выключении модуля она будет снята. */
    void registerCommand(Command command);

    ModuleScheduler scheduler();

    /** module/&lt;id&gt;/ — создаётся при первом обращении. */
    Path dataFolder();

    /** config.yml модуля. */
    ModuleConfig config();

    /** Общий реестр сервисов: так модули дают друг другу свой API. */
    Services services();

    /** Другой включённый модуль по id (для модулей из depends/softdepends). */
    Optional<OdysseyModule> module(String id);

    OdysseyServerApi server();

    /**
     * Зарегистрировать генератор планеты (map.yml карты: {@code generator: <id>}). Снимается при выключении модуля.
     * Пока карта с этим генератором загружена (или идёт pregen), модуль не выгрузить и не перезагрузить:
     * сначала /homap unload &lt;карта&gt;. Исключение — id занят.
     */
    void registerGenerator(ink.hoka.odyssey.api.worldgen.GeneratorProvider provider);

    /**
     * Зарегистрировать ПЕРЕНОСИМЫЙ генератор (jar на gen-api с hoka-gen.json): работает на сервере и у клиентов 0.59+
     * (нетронутые кубы генерирует клиент). Снимается при выключении модуля. Исключение — jar плохой или id занят.
     */
    default void registerGenerator(java.nio.file.Path genJar) { throw new UnsupportedOperationException("ядро без переносимых генераторов"); }
}
