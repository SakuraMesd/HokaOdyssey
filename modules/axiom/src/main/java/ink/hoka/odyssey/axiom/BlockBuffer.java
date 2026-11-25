package ink.hoka.odyssey.axiom;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Разбор axiom:set_buffer. Формат тела после {@code ResourceKey dim, UUID, byte type}:
 * <pre>
 * type 0 (блоки): повтор до метки конца { long ключ секции = BlockPos.asLong(sx,sy,sz); PalettedContainer;
 *                 VarInt n (≤4096); n × (short x | y&lt;&lt;4 | z&lt;&lt;8, CBE) }; VarInt clientAvailableDispatchSends
 * type 1 (биомы): byte размер палитры, × Identifier; byte по умолчанию; повтор до метки { long ключ; 4096 байт };
 *                 VarInt clientAvailableDispatchSends — биомы пока не ставим, только дочитываем
 * </pre>
 * {@code minecraft:void_air} в палитре — «блок не трогать».
 */
final class BlockBuffer {

    private BlockBuffer() {}

    static final int VOID_AIR = Block.VOID_AIR.stateId();

    /**
     * Одна секция правки, компактно (в очереди их могут быть десятки тысяч — int[4096] на секцию съел бы сотни МБ):
     * палитра pal + индексы idx8 (≤256 значений) или idx16; без индексов — вся секция одно значение pal[0].
     * Значение — новое состояние или −1 (не трогать); nbt — по индексу y<<8|z<<4|x.
     */
    record Section(int sx, int sy, int sz, int[] pal, byte[] idx8, char[] idx16, Map<Integer, CompoundBinaryTag> nbt) {

        /** Состояние по индексу y<<8|z<<4|x (−1 — не трогать). */
        int id(int i) {
            if (idx8 != null) return pal[idx8[i] & 0xFF];
            if (idx16 != null) return pal[idx16[i]];
            return pal[0];
        }

        boolean single() { return idx8 == null && idx16 == null; }

        static Section of(int sx, int sy, int sz, int[] ids, Map<Integer, CompoundBinaryTag> nbt) {
            HashMap<Integer, Integer> index = new HashMap<>();
            int[] pal = new int[4096];
            int n = 0;
            for (int id : ids) if (index.putIfAbsent(id, n) == null) pal[n++] = id;
            pal = java.util.Arrays.copyOf(pal, n);
            if (n == 1) return new Section(sx, sy, sz, pal, null, null, nbt);
            if (n <= 256) {
                byte[] ix = new byte[4096];
                for (int i = 0; i < 4096; i++) ix[i] = (byte) (int) index.get(ids[i]);
                return new Section(sx, sy, sz, pal, ix, null, nbt);
            }
            char[] ix = new char[4096];
            for (int i = 0; i < 4096; i++) ix[i] = (char) (int) index.get(ids[i]);
            return new Section(sx, sy, sz, pal, null, ix, nbt);
        }
    }

    /** Итог разбора: секции (не больше предела), всего секций в буфере (для бюджета), метка конца, бюджет клиента. */
    record Parsed(String dimension, byte type, List<Section> sections, int total, int dropped, long term, int clientSends, int nbtFailed) {}

    static Parsed parse(Buf b, boolean rawTall, Settings cfg, boolean allowNbt) {
        String dim = b.readUtf();
        b.readUuid();                                                   // id буфера у клиента — не нужен
        byte type = b.readByte();
        List<Section> out = new ArrayList<>();
        int total = 0, dropped = 0, nbtFailed = 0;
        long term;
        if (type == 0) {
            while (true) {
                long key = b.readLong();
                if (Packing.isTerm(key)) { term = key; break; }
                int[] ids = Palettes.read(b);
                for (int i = 0; i < 4096; i++) if (ids[i] == VOID_AIR) ids[i] = -1;
                int n = Math.min(4096, b.readVarInt());
                Map<Integer, CompoundBinaryTag> nbt = n > 0 ? new HashMap<>() : Map.of();
                for (int i = 0; i < n; i++) {
                    int off = b.readShort() & 0xFFFF;
                    CompoundBinaryTag tag = null;
                    try {
                        tag = ZstdCodec.readCbe(b, cfg.maxNbtBytes(), allowNbt);
                    } catch (Exception | LinkageError e) {
                        nbtFailed++;                                    // байты CBE уже дочитаны — идём дальше
                    }
                    if (tag == null) continue;
                    int x = off & 15, y = (off >> 4) & 15, z = (off >> 8) & 15;
                    nbt.put(y << 8 | z << 4 | x, tag);
                }
                total++;
                if (out.size() < cfg.maxBufferSections()) {
                    Section sec = Section.of(Packing.x(key, rawTall), Packing.y(key, rawTall), Packing.z(key, rawTall), ids, nbt);
                    if (!(sec.single() && sec.pal()[0] < 0 && nbt.isEmpty())) out.add(sec);   // вся секция «не трогать»
                } else {
                    dropped++;
                }
            }
        } else if (type == 1) {
            int pal = b.readByte();                                      // как у плагина: знаковый byte
            for (int i = 0; i < pal; i++) b.readUtf();
            b.readByte();                                                // значение по умолчанию
            while (true) {
                long key = b.readLong();
                if (Packing.isTerm(key)) { term = key; break; }
                b.skip(4096);
                total++;
            }
        } else {
            throw new IllegalStateException("неизвестный тип буфера " + type);
        }
        int clientSends = b.readVarInt();
        return new Parsed(dim, type, out, total, dropped, term, clientSends, nbtFailed);
    }
}
