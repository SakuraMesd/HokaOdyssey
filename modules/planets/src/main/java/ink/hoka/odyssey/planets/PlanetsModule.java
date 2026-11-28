package ink.hoka.odyssey.planets;

import ink.hoka.odyssey.api.OdysseyModule;
import ink.hoka.odyssey.clientmods.ClientModsApi;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Планеты. Издали планета — модель (скруглённый куб, рисует клиентский модуль planets), из блоков её не строим и
 * построек на ней не показываем. Подлетел к поверхности ближе {@code approach} блоков (по расстоянию до скруглённого
 * куба) — вход: экран темнеет, игрок (и пилот без корабля) переносится на карту планеты, экран светлеет. Это и есть
 * «невидимый барьер»: к планете ближе не подлететь, но корабль не упирается в стену — вход срабатывает раньше.
 * Конфиг — config.yml модуля (planets.&lt;id&gt;: map, x, y, z, size, round, color, atmosphere, approach, target, tx, ty, tz,
 * type: terra | desert | ice | lava | gas | rock | star). Звезда (star) светит планетам карты и не впускает — отбрасывает.
 * <p>
 * 0.3 (vessels-6): у планеты с target — посадка корабля (ship-scatter, ship-x, ship-z, ship-entry-y, ship-y,
 * ship-descent, ship-exit-y). Сам корабль переносит модуль vessels через сервис {@link PlanetsApi}: пилотов и экипаж
 * (фильтр {@link PlanetsApi#skip}) planets не трогает. Без vessels — всё как раньше.
 */
public final class PlanetsModule extends OdysseyModule implements PlanetsApi {

    static final String MODULE = "planets";

    record Planet(String id, String map, double x, double y, double z, double size, double round, int rgb, int atmo,
                  double approach, String target, Pos targetPos, int seed, String type, ShipEntry ship) {

        boolean star() { return "star".equals(type); }

        /** Расстояние от точки до поверхности скруглённого куба (отрицательное — внутри). */
        double distance(double px, double py, double pz) { return PlanetsApi.roundedBox(px - x, py - y, pz - z, size, round); }

        /** Для сервиса. */
        PlanetsApi.Planet api() { return new PlanetsApi.Planet(id, map, x, y, z, size, round, approach, target, star(), ship); }
    }

    private volatile List<Planet> planets = List.of();
    /** Пилоты кораблей и экипаж — их переносит vessels ({@link PlanetsApi#skip}). */
    private volatile java.util.function.Predicate<Player> skip;
    private ClientModsApi api;
    private final Set<UUID> entering = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> lastMap = new ConcurrentHashMap<>();
    private static final int FADE_IN = 24, HOLD = 50, FADE_OUT = 34;   // тики: темнеет ~1.2 с, чёрный 2.5 с (за ним — загрузка мира), светлеет ~1.7 с

    @Override
    protected void onEnable() {
        loadPlanets();
        api = services().get(ClientModsApi.class).orElse(null);
        if (api == null) { logger().warn("нет clientmods — планеты не видны и вход не затемняется"); }
        else api.onReady(MODULE, this::sendList);
        events().addListener(PlayerSpawnEvent.class, e -> sendList(e.getPlayer()));   // сменил карту — свои планеты
        events().addListener(PlayerDisconnectEvent.class, e -> { entering.remove(e.getPlayer().getUuid()); lastMap.remove(e.getPlayer().getUuid()); });
        scheduler().repeat(this::tick, 20, 2);
        services().register(PlanetsApi.class, this);
        logger().info("планет: {}", planets.size());
    }

    @Override
    protected void onDisable() {
        skip = null;
        if (api != null) api.off(MODULE);
    }

    private void loadPlanets() {
        List<Planet> planets = new ArrayList<>();
        for (String id : config().keys("planets")) {
            String k = "planets." + id + ".";
            String color = config().getString(k + "color", "#4a8f4f").replace("#", "");
            String atmo = config().getString(k + "atmosphere", "#4080c0ff").replace("#", "");
            int argb = atmo.length() == 8 ? (int) Long.parseLong(atmo.substring(6) + atmo.substring(0, 6), 16) : 0x40000000 | Integer.parseInt(atmo, 16);
            planets.add(new Planet(id, config().getString(k + "map", "cubes"),
                    config().getDouble(k + "x", 0), config().getDouble(k + "y", 0), config().getDouble(k + "z", 0),
                    config().getDouble(k + "size", 512), config().getDouble(k + "round", 64),
                    Integer.parseInt(color, 16), argb, config().getDouble(k + "approach", 50),
                    config().getString(k + "target", ""),
                    new Pos(config().getDouble(k + "tx", 0.5), config().getDouble(k + "ty", 41), config().getDouble(k + "tz", 0.5)),
                    config().getInt(k + "seed", id.hashCode()),
                    config().getString(k + "type", "terra").toLowerCase(), ship(id, k)));
        }
        this.planets = List.copyOf(planets);
    }

    /** Посадка корабля (vessels-6) — только у планеты с target; высоты проверяет и урезает vessels (по высоте карты). */
    private ShipEntry ship(String id, String k) {
        if (config().getString(k + "target", "").isEmpty()) return null;
        ShipEntry d = ShipEntry.DEFAULT;
        ShipEntry e = new ShipEntry(Math.max(0, config().getDouble(k + "ship-scatter", d.scatter())),
                config().getDouble(k + "ship-x", d.x()), config().getDouble(k + "ship-z", d.z()),
                config().getDouble(k + "ship-entry-y", d.entryY()), config().getDouble(k + "ship-y", d.y()),
                Math.max(1, config().getDouble(k + "ship-descent", d.descentSec())), config().getDouble(k + "ship-exit-y", d.exitY()));
        if (e.entryY() < e.y()) logger().warn("планета {}: ship-entry-y {} ниже ship-y {} — корабль появится на ship-y", id, e.entryY(), e.y());
        return e;
    }

    // ───────────────────────── сервис PlanetsApi ─────────────────────────

    @Override
    public List<PlanetsApi.Planet> planets() { return planets.stream().map(Planet::api).toList(); }

    @Override
    public java.util.Optional<PlanetsApi.Planet> near(String map, double x, double y, double z, double pad) {
        Planet best = null;
        double bd = Double.MAX_VALUE;
        for (Planet pl : planets) {
            if (!pl.map.equals(map)) continue;
            double d = pl.distance(x, y, z);
            if (d <= pl.approach + pad && d < bd) { bd = d; best = pl; }
        }
        return java.util.Optional.ofNullable(best).map(Planet::api);
    }

    @Override
    public java.util.Optional<PlanetsApi.Planet> byTarget(String map) {
        for (Planet pl : planets) if (pl.ship != null && !pl.star() && pl.target.equals(map)) return java.util.Optional.of(pl.api());
        return java.util.Optional.empty();
    }

    @Override
    public void skip(java.util.function.Predicate<Player> who) { skip = who; }

    @Override
    public void fade(Player p, int fadeIn, int hold, int fadeOut) {
        send(p, out -> { out.writeByte(2); out.writeInt(Math.max(0, fadeIn)); out.writeInt(Math.max(0, hold)); out.writeInt(Math.max(0, fadeOut)); });
    }

    /** Игрока ведёт другой модуль (пилот корабля, экипаж) — фильтр упал — не ведёт. */
    private boolean skipped(Player p) {
        var s = skip;
        if (s == null) return false;
        try {
            return s.test(p);
        } catch (RuntimeException | LinkageError e) {
            logger().warn("фильтр пилотов (vessels) упал: {} — снят", e.toString());
            skip = null;
            return false;
        }
    }

    private String mapOf(Player p) { return p.getInstance() == null ? "" : server().mapName(p.getInstance()).orElse(""); }

    private void sendList(Player p) {
        if (api == null || !api.isReady(p, MODULE)) return;
        String map = mapOf(p);
        lastMap.put(p.getUuid(), map);
        List<Planet> here = planets.stream().filter(pl -> pl.map.equals(map)).toList();
        send(p, out -> {
            out.writeByte(1);
            out.writeInt(here.size());
            for (Planet pl : here) {
                out.writeDouble(pl.x); out.writeDouble(pl.y); out.writeDouble(pl.z);
                out.writeFloat((float) pl.size); out.writeFloat((float) pl.round);
                out.writeInt(pl.rgb); out.writeInt(pl.atmo); out.writeInt(pl.seed);
            }
            for (Planet pl : here) out.writeUTF(pl.type);                       // 0.3: вид планеты (после списка — старые клиенты не ломаются)
        });
    }

    private void tick() {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (entering.contains(p.getUuid()) || p.getInstance() == null) continue;
            String map = mapOf(p);
            if (!map.equals(lastMap.get(p.getUuid()))) sendList(p);           // карта сменилась без respawn-события
            var pos = p.getPosition();
            for (Planet pl : planets) {
                if (!pl.map.equals(map)) continue;
                if (pl.distance(pos.x(), pos.y() + 1, pos.z()) > pl.approach) continue;
                if (skipped(p)) break;                                          // пилот корабля / экипаж — ведёт vessels
                if (pl.star()) { push(p, pl, "Слишком жарко — щиты не выдержат!"); break; }
                if (pl.target.isEmpty()) { push(p, pl, "Посадка на эту планету пока недоступна"); break; }
                enter(p, pl);
                break;
            }
        }
    }

    /** Звезда / планета без карты: войти нельзя — отбрасывает назад (и корабль с пилотом), сообщение. */
    private void push(Player p, Planet pl, String msg) {
        var pos = p.getPosition();
        var dir = new net.minestom.server.coordinate.Vec(pos.x() - pl.x, pos.y() - pl.y, pos.z() - pl.z).normalize();
        var back = pos.add(dir.mul(pl.approach * 0.6 + 20));
        var v = p.getVehicle();
        if (v != null) v.teleport(back); else p.teleport(back);
        p.sendActionBar(net.kyori.adventure.text.Component.text(msg, net.kyori.adventure.text.format.NamedTextColor.GOLD));
    }

    /** Вход в планету: затемнение → перенос на карту планеты (в момент полной темноты) → просветление. */
    private void enter(Player p, Planet pl) {
        entering.add(p.getUuid());
        send(p, out -> { out.writeByte(2); out.writeInt(FADE_IN); out.writeInt(HOLD); out.writeInt(FADE_OUT); });
        scheduler().later(() -> {
            // 0.61: «вход» держим до конца переноса: первая загрузка карты планеты долгая, и тик успевал позвать
            // setInstance второй раз — Minestom падал «The dimension needs to be different than the current one!»
            java.util.concurrent.CompletableFuture<Void> move = java.util.concurrent.CompletableFuture.completedFuture(null);
            try {
                if (!p.isOnline()) return;
                var target = pl.target.isEmpty() ? java.util.Optional.<net.minestom.server.instance.InstanceContainer>empty() : server().map(pl.target);
                if (p.getVehicle() != null) p.getVehicle().removePassenger(p);    // из кресла корабля — корабль остаётся на орбите
                if (target.isPresent()) {
                    move = p.getInstance() == target.get() ? p.teleport(pl.targetPos) : p.setInstance(target.get(), pl.targetPos);
                } else {
                    logger().warn("планета {}: карта «{}» не найдена — просто отталкиваем", pl.id, pl.target);
                    var pos = p.getPosition();
                    var dir = new net.minestom.server.coordinate.Vec(pos.x() - pl.x, pos.y() - pl.y, pos.z() - pl.z).normalize();
                    p.teleport(pos.add(dir.mul(pl.approach)));
                }
                logger().info("{} вошёл в планету {}", p.getUsername(), pl.id);
            } finally {
                move.whenComplete((v, e) -> scheduler().later(() -> entering.remove(p.getUuid()), HOLD + FADE_OUT + 40));
            }
        }, FADE_IN + 4);
    }

    private interface Writer { void write(DataOutputStream out) throws java.io.IOException; }

    private void send(Player p, Writer w) {
        if (api == null || !api.isReady(p, MODULE)) return;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) { w.write(out); } catch (java.io.IOException e) { return; }
        api.send(p, MODULE, b.toByteArray());
    }
}
