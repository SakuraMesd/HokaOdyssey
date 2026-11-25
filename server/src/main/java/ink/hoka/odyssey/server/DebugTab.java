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
 * <p>Пинг — свой PingPacket раз в секунду (id 0x484B____), ответ ClientPongPacket: время туда-обратно, включая
 * ожидание тика сервера (pong разбирается в тике) — длинные тики видны в пинге. Рядом — keepalive Minestom (раз в 10 с).</p>
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
        ev.addListener(PlayerDisconnectEvent.class, e -> RTT.remove(e.getPlayer().getUuid()));
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
            Component footer = Component.text()
                    .append(Component.text("Пинг ", NamedTextColor.GRAY))
                    .append(Component.text(rtt == null ? "—" : String.format("%.0f мс", rtt), pingColor(rtt == null ? -1 : rtt)))
                    .append(Component.text(" (keepalive " + p.getLatency() + ")", NamedTextColor.DARK_GRAY))
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

    private static TextColor msptColor(double ms) { return ms < 25 ? NamedTextColor.GREEN : ms < 50 ? NamedTextColor.YELLOW : NamedTextColor.RED; }

    private static TextColor pingColor(double ms) { return ms < 0 ? NamedTextColor.GRAY : ms < 80 ? NamedTextColor.GREEN : ms < 200 ? NamedTextColor.YELLOW : NamedTextColor.RED; }
}
