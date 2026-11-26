package ink.hoka.odyssey.server.net;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Парсер PROXY v1/v2: части, мусор, TLV, IPv6; главное — не съесть ни байта после заголовка. */
class ProxyHeaderTest {

    /** Канал из массива; отдаёт не больше chunk байт за read (частичные пакеты). */
    static final class Chunked implements ReadableByteChannel {
        final byte[] data; final int chunk; int pos;
        Chunked(byte[] data, int chunk) { this.data = data; this.chunk = chunk; }
        @Override public int read(ByteBuffer dst) {
            if (pos >= data.length) return -1;
            int n = Math.min(Math.min(chunk, dst.remaining()), data.length - pos);
            dst.put(data, pos, n);
            pos += n;
            return n;
        }
        @Override public boolean isOpen() { return true; }
        @Override public void close() {}
    }

    static final byte[] TAIL = {0x10, 0x00, (byte) 0xFD, 0x05};       // начало MC-хендшейка — должно остаться

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    static byte[] v2(int verCmd, int famProto, byte[] body) {
        byte[] h = Arrays.copyOf(ProxyHeader.V2_SIG, 16);
        h[12] = (byte) verCmd; h[13] = (byte) famProto;
        h[14] = (byte) (body.length >> 8); h[15] = (byte) body.length;
        return cat(h, body);
    }

    static byte[] tcp4(String src, int sp, String dst, int dp) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(12);
        b.put(InetAddress.getByName(src).getAddress()).put(InetAddress.getByName(dst).getAddress());
        b.putShort((short) sp).putShort((short) dp);
        return b.array();
    }

    static ProxyHeader.Result parseAndCheckTail(byte[] header, int chunk) throws IOException {
        Chunked ch = new Chunked(cat(header, TAIL), chunk);
        ProxyHeader.Result r = ProxyHeader.read(ch);
        assertEquals(header.length, ch.pos, "прочитан ровно заголовок");
        return r;
    }

    static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    @Test
    void v2Tcp4AnyChunking() throws IOException {
        byte[] h = v2(0x21, 0x11, tcp4("203.0.113.7", 51234, "2.152.87.39", 25573));
        for (int chunk : new int[]{1, 2, 3, 7, 16, 1000}) {
            ProxyHeader.Result r = parseAndCheckTail(h, chunk);
            assertEquals(2, r.version());
            assertFalse(r.local());
            assertEquals(new InetSocketAddress("203.0.113.7", 51234), r.source());
            assertEquals(new InetSocketAddress("2.152.87.39", 25573), r.destination());
        }
    }

    @Test
    void v2Tcp6WithBigTlv() throws IOException {
        ByteBuffer b = ByteBuffer.allocate(36 + 3 + 3000);
        b.put(InetAddress.getByName("2001:db8::42").getAddress()).put(InetAddress.getByName("2001:db8::1").getAddress());
        b.putShort((short) 40000).putShort((short) 25573);
        b.put((byte) 0x04).putShort((short) 3000).put(new byte[3000]);    // TLV NOOP 3000 байт — пропустить
        ProxyHeader.Result r = parseAndCheckTail(v2(0x21, 0x21, b.array()), 5);
        assertEquals(new InetSocketAddress("2001:db8::42", 40000), r.source());
    }

    @Test
    void v2LocalAndOddFamilies() throws IOException {
        assertTrue(parseAndCheckTail(v2(0x20, 0x00, new byte[0]), 1).local());                       // LOCAL
        assertTrue(parseAndCheckTail(v2(0x20, 0x11, tcp4("1.2.3.4", 1, "5.6.7.8", 2)), 1).local()); // LOCAL c адресами
        assertTrue(parseAndCheckTail(v2(0x21, 0x00, new byte[0]), 1).local());                       // UNSPEC
        assertTrue(parseAndCheckTail(v2(0x21, 0x12, tcp4("1.2.3.4", 1, "5.6.7.8", 2)), 1).local()); // UDP4
        assertTrue(parseAndCheckTail(v2(0x21, 0x31, new byte[216]), 1).local());                     // UNIX
        assertNull(parseAndCheckTail(v2(0x21, 0x00, new byte[0]), 1).source());
    }

    @Test
    void v2Garbage() throws IOException {
        assertThrows(ProxyHeader.ProxyException.class, () -> ProxyHeader.read(new Chunked(v2(0x11, 0x11, tcp4("1.2.3.4", 1, "5.6.7.8", 2)), 3))); // версия 1
        assertThrows(ProxyHeader.ProxyException.class, () -> ProxyHeader.read(new Chunked(v2(0x22, 0x11, tcp4("1.2.3.4", 1, "5.6.7.8", 2)), 3))); // команда 2
        assertThrows(ProxyHeader.ProxyException.class, () -> ProxyHeader.read(new Chunked(v2(0x21, 0x11, new byte[8]), 3)));   // мало для TCP4
        assertThrows(ProxyHeader.ProxyException.class, () -> ProxyHeader.read(new Chunked(v2(0x21, 0x21, new byte[20]), 3)));  // мало для TCP6
        assertThrows(ProxyHeader.ProxyException.class, () -> ProxyHeader.read(new Chunked(v2(0x21, 0x41, new byte[0]), 3)));   // семейство 4
        assertThrows(ProxyHeader.ProxyException.class, () -> ProxyHeader.read(new Chunked(v2(0x21, 0x11, new byte[ProxyHeader.V2_MAX_LEN + 1]), 100))); // лимит
        byte[] badSig = v2(0x21, 0x11, tcp4("1.2.3.4", 1, "5.6.7.8", 2));
        badSig[10] = 'X';
        assertThrows(ProxyHeader.ProxyException.class, () -> ProxyHeader.read(new Chunked(badSig, 1)));
        // обрыв посреди заголовка
        byte[] full = v2(0x21, 0x11, tcp4("1.2.3.4", 1, "5.6.7.8", 2));
        assertThrows(EOFException.class, () -> ProxyHeader.read(new Chunked(Arrays.copyOf(full, 20), 4)));
    }

    @Test
    void v1Tcp4Tcp6Unknown() throws IOException {
        ProxyHeader.Result r = parseAndCheckTail(ascii("PROXY TCP4 198.51.100.22 2.152.87.39 35646 25573\r\n"), 1);
        assertEquals(1, r.version());
        assertEquals(new InetSocketAddress("198.51.100.22", 35646), r.source());
        r = parseAndCheckTail(ascii("PROXY TCP6 2001:db8::42 ::1 40000 25573\r\n"), 3);
        assertEquals(new InetSocketAddress("2001:db8::42", 40000), r.source());
        assertTrue(parseAndCheckTail(ascii("PROXY UNKNOWN\r\n"), 2).local());
        assertTrue(parseAndCheckTail(ascii("PROXY UNKNOWN ffff:f...f:ffff ffff:f...f:ffff 65535 65535\r\n"), 2).local());
        // самая длинная допустимая строка (107 байт)
        String f6 = "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff";
        assertEquals(65535, parseAndCheckTail(ascii("PROXY TCP6 " + f6 + " " + f6 + " 65535 65535\r\n"), 1).source().getPort());
        String max = "PROXY UNKNOWN " + f6 + " " + f6 + " 65535 65535\r\n";
        assertEquals(ProxyHeader.V1_MAX, max.length());
        assertTrue(parseAndCheckTail(ascii(max), 1).local());
    }

    @Test
    void v1Garbage() {
        String[] bad = {
                "PROXY TCP4 1.2.3.4 5.6.7.8 1 2\n",            // нет CR
                "PROXY TCP4 1.2.3 5.6.7.8 1 2\r\n",            // 3 октета
                "PROXY TCP4 1.2.3.256 5.6.7.8 1 2\r\n",        // > 255
                "PROXY TCP4 01.2.3.4 5.6.7.8 1 2\r\n",         // ведущий ноль
                "PROXY TCP4 1.2.3.4 5.6.7.8 1 65536\r\n",      // порт
                "PROXY TCP4 1.2.3.4 5.6.7.8 -1 2\r\n",
                "PROXY TCP4 1.2.3.4 5.6.7.8 1\r\n",            // мало полей
                "PROXY TCP4 1.2.3.4  5.6.7.8 1 2\r\n",         // двойной пробел
                "PROXY TCP4 example.com 5.6.7.8 1 2\r\n",      // имя — никакого DNS
                "PROXY TCP6 example.com ::1 1 2\r\n",
                "PROXY TCP6 1.2.3.4 ::1 1 2\r\n",               // не IPv6
                "PROXY UDP4 1.2.3.4 5.6.7.8 1 2\r\n",
                "PROXY TCP4 1.2.3.4 5.6.7.8 1 2\u0001\r\n",    // управляющий байт
                "PROXY " + "A".repeat(200) + "\r\n",           // длиннее 107
                "GET / HTTP/1.1\r\nHost: x\r\n\r\n",            // не PROXY вовсе
        };
        for (String s : bad)
            assertThrows(ProxyHeader.ProxyException.class, () -> ProxyHeader.read(new Chunked(ascii(s), 2)), s);
        // MC-хендшейк без заголовка — отказ (от доверенного он обязателен)
        assertThrows(ProxyHeader.ProxyException.class,
                () -> ProxyHeader.read(new Chunked(new byte[]{0x10, 0x00, (byte) 0xFD, 0x05, 9, 'l', 'o', 'c', 'a', 'l'}, 64)));
        // слишком длинная строка: читаем не больше 107 байт
        Chunked ch = new Chunked(ascii("PROXY " + "A".repeat(500)), 1);
        assertThrows(ProxyHeader.ProxyException.class, () -> ProxyHeader.read(ch));
        assertTrue(ch.pos <= ProxyHeader.V1_MAX, "прочитано " + ch.pos);
    }

    @Test
    void looksLikeProxy() {
        assertTrue(ProxyHeader.looksLikeProxy(ascii("PR"), 2));
        assertTrue(ProxyHeader.looksLikeProxy(ProxyHeader.V2_SIG, 2));
        assertFalse(ProxyHeader.looksLikeProxy(new byte[]{0x10, 0x00}, 2));         // обычный хендшейк
        assertFalse(ProxyHeader.looksLikeProxy(new byte[]{(byte) 0xFE, 0x01}, 2));  // legacy ping
        assertFalse(ProxyHeader.looksLikeProxy(new byte[]{0x50, 0x00}, 2));         // длина 80, id 0 — легальный MC
        assertFalse(ProxyHeader.looksLikeProxy(new byte[]{0x0D, 0x00}, 2));         // длина 13, id 0 — легальный MC
        // второй байт ждём только после 'P'/0x0D — обычный клиент не задерживается
        assertTrue(ProxyHeader.mayStartProxy((byte) 'P'));
        assertTrue(ProxyHeader.mayStartProxy((byte) 0x0D));
        assertFalse(ProxyHeader.mayStartProxy((byte) 0x10));
        assertFalse(ProxyHeader.mayStartProxy((byte) 0xFE));
        // IPv4-mapped в списке доверенных совпадает с обычным IPv4
        assertTrue(TrustedProxies.parse("::ffff:91.199.149.84").contains(InetAddress.ofLiteral("91.199.149.84")));
        assertTrue(TrustedProxies.parse("91.199.149.84").contains(InetAddress.ofLiteral("::ffff:91.199.149.84")));
    }

    @Test
    void trustedList() throws IOException {
        TrustedProxies t = TrustedProxies.parse("91.199.149.84, 127.0.0.1;10.0.0.0/8 2001:db8::/32");
        assertTrue(t.contains(InetAddress.getByName("91.199.149.84")));
        assertFalse(t.contains(InetAddress.getByName("91.199.149.85")));
        assertTrue(t.contains(InetAddress.getByName("10.200.1.1")));
        assertFalse(t.contains(InetAddress.getByName("11.0.0.1")));
        assertTrue(t.contains(InetAddress.getByName("2001:db8:ffff::1")));
        assertFalse(t.contains(InetAddress.getByName("2001:db9::1")));
        assertTrue(TrustedProxies.parse("").isEmpty());
        assertTrue(TrustedProxies.parse(null).isEmpty());
        assertTrue(TrustedProxies.parse("0.0.0.0/0").contains(InetAddress.getByName("8.8.8.8")));
        assertThrows(IllegalArgumentException.class, () -> TrustedProxies.parse("proxy.example.com"));
        assertThrows(IllegalArgumentException.class, () -> TrustedProxies.parse("10.0.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> TrustedProxies.parse("10.0.0.0/x"));
    }
}
