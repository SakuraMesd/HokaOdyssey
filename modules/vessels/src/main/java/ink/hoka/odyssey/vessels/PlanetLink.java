package ink.hoka.odyssey.vessels;

import net.minestom.server.entity.Player;

import java.util.function.Predicate;

/**
 * Связь с модулем planets (сервис PlanetsApi; softdepends, vessels-6). Только здесь упоминаются его классы — если
 * planets не стоит (или старый, без сервиса), класса/сервиса нет: всё ловится и значит «планет нет» (посадки и взлёта
 * с кораблём нет, корабли летают как раньше).
 */
final class PlanetLink {

    private final VesselsModule mod;

    PlanetLink(VesselsModule mod) { this.mod = mod; }

    /** Сервис или null (берём каждый раз: после reload planets это новый объект). */
    private Object svc() {
        try {
            return mod.services().get(ink.hoka.odyssey.planets.PlanetsApi.class).orElse(null);
        } catch (Throwable noModule) {
            return null;
        }
    }

    boolean present() { return svc() != null; }

    /** Планета карты map, к которой точка ближе approach + pad; null — нет. */
    LandingMath.Planet near(String map, double x, double y, double z, double pad) {
        Object s = svc();
        if (s == null || map == null) return null;
        try {
            return ((ink.hoka.odyssey.planets.PlanetsApi) s).near(map, x, y, z, pad).map(PlanetLink::of).orElse(null);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Планета, вход которой ведёт на карту map (у неё есть посадка кораблей); null — map не карта планеты. */
    LandingMath.Planet byTarget(String map) {
        Object s = svc();
        if (s == null || map == null) return null;
        try {
            return ((ink.hoka.odyssey.planets.PlanetsApi) s).byTarget(map).map(PlanetLink::of).orElse(null);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Этих игроков planets сам не переносит (null — снять). */
    void skip(Predicate<Player> who) {
        Object s = svc();
        if (s == null) return;
        try { ((ink.hoka.odyssey.planets.PlanetsApi) s).skip(who); } catch (Throwable ignored) { }
    }

    /** Затемнение экрана (клиентский модуль planets); planets нет — без затемнения. */
    void fade(Player p, int in, int hold, int out) {
        Object s = svc();
        if (s == null || p == null) return;
        try { ((ink.hoka.odyssey.planets.PlanetsApi) s).fade(p, in, hold, out); } catch (Throwable ignored) { }
    }

    private static LandingMath.Planet of(ink.hoka.odyssey.planets.PlanetsApi.Planet p) {
        var e = p.ship();
        if (e == null) e = new ink.hoka.odyssey.planets.PlanetsApi.ShipEntry(0, 0, 0, 0, 0, 1, Double.MAX_VALUE);
        return new LandingMath.Planet(p.id(), p.map(), p.x(), p.y(), p.z(), p.size(), p.round(), p.approach(), p.target(), p.star(),
                e.scatter(), e.x(), e.z(), e.entryY(), e.y(), e.descentSec(), e.exitY());
    }
}
