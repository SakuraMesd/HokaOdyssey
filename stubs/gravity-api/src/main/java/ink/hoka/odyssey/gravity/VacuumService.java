package ink.hoka.odyssey.gravity;

import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

/**
 * ЗАГЛУШКА: сигнатуры сервиса модуля gravity (tearsmilk, 0.4.0), сняты javap с jar на сервере. Тела — пустышки:
 * в рантайме этот класс берётся из gravity.jar (загрузчик модулей отдаёт классы модулей из softdepends), а этот
 * файл в jar stations не попадает. Не менять сигнатуры без сверки с gravity.jar.
 */
public interface VacuumService {

    void override(Player p, Mode m);

    Mode override(Player p);

    default void force(Player p, boolean b) {}

    boolean inVacuum(Player p);

    void push(Player p, double x, double y, double z);

    default void recoil(Player p, double x, double y, double z, boolean b) {}

    default void expectRecoil(Player p, double d) {}

    void zoneProvider(ZoneProvider provider);

    void removeZoneProvider(ZoneProvider provider);

    static boolean itemInVacuum(boolean b, Mode m) { return false; }

    enum Mode { MAP, VACUUM, NORMAL }

    interface ZoneLookup { Mode modeAt(double x, double y, double z); }

    interface ZoneProvider {
        ZoneLookup zones(Instance instance);

        default void released(ItemEntity item) {}
    }
}
