package ink.hoka.odyssey.server;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.event.server.ServerTickMonitorEvent;
import net.minestom.server.network.packet.client.common.ClientPongPacket;
import net.minestom.server.network.packet.server.common.PingPacket;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Отладка: в подвале списка игроков (TAB) — пинг игрока, TPS и MSPT сервера, очередь света; раз в секунду.
 * Временно. Выключить: -Dhoka.tab.debug=false или убрать строку DebugTab.register() в OdysseyServer.
 * <p>Первая строка: «Сеть» — keepalive Minestom (клиент отвечает прямо из сетевого потока — чистая сеть + тик сервера;
 * раз в minestom.keep-alive-delay). «Игра отвечает за» — сколько ответ на наш Ping ждал главного потока клиента:
 * ядро 0.57+ меряет само и шлёт раз в секунду (wc op 7 LAG); старое ядро — оценка «пинг − сеть» (свой PingPacket раз
 * в секунду, id 0x484B____; ответ разбирается в тике, отсюда погрешность до тика).</p>
 */
public final class DebugTab {

    private DebugTab() {}

    /** По умолчанию включено (на время отладки); -Dhoka.tab.debug=false — выключить. */
    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("hoka.tab.debug", "true"));

    private static final int RING = 128, PING_TAG = 0x484B0000;
    /** Кольцо тиков: время конца (нс) и длительность (мс). Пишет и читает только поток тика. */
    private static final long[] END = new long[RING];
    private static final double[] MS = new double[RING];
    private static int n;
    /** id пинга → время отправки (нс); пинг игрока → последняя RTT (мс). */
    private static final Map<Integer, Long> SENT = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> RTT = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQ = new AtomicInteger();
    /** Задержка клиента от ядра 0.57+ (op 7 LAG): мс последняя, макс за 5 с, очередь кубов, когда пришло (нс). */
    record Lag(int last, int max5, int queue, long at) {}
    private static final Map<UUID, Lag> LAG = new ConcurrentHashMap<>();
    /** LAG старше этого — не верим (клиент перестал слать), показываем оценку. */
    static final long LAG_STALE_NS = 3_000_000_000L;

    /** C2S wc op 7 (CubeStreamer): отчёт клиента о задержке главного потока. */
    public static void clientLag(Player p, int last, int max5, int queue) {
        if (!ENABLED) return;
        LAG.put(p.getUuid(), new Lag(Math.max(0, last), Math.max(0, max5), Math.max(0, queue), System.nanoTime()));
    }

    public static void register() {
        if (!ENABLED) return;
        var ev = MinecraftServer.getGlobalEventHandler();
        ev.addListener(ServerTickMonitorEvent.class, e -> {
            int k = n++ % RING;
            END[k] = System.nanoTime();
            MS[k] = e.getTickMonitor().getTickTime();
        });
        ev.addListener(PlayerPacketEvent.class, e -> {
            if (!(e.getPacket() instanceof ClientPongPacket pong) || (pong.id() & 0xFFFF0000) != PING_TAG) return;
            Long t = SENT.remove(pong.id());
            if (t != null) RTT.put(e.getPlayer().getUuid(), (System.nanoTime() - t) / 1e6);
        });
        ev.addListener(PlayerDisconnectEvent.class, e -> { RTT.remove(e.getPlayer().getUuid()); LAG.remove(e.getPlayer().getUuid()); });
        MinecraftServer.getSchedulerManager().buildTask(DebugTab::second).repeat(TaskSchedule.tick(20)).schedule();
    }

    /** Раз в секунду (в тике): пинг каждому игроку и подвал TAB. */
    private static void second() {
        long now = System.nanoTime();
        SENT.values().removeIf(t -> now - t > 30_000_000_000L);           // ответа нет 30 с — забыть
        int ticks1s = 0;
        double sum20 = 0, max1s = 0, max5s = 0;
        int have = Math.min(n, RING), cnt20 = 0;
        for (int j = 0; j < have; j++) {
            int k = Math.floorMod(n - 1 - j, RING);
            long age = now - END[k];
            double ms = MS[k];
            if (j < 20) { sum20 += ms; cnt20++; }
            if (age <= 1_000_000_000L) { ticks1s++; max1s = Math.max(max1s, ms); }
            if (age <= 5_000_000_000L) max5s = Math.max(max5s, ms);
        }
        double tps = Math.min(20, ticks1s), avg = cnt20 == 0 ? 0 : sum20 / cnt20;
        int[] q = ink.hoka.odyssey.server.tall.CubeStreamer.lightQueued();
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            int id = PING_TAG | (SEQ.incrementAndGet() & 0xFFFF);
            SENT.put(id, System.nanoTime());
            p.sendPacket(new PingPacket(id));
            Double rtt = RTT.get(p.getUuid());
            Lag lag = LAG.get(p.getUuid());
            if (lag != null && now - lag.at() > LAG_STALE_NS) lag = null;
            Component footer = Component.text()
                    .append(netLine(p.getLatency(), rtt, lag, net.minestom.server.ServerFlag.KEEP_ALIVE_DELAY))
                    .append(Component.text(" · TPS ", NamedTextColor.GRAY))
                    .append(Component.text(String.format("%.1f", tps), tps >= 19.5 ? NamedTextColor.GREEN : tps >= 15 ? NamedTextColor.YELLOW : NamedTextColor.RED))
                    .appendNewline()
                    .append(Component.text("MSPT ", NamedTextColor.GRAY))
                    .append(Component.text(String.format("%.1f", avg), msptColor(avg)))
                    .append(Component.text(" ср / ", NamedTextColor.GRAY))
                    .append(Component.text(String.format("%.0f", max1s), msptColor(max1s)))
                    .append(Component.text(" макс 1 с / ", NamedTextColor.GRAY))
                    .append(Component.text(String.format("%.0f", max5s), msptColor(max5s)))
                    .append(Component.text(" макс 5 с", NamedTextColor.GRAY))
                    .appendNewline()
                    .append(Component.text(lightLine(q), NamedTextColor.DARK_GRAY))
                    .build();
            p.sendPlayerListHeaderAndFooter(Component.empty(), footer);
        }
    }

    /**
     * Свет: правок, очередь (по типам: кубы загрузились/выгрузились, столбцы, небо), столбцов ждут кэша, готово к
     * рассылке, загрузка потока света за секунду (растёт к 100% и очередь копится — не успевает).
     */
    private static String lightLine(int[] q) {
        StringBuilder b = new StringBuilder("свет: правок ").append(q[0]).append(", очередь ").append(q[1]);
        if (q[1] > 0) b.append(" (куб+ ").append(q[2]).append(", куб− ").append(q[3]).append(", столб ").append(q[4]).append(", небо ").append(q[5]).append(')');
        if (q[6] > 0) b.append(", ждут столбцов ").append(q[6]);
        if (q[7] > 0) b.append(", к рассылке ").append(q[7]);
        return b.append(", поток ").append(q[8] / 10).append('%').toString();
    }

    /**
     * «Сеть N мс (замер раз в K с) · игра отвечает за M мс (макс X за 5 с)». lag = null — оценка по пингу:
     * «задержка клиента ≈ пинг − сеть». keepalive 0 — ещё не мерили.
     */
    static Component netLine(int keepalive, Double rtt, Lag lag, long keepAliveDelayMs) {
        var b = Component.text()
                .append(Component.text("Сеть ", NamedTextColor.GRAY))
                .append(Component.text(keepalive <= 0 ? "—" : keepalive + " мс", pingColor(keepalive <= 0 ? -1 : keepalive)))
                .append(Component.text(" (замер раз в " + fmtSec(keepAliveDelayMs) + ")", NamedTextColor.DARK_GRAY))
                .append(Component.text(" · ", NamedTextColor.GRAY));
        if (lag != null) {
            b.append(Component.text("игра отвечает за ", NamedTextColor.GRAY))
                    .append(Component.text(lag.last() + " мс", lagColor(lag.last())))
                    .append(Component.text(" (макс ", NamedTextColor.GRAY))
                    .append(Component.text(String.valueOf(lag.max5()), lagColor(lag.max5())))
                    .append(Component.text(" за 5 с)", NamedTextColor.GRAY));
            if (lag.queue() > 0) b.append(Component.text(", очередь кубов " + lag.queue(), NamedTextColor.DARK_GRAY));
            return b.build();
        }
        if (rtt == null || keepalive <= 0) return b.append(Component.text("задержка клиента —", NamedTextColor.GRAY)).build();
        double d = Math.max(0, rtt - keepalive);
        return b.append(Component.text("задержка клиента ≈ ", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.0f мс", d), lagColor(d)))
                .append(Component.text(" (пинг " + String.format("%.0f", rtt) + " − сеть, с ожиданием тика)", NamedTextColor.DARK_GRAY))
                .build();
    }

    private static String fmtSec(long ms) { return ms % 1000 == 0 ? (ms / 1000) + " с" : String.format("%.1f с", ms / 1000.0); }

    /** Задержка главного потока клиента: до 30 мс — зелёный, до 100 — жёлтый, дальше красный. */
    static TextColor lagColor(double ms) { return ms < 30 ? NamedTextColor.GREEN : ms < 100 ? NamedTextColor.YELLOW : NamedTextColor.RED; }

    private static TextColor msptColor(double ms) { return ms < 25 ? NamedTextColor.GREEN : ms < 50 ? NamedTextColor.YELLOW : NamedTextColor.RED; }

    private static TextColor pingColor(double ms) { return ms < 0 ? NamedTextColor.GRAY : ms < 80 ? NamedTextColor.GREEN : ms < 200 ? NamedTextColor.YELLOW : NamedTextColor.RED; }
}
