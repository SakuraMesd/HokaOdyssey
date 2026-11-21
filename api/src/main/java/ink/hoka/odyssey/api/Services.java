package ink.hoka.odyssey.api;

import java.util.Optional;

/**
 * Реестр сервисов — так модули дают API друг другу без жёсткой привязки к классам реализации.
 * <pre>
 * // модуль space:
 * services().register(SpaceApi.class, new SpaceApiImpl());
 * // модуль ships (depends: [space]):
 * SpaceApi space = services().require(SpaceApi.class);
 * </pre>
 * Сервисы модуля снимаются при его выключении. Интерфейс сервиса держите в модуле-владельце (или в общем модуле-api).
 */
public interface Services {

    <T> void register(Class<T> type, T impl);

    <T> Optional<T> get(Class<T> type);

    /** Как get, но исключение, если сервиса нет. */
    default <T> T require(Class<T> type) {
        return get(type).orElseThrow(() -> new IllegalStateException("сервис " + type.getName() + " не зарегистрирован"));
    }

    void unregister(Class<?> type);
}
