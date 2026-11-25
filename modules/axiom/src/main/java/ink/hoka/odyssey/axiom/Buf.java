package ink.hoka.odyssey.axiom;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Чтение пакета Axiom: как ванильный FriendlyByteBuf (big-endian, VarInt, строки с VarInt-длиной). */
final class Buf {

    private final byte[] a;
    private int p;
    private final int end;

    Buf(byte[] a) { this(a, 0, a.length); }

    Buf(byte[] a, int off, int len) {
        this.a = a;
        this.p = off;
        this.end = off + len;
    }

    byte[] array() { return a; }
    int pos() { return p; }
    int remaining() { return end - p; }
    void skipAll() { p = end; }

    private void need(int n) {
        if (n < 0 || p + n > end) throw new IllegalStateException("пакет короче, чем нужно (" + n + " байт, осталось " + (end - p) + ")");
    }

    void skip(int n) { need(n); p += n; }

    byte readByte() { need(1); return a[p++]; }

    boolean readBool() { return readByte() != 0; }

    short readShort() {
        need(2);
        int v = (a[p] & 0xFF) << 8 | (a[p + 1] & 0xFF);
        p += 2;
        return (short) v;
    }

    int readInt() {
        need(4);
        int v = (a[p] & 0xFF) << 24 | (a[p + 1] & 0xFF) << 16 | (a[p + 2] & 0xFF) << 8 | (a[p + 3] & 0xFF);
        p += 4;
        return v;
    }

    long readLong() { return (long) readInt() << 32 | (readInt() & 0xFFFFFFFFL); }

    float readFloat() { return Float.intBitsToFloat(readInt()); }

    double readDouble() { return Double.longBitsToDouble(readLong()); }

    int readVarInt() {
        int v = 0, s = 0;
        byte b;
        do {
            if (s >= 35) throw new IllegalStateException("VarInt длиннее 5 байт");
            b = readByte();
            v |= (b & 0x7F) << s;
            s += 7;
        } while ((b & 0x80) != 0);
        return v;
    }

    byte[] readBytes(int n) {
        need(n);
        byte[] r = new byte[n];
        System.arraycopy(a, p, r, 0, n);
        p += n;
        return r;
    }

    /** VarInt-длина + байты. */
    byte[] readByteArray(int max) {
        int n = readVarInt();
        if (n < 0 || n > max) throw new IllegalStateException("массив " + n + " байт (предел " + max + ")");
        return readBytes(n);
    }

    String readUtf() { return readUtf(32767); }

    String readUtf(int maxChars) {
        int n = readVarInt();
        if (n < 0 || n > maxChars * 3) throw new IllegalStateException("строка " + n + " байт");
        need(n);
        String s = new String(a, p, n, StandardCharsets.UTF_8);
        p += n;
        return s;
    }

    UUID readUuid() { return new UUID(readLong(), readLong()); }
}
