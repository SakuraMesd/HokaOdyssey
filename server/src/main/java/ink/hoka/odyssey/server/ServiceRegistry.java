package ink.hoka.odyssey.server;

import ink.hoka.odyssey.api.Services;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Общий реестр сервисов. Каждый модуль видит его через свой {@link View} — так ядро знает владельца. */
final class ServiceRegistry {

    private record Entry(String owner, Object impl) {}

    private final Map<Class<?>, Entry> map = new ConcurrentHashMap<>();

    View view(String owner) { return new View(owner); }

    /** Снять все сервисы модуля (при выключении). */
    void removeOwner(String owner) { map.values().removeIf(e -> e.owner.equals(owner)); }

    final class View implements Services {
        private final String owner;

        private View(String owner) { this.owner = owner; }

        @Override
        public <T> void register(Class<T> type, T impl) {
            if (!type.isInstance(impl)) throw new IllegalArgumentException(impl + " не " + type.getName());
            Entry prev = map.putIfAbsent(type, new Entry(owner, impl));
            if (prev != null && !prev.owner.equals(owner))
                throw new IllegalStateException("сервис " + type.getName() + " уже зарегистрирован модулем " + prev.owner);
            if (prev != null) map.put(type, new Entry(owner, impl));
        }

        @Override
        public <T> Optional<T> get(Class<T> type) {
            Entry e = map.get(type);
            return e == null ? Optional.empty() : Optional.of(type.cast(e.impl));
        }

        @Override
        public void unregister(Class<?> type) {
            map.computeIfPresent(type, (k, e) -> e.owner.equals(owner) ? null : e);
        }
    }
}
