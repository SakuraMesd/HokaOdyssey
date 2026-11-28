package ink.hoka.odyssey.survival;

import net.minestom.server.instance.Instance;

/**
 * Сервис survival для других модулей: PvP-зоны. Зона — коробка блоков на карте с именем (pvp-zones.json, команда
 * /hopvp); вне зон — значение карты по умолчанию (нет — «не PvP»). Проверка дешёвая (перебор коробок своей карты,
 * снимок без блокировок) — можно звать хоть каждый тик и из любого потока.
 * <pre>
 * PvpZones z = services().get(PvpZones.class).orElse(null);   // модуль: softdepends: [survival]
 * boolean fight = z == null || z.isPvp(instance, x, y, z);
 * </pre>
 */
public interface PvpZones {

    /** Точка в PvP-зоне (или вне зон на карте, где по умолчанию PvP). Режим боя игроков (pvp-mode) не учитывается. */
    boolean isPvp(String map, double x, double y, double z);

    /** То же по миру (не карта из maps/ — не PvP). */
    boolean isPvp(Instance inst, double x, double y, double z);

    /** Имя зоны в точке или null (вне зон). */
    String zoneAt(Instance inst, double x, double y, double z);

    /** Режим боя игрок↔игрок из config.yml survival: «all», «zones» или «off». */
    String playerMode();

    /** Бьёт ли игрок игрока здесь по режиму pvp-mode (zones — оба в PvP-зоне). */
    boolean playerPvp(Instance inst, double ax, double ay, double az, double tx, double ty, double tz);
}
