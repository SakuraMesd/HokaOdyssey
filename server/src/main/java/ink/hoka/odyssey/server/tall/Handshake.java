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
 * <p>Клиент без мода не ответит никогда — раньше он ждал все 2 с. Теперь ждём не дольше {@link #AFTER_PACKS_MS} после
 * ответа на SelectKnownPacks: Minestom шлёт его ДО нашего сигнала, клиент отвечает на оба в одном проходе (порядок
 * пакетов), а сервер разбирает пакеты configuration в одном тике — hi с модом приходит вместе с ответом о паках.</p>
 */
public final class Handshake {

    private Handshake() {}

    private static final Logger LOG = LoggerFactory.getLogger("cubes");
    static final long WAIT_MS = 2000;
    /** Сколько ещё ждать hi после ответа клиента о паках (-Dhoka.handshake.after-packs-ms, 0 — ждать все WAIT_MS). */
    static final long AFTER_PACKS_MS = Long.getLong("hoka.handshake.after-packs-ms", 250);
    /** С какой версии ядро само считает свет (небо и блоки) в своём окне: сервер свет не шлёт (CubeStreamer.clientLight). */
    public static final int[] CLIENT_LIGHT_CORE = {0, 57, 0};
    /** С какой версии ядро генерирует нетронутые кубы само (op 20–23, wc op 8/9; ClientGen). */
    public static final int[] CLIENT_GEN_CORE = {0, 59, 0};
    /** Ядро 0.60: объекты генератора (пакет .hopk, хвост op 20, API 2). Старее — карты с объектами идут старым путём op 2. */
    public static final int[] OBJECTS_CORE = {0, 60, 0};
    /** Ядро 0.63.1: просит заново столбцы, которых у него нет, а сервер считает отправленными (wc op 10, CubeStreamer). */
    public static final int[] RESEND_CORE = {0, 63, 1};

    /** Что сообщил клиент. core = null — ответа не было (ядро старше 0.29 или без мода). */
    public record Hi(String core, boolean sodium, String sodiumVersion, long ms) {
        public boolean known() { return core != null; }
    }

    private static final Map<UUID, CompletableFuture<Hi>> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Hi> HI = new ConcurrentHashMap<>();
    /** Клиент ответил на SelectKnownPacks (этого прохода configuration). */
    private static final Map<UUID, CompletableFuture<Void>> PACKS = new ConcurrentHashMap<>();

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
        // ответ о паках: как у Minestom (LoginListener.selectKnownPacks) + отметка для раннего конца ожидания hi
        MinecraftServer.getPacketListenerManager().setConfigurationListener(
                net.minestom.server.network.packet.client.configuration.ClientSelectKnownPacksPacket.class, (packet, player) -> {
                    net.minestom.server.listener.preplay.LoginListener.selectKnownPacks(packet, player);
                    PACKS.computeIfAbsent(player.getUuid(), u -> new CompletableFuture<>()).complete(null);
                });
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
                // запомнить всегда: ожидание могло только что кончиться (окно после паков) — тогда будущее уже никто не
                // прочтёт, а итог onConfiguration не должен затереть ответ (см. HI.merge там)
                HI.put(e.getPlayer().getUuid(), hi);
                if (f != null) f.complete(hi);
            } catch (Exception ex) {
                LOG.warn("{}: битый hoka_odyssey:hi ({})", e.getPlayer().getUsername(), ex.toString());
            }
        });
        ev.addListener(PlayerDisconnectEvent.class, e -> { HI.remove(e.getPlayer().getUuid()); PENDING.remove(e.getPlayer().getUuid()); PACKS.remove(e.getPlayer().getUuid()); });
    }

    /** Вызывается из AsyncPlayerConfigurationEvent: сигнал «высокий мир» и ожидание ответа ядра. */
    public static void onConfiguration(AsyncPlayerConfigurationEvent e) {
        Player p = e.getPlayer();
        var f = new CompletableFuture<Hi>();
        PENDING.put(p.getUuid(), f);
        long t0 = System.nanoTime();
        p.sendPacket(new PluginMessagePacket("hoka_odyssey:tall", new byte[0]));
        // ответ о паках мог прийти раньше этого места (локальный вход) — будущее общее
        var packs = PACKS.computeIfAbsent(p.getUuid(), u -> new CompletableFuture<>());
        CompletableFuture<?> stop = AFTER_PACKS_MS <= 0 ? f
                : CompletableFuture.anyOf(f, packs.thenCompose(v -> CompletableFuture.runAsync(() -> {},
                        CompletableFuture.delayedExecutor(AFTER_PACKS_MS, TimeUnit.MILLISECONDS))));
        Hi hi;
        try {
            try { stop.get(WAIT_MS, TimeUnit.MILLISECONDS); } catch (java.util.concurrent.TimeoutException ignored) { }
            Hi got = f.getNow(null);
            hi = got != null ? new Hi(got.core(), got.sodium(), got.sodiumVersion(), (System.nanoTime() - t0) / 1_000_000)
                    : HI.getOrDefault(p.getUuid(), new Hi(null, false, "", -1));
        } catch (Exception e2) {
            hi = HI.getOrDefault(p.getUuid(), new Hi(null, false, "", -1));
        } finally {
            PENDING.remove(p.getUuid());
            PACKS.remove(p.getUuid());
        }
        long waited = (System.nanoTime() - t0) / 1_000_000;
        // ответ, пришедший сразу после конца ожидания, не затирать «ответа нет»
        hi = HI.merge(p.getUuid(), hi, (old, cur) -> cur.known() || !old.known() ? cur : old);
        if (hi.known()) LOG.info("рукопожатие {}: ядро {}, Sodium {}, ответ за {} мс", p.getUsername(), hi.core(),
                hi.sodium() ? hi.sodiumVersion() : "нет", hi.ms());
        else LOG.info("рукопожатие {}: ответа нет за {} мс (ядро старше 0.29 или без мода)", p.getUsername(), waited);
    }
}
