package ink.hoka.odyssey.stations;

import java.util.Locale;

/** Гравитация региона: обычная (как на земле), вакуум (невесомость) или «как у карты» (регион не меняет гравитацию). */
public enum Grav {
    NORMAL, VACUUM, MAP;

    /** Имя в файле и командах: normal | vacuum | map. */
    public String key() { return name().toLowerCase(Locale.ROOT); }

    /** По имени (normal | vacuum | map), null — не знаем такое. */
    public static Grav parse(String s) {
        if (s == null) return null;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "normal", "1g", "on" -> NORMAL;
            case "vacuum", "zero", "off" -> VACUUM;
            case "map" -> MAP;
            default -> null;
        };
    }

    /** Байт для клиентского модуля: 0 — normal, 1 — vacuum, 2 — map. */
    public int code() { return ordinal(); }
}
