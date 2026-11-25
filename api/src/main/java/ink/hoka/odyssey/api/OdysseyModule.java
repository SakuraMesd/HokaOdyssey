package ink.hoka.odyssey.api;

import net.minestom.server.command.builder.Command;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Базовый класс модуля Hoka Odyssey — аналог JavaPlugin у Paper.
 *
 * <pre>
 * public final class ShipsModule extends OdysseyModule {
 *     protected void onEnable() {
 *         events().addListener(PlayerSpawnEvent.class, e -> e.getPlayer().sendMessage("Добро пожаловать на борт"));
 *         registerCommand(new LaunchCommand());
 *     }
 * }
 * </pre>
 *
 * Правила:
 * <ul>
 *   <li>Слушатели — только через {@link #events()}, команды — через {@link #registerCommand}, задачи — через
 *       {@link #scheduler()}. Тогда при выключении/перезагрузке модуля ядро само всё снимет.</li>
 *   <li>Не храните состояние в static-полях других модулей и не держите ссылки на чужие классы после их выключения.</li>
 *   <li>Всё, что модуль создал сам вне этих механизмов (сущности, миры, потоки), — убирать в {@link #onDisable()}.</li>
 * </ul>
 */
public abstract class OdysseyModule {

    private ModuleContext context;

    /** Вызывает ядро один раз, сразу после создания модуля. Модулю вызывать не нужно. */
    public final void attach(ModuleContext context) {
        if (this.context != null) throw new IllegalStateException("модуль уже подключён");
        this.context = context;
    }

    /** Модуль загружен, зависимости уже загружены, но ещё не включены. Обычно не нужен. */
    protected void onLoad() {}

    /** Включение: регистрируем слушатели, команды, задачи. Исключение здесь — модуль не включится. */
    protected abstract void onEnable();

    /** Выключение (остановка сервера или /module reload|unload). Слушатели, команды и задачи ядро снимет само. */
    protected void onDisable() {}

    // ---- вызывает ядро ----
    public final void load() { onLoad(); }
    public final void enable() { onEnable(); }
    public final void disable() { onDisable(); }

    // ---- короткие пути к контексту ----

    public final ModuleContext context() {
        if (context == null) throw new IllegalStateException("модуль ещё не подключён ядром");
        return context;
    }

    public final ModuleDescriptor descriptor() { return context().descriptor(); }

    public final String id() { return descriptor().id(); }

    public final Logger logger() { return context().logger(); }

    /** События этого модуля. Снимаются целиком при выключении модуля. */
    public final EventNode<Event> events() { return context().events(); }

    public final void registerCommand(Command command) { context().registerCommand(command); }

    public final ModuleScheduler scheduler() { return context().scheduler(); }

    /** Папка модуля: module/&lt;id&gt;/ (конфиг, данные). */
    public final Path dataFolder() { return context().dataFolder(); }

    /** config.yml модуля (при первом запуске копируется из ресурсов jar, если там есть). */
    public final ModuleConfig config() { return context().config(); }

    public final Services services() { return context().services(); }

    public final OdysseyServerApi server() { return context().server(); }

    /** Генератор планеты (map.yml generator: id). Снимается при выключении модуля. */
    public final void registerGenerator(ink.hoka.odyssey.api.worldgen.GeneratorProvider provider) { context().registerGenerator(provider); }
}
