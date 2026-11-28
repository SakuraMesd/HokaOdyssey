package ink.hoka.odyssey.vessels;

import java.util.Locale;

/**
 * Бой кораблей и PvP-зоны (vessels-5): урон чужому кораблю болтами и урон игрокам орудием корабля — по режиму
 * combat.mode: zones — только если и стрелок (глаза пилота), и точка попадания в PvP-зоне survival; all — везде;
 * off — нигде. Нет модуля survival — везде (как раньше). Игроков орудие бьёт ещё и только если в survival
 * pvp-mode не off. Мобов — всегда, удар о блоки — не PvP (всегда). Решение — здесь, без мира (тесты).
 */
final class PvpRule {

    enum Mode { ZONES, ALL, OFF }

    private PvpRule() {}

    /** Режим из config.yml (YAML читает off без кавычек как false). */
    static Mode mode(String s) {
        if (s == null) return Mode.ZONES;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "all", "true", "on" -> Mode.ALL;
            case "off", "false", "no" -> Mode.OFF;
            default -> Mode.ZONES;
        };
    }

    /**
     * Бить ли цель (чужой корабль или игрока): survival — есть ли модуль зон; shooterIn/targetIn — стрелок и точка
     * попадания в PvP-зоне.
     */
    static boolean fight(Mode m, boolean survival, boolean shooterIn, boolean targetIn) {
        return switch (m) {
            case OFF -> false;
            case ALL -> true;
            case ZONES -> !survival || (shooterIn && targetIn);
        };
    }

    /** Игрока: как корабль, и ещё режим боя игроков survival (pvp-mode) не off; null — survival нет. */
    static boolean fightPlayer(Mode m, String playerMode, boolean shooterIn, boolean targetIn) {
        if (playerMode != null && playerMode.equals("off")) return false;
        return fight(m, playerMode != null, shooterIn, targetIn);
    }

    /** Метка «PvP-ЗОНА» в HUD: здесь корабли могут драться (в режиме zones — корабль в зоне). */
    static boolean label(Mode m, boolean survival, boolean in) {
        return switch (m) {
            case OFF -> false;
            case ALL -> true;
            case ZONES -> survival && in;
        };
    }
}
