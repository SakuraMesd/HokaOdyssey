package ink.hoka.odyssey.server.tall;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.play.data.LightData;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * op 11 LIGHT канала {@code hoka_odyssey:w} (шаг 14, CUBIC-CHUNKS.md §3.3, §5.2) — свет кубов своим компактным
 * форматом, только ядру 0.44.0+ (Handshake). Остальным — ванильный UpdateLightPacket, как раньше.
 * <pre>
 * byte 11, varint n, n × куб:
 *   long  pos   — SectionPos.asLong (как в CUBES)
 *   byte  flags — биты 2–3 небо: 0 «по формуле» (ступенька: снизу d нулей, выше 15) / 1 всё 0 / 2 всё 15 / 3 массив;
 *                 бит 6 (при небе 3) — массив одним слоем 16×16 (128 Б), одинаковым на все 16 высот;
 *                 биты 4–5 свет блоков: 0 не трогать / 1 всё 0 / 3 массив; биты 0–1, 7 — 0 (как CUBES: блоки/BE).
 *   [небо 0]  byte d: 0…16 — у всех 256 колонок d тёмных снизу; 0xFF — дальше 256 байт d по колонкам (z&lt;&lt;4|x)
 *   [небо 3]  128 Б (бит 6) или 2048 Б — полубайты как у DataLayer (индекс y&lt;&lt;8|z&lt;&lt;4|x, чётный — младший)
 *   [блоки 3] 2048 Б
 * </pre>
 * Куб, свет которого совпал с формулой F (небо 15 выше крыши, 0 ниже, блоков нет), идёт 2–3 байтами вместо 4 КБ.
 * Каждый куб — полная запись (небо и блоки), поэтому у клиента не остаётся старых слоёв.
 */
final class LightOp {

    private LightOp() {}

    static final byte OP = 11;
    /** С какой версии ядро знает op 11. */
    static final int[] CORE = {0, 44, 0};
    /** Кубов на пакет: худший куб ≈ 4,1 КБ → пакет ≤ 530 КБ (предел канала 1 МБ). */
    static final int PER_PACKET = 128;

    static final int SKY_FORMULA = 0, SKY_ZERO = 1, SKY_FULL = 2, SKY_ARRAY = 3, LAYER = 0x40;
    static final int BLK_KEEP = 0, BLK_ZERO = 1, BLK_ARRAY = 3;

    /** Отладка: кубов и байт отправлено op 11; из них кубов без массива неба. */
    static volatile long cubes, bytes, compact;

    /**
     * Пакеты op 11 для кубов idx (индексы секций столбца) из света столбца l в ванильной форме (бит маски = индекс + 1).
     * l — с «явным» небом (CubeLight.data(..., true) или slice + withFull); куб без массива неба: пустой бит → всё 0,
     * иначе всё 15; без массива блоков — свет блоков 0. l = null — у кубов нет ни неба, ни блоков (небо всё 0/15 по sun).
     */
    static List<byte[]> encode(int cx, int cz, int minSection, LightData l, List<Integer> idx, boolean sun) {
        return pack(records(cx, cz, minSection, l, idx, sun));
    }

    /** Запись op 11 одного куба: i — индекс секции, bytes — от pos до конца, light — её хэш (CubeHash.light, кэш 0.51). */
    record Rec(int i, long pos, byte[] bytes, long light) {}

    /** S2C op 14 LIGHT_REF (0.51): varint n; n × (long pos, long L) — свет из кэша клиента. */
    static final byte OP_REF = 14;
    /** Запись не длиннее — всегда целиком (ссылка 16 Б не выгоднее). */
    static final int REF_MIN = 24;

    /** Записи op 11 для кубов idx (как {@link #encode}, без упаковки в пакеты). */
    static List<Rec> records(int cx, int cz, int minSection, LightData l, List<Integer> idx, boolean sun) {
        Map<Integer, byte[]> sky = arrays(l == null ? null : l.skyMask(), l == null ? null : l.skyLight());
        Map<Integer, byte[]> blk = arrays(l == null ? null : l.blockMask(), l == null ? null : l.blockLight());
        BitSet es = l == null || l.emptySkyMask() == null ? new BitSet() : l.emptySkyMask();
        List<Integer> sorted = new ArrayList<>(new TreeSet<>(idx));
        List<Rec> out = new ArrayList<>(sorted.size());
        byte[] prof = new byte[256];
        for (int i : sorted) {
            byte[] a = sky.get(i + 1);
            int sm;
            if (!sun) sm = SKY_ZERO;                                          // неба у измерения нет — клиент его не держит
            else if (a != null) sm = skyMode(a, prof);
            else sm = es.get(i + 1) ? SKY_ZERO : SKY_FULL;
            byte[] bl = blk.get(i + 1);
            int bm = bl == null || zero(bl) ? BLK_ZERO : BLK_ARRAY;
            long pos = CubeStreamer.pos(cx, i + minSection, cz);
            // канонический вид для хэша — как ServerLight.Cube клиента (sky 0..4, sd, blk, bd)
            int kind;
            byte[] sd;
            switch (sm & 3) {
                case SKY_FORMULA -> { kind = 0; sd = prof.clone(); }
                case SKY_ARRAY -> { if ((sm & LAYER) != 0) { kind = 4; sd = java.util.Arrays.copyOf(a, 128); } else { kind = 3; sd = a; } }
                default -> { kind = sm & 3; sd = null; }
            }
            byte[] bd = bm == BLK_ARRAY ? bl : null;
            final int fsm = sm;
            byte[] rec = NetworkBuffer.makeArray(buf -> {
                buf.write(NetworkBuffer.LONG, pos);
                buf.write(NetworkBuffer.BYTE, (byte) ((fsm & 3) << 2 | bm << 4 | (fsm & LAYER)));
                switch (fsm & 3) {
                    case SKY_FORMULA -> {
                        int d0 = sd[0];
                        boolean same = true;
                        for (int c = 1; c < 256; c++) if (sd[c] != d0) { same = false; break; }
                        if (same) buf.write(NetworkBuffer.BYTE, (byte) d0);
                        else { buf.write(NetworkBuffer.BYTE, (byte) 0xFF); buf.write(NetworkBuffer.RAW_BYTES, sd); }
                    }
                    case SKY_ARRAY -> buf.write(NetworkBuffer.RAW_BYTES, sd);
                    default -> {}
                }
                if (bm == BLK_ARRAY) buf.write(NetworkBuffer.RAW_BYTES, bl);
            });
            if ((sm & 3) != SKY_ARRAY) compact++;
            out.add(new Rec(i, pos, rec, CubeHash.light(kind, sd, bm, bd)));
        }
        return out;
    }

    /** Пакеты op 11 из записей (≤ PER_PACKET на пакет). */
    static List<byte[]> pack(List<Rec> recs) {
        List<byte[]> out = new ArrayList<>();
        for (int s = 0; s < recs.size(); s += PER_PACKET) {
            List<Rec> part = recs.subList(s, Math.min(recs.size(), s + PER_PACKET));
            byte[] b = NetworkBuffer.makeArray(buf -> {
                buf.write(NetworkBuffer.BYTE, OP);
                buf.write(NetworkBuffer.VAR_INT, part.size());
                for (Rec r : part) buf.write(NetworkBuffer.RAW_BYTES, r.bytes());
            });
            out.add(b);
            bytes += b.length;
        }
        cubes += recs.size();
        return out;
    }

    /** Пакеты op 14 LIGHT_REF (≤ 512 на пакет). */
    static List<byte[]> packRefs(List<Rec> refs) {
        List<byte[]> out = new ArrayList<>();
        for (int s = 0; s < refs.size(); s += 512) {
            List<Rec> part = refs.subList(s, Math.min(refs.size(), s + 512));
            out.add(NetworkBuffer.makeArray(buf -> {
                buf.write(NetworkBuffer.BYTE, OP_REF);
                buf.write(NetworkBuffer.VAR_INT, part.size());
                for (Rec r : part) { buf.write(NetworkBuffer.LONG, r.pos()); buf.write(NetworkBuffer.LONG, r.light()); }
            }));
        }
        return out;
    }

    /** Режим неба для массива a; при «по формуле» в prof — d по колонкам. */
    static int skyMode(byte[] a, byte[] prof) {
        byte b0 = a[0];
        if (b0 == 0 || b0 == (byte) 0xFF) {
            boolean same = true;
            for (int k = 1; k < 2048; k++) if (a[k] != b0) { same = false; break; }
            if (same) return b0 == 0 ? SKY_ZERO : SKY_FULL;
        }
        boolean step = true;
        for (int c = 0; c < 256 && step; c++) {
            int d = 16;
            for (int y = 0; y < 16; y++) {
                int v = CubeLight.nib(a, y << 8 | c);
                if (y < d) {
                    if (v == 15) d = y;
                    else if (v != 0) { step = false; break; }
                } else if (v != 15) { step = false; break; }
            }
            prof[c] = (byte) d;
        }
        if (step) return SKY_FORMULA;
        for (int k = 128; k < 2048; k++) if (a[k] != a[k & 127]) return SKY_ARRAY;
        return SKY_ARRAY | LAYER;
    }

    private static boolean zero(byte[] a) {
        for (byte b : a) if (b != 0) return false;
        return true;
    }

    private static Map<Integer, byte[]> arrays(BitSet mask, List<byte[]> arr) {
        Map<Integer, byte[]> m = new HashMap<>();
        if (mask == null) return m;
        int n = 0;
        for (int b = mask.nextSetBit(0); b >= 0; b = mask.nextSetBit(b + 1), n++) m.put(b, arr.get(n));
        return m;
    }

    /** Индексы кубов, у которых свет o и l различается (небо, блоки или «пусто»); o = null — все idx. */
    static List<Integer> changed(LightData o, LightData l, List<Integer> idx) {
        if (o == null) return idx;
        Map<Integer, byte[]> os = arrays(o.skyMask(), o.skyLight()), ob = arrays(o.blockMask(), o.blockLight());
        Map<Integer, byte[]> ns = l == null ? Map.of() : arrays(l.skyMask(), l.skyLight());
        Map<Integer, byte[]> nb = l == null ? Map.of() : arrays(l.blockMask(), l.blockLight());
        List<Integer> out = new ArrayList<>();
        for (int i : idx) {
            int b = i + 1;
            if (!java.util.Arrays.equals(os.get(b), ns.get(b)) || !java.util.Arrays.equals(ob.get(b), nb.get(b))
                    || bit(o.emptySkyMask(), b) != (l != null && bit(l.emptySkyMask(), b))) out.add(i);
        }
        return out;
    }

    private static boolean bit(BitSet s, int b) { return s != null && s.get(b); }

    static String stats() {
        return "свет op 11: кубов " + cubes + " (без массива неба " + compact + "), " + (bytes / 1024) + " КБ";
    }
}
