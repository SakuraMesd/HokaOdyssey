package ink.hoka.odyssey.api;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.instance.Instance;

/**
 * Куда положить выброшенный предмет (сервис, один на сервер). Ядро спрашивает его перед тем, как положить предмет в
 * мир: корабли кладут предмет, брошенный на палубу, в свою верфь — на настоящие блоки корабля.
 * <pre>services().register(DropPlacer.class, (item, inst, at, vel) -&gt; …);</pre>
 */
public interface DropPlacer {
    /** Положить предмет сам и вернуть true — или false: ядро положит его в мир в точку at со скоростью vel (блоки/с). */
    boolean place(ItemEntity item, Instance instance, Pos at, Vec velocity);

    /**
     * То же, но выбросил игрок (at — его глаза по вертикали мира − 0.3). Корабль переводит точку сам: глаза — по «верху»
     * корабля, иначе на корабле на боку предмет рождался в полу.
     */
    default boolean place(net.minestom.server.entity.Player dropper, ItemEntity item, Instance instance, Pos at, Vec velocity) {
        return place(item, instance, at, velocity);
    }
}
