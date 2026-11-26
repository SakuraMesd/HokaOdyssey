package ink.hoka.odyssey.gen;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Протокол генерации на клиенте (ядро 0.59+) — общий кодек сервера и клиента. Канал S2C {@code hoka_odyssey:w}, C2S
 * {@code hoka_odyssey:wc}; первый байт сообщения — op. Типы как у NetworkBuffer Minestom / FriendlyByteBuf MC:
 * int/long/short — big-endian, varint — LEB128, string — varint длины + UTF-8.
 * <pre>
 * S2C 20 GEN_DEF   varint slot (0 — генерации на клиенте нет / сброс; остальное не шлётся); string id; varint version;
 *                  string filePath ("gen/&lt;id&gt;@&lt;v&gt;-&lt;sha8&gt;.jar" в файлах clientmods или "builtin:&lt;id&gt;"); string sha256;
 *                  long seed; string optionsJson (generator_options, ключи по алфавиту); int minY, maxY, size
 *                  (границы карты = GenContext.bounds(), НЕ обрезанные по миру);
 *                  int statesCount; long statesDigest; long biomesDigest; long fingerprint; byte flags (bit0 — секреты есть)
 * S2C 21 GEN_CUBES varint slot; varint nCol; на столбец: int cx, int cz, int sy0, varint len, ⌈len/8⌉ Б маска
 *                  (бит j — куб sy0+j сгенерировать; младший бит байта — меньший j); varint k; k × (varint j,
 *                  byte f: f&amp;1 → long visHash (сверить после генерации), f&amp;2 → varint state, varint biome (однородный —
 *                  не генерировать, залить)). Идёт в одном FIFO с op 2/4.
 * S2C 22 SECRETS   varint n; n × (long pos, varint m, m × (short y&lt;&lt;8|z&lt;&lt;4|x, varint state),
 *                  varint e, e × (short индекс, string SNBT блок-сущности)) — поверх видимой части куба
 * S2C 23 GEN_CHECK varint n; n × (long pos, long visHash) — отладка: сверить и ответить op 9
 * C2S 8  GEN_STATUS varint slot; byte status (0 ок, 1 нет jar, 2 номера, 3 отпечаток, 4 ошибка, 5 выкл игроком); varint потоков
 * C2S 9  GEN_SAMPLE varint slot; varint n; n × (long pos, long visHash) — выборка сгенерированных и ответы на op 23
 * </pre>
 * pos — SectionPos.asLong: X 22 бита (старшие), Z 22, Y 20 (младшие).
 */
public final class GenWire {

    private GenWire() {}

    public static final byte OP_DEF = 20, OP_CUBES = 21, OP_SECRETS = 22, OP_CHECK = 23;
    public static final byte OP_STATUS = 8, OP_SAMPLE = 9;
    public static final int ST_OK = 0, ST_NO_JAR = 1, ST_IDS = 2, ST_FINGERPRINT = 3, ST_ERROR = 4, ST_OFF = 5;
    public static final int F_HASH = 1, F_UNIFORM = 2;

    public static long pos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 20) | (y & 0xFFFFF);
    }
    public static int posX(long l) { return (int) (l >> 42); }
    public static int posY(long l) { return (int) (l << 44 >> 44); }
    public static int posZ(long l) { return (int) (l << 22 >> 42); }

    // ───────────── op 20 ─────────────

    public record Def(int slot, String id, int version, String filePath, String sha256, long seed, String optionsJson,
                      int minY, int maxY, int size, int statesCount, long statesDigest, long biomesDigest, long fingerprint, int flags) {
        public static Def off() { return new Def(0, "", 0, "", "", 0, "{}", 0, 0, 0, 0, 0, 0, 0, 0); }
        public boolean secrets() { return (flags & 1) != 0; }
    }

    public static byte[] encodeDef(Def d) {
        Out o = new Out();
        o.b(OP_DEF).vi(d.slot()).str(d.id()).vi(d.version()).str(d.filePath()).str(d.sha256()).l(d.seed()).str(d.optionsJson())
                .i(d.minY()).i(d.maxY()).i(d.size()).i(d.statesCount()).l(d.statesDigest()).l(d.biomesDigest()).l(d.fingerprint()).b(d.flags());
        return o.bytes();
    }

    /** Разбор op 20 (с байтом op). */
    public static Def decodeDef(byte[] m) {
        In in = new In(m);
        expect(in, OP_DEF);
        return new Def(in.vi(), in.str(), in.vi(), in.str(), in.str(), in.l(), in.str(), in.i(), in.i(), in.i(), in.i(), in.l(), in.l(), in.l(), in.b() & 0xFF);
    }

    // ───────────── op 21 ─────────────

    /** Куб столбца в op 21: sy — секция Y; hash 0 — без сверки; uniformState −1 — генерировать. */
    public record Cube(int sy, long hash, int uniformState, int uniformBiome) {
        public static Cube gen(int sy) { return new Cube(sy, 0, -1, 0); }
    }

    public record Col(int cx, int cz, List<Cube> cubes) {}

    public record Cubes(int slot, List<Col> cols) {}

    /** Кубов на сообщение op 21 не больше (столбцы целиком). */
    public static final int CUBES_PER_MESSAGE = 4096;

    public static byte[] encodeCubes(int slot, List<Col> cols) {
        Out o = new Out();
        o.b(OP_CUBES).vi(slot).vi(cols.size());
        for (Col c : cols) {
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (Cube k : c.cubes()) { lo = Math.min(lo, k.sy()); hi = Math.max(hi, k.sy()); }
            if (c.cubes().isEmpty()) { lo = 0; hi = -1; }
            int len = hi - lo + 1;
            byte[] mask = new byte[(len + 7) >> 3];
            List<Cube> extra = new ArrayList<>();
            for (Cube k : c.cubes()) {
                int j = k.sy() - lo;
                mask[j >> 3] |= (byte) (1 << (j & 7));
                if (k.hash() != 0 || k.uniformState() >= 0) extra.add(k);
            }
            o.i(c.cx()).i(c.cz()).i(lo).vi(len).raw(mask).vi(extra.size());
            for (Cube k : extra) {
                int f = (k.hash() != 0 ? F_HASH : 0) | (k.uniformState() >= 0 ? F_UNIFORM : 0);
                o.vi(k.sy() - lo).b(f);
                if ((f & F_HASH) != 0) o.l(k.hash());
                if ((f & F_UNIFORM) != 0) o.vi(k.uniformState()).vi(k.uniformBiome());
            }
        }
        return o.bytes();
    }

    /** Разбор op 21 (с байтом op); кубы столбца — по возрастанию sy. */
    public static Cubes decodeCubes(byte[] m) {
        In in = new In(m);
        expect(in, OP_CUBES);
        int slot = in.vi(), n = in.vi();
        if (n < 0 || n > 1 << 20) throw new IllegalArgumentException("op 21: столбцов " + n);
        List<Col> cols = new ArrayList<>(n);
        for (int c = 0; c < n; c++) {
            int cx = in.i(), cz = in.i(), sy0 = in.i(), len = in.vi();
            if (len < 0 || len > 4096) throw new IllegalArgumentException("op 21: длина " + len);
            byte[] mask = in.raw((len + 7) >> 3);
            long[] hash = new long[len];
            int[] us = new int[len], ub = new int[len];
            Arrays.fill(us, -1);
            int k = in.vi();
            for (int e = 0; e < k; e++) {
                int j = in.vi(), f = in.b();
                if (j < 0 || j >= len) throw new IllegalArgumentException("op 21: бит " + j);
                if ((f & F_HASH) != 0) hash[j] = in.l();
                if ((f & F_UNIFORM) != 0) { us[j] = in.vi(); ub[j] = in.vi(); }
            }
            List<Cube> cubes = new ArrayList<>();
            for (int j = 0; j < len; j++) if ((mask[j >> 3] >> (j & 7) & 1) != 0) cubes.add(new Cube(sy0 + j, hash[j], us[j], ub[j]));
            cols.add(new Col(cx, cz, cubes));
        }
        return new Cubes(slot, cols);
    }

    // ───────────── op 22 ─────────────

    /** Секреты куба: индексы (y&lt;&lt;8|z&lt;&lt;4|x) и состояния; данные блок-сущностей (индекс → SNBT). */
    public record Secret(long pos, int[] idx, int[] state, int[] dataIdx, String[] snbt) {}

    public static byte[] encodeSecrets(List<Secret> list) {
        Out o = new Out();
        o.b(OP_SECRETS).vi(list.size());
        for (Secret s : list) {
            o.l(s.pos()).vi(s.idx().length);
            for (int k = 0; k < s.idx().length; k++) o.s(s.idx()[k]).vi(s.state()[k]);
            o.vi(s.dataIdx().length);
            for (int k = 0; k < s.dataIdx().length; k++) o.s(s.dataIdx()[k]).str(s.snbt()[k]);
        }
        return o.bytes();
    }

    public static List<Secret> decodeSecrets(byte[] m) {
        In in = new In(m);
        expect(in, OP_SECRETS);
        int n = in.vi();
        List<Secret> r = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            long pos = in.l();
            int c = in.vi();
            if (c < 0 || c > 4096) throw new IllegalArgumentException("op 22: секретов " + c);
            int[] idx = new int[c], st = new int[c];
            for (int j = 0; j < c; j++) { idx[j] = in.s() & 0xFFF; st[j] = in.vi(); }
            int e = in.vi();
            if (e < 0 || e > 4096) throw new IllegalArgumentException("op 22: данных " + e);
            int[] di = new int[e];
            String[] sn = new String[e];
            for (int j = 0; j < e; j++) { di[j] = in.s() & 0xFFF; sn[j] = in.str(); }
            r.add(new Secret(pos, idx, st, di, sn));
        }
        return r;
    }

    // ───────────── op 23 / op 9 / op 8 ─────────────

    public static byte[] encodeCheck(long[] pos, long[] hash) {
        Out o = new Out();
        o.b(OP_CHECK).vi(pos.length);
        for (int k = 0; k < pos.length; k++) o.l(pos[k]).l(hash[k]);
        return o.bytes();
    }

    /** op 23 → [pos[], hash[]]. */
    public static long[][] decodeCheck(byte[] m) {
        In in = new In(m);
        expect(in, OP_CHECK);
        int n = in.vi();
        if (n < 0 || n > 65536) throw new IllegalArgumentException("op 23: " + n);
        long[] p = new long[n], h = new long[n];
        for (int k = 0; k < n; k++) { p[k] = in.l(); h[k] = in.l(); }
        return new long[][]{p, h};
    }

    public record Status(int slot, int status, int threads) {}

    public static byte[] encodeStatus(Status s) { return new Out().b(OP_STATUS).vi(s.slot()).b(s.status()).vi(s.threads()).bytes(); }

    public static Status decodeStatus(byte[] m) {
        In in = new In(m);
        expect(in, OP_STATUS);
        return new Status(in.vi(), in.b() & 0xFF, in.vi());
    }

    public record Sample(int slot, long[] pos, long[] hash) {}

    public static byte[] encodeSample(Sample s) {
        Out o = new Out();
        o.b(OP_SAMPLE).vi(s.slot()).vi(s.pos().length);
        for (int k = 0; k < s.pos().length; k++) o.l(s.pos()[k]).l(s.hash()[k]);
        return o.bytes();
    }

    public static Sample decodeSample(byte[] m) {
        In in = new In(m);
        expect(in, OP_SAMPLE);
        int slot = in.vi(), n = in.vi();
        if (n < 0 || n > 4096) throw new IllegalArgumentException("op 9: " + n);
        long[] p = new long[n], h = new long[n];
        for (int k = 0; k < n; k++) { p[k] = in.l(); h[k] = in.l(); }
        return new Sample(slot, p, h);
    }

    private static void expect(In in, byte op) {
        int got = in.b();
        if (got != op) throw new IllegalArgumentException("ожидался op " + op + ", а пришёл " + got);
    }

    // ───────────── запись / чтение ─────────────

    public static final class Out {
        private byte[] a = new byte[64];
        private int n;

        private void need(int k) { if (n + k > a.length) a = Arrays.copyOf(a, Math.max(a.length * 2, n + k)); }

        public Out b(int v) { need(1); a[n++] = (byte) v; return this; }
        public Out s(int v) { need(2); a[n++] = (byte) (v >> 8); a[n++] = (byte) v; return this; }
        public Out i(int v) { need(4); for (int k = 24; k >= 0; k -= 8) a[n++] = (byte) (v >> k); return this; }
        public Out l(long v) { need(8); for (int k = 56; k >= 0; k -= 8) a[n++] = (byte) (v >> k); return this; }
        public Out vi(int v) {
            need(5);
            while ((v & ~0x7F) != 0) { a[n++] = (byte) ((v & 0x7F) | 0x80); v >>>= 7; }
            a[n++] = (byte) v;
            return this;
        }
        public Out raw(byte[] b) { need(b.length); System.arraycopy(b, 0, a, n, b.length); n += b.length; return this; }
        public Out str(String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); return vi(b.length).raw(b); }
        public int size() { return n; }
        public byte[] bytes() { return Arrays.copyOf(a, n); }
    }

    public static final class In {
        private final byte[] a;
        private int p;

        public In(byte[] a) { this.a = a; }

        public int b() { return a[p++]; }
        public int s() { int v = (a[p] & 0xFF) << 8 | (a[p + 1] & 0xFF); p += 2; return (short) v; }
        public int i() { int v = 0; for (int k = 0; k < 4; k++) v = v << 8 | (a[p++] & 0xFF); return v; }
        public long l() { long v = 0; for (int k = 0; k < 8; k++) v = v << 8 | (a[p++] & 0xFF); return v; }
        public int vi() {
            int v = 0, s = 0;
            while (true) {
                byte x = a[p++];
                v |= (x & 0x7F) << s;
                if (x >= 0) return v;
                s += 7;
                if (s >= 35) throw new IllegalArgumentException("varint");
            }
        }
        public byte[] raw(int k) { if (k < 0 || p + k > a.length) throw new IllegalArgumentException("мало данных"); byte[] r = Arrays.copyOfRange(a, p, p + k); p += k; return r; }
        public String str() {
            int k = vi();
            if (k < 0 || k > 1 << 20) throw new IllegalArgumentException("строка " + k);
            return new String(raw(k), StandardCharsets.UTF_8);
        }
        public int remaining() { return a.length - p; }
    }
}
