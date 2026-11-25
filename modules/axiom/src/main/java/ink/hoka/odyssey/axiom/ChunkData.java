package ink.hoka.odyssey.axiom;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * axiom:request_chunk_data → axiom:response_chunk_data (кисти и выделения Axiom читают мир с сервера).
 * <pre>
 * C→S: long id, Identifier мир, bool блок-сущности в секциях, VarInt n × long позиция блока (нужные блок-сущности),
 *      VarInt m × long ключ секции = BlockPos.asLong(sx,sy,sz)
 * S→C: long id; [long позиция, CBE]… метка; [long ключ, bool есть, PalettedContainer если есть]… метка; bool готово
 * </pre>
 * Части не больше ~1 МиБ: разрыв в блок-сущностях — новая часть = id и продолжение списка; разрыв в секциях — id,
 * сразу метка, продолжение секций. Секции читаем из Chunk.getSection (у TallChunk — разреженный список), а не из
 * пакета чанка: на кубовой карте тот — пустой «скелет». Незагруженные столбцы не грузим.
 */
final class ChunkData {

    private ChunkData() {}

    static final String RESPONSE = "axiom:response_chunk_data";
    private static final int MAX_PART = 0x100000 - 64;

    /** Пустой ответ (нет прав, чужой мир): клиент ждёт ответ на каждый запрос. */
    static void sendEmpty(Player p, long id, long term) {
        p.sendPluginMessage(RESPONSE, new Out(32).writeLong(id).writeLong(term).writeLong(term).writeBool(true).toByteArray());
    }

    static void handle(Player p, Buf b, Settings cfg, boolean rawTall, long term, boolean mapAllowed, Logger log) {
        long id = b.readLong();
        Instance inst = p.getInstance();
        String dim = b.readUtf();
        boolean beInSections = b.readBool();
        int n = b.readVarInt();
        if (n < 0 || n > b.remaining() / 8) throw new IllegalStateException("блок-сущностей " + n);
        long[] bePos = new long[n];
        for (int i = 0; i < n; i++) bePos[i] = b.readLong();
        int m = b.readVarInt();
        if (m < 0 || m > b.remaining() / 8) throw new IllegalStateException("секций " + m);
        long[] secKeys = new long[m];
        for (int i = 0; i < m; i++) secKeys[i] = b.readLong();

        if (inst == null || !mapAllowed || !dim.equals(inst.getDimensionName())) {
            if (cfg.debug()) log.info("{}: запрос секций для «{}» — пустой ответ", p.getUsername(), dim);
            sendEmpty(p, id, term);
            return;
        }
        int pcx = p.getPosition().blockX() >> 4, pcz = p.getPosition().blockZ() >> 4;
        List<byte[]> bes = new ArrayList<>();
        List<byte[]> secs = new ArrayList<>();

        // запрошенные блок-сущности
        for (long pos : bePos) {
            if (Packing.isTerm(pos)) continue;
            int x = Packing.x(pos, rawTall), y = Packing.y(pos, rawTall), z = Packing.z(pos, rawTall);
            if (Math.abs((x >> 4) - pcx) + Math.abs((z >> 4) - pcz) >= cfg.maxChunkDistance()) continue;
            Chunk c = inst.getChunk(x >> 4, z >> 4);
            if (c == null || !c.isLoaded() || (y >> 4) < c.getMinSection() || (y >> 4) >= c.getMaxSection()) continue;
            Block blk = c.getBlock(x, y, z);
            byte[] e = blockEntity(pos, blk, log);
            if (e != null) bes.add(e);
        }

        // секции
        int count = 0;
        for (long key : secKeys) {
            if (Packing.isTerm(key) || count >= cfg.maxRequestSections()) continue;
            int sx = Packing.x(key, rawTall), sy = Packing.y(key, rawTall), sz = Packing.z(key, rawTall);
            if (Math.abs(sx - pcx) + Math.abs(sz - pcz) >= cfg.maxChunkDistance()) continue;
            Chunk c = inst.getChunk(sx, sz);
            if (c == null || !c.isLoaded() || sy < c.getMinSection() || sy >= c.getMaxSection()) continue;
            count++;
            Section s = c.getSection(sy);
            Palette pal = s.blockPalette();
            Out o = new Out(64);
            o.writeLong(key);
            if (pal.count() == 0) {
                o.writeBool(false);
            } else {
                o.writeBool(true);
                Palettes.write(o, pal);
                if (beInSections) sectionBlockEntities(c, sx, sy, sz, pal, rawTall, bes, log);
            }
            secs.add(o.toByteArray());
        }
        send(p, id, term, bes, secs);
        if (cfg.debug()) log.info("{}: ответ на запрос: секций {}/{}, блок-сущностей {}", p.getUsername(), secs.size(), m, bes.size());
    }

    /** Блок-сущности секции: ищем в палитре состояния с типом блок-сущности, NBT берём из столбца. */
    private static void sectionBlockEntities(Chunk c, int sx, int sy, int sz, Palette pal, boolean rawTall, List<byte[]> out, Logger log) {
        pal.getAllPresent((x, y, z, v) -> {
            Block t = Block.fromStateId(v);
            if (t == null || t.registry().blockEntityType() == null) return;
            int wx = sx << 4 | x, wy = sy << 4 | y, wz = sz << 4 | z;
            long pos = Packing.pack(wx, wy, wz, rawTall);
            if (Packing.isTerm(pos)) return;                                 // (0,−2048,0) совпал бы с меткой конца
            byte[] e = blockEntity(pos, c.getBlock(wx, wy, wz), log);
            if (e != null) out.add(e);
        });
    }

    private static byte[] blockEntity(long pos, Block blk, Logger log) {
        if (blk == null || blk.registry().blockEntityType() == null || !ZstdCodec.ready()) return null;
        CompoundBinaryTag nbt = blk.nbt();
        if (nbt == null) nbt = CompoundBinaryTag.empty();
        try {
            Out o = new Out(64);
            o.writeLong(pos);
            ZstdCodec.writeCbe(o, nbt);
            return o.toByteArray();
        } catch (Exception | LinkageError e) {
            log.warn("блок-сущность не сжата: {}", e.toString());
            return null;
        }
    }

    /** Нарезать ответ на части по ~1 МиБ (см. описание класса). */
    private static void send(Player p, long id, long term, List<byte[]> bes, List<byte[]> secs) {
        Out o = new Out(4096);
        o.writeLong(id);
        boolean any = false;                                            // в текущей части уже есть запись
        for (byte[] e : bes) {
            if (any && o.size() + e.length + 17 > MAX_PART) {
                o.writeLong(term).writeLong(term).writeBool(false);
                p.sendPluginMessage(RESPONSE, o.toByteArray());
                o = new Out(4096);
                o.writeLong(id);
                any = false;
            }
            o.writeBytes(e);
            any = true;
        }
        o.writeLong(term);
        for (byte[] e : secs) {
            if (any && o.size() + e.length + 9 > MAX_PART) {
                o.writeLong(term).writeBool(false);
                p.sendPluginMessage(RESPONSE, o.toByteArray());
                o = new Out(4096);
                o.writeLong(id).writeLong(term);
                any = false;
            }
            o.writeBytes(e);
            any = true;
        }
        o.writeLong(term).writeBool(true);
        p.sendPluginMessage(RESPONSE, o.toByteArray());
    }
}
