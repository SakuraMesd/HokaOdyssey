package ink.hoka.odyssey.server.tall.region;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.TagStringIO;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.palette.Palette;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Куб 16×16×16 в регионе (CUBIC-CHUNKS.md §4.9). Блоки и биомы — палитрой ИМЁН (как Anvil): номера состояний и
 * биомов меняются между версиями игры и реестрами, имена — нет.
 * <pre>
 * byte версия; short блоков в палитре, UTF состояния; индексы 4096 (y,z,x): 1 значение — нет, ≤256 — byte, иначе short;
 * byte биомов в палитре, UTF ключи; индексы 64 (4×4×4) — byte (1 значение — нет);
 * byte режим света (0 — не хранится, считается при загрузке); int отметка высот (§4.6 п.5, CubeMark; 0 — нет);
 * short блоков с данными; на блок: short индекс в кубе (y&lt;&lt;8|z&lt;&lt;4|x), UTF состояние, UTF SNBT (пусто — нет).
 * </pre>
 * Неизвестное имя блока при чтении → воздух, биома → нулевой биом реестра (считается в {@link Decoded#unknown}).
 */
public final class CubeCodec {

    private CubeCodec() {}

    private static final int VERSION = 1;

    /** Прочитанный куб: палитры, блоки с данными (ключ — индекс в кубе), отметка высот, сколько имён не узнали. */
    public record Decoded(Palette blocks, Palette biomes, Map<Integer, Block> data, int heightMark, int unknown) {}

    public static int index(int x, int y, int z) { return (y & 15) << 8 | (z & 15) << 4 | (x & 15); }

    public static byte[] encode(Palette blocks, Palette biomes, Map<Integer, Block> data, int heightMark) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(4096);
            DataOutputStream out = new DataOutputStream(bo);
            out.writeByte(VERSION);
            // блоки
            int[] ids = new int[4096];
            blocks.getAll((x, y, z, v) -> ids[index(x, y, z)] = v);
            List<String> names = new ArrayList<>();
            int[] idx = palette(ids, v -> { Block b = Block.fromStateId(v); return b == null ? "minecraft:air" : b.state(); }, names);
            out.writeShort(names.size());
            for (String n : names) out.writeUTF(n);
            writeIndices(out, idx, names.size());
            // биомы
            int[] bids = new int[64];
            biomes.getAll((x, y, z, v) -> bids[(y << 4) | (z << 2) | x] = v);
            List<String> bnames = new ArrayList<>();
            var reg = MinecraftServer.getBiomeRegistry();
            int[] bidx = palette(bids, v -> { var k = reg.getKey(v); return k == null ? "minecraft:plains" : k.key().asString(); }, bnames);
            out.writeByte(bnames.size());
            for (String n : bnames) out.writeUTF(n);
            if (bnames.size() > 1) for (int v : bidx) out.writeByte(v);
            out.writeByte(0);                                                // свет: не хранится
            out.writeInt(heightMark);
            Map<Integer, Block> sorted = new TreeMap<>(data);
            out.writeShort(sorted.size());
            for (var e : sorted.entrySet()) {
                Block b = e.getValue();
                out.writeShort(e.getKey());
                out.writeUTF(b.state());
                CompoundBinaryTag nbt = b.nbt();
                out.writeUTF(nbt == null ? "" : TagStringIO.tagStringIO().asString(nbt));
            }
            out.flush();
            return bo.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    public static Decoded decode(byte[] bytes) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int v = in.readUnsignedByte();
            if (v != VERSION) throw new RegionFile.Corrupt("куб: версия " + v);
            int unknown = 0;
            int n = in.readUnsignedShort();
            if (n < 1 || n > 4096) throw new RegionFile.Corrupt("куб: палитра " + n);
            int[] map = new int[n];
            for (int i = 0; i < n; i++) {
                Block b = Block.fromState(in.readUTF());
                if (b == null) { unknown++; map[i] = 0; } else map[i] = b.stateId();
            }
            int[] idx = readIndices(in, 4096, n);
            Palette blocks = Palette.blocks();
            if (n == 1) blocks.fill(map[0]);
            else blocks.setAll((x, y, z) -> map[idx[index(x, y, z)]]);
            int bn = in.readUnsignedByte();
            if (bn < 1 || bn > 64) throw new RegionFile.Corrupt("куб: биомов " + bn);
            var reg = MinecraftServer.getBiomeRegistry();
            int[] bmap = new int[bn];
            for (int i = 0; i < bn; i++) {
                var k = reg.getKey(Key.key(in.readUTF()));
                int id = k == null ? -1 : reg.getId(k);
                if (id < 0) { unknown++; id = 0; }
                bmap[i] = id;
            }
            int[] bidx = new int[64];
            if (bn > 1) for (int i = 0; i < 64; i++) { bidx[i] = in.readUnsignedByte(); if (bidx[i] >= bn) throw new RegionFile.Corrupt("куб: биом " + bidx[i]); }
            Palette biomes = Palette.biomes();
            if (bn == 1) biomes.fill(bmap[0]);
            else biomes.setAll((x, y, z) -> bmap[bidx[(y << 4) | (z << 2) | x]]);
            in.readUnsignedByte();                                           // режим света — пока всегда 0
            int mark = in.readInt();
            int m = in.readUnsignedShort();
            Map<Integer, Block> data = new HashMap<>();
            for (int i = 0; i < m; i++) {
                int key = in.readUnsignedShort();
                Block b = Block.fromState(in.readUTF());
                String nbt = in.readUTF();
                if (key >= 4096) throw new RegionFile.Corrupt("куб: блок с данными " + key);
                if (b == null) { unknown++; continue; }
                if (!nbt.isEmpty()) b = b.withNbt(TagStringIO.tagStringIO().asCompound(nbt));
                BlockHandler h = MinecraftServer.getBlockManager().getHandler(b.key().asString());
                if (h != null) b = b.withHandler(h);
                data.put(key, b);
            }
            return new Decoded(blocks, biomes, data, mark, unknown);
        } catch (RegionFile.Corrupt e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new RegionFile.Corrupt("куб: " + e);
        }
    }

    /** Значения → индексы в палитре имён (имена добавляются в names по первому появлению). */
    private static int[] palette(int[] vals, java.util.function.IntFunction<String> name, List<String> names) {
        Map<Integer, Integer> byVal = new HashMap<>();
        Map<String, Integer> byName = new HashMap<>();
        int[] out = new int[vals.length];
        for (int i = 0; i < vals.length; i++) {
            int v = vals[i];
            Integer k = byVal.get(v);
            if (k == null) {
                String nm = name.apply(v);
                k = byName.get(nm);
                if (k == null) { k = names.size(); names.add(nm); byName.put(nm, k); }
                byVal.put(v, k);
            }
            out[i] = k;
        }
        return out;
    }

    private static void writeIndices(DataOutputStream out, int[] idx, int n) throws IOException {
        if (n <= 1) return;
        if (n <= 256) for (int v : idx) out.writeByte(v);
        else for (int v : idx) out.writeShort(v);
    }

    private static int[] readIndices(DataInputStream in, int len, int n) throws IOException {
        int[] idx = new int[len];
        if (n <= 1) return idx;
        for (int i = 0; i < len; i++) {
            idx[i] = n <= 256 ? in.readUnsignedByte() : in.readUnsignedShort();
            if (idx[i] >= n) throw new RegionFile.Corrupt("куб: индекс палитры " + idx[i]);
        }
        return idx;
    }
}
