package ink.hoka.odyssey.server.net;

import net.minestom.server.MinecraftServer;
import net.minestom.server.event.server.ServerListPingEvent;
import net.minestom.server.network.socket.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Настоящий сокет-сервер ядра (наша копия Server) на свободном порту: status-пинг с заголовком и без.
 * Адрес, который увидел Minestom, ловим в ServerListPingEvent (connection.getRemoteAddress()).
 */
class ProxyProtocolServerTest {
    static Server server;
    static final LinkedBlockingQueue<SocketAddress> seen = new LinkedBlockingQueue<>();

    @BeforeAll
    static void start() throws IOException {
        MinecraftServer.init();
        MinecraftServer.getGlobalEventHandler().addListener(ServerListPingEvent.class, e -> {
            if (e.getConnection() != null) seen.add(e.getConnection().getRemoteAddress());
        });
        server = new Server();
        server.init(new InetSocketAddress("127.0.0.1", 0));
        server.start();
    }

    @AfterAll
    static void stop() { server.stop(); ProxyProtocol.configure(null, 0); }

    @AfterEach
    void clear() { seen.clear(); }

    // ---------- клиент ----------

    static void varInt(ByteArrayOutputStream o, int v) {
        while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; }
        o.write(v);
    }

    static byte[] packet(byte[] body) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        varInt(o, body.length);
        o.writeBytes(body);
        return o.toByteArray();
    }

    /** Хендшейк (intent=status) + status request. */
    static byte[] statusPing() {
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        varInt(h, 0x00);
        varInt(h, 774);
        byte[] host = "mc.hoka.ink".getBytes(StandardCharsets.UTF_8);
        varInt(h, host.length);
        h.writeBytes(host);
        h.write(25573 >> 8); h.write(25573 & 0xFF);
        varInt(h, 1);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.writeBytes(packet(h.toByteArray()));
        all.writeBytes(packet(new byte[]{0x00}));
        return all.toByteArray();
    }

    static byte[] v2(String src, int sport) throws IOException {
        byte[] addr = ProxyHeaderTest.tcp4(src, sport, "127.0.0.1", server.getPort());
        return ProxyHeaderTest.v2(0x21, 0x11, addr);
    }

    static Socket connect() throws IOException {
        Socket s = new Socket("127.0.0.1", server.getPort());
        s.setSoTimeout(5000);
        return s;
    }

    /** Ответ статуса пришёл (JSON) — true; сервер закрыл соединение — false. */
    static boolean gotStatus(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        try {
            while (total < buf.length) {
                int n = in.read(buf, total, buf.length - total);
                if (n < 0) return false;
                total += n;
                if (new String(buf, 0, total, StandardCharsets.ISO_8859_1).contains("\"version\"")) return true;
            }
        } catch (SocketTimeoutException e) { fail("сервер молчит"); }
        catch (IOException e) { return false; }                             // reset
        return false;
    }

    static SocketAddress seenAddress() throws InterruptedException { return seen.poll(5, TimeUnit.SECONDS); }

    // ---------- случаи ----------

    @Test
    void disabledBehavesAsBefore() throws Exception {
        ProxyProtocol.configure(null, 0);
        try (Socket s = connect()) {
            s.getOutputStream().write(statusPing());
            assertTrue(gotStatus(s));
            assertEquals("127.0.0.1", ((InetSocketAddress) seenAddress()).getHostString());
        }
    }

    @Test
    void trustedV2InPiecesGivesRealIp() throws Exception {
        ProxyProtocol.configure(TrustedProxies.parse("127.0.0.1"), 2000);
        try (Socket s = connect()) {
            OutputStream out = s.getOutputStream();
            byte[] all = cat(v2("203.0.113.7", 51234), statusPing());
            for (int i = 0; i < all.length; i += 5) {                       // по 5 байт с паузой — заголовок частями
                out.write(all, i, Math.min(5, all.length - i));
                out.flush();
                Thread.sleep(3);
            }
            assertTrue(gotStatus(s));
            assertEquals(new InetSocketAddress("203.0.113.7", 51234), seenAddress());
        }
    }

    @Test
    void trustedV1OneWrite() throws Exception {
        ProxyProtocol.configure(TrustedProxies.parse("127.0.0.0/8"), 2000);
        try (Socket s = connect()) {
            // заголовок и хендшейк одним пакетом — байты после заголовка не теряются
            s.getOutputStream().write(cat("PROXY TCP4 198.51.100.22 127.0.0.1 35646 25573\r\n".getBytes(StandardCharsets.US_ASCII), statusPing()));
            assertTrue(gotStatus(s));
            assertEquals(new InetSocketAddress("198.51.100.22", 35646), seenAddress());
        }
    }

    @Test
    void trustedLocalKeepsProxyAddress() throws Exception {
        ProxyProtocol.configure(TrustedProxies.parse("127.0.0.1"), 2000);
        try (Socket s = connect()) {
            s.getOutputStream().write(cat(ProxyHeaderTest.v2(0x20, 0x00, new byte[0]), statusPing()));
            assertTrue(gotStatus(s));
            assertEquals("127.0.0.1", ((InetSocketAddress) seenAddress()).getHostString());
        }
    }

    @Test
    void trustedWithoutHeaderIsClosed() throws Exception {
        ProxyProtocol.configure(TrustedProxies.parse("127.0.0.1"), 2000);
        try (Socket s = connect()) {
            s.getOutputStream().write(statusPing());
            assertFalse(gotStatus(s));
            assertNull(seen.poll(200, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void trustedSilentTimesOut() throws Exception {
        ProxyProtocol.configure(TrustedProxies.parse("127.0.0.1"), 300);
        try (Socket s = connect()) {
            s.getOutputStream().write(ProxyHeader.V2_SIG, 0, 5);            // начал и замолчал
            long t0 = System.nanoTime();
            assertFalse(gotStatus(s));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms >= 200 && ms < 3000, "закрыт через " + ms + " мс");
        }
    }

    @Test
    void untrustedNormalStillWorks() throws Exception {
        ProxyProtocol.configure(TrustedProxies.parse("10.0.0.0/8"), 2000);  // 127.0.0.1 — не доверенный
        try (Socket s = connect()) {
            OutputStream out = s.getOutputStream();
            byte[] ping = statusPing();
            out.write(ping, 0, 1);                                          // первый байт отдельно — подсмотр 2 байт
            out.flush();
            Thread.sleep(20);
            out.write(ping, 1, ping.length - 1);
            assertTrue(gotStatus(s));
            assertEquals("127.0.0.1", ((InetSocketAddress) seenAddress()).getHostString());
        }
    }

    @Test
    void untrustedWithHeaderIsClosed() throws Exception {
        ProxyProtocol.configure(TrustedProxies.parse("10.0.0.0/8"), 2000);
        try (Socket s = connect()) {
            s.getOutputStream().write(cat(v2("1.1.1.1", 1), statusPing()));
            assertFalse(gotStatus(s));
        }
        try (Socket s = connect()) {
            s.getOutputStream().write(cat("PROXY TCP4 1.1.1.1 127.0.0.1 1 2\r\n".getBytes(StandardCharsets.US_ASCII), statusPing()));
            assertFalse(gotStatus(s));
        }
        assertNull(seen.poll(200, TimeUnit.MILLISECONDS));
    }

    static byte[] cat(byte[] a, byte[] b) { return ProxyHeaderTest.cat(a, b); }
}
