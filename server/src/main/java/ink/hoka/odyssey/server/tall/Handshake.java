package ink.hoka.odyssey.server.tall;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerPluginMessageEvent;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Рукопожатие на этапе входа (configuration), CUBIC-CHUNKS.md §3.8. Сервер шлёт {@code hoka_odyssey:tall}, ядро
 * 0.29+ отвечает {@code hoka_odyssey:hi} (строка версия ядра, bool Sodium, строка версия Sodium). Старое ядро молчит.
 * Ждём ответ до {@link #WAIT_MS} мс прямо в AsyncPlayerConfigurationEvent (событие асинхронное) — потом решение о
 * допуске на кубовые карты (этап 3). Пока только запоминаем и пишем в лог.
 */
public final class Handshake {

    private Handshake() {}

    private static final Logger LOG = LoggerFactory.getLogger("cubes");
    static final long WAIT_MS = 2000;
    /** С какой версии ядро само считает свет (небо и блоки) в своём окне: сервер свет не шлёт (CubeStreamer.clientLight). */
    public static final int[] CLIENT_LIGHT_CORE = {0, 57, 0};

    /** Что сообщил клиент. core = null — ответа не было (ядро старше 0.29 или без мода). */
    public record Hi(String core, boolean sodium, String sodiumVersion, long ms) {
        public boolean known() { return core != null; }
    }

    private static final Map<UUID, CompletableFuture<Hi>> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Hi> HI = new ConcurrentHashMap<>();

    public static Hi of(Player p) { return HI.getOrDefault(p.getUuid(), new Hi(null, false, "", -1)); }

    /** Ядро клиента не старше min (например {0, 44, 0}); ответа не было или версия не разобрана — false. */
    public static boolean coreAtLeast(Player p, int[] min) {
        Hi hi = HI.get(p.getUuid());
        if (hi == null || hi.core() == null) return false;
        return CMP.computeIfAbsent(hi.core() + "|" + java.util.Arrays.toString(min), k -> atLeast(hi.core(), min));
    }

    /** Тесты: ответ ядра без рукопожатия. */
    static void putForTest(UUID u, String core) { HI.put(u, new Hi(core, false, "", 0)); }

    /** Разобранные сравнения версий (версий ядра у игроков — единицы). */
    private static final Map<String, Boolean> CMP = new ConcurrentHashMap<>();

    /** "0.44.0", "0.44.0+1.21.11", "0.44.0-dev" ≥ min по числам (до трёх). */
    static boolean atLeast(String v, int[] min) {
        if (v == null) return false;
        String[] parts = v.split("[+\\-]", 2)[0].split("\\.");
        for (int k = 0; k < min.length; k++) {
            int n;
            try { n = k < parts.length ? Integer.parseInt(parts[k].trim()) : 0; }
            catch (NumberFormatException e) { return false; }
            if (n != min[k]) return n > min[k];
        }
        return true;
    }

    public static void register() {
        var ev = MinecraftServer.getGlobalEventHandler();
        ev.addListener(PlayerPluginMessageEvent.class, e -> {
            if (!e.getIdentifier().equals("hoka_odyssey:hi")) return;
            var f = PENDING.get(e.getPlayer().getUuid());
            try {
                NetworkBuffer b = NetworkBuffer.wrap(e.getMessage(), 0, e.getMessage().length);
                String core = b.read(NetworkBuffer.STRING);
                boolean sodium = b.read(NetworkBuffer.BOOLEAN);
                String sv = b.read(NetworkBuffer.STRING);
                Hi hi = new Hi(core, sodium, sv, 0);
                if (f != null) f.complete(hi);
                else HI.put(e.getPlayer().getUuid(), hi);                 // пришло после ожидания — всё равно запомним
            } catch (Exception ex) {
                LOG.warn("{}: битый hoka_odyssey:hi ({})", e.getPlayer().getUsername(), ex.toString());
            }
        });
        ev.addListener(PlayerDisconnectEvent.class, e -> { HI.remove(e.getPlayer().getUuid()); PENDING.remove(e.getPlayer().getUuid()); });
    }

    /** Вызывается из AsyncPlayerConfigurationEvent: сигнал «высокий мир» и ожидание ответа ядра. */
    public static void onConfiguration(AsyncPlayerConfigurationEvent e) {
        Player p = e.getPlayer();
        var f = new CompletableFuture<Hi>();
        PENDING.put(p.getUuid(), f);
        long t0 = System.nanoTime();
        p.sendPacket(new PluginMessagePacket("hoka_odyssey:tall", new byte[0]));
        Hi hi;
        try {
            Hi got = f.get(WAIT_MS, TimeUnit.MILLISECONDS);
            hi = new Hi(got.core(), got.sodium(), got.sodiumVersion(), (System.nanoTime() - t0) / 1_000_000);
        } catch (Exception timeout) {
            hi = HI.getOrDefault(p.getUuid(), new Hi(null, false, "", -1));
        } finally {
            PENDING.remove(p.getUuid());
        }
        HI.put(p.getUuid(), hi);
        if (hi.known()) LOG.info("рукопожатие {}: ядро {}, Sodium {}, ответ за {} мс", p.getUsername(), hi.core(),
                hi.sodium() ? hi.sodiumVersion() : "нет", hi.ms());
        else LOG.info("рукопожатие {}: ответа нет за {} мс (ядро старше 0.29 или без мода)", p.getUsername(), WAIT_MS);
    }
}
