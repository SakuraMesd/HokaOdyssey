package ink.hoka.odyssey.stations;

import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

import java.util.List;
import java.util.Optional;

/**
 * Сервис модуля stations для других модулей (доки, корабли…). Получить: {@code services().get(StationsApi.class)},
 * в module.yml — {@code softdepends: [stations]}. Не кэшировать навсегда: после перезагрузки stations — новый объект.
 * Регионы неизменяемые (record): держать можно, но актуальные — спрашивать заново.
 */
public interface StationsApi {

    /** Главный регион в точке карты (меньший по объёму при пересечении) или пусто. */
    Optional<Region> regionAt(String map, double x, double y, double z);

    /** То же по Instance карты (карта не из maps/ — пусто). */
    Optional<Region> regionAt(Instance instance, double x, double y, double z);

    /** Регион по id. */
    Optional<Region> region(String id);

    /** Регионы карты (главные — раньше). */
    List<Region> regions(String map);

    /** Регион, в котором игрок был при последней проверке (раз в check-period тиков), или пусто. */
    Optional<Region> current(Player p);

    /** Вход/выход игрока: from/to — регион или null (снаружи). Зовётся в тике сервера. */
    interface TransitionListener { void changed(Player p, Region from, Region to); }

    /** Подписаться (снять — {@link #removeListener}, обязательно в onDisable подписчика). */
    void addListener(TransitionListener l);

    void removeListener(TransitionListener l);
}
