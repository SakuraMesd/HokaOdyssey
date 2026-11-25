package ink.hoka.odyssey.axiom;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Запись пакета Axiom (big-endian, VarInt) в растущий массив. */
final class Out {

    private byte[] a;
    private int n;

    Out() { this(256); }

    Out(int cap) { a = new byte[Math.max(16, cap)]; }

    int size() { return n; }

    /** Откатить запись до длины len. */
    void truncate(int len) { n = len; }

    byte[] toByteArray() { return Arrays.copyOf(a, n); }

    /** Кусок уже записанного [from, to). */
    byte[] slice(int from, int to) { return Arrays.copyOfRange(a, from, to); }

    private void grow(int k) {
        if (n + k <= a.length) return;
        a = Arrays.copyOf(a, Math.max(a.length * 2, n + k));
    }

    Out writeByte(int v) { grow(1); a[n++] = (byte) v; return this; }

    Out writeBool(boolean v) { return writeByte(v ? 1 : 0); }

    Out writeShort(int v) { grow(2); a[n++] = (byte) (v >> 8); a[n++] = (byte) v; return this; }

    Out writeInt(int v) {
        grow(4);
        a[n++] = (byte) (v >> 24); a[n++] = (byte) (v >> 16); a[n++] = (byte) (v >> 8); a[n++] = (byte) v;
        return this;
    }

    Out writeLong(long v) { writeInt((int) (v >>> 32)); return writeInt((int) v); }

    Out writeFloat(float v) { return writeInt(Float.floatToIntBits(v)); }

    Out writeVarInt(int v) {
        while ((v & ~0x7F) != 0) { writeByte((v & 0x7F) | 0x80); v >>>= 7; }
        return writeByte(v);
    }

    Out writeBytes(byte[] b) { grow(b.length); System.arraycopy(b, 0, a, n, b.length); n += b.length; return this; }

    Out writeByteArray(byte[] b) { writeVarInt(b.length); return writeBytes(b); }

    Out writeUtf(String s) { return writeByteArray(s.getBytes(StandardCharsets.UTF_8)); }
}
