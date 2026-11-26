package ink.hoka.odyssey.server.net;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.network.player.PlayerSocketConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HAProxy PROXY protocol на порту ядра. Вызывается из нашей копии {@code net.minestom.server.network.socket.Server}
 * в потоке чтения соединения ДО первого пакета.
 * <ul>
 * <li>источник в списке доверенных → заголовок обязателен (нет/битый/таймаут — закрыть), адрес соединения → настоящий;</li>
 * <li>недоверенный → обычное подключение; прислал PROXY — закрыть (подмена IP).</li>
 * </ul>
 * Настройка: {@code -Dhoka.proxy.trusted=91.199.149.84,127.0.0.1} (или env HOKA_PROXY_TRUSTED; IP и CIDR),
 * {@code -Dhoka.proxy.timeout-ms=3000}. Пусто — выключено, ядро ведёт себя как без этого кода.
 */
public final class ProxyProtocol {
    private static final Logger LOG = LoggerFactory.getLogger("proxy");

    private static volatile ProxyProtocol active;          // null — выключено (одно чтение volatile на соединение)

    private final TrustedProxies trusted;
    private final long timeoutMs;
    private final Field readBufferField;
    private final ScheduledExecutorService timer;
    /** соединение → адрес прокси (для строки входа). Слабые ключи: соединение ушло — запись ушла. */
    private final Map<PlayerConnection, SocketAddress> viaProxy = Collections.synchronizedMap(new WeakHashMap<>());
    private final RateLog rejects = new RateLog(20, 60_000);

    private ProxyProtocol(TrustedProxies trusted, long timeoutMs) {
        this.trusted = trusted;
        this.timeoutMs = timeoutMs;
        // недоверенному возвращаем 2 подсмотренных байта в буфер чтения Minestom; поле проверяем при старте,
        // а не на первом игроке — после обновления Minestom упадёт сразу и понятно
        try {
            Field f = PlayerSocketConnection.class.getDeclaredField("readBuffer");
            if (f.getType() != NetworkBuffer.class) throw new NoSuchFieldException("readBuffer: тип " + f.getType());
            f.setAccessible(true);
            this.readBufferField = f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("PROXY protocol: Minestom изменился (PlayerSocketConnection.readBuffer)", e);
        }
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "proxy-header-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    /** Из настроек JVM/окружения. До server.start(). */
    public static void configureFromSystem() {
        String spec = System.getProperty("hoka.proxy.trusted");
        if (spec == null) spec = System.getenv("HOKA_PROXY_TRUSTED");
        if (spec == null) {                                              // без флага — файл в папке сервера (строки IP/CIDR, # — комментарий)
            java.nio.file.Path f = java.nio.file.Path.of("trusted-proxies.txt");
            if (java.nio.file.Files.isRegularFile(f)) {
                try {
                    StringBuilder sb = new StringBuilder();
                    for (String line : java.nio.file.Files.readAllLines(f)) {
                        int h = line.indexOf('#');
                        sb.append(h >= 0 ? line.substring(0, h) : line).append(',');
                    }
                    spec = sb.toString();
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("trusted-proxies.txt не читается: " + e, e);
                }
            }
        }
        long timeout = Long.getLong("hoka.proxy.timeout-ms", 3000L);
        configure(TrustedProxies.parse(spec), timeout);
    }

    /** Включить (непустой список) или выключить. */
    public static void configure(TrustedProxies trusted, long timeoutMs) {
        ProxyProtocol old = active;
        if (trusted == null || trusted.isEmpty()) {
            active = null;
        } else {
            active = new ProxyProtocol(trusted, Math.max(100, timeoutMs));
            LOG.info("PROXY protocol: доверенные {} (заголовок v1/v2 от них обязателен, таймаут {} мс)", trusted, active.timeoutMs);
        }
        if (old != null) old.timer.shutdownNow();
    }

    public static boolean enabled() { return active != null; }

    /** Адрес прокси, через который вошло соединение (null — напрямую). */
    public static SocketAddress proxyOf(PlayerConnection c) {
        ProxyProtocol p = active;
        return p == null ? null : p.viaProxy.get(c);
    }

    /**
     * Предисловие соединения. true — читать пакеты дальше; false — соединение уже закрыто.
     * Выключено — сразу true (ничего не читаем).
     */
    public static boolean preamble(PlayerSocketConnection conn, SocketChannel ch) {
        ProxyProtocol p = active;
        if (p == null) return true;
        if (!(conn.getRemoteAddress() instanceof InetSocketAddress peer)) return true;   // unix-сокет и т.п.
        try {
            return p.trusted.contains(peer.getAddress()) ? p.fromTrusted(conn, ch, peer) : p.fromUntrusted(conn, ch, peer);
        } catch (RuntimeException e) {                      // неожиданное — не оставлять соединение висеть
            p.reject(conn, ch, peer, "ошибка разбора: " + e);
            return false;
        }
    }

    private boolean fromTrusted(PlayerSocketConnection conn, SocketChannel ch, InetSocketAddress peer) {
        // 0 — читаем, 1 — заголовок прочитан, 2 — таймаут. CAS исключает гонку «прочитали ровно в момент таймера»
        AtomicInteger state = new AtomicInteger();
        ScheduledFuture<?> guard = timer.schedule(() -> {         // блокирующее чтение прерываем закрытием канала
            if (state.compareAndSet(0, 2)) closeQuietly(ch);
        }, timeoutMs, TimeUnit.MILLISECONDS);
        ProxyHeader.Result r;
        try {
            r = ProxyHeader.read(ch);
        } catch (IOException e) {
            guard.cancel(false);
            boolean late = !state.compareAndSet(0, 1);
            reject(conn, ch, peer, late ? "таймаут заголовка " + timeoutMs + " мс" : e.getMessage());
            return false;
        }
        guard.cancel(false);
        if (!state.compareAndSet(0, 1)) { reject(conn, ch, peer, "таймаут заголовка " + timeoutMs + " мс"); return false; }
        if (!r.local()) {
            conn.setRemoteAddress(r.source());
            viaProxy.put(conn, peer);
        }
        return true;
    }

    private boolean fromUntrusted(PlayerSocketConnection conn, SocketChannel ch, InetSocketAddress peer) {
        ByteBuffer b = ByteBuffer.allocate(2);
        try {
            // первый байт; второй ждём, только если первый похож на начало PROXY ('P' или 0x0D) —
            // обычный клиент не задерживается ни на байт
            b.limit(1);
            while (b.hasRemaining() && ch.read(b) >= 0) { }
            if (b.position() == 1 && ProxyHeader.mayStartProxy(b.get(0))) {
                b.limit(2);
                while (b.hasRemaining() && ch.read(b) >= 0) { }
            }
        } catch (IOException e) {
            closeQuietly(ch);
            conn.disconnect();
            return false;
        }
        int n = b.position();
        if (ProxyHeader.looksLikeProxy(b.array(), n)) {
            reject(conn, ch, peer, "заголовок PROXY от недоверенного источника (подмена IP?)");
            return false;
        }
        if (n > 0) {
            try {
                NetworkBuffer rb = (NetworkBuffer) readBufferField.get(conn);
                rb.write(NetworkBuffer.RAW_BYTES, java.util.Arrays.copyOf(b.array(), n));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return true;   // n < 2 — был EOF: первый read Minestom увидит его сам и отключит
    }

    private void reject(PlayerSocketConnection conn, SocketChannel ch, InetSocketAddress peer, String why) {
        closeQuietly(ch);
        conn.disconnect();
        rejects.log(LOG, "PROXY protocol: отказ {} — {}", peer, why);
    }

    private static void closeQuietly(SocketChannel ch) {
        try { ch.close(); } catch (IOException ignored) {}
    }

    /** Не больше max строк за окно; остальные — числом в начале следующего окна. */
    static final class RateLog {
        private final int max;
        private final long windowMs;
        private long windowStart;
        private int count, suppressed;

        RateLog(int max, long windowMs) { this.max = max; this.windowMs = windowMs; }

        synchronized void log(Logger log, String fmt, Object a, Object b) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= windowMs) {
                if (suppressed > 0) log.warn("PROXY protocol: ещё {} отказов скрыто", suppressed);
                windowStart = now;
                count = 0;
                suppressed = 0;
            }
            if (count++ < max) log.warn(fmt, a, b);
            else suppressed++;
        }
    }
}
