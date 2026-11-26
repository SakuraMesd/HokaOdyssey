package ink.hoka.odyssey.server;

import net.kyori.adventure.text.Component;

/** Тесты из других пакетов: строка сети DebugTab. last &lt; 0 — отчёта LAG нет (старое ядро). */
public final class DebugTabAccess {
    private DebugTabAccess() {}

    public static Component line(int keepalive, Double rtt, int last, int max5, int queue, long delayMs) {
        DebugTab.Lag lag = last < 0 ? null : new DebugTab.Lag(last, max5, queue, System.nanoTime());
        return DebugTab.netLine(keepalive, rtt, lag, delayMs);
    }
}
