package ink.hoka.odyssey.server;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.instance.AddEntityToInstanceEvent;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerLoadedEvent;
import net.minestom.server.event.player.PlayerSkinInitEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.timer.TaskSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Одна строка в лог на вход: «вход ник: авторизация→мир N мс (этапы: …)». Время — от AsyncPlayerPreLoginEvent (сразу
 * после проверки аккаунта Mojang) до PlayerLoadedEvent (клиент закрыл экран загрузки мира). Этапы — накопительно, мс:
 * конфиг (начало configuration), рукопожатие (ответ ядра hoka_odyssey:hi), play (клиент подтвердил конец
 * configuration, Minestom шлёт JoinGame), столбцы (дождались загрузки столбцов вокруг точки появления), появление
 * (PlayerSpawnEvent), мир, и отметки модулей ({@code joinStage}: «модули» — клиентские модули загружены, «axiom»).
 * Строка печатается через 500 мс после «мира» (и «модулей», если у игрока мод) — или через 15 с после play.
 */
final class JoinTrace {

    private JoinTrace() {}

    private static final Logger LOG = LoggerFactory.getLogger("odyssey");
    private static final long PRINT_DELAY_MS = 500, GIVE_UP_MS = 15_000;

    private static final class T {
        final long t0 = System.nanoTime();
        final List<String> names = new ArrayList<>();
        final List<Long> at = new ArrayList<>();
        boolean world, mods, needMods, scheduled, printed;
    }

    private static final Map<UUID, T> TRACES = new ConcurrentHashMap<>();
    private static final Map<net.minestom.server.network.player.PlayerConnection, T> AUTH = new ConcurrentHashMap<>();

    static void register(GlobalEventHandler ev) {
        // UUID в профиле ещё могут поменять обработчики входа — до configuration ключ — соединение
        ev.addListener(AsyncPlayerPreLoginEvent.class, e -> AUTH.put(e.getConnection(), new T()));
        ev.addListener(AsyncPlayerConfigurationEvent.class, e -> {
            if (!e.isFirstConfig()) return;
            T t = AUTH.remove(e.getPlayer().getPlayerConnection());
            if (t == null) return;
            TRACES.put(e.getPlayer().getUuid(), t);
            mark(e.getPlayer(), "конфиг");
        });
        ev.addListener(PlayerSkinInitEvent.class, e -> {
            mark(e.getPlayer(), "play");
            MinecraftServer.getSchedulerManager().buildTask(() -> print(e.getPlayer()))
                    .delay(TaskSchedule.millis(GIVE_UP_MS)).schedule();
        });
        ev.addListener(AddEntityToInstanceEvent.class, e -> { if (e.getEntity() instanceof Player p) mark(p, "столбцы"); });
        ev.addListener(PlayerSpawnEvent.class, e -> { if (e.isFirstSpawn()) mark(e.getPlayer(), "появление"); });
        ev.addListener(PlayerLoadedEvent.class, e -> {
            T t = TRACES.get(e.getPlayer().getUuid());
            if (t == null) return;
            mark(e.getPlayer(), "мир");
            synchronized (t) { t.world = true; }
            maybeSchedule(e.getPlayer(), t);
        });
        ev.addListener(PlayerDisconnectEvent.class, e -> {
            TRACES.remove(e.getPlayer().getUuid());
            AUTH.remove(e.getPlayer().getPlayerConnection());
        });
        // отвалился до configuration (PlayerDisconnectEvent тогда не приходит) — не копить
        MinecraftServer.getSchedulerManager().buildTask(() -> AUTH.values().removeIf(t -> System.nanoTime() - t.t0 > 60_000_000_000L))
                .repeat(TaskSchedule.seconds(30)).schedule();
    }

    /** Ядро мода ответило на рукопожатие — ждать в строке ещё и «модули». */
    static void expectMods(Player p) {
        T t = TRACES.get(p.getUuid());
        if (t != null) synchronized (t) { t.needMods = true; }
    }

    static void mark(Player p, String stage) {
        T t = TRACES.get(p.getUuid());
        if (t == null) return;
        long now = System.nanoTime();
        synchronized (t) {
            if (t.printed || t.names.contains(stage)) return;              // повторные (смена мира, вторая отметка) — мимо
            t.names.add(stage);
            t.at.add(now);
            if ("модули".equals(stage)) t.mods = true;
        }
        if ("модули".equals(stage)) maybeSchedule(p, t);
    }

    private static void maybeSchedule(Player p, T t) {
        synchronized (t) {
            if (t.scheduled || !t.world || (t.needMods && !t.mods)) return;
            t.scheduled = true;
        }
        MinecraftServer.getSchedulerManager().buildTask(() -> print(p))
                .delay(TaskSchedule.millis(PRINT_DELAY_MS)).schedule();
    }

    private static void print(Player p) {
        T t = TRACES.get(p.getUuid());
        if (t == null) return;
        StringBuilder sb = new StringBuilder();
        long world = -1;
        synchronized (t) {
            if (t.printed) return;
            t.printed = true;
            for (int i = 0; i < t.names.size(); i++) {
                long ms = (t.at.get(i) - t.t0) / 1_000_000;
                if ("мир".equals(t.names.get(i))) world = ms;
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(t.names.get(i)).append(' ').append(ms);
            }
        }
        TRACES.remove(p.getUuid());
        if (world >= 0) LOG.info("вход {}: авторизация→мир {} мс (этапы: {})", p.getUsername(), world, sb);
        else LOG.info("вход {}: авторизация→мир — клиент не сообщил о загрузке мира за {} с (этапы: {})", p.getUsername(),
                GIVE_UP_MS / 1000, sb);
    }
}
