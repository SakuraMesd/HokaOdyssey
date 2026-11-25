package ink.hoka.odyssey.api.worldgen;

import java.util.Map;

/**
 * Настройки генератора для одной карты: имя карты, сид (map.yml seed), границы (Bounds провайдера, поверх —
 * map.yml gen_min_y/gen_max_y/gen_size), options — map.yml generator_options как есть (только чтение).
 */
public record GenSettings(String map, long seed, Bounds bounds, Map<String, Object> options) {
    public GenSettings { options = options == null ? Map.of() : Map.copyOf(options); }

    public int intOption(String k, int def) { return options.get(k) instanceof Number n ? n.intValue() : def; }
    public double doubleOption(String k, double def) { return options.get(k) instanceof Number n ? n.doubleValue() : def; }
    public String stringOption(String k, String def) { Object v = options.get(k); return v == null ? def : v.toString(); }
}
