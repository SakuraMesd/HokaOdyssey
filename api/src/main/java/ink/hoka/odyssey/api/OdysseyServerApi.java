package ink.hoka.odyssey.api;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;

import java.util.List;

/** Общие вещи ядра, доступные модулям. */
public interface OdysseyServerApi {

    /** Основная карта maps/world (куда игроки попадают при входе, если модуль не выбрал другую). */
    InstanceContainer world();

    Pos spawn();

    /** Включённые модули по порядку включения. */
    List<ModuleDescriptor> modules();

    /** Версия ядра. */
    String version();

    /** Оператор сервера (ops.json, выдаётся /hoop). Для своих админ-команд модуля. */
    boolean isOp(net.minestom.server.entity.Player player);

    /** Карта из maps/ по имени (загружается при необходимости); пусто — такой карты нет. */
    java.util.Optional<InstanceContainer> map(String name);

    /** Имена всех карт в maps/. */
    java.util.SortedSet<String> mapNames();

    /** Имя карты, если мир — карта из maps/ (иначе пусто). */
    java.util.Optional<String> mapName(net.minestom.server.instance.Instance instance);
}
