package ink.hoka.odyssey.sky;

import ink.hoka.odyssey.api.OdysseyModule;
import ink.hoka.odyssey.clientmods.ClientModsApi;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Небо карты. Раздел {@code sky:} в maps/&lt;карта&gt;/map.yml сервер не толкует — разворачивает в пары
 * «ключ.через.точку → строка» и шлёт клиентскому модулю sky при входе на карту (и при смене карты). Нет раздела
 * (или {@code sky: off}) — пустой список: у игрока ванильное небо. /hosky reload — перечитать и разослать всем (OP).
 * <pre>
 * S2C: 1 SKY  int n; n × (UTF key, UTF value)   — n = 0: ванильное небо
 * </pre>
 */
public final class SkyModule extends OdysseyModule {

    static final String MODULE = "sky";

    private ClientModsApi api;
    /** Карта, чьё небо игрок уже получил. */
    private final Map<UUID, String> sent = new ConcurrentHashMap<>();

    @Override
    protected void onEnable() {
        api = services().get(ClientModsApi.class).orElse(null);
        if (api == null) { logger().warn("нет clientmods — своё небо не видно"); return; }
        api.onReady(MODULE, p -> send(p, true));
        events().addListener(PlayerSpawnEvent.class, e -> send(e.getPlayer(), false));
        events().addListener(PlayerDisconnectEvent.class, e -> sent.remove(e.getPlayer().getUuid()));
        scheduler().repeat(() -> {                                              // карта сменилась без respawn-события
            for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) send(p, false);
        }, 20, 20);

        Command cmd = new Command("hosky");
        cmd.setDefaultExecutor((s, c) -> {
            if (s instanceof Player p && !server().isOp(p)) { s.sendMessage(Component.text("Только для OP")); return; }
            sent.clear();
            int n = 0;
            for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) if (send(p, true)) n++;
            s.sendMessage(Component.text("Небо перечитано из map.yml, разослано: " + n));
        });
        registerCommand(cmd);
    }

    @Override
    protected void onDisable() { if (api != null) api.off(MODULE); }

    /** Послать небо карты игрока (force — даже если эту карту он уже получил). true — отправлено. */
    private boolean send(Player p, boolean force) {
        if (api == null || p.getInstance() == null || !api.isReady(p, MODULE)) return false;
        String map = server().mapName(p.getInstance()).orElse("");
        if (!force && map.equals(sent.get(p.getUuid()))) return false;
        sent.put(p.getUuid(), map);
        Map<String, String> kv = new LinkedHashMap<>();
        try {
            server().mapConfig(map).ifPresent(cfg -> {
                Object sky = cfg.get("sky");
                if (sky instanceof Map<?, ?> m) flatten("", m, kv);
                else if (sky instanceof Boolean b && b) kv.put("enabled", "true");   // sky: true — всё по умолчанию
                else if (sky instanceof String s && !s.isBlank() && !s.equals("off")) kv.put("preset", s);   // sky: hoka
            });
        } catch (RuntimeException e) {
            logger().warn("небо карты {}: {}", map, e.getMessage());
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) {
            out.writeByte(1);
            out.writeInt(kv.size());
            for (var e : kv.entrySet()) { out.writeUTF(e.getKey()); out.writeUTF(e.getValue()); }
        } catch (java.io.IOException e) { return false; }
        api.send(p, MODULE, b.toByteArray());
        return true;
    }

    /** Раздел YAML → плоские пары «a.b.c → строка» (списки — через запятую). */
    private static void flatten(String prefix, Map<?, ?> m, Map<String, String> out) {
        for (var e : m.entrySet()) {
            String k = prefix + e.getKey();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> sub) flatten(k + ".", sub, out);
            else if (v instanceof List<?> l) out.put(k, String.join(",", l.stream().map(String::valueOf).toList()));
            else if (v != null) out.put(k, String.valueOf(v));
        }
    }
}
