package ink.hoka.odyssey.planets;

import net.minestom.server.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Сервис модуля planets для других модулей (vessels-6: посадка с кораблём). Получать каждый раз через
 * {@code services().get(PlanetsApi.class)} — после {@code homodule reload planets} это новый объект.
 * <pre>
 * PlanetsApi api = services().get(PlanetsApi.class).orElse(null);
 * api.near("cosmos", x, y, z, 0)       // планета, к которой подлетели ближе approach
 * api.byTarget("hoka23")               // планета, на карту которой ведёт вход (взлёт обратно)
 * api.skip(p -> isPilot(p))            // этих игроков planets сам не переносит и не отталкивает
 * api.fade(p, 24, 50, 34)              // затемнение экрана, как при входе
 * </pre>
 */
public interface PlanetsApi {

    /**
     * Посадка корабля на карту планеты: случайная точка в круге радиуса scatter у (x, z), появление на высоте entryY,
     * спуск за descentSec секунд до y (абсолютный Y; рельеф выше — над рельефом); выше exitY на карте планеты — взлёт
     * в космос.
     */
    record ShipEntry(double scatter, double x, double z, double entryY, double y, double descentSec, double exitY) {
        public static final ShipEntry DEFAULT = new ShipEntry(2000, 0, 0, 2400, 2000, 6, 2600);
    }

    /**
     * Планета (снимок config.yml). map — карта, где висит модель; target — карта планеты (пусто — входа нет); star —
     * звезда (не впускает); ship — посадка с кораблём (null — нет target).
     */
    record Planet(String id, String map, double x, double y, double z, double size, double round, double approach,
                  String target, boolean star, ShipEntry ship) {

        /** Расстояние от точки до поверхности скруглённого куба (отрицательное — внутри). */
        public double distance(double px, double py, double pz) { return roundedBox(px - x, py - y, pz - z, size, round); }

        /** Вход есть (не звезда и есть карта планеты). */
        public boolean enterable() { return !star && target != null && !target.isEmpty(); }
    }

    /** Расстояние от точки (относительно середины) до скруглённого куба со стороной size и скруглением round. */
    static double roundedBox(double dx, double dy, double dz, double size, double round) {
        double h = size / 2, r = Math.min(round, h), in = h - r;
        double qx = Math.abs(dx) - in, qy = Math.abs(dy) - in, qz = Math.abs(dz) - in;
        double ox = Math.max(qx, 0), oy = Math.max(qy, 0), oz = Math.max(qz, 0);
        return Math.sqrt(ox * ox + oy * oy + oz * oz) + Math.min(Math.max(qx, Math.max(qy, qz)), 0) - r;
    }

    /** Все планеты. */
    List<Planet> planets();

    /** Планета карты map, к поверхности которой точка ближе approach + pad (из нескольких — ближайшая); пусто — нет. */
    Optional<Planet> near(String map, double x, double y, double z, double pad);

    /** Планета с входом, ведущим на карту map (взлёт обратно в космос); пусто — map не карта планеты. */
    Optional<Planet> byTarget(String map);

    /**
     * Игроков, для которых who истинно, planets сам не переносит и не отталкивает (пилоты кораблей и их экипаж — вход
     * с кораблём делает модуль vessels). null — снять. Один фильтр на сервер (последний поставленный).
     */
    void skip(Predicate<Player> who);

    /** Затемнение экрана, как при входе (тики): темнеет fadeIn, чёрный hold, светлеет fadeOut. fadeIn 0 — сразу чёрный. */
    void fade(Player p, int fadeIn, int hold, int fadeOut);
}
