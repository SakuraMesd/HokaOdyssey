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
 * Конфиг — config.yml модуля (planets.&lt;id&gt;: map, x, y, z, size, round, color, atmosphere, approach, target, tx, ty, tz).
 */
public final class PlanetsModule extends OdysseyModule {

    static final String MODULE = "planets";

    record Planet(String id, String map, double x, double y, double z, double size, double round, int rgb, int atmo,
                  double approach, String target, Pos targetPos, int seed) {

        /** Расстояние от точки до поверхности скруглённого куба (отрицательное — внутри). */
        double distance(double px, double py, double pz) {
            double h = size / 2, r = Math.min(round, h), in = h - r;
            double qx = Math.abs(px - x) - in, qy = Math.abs(py - y) - in, qz = Math.abs(pz - z) - in;
            double ox = Math.max(qx, 0), oy = Math.max(qy, 0), oz = Math.max(qz, 0);
            return Math.sqrt(ox * ox + oy * oy + oz * oz) + Math.min(Math.max(qx, Math.max(qy, qz)), 0) - r;
        }
    }

    private final List<Planet> planets = new ArrayList<>();
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
        logger().info("планет: {}", planets.size());
    }

    @Override
    protected void onDisable() { if (api != null) api.off(MODULE); }

    private void loadPlanets() {
        planets.clear();
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
                    config().getInt(k + "seed", id.hashCode())));
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
                enter(p, pl);
                break;
            }
        }
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
