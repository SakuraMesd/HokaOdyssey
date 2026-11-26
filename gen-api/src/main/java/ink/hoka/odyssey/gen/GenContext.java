package ink.hoka.odyssey.gen;

import java.util.Map;

/** Настройки генератора для карты: имя, сид, границы, map.yml generator_options (только чтение), таблица номеров. */
public interface GenContext {
    String map();
    long seed();
    GenBounds bounds();
    Map<String, Object> options();
    GenIds ids();

    /** true — сервер, false — клиент. */
    boolean server();

    /** Сид секретов (map.yml secret_seed) — только сервер; на клиенте исключение. */
    long secretSeed();

    default int intOption(String k, int def) { return options().get(k) instanceof Number n ? n.intValue() : def; }
    default double doubleOption(String k, double def) { return options().get(k) instanceof Number n ? n.doubleValue() : def; }
    default String stringOption(String k, String def) { Object v = options().get(k); return v == null ? def : v.toString(); }

    /** Простая реализация (сервер и клиент). secretSeed == null — секретов нет (клиент). */
    static GenContext of(String map, long seed, GenBounds bounds, Map<String, Object> options, GenIds ids, boolean server, Long secretSeed) {
        Map<String, Object> o = options == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(options));
        return new GenContext() {
            @Override public String map() { return map; }
            @Override public long seed() { return seed; }
            @Override public GenBounds bounds() { return bounds; }
            @Override public Map<String, Object> options() { return o; }
            @Override public GenIds ids() { return ids; }
            @Override public boolean server() { return server; }
            @Override public long secretSeed() {
                if (!server || secretSeed == null) throw new IllegalStateException("secretSeed — только на сервере");
                return secretSeed;
            }
        };
    }
}
