package ink.hoka.odyssey.stations;

import ink.hoka.odyssey.gravity.VacuumService;
import net.minestom.server.instance.Instance;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * Гравитация станций через сервис чужого модуля gravity ({@link VacuumService#zoneProvider}). Единственный класс,
 * который ссылается на VacuumService: модуль грузит его, только если класс виден (gravity включён — его jar в
 * загрузчике классов stations через softdepends). Так без gravity stations работает, просто без гравитации.
 * <p>
 * Провайдер на карту отдаёт {@link VacuumService.ZoneLookup}: в регионе — его гравитация (normal → NORMAL,
 * vacuum → VACUUM, map → MAP), снаружи — MAP («как у карты»). Поиск — по неизменяемому индексу, без аллокаций.
 */
final class GravityBridge {

    private final VacuumService service;
    private final VacuumService.ZoneProvider provider;
    private final Regions regions;
    private final Function<Instance, String> mapName;
    /** Поиск по карте на Instance (слабые ключи — выгруженная карта не держится в памяти). */
    private final Map<Instance, Lookup> lookups = Collections.synchronizedMap(new WeakHashMap<>());

    GravityBridge(VacuumService service, Regions regions, Function<Instance, String> mapName) {
        this.service = service;
        this.regions = regions;
        this.mapName = mapName;
        this.provider = this::zones;
    }

    /** Сервис gravity из реестра (класс у нас и у gravity один — из gravity.jar) или null. */
    static GravityBridge attach(ink.hoka.odyssey.api.Services services, Regions regions, Function<Instance, String> mapName) {
        VacuumService s = services.get(VacuumService.class).orElse(null);
        if (s == null) return null;
        GravityBridge b = new GravityBridge(s, regions, mapName);
        s.zoneProvider(b.provider);
        return b;
    }

    void detach() {
        try { service.removeZoneProvider(provider); } finally { lookups.clear(); }
    }

    VacuumService.ZoneProvider provider() { return provider; }

    /** Не в вакууме ли игрок по мнению gravity (для сообщений). */
    boolean inVacuum(net.minestom.server.entity.Player p) {
        try { return service.inVacuum(p); } catch (RuntimeException e) { return false; }
    }

    VacuumService.ZoneLookup zones(Instance instance) {
        Lookup l = lookups.get(instance);
        if (l == null) {
            l = new Lookup(instance == null ? null : mapName.apply(instance));
            if (instance != null) lookups.put(instance, l);
        }
        return l;
    }

    /** Поиск по имени карты (для тестов и отладки). */
    VacuumService.ZoneLookup zonesForMap(String map) { return new Lookup(map); }

    static VacuumService.Mode mode(Grav g) {
        return switch (g) {
            case NORMAL -> VacuumService.Mode.NORMAL;
            case VACUUM -> VacuumService.Mode.VACUUM;
            case MAP -> VacuumService.Mode.MAP;
        };
    }

    /** Поиск по одной карте: держит индекс карты и пересобирает ссылку, когда регионы поменялись. */
    private final class Lookup implements VacuumService.ZoneLookup {
        private final String map;
        private volatile int ver = -1;
        private volatile RegionIndex.MapIndex idx;

        Lookup(String map) { this.map = map; }

        @Override
        public VacuumService.Mode modeAt(double x, double y, double z) {
            if (map == null) return VacuumService.Mode.MAP;
            RegionIndex.MapIndex mi = idx;
            int v = regions.version();
            if (mi == null || ver != v) { mi = regions.index().map(map); idx = mi; ver = v; }
            Region r = mi.at(x, y, z);
            return r == null ? VacuumService.Mode.MAP : mode(r.gravity());
        }
    }
}
