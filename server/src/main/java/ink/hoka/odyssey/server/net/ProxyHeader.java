package ink.hoka.odyssey.server.net;

import java.io.EOFException;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Разбор заголовка HAProxy PROXY protocol (v1 текст, v2 бинарный) прямо из канала.
 * Читаем РОВНО заголовок и ни байта больше: v2 — 16 байт + длина, v1 — по байту до CRLF. Поэтому начало
 * MC-хендшейка остаётся в сокете и Minestom читает его как обычно (не нужен «возврат» байтов).
 * Части (несколько read) — не проблема: читаем до полного размера.
 */
public final class ProxyHeader {
    private ProxyHeader() {}

    /** Сигнатура v2 (12 байт). */
    static final byte[] V2_SIG = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};
    static final byte[] V1_PREFIX = "PROXY ".getBytes(StandardCharsets.US_ASCII);
    /** v1: строка целиком с CRLF — не длиннее 107 байт (спецификация). */
    public static final int V1_MAX = 107;
    /** v2: предел длины адресов+TLV (спецификация допускает 65535; нам столько не нужно). */
    public static final int V2_MAX_LEN = 4096;

    /** Итог: {@code source == null} — LOCAL/UNKNOWN/UNSPEC/UDP/UNIX: адрес не меняем. */
    public record Result(int version, boolean local, InetSocketAddress source, InetSocketAddress destination) {}

    /** Заголовок битый/чужой — соединение закрыть. */
    public static final class ProxyException extends IOException {
        public ProxyException(String reason) { super(reason); }
    }

    /** Похоже ли начало потока на PROXY-заголовок. Хватает 2 байт: MC шлёт длину-VarInt, затем id 0x00. */
    public static boolean looksLikeProxy(byte[] b, int n) {
        if (n < 2) return false;
        return (b[0] == V1_PREFIX[0] && b[1] == V1_PREFIX[1]) || (b[0] == V2_SIG[0] && b[1] == V2_SIG[1]);
    }

    /** Может ли этот первый байт начинать PROXY-заголовок ('P' v1 или 0x0D v2). */
    public static boolean mayStartProxy(byte b0) { return b0 == V1_PREFIX[0] || b0 == V2_SIG[0]; }

    /** Прочитать и разобрать заголовок. Блокирует; таймаут — снаружи (закрытием канала). */
    public static Result read(ReadableByteChannel ch) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(16);
        head.limit(8);                                    // 8 байт: хватает отличить v1/v2 и меньше любого v1 (≥15)
        readFully(ch, head);
        byte[] h = head.array();
        if (startsWith(h, V2_SIG, 8)) {
            head.limit(16);
            readFully(ch, head);
            return parseV2Tail(h, ch);
        }
        if (startsWith(h, V1_PREFIX, V1_PREFIX.length)) return readV1(ch, h);
        throw new ProxyException("нет заголовка PROXY");
    }

    // ---------- v2 ----------

    private static Result parseV2Tail(byte[] h, ReadableByteChannel ch) throws IOException {
        if (!startsWith(h, V2_SIG, 12)) throw new ProxyException("v2: битая сигнатура");
        int verCmd = h[12] & 0xFF, famProto = h[13] & 0xFF;
        int len = (h[14] & 0xFF) << 8 | (h[15] & 0xFF);
        if (verCmd >> 4 != 2) throw new ProxyException("v2: версия " + (verCmd >> 4));
        int cmd = verCmd & 0x0F;
        if (cmd > 1) throw new ProxyException("v2: команда " + cmd);
        if (len > V2_MAX_LEN) throw new ProxyException("v2: длина " + len + " > " + V2_MAX_LEN);
        ByteBuffer body = ByteBuffer.allocate(len);
        readFully(ch, body);                               // адреса + TLV (TLV просто пропускаем)
        byte[] b = body.array();
        if (cmd == 0) return new Result(2, true, null, null);           // LOCAL: проверка здоровья и т.п.
        int fam = famProto >> 4, proto = famProto & 0x0F;
        if (proto > 2) throw new ProxyException("v2: транспорт " + proto);
        if (fam > 3) throw new ProxyException("v2: семейство " + fam);
        if (proto != 1 || (fam != 1 && fam != 2)) return new Result(2, true, null, null); // UNSPEC/UDP/UNIX — как LOCAL
        int alen = fam == 1 ? 4 : 16;
        if (len < 2 * alen + 4) throw new ProxyException("v2: длина " + len + " мала для " + (fam == 1 ? "TCP4" : "TCP6"));
        InetAddress src = InetAddress.getByAddress(Arrays.copyOfRange(b, 0, alen));      // из байт — без DNS
        InetAddress dst = InetAddress.getByAddress(Arrays.copyOfRange(b, alen, 2 * alen));
        int sp = (b[2 * alen] & 0xFF) << 8 | (b[2 * alen + 1] & 0xFF);
        int dp = (b[2 * alen + 2] & 0xFF) << 8 | (b[2 * alen + 3] & 0xFF);
        return new Result(2, false, new InetSocketAddress(src, sp), new InetSocketAddress(dst, dp));
    }

    // ---------- v1 ----------

    private static Result readV1(ReadableByteChannel ch, byte[] first8) throws IOException {
        ByteBuffer line = ByteBuffer.allocate(V1_MAX);
        line.put(first8, 0, 8);
        ByteBuffer one = ByteBuffer.allocate(1);
        while (true) {                                     // по байту: не захватить начало хендшейка
            byte prev = line.get(line.position() - 1);
            if (prev == '\n') break;
            if (!line.hasRemaining()) throw new ProxyException("v1: строка длиннее " + V1_MAX);
            one.clear();
            readFully(ch, one);
            line.put(one.get(0));
        }
        int n = line.position();
        byte[] a = line.array();
        if (a[n - 2] != '\r') throw new ProxyException("v1: нет CR перед LF");
        for (int i = 0; i < n - 2; i++) if (a[i] < 0x20 || a[i] > 0x7E) throw new ProxyException("v1: недопустимый байт");
        return parseV1(new String(a, 0, n - 2, StandardCharsets.US_ASCII));
    }

    /** Строка без CRLF: «PROXY TCP4 src dst sport dport» / «PROXY UNKNOWN …». */
    static Result parseV1(String s) throws ProxyException {
        String[] p = s.split(" ", -1);
        if (p.length < 2 || !p[0].equals("PROXY")) throw new ProxyException("v1: не PROXY");
        if (p[1].equals("UNKNOWN")) return new Result(1, true, null, null);   // остальное игнорируем (спецификация)
        boolean v4 = p[1].equals("TCP4");
        if (!v4 && !p[1].equals("TCP6")) throw new ProxyException("v1: протокол " + p[1]);
        if (p.length != 6) throw new ProxyException("v1: полей " + p.length);
        InetAddress src = v4 ? ipv4(p[2]) : ipv6(p[2]);
        InetAddress dst = v4 ? ipv4(p[3]) : ipv6(p[3]);
        return new Result(1, false, new InetSocketAddress(src, port(p[4])), new InetSocketAddress(dst, port(p[5])));
    }

    private static InetAddress ipv4(String s) throws ProxyException {
        String[] q = s.split("\\.", -1);
        if (q.length != 4) throw new ProxyException("v1: адрес " + clip(s));
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) b[i] = (byte) num(q[i], 3, 255, s);
        try { return Inet4Address.getByAddress(b); } catch (IOException e) { throw new ProxyException("v1: адрес " + clip(s)); }
    }

    private static InetAddress ipv6(String s) throws ProxyException {
        if (s.isEmpty() || s.indexOf(':') < 0) throw new ProxyException("v1: адрес " + clip(s));
        for (int i = 0; i < s.length(); i++) {             // только hex, ':' и '.' (хвост IPv4) — никакого DNS
            char c = s.charAt(i);
            if (!(c == ':' || c == '.' || Character.digit(c, 16) >= 0)) throw new ProxyException("v1: адрес " + clip(s));
        }
        try { return Inet6Address.ofLiteral(s); } catch (IllegalArgumentException e) { throw new ProxyException("v1: адрес " + clip(s)); }
    }

    private static int port(String s) throws ProxyException { return num(s, 5, 65535, s); }

    private static int num(String s, int maxDigits, int max, String ctx) throws ProxyException {
        if (s.isEmpty() || s.length() > maxDigits) throw new ProxyException("v1: число " + clip(ctx));
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') throw new ProxyException("v1: число " + clip(ctx));
            v = v * 10 + (c - '0');
        }
        if (v > max || (s.length() > 1 && s.charAt(0) == '0')) throw new ProxyException("v1: число " + clip(ctx));
        return v;
    }

    // ---------- общее ----------

    static void readFully(ReadableByteChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) if (ch.read(buf) < 0) throw new EOFException("обрыв на заголовке PROXY");
    }

    private static boolean startsWith(byte[] a, byte[] prefix, int n) {
        return Arrays.equals(a, 0, n, prefix, 0, n);
    }

    private static String clip(String s) { return s.length() > 48 ? s.substring(0, 48) + "…" : s; }
}
