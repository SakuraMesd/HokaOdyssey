package ink.hoka.odyssey.server.tall;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.TagStringIO;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.network.NetworkBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Хранение высокого мира: Anvil держит номер секции байтом (±2048 блоков), поэтому свой формат — файл на столбец
 * (папка tall/ карты), в нём только НЕПУСТЫЕ секции (палитра блоков как в пакете Minestom) и блоки с данными
 * (сундуки, таблички: состояние + NBT строкой). Пустой столбец — файла нет (сгенерируется заново).
 * Формат (gzip): int HTW2, int секций; на секцию: short номер (от нижней), int длина, палитра блоков, int длина,
 * палитра биомов; int блоков с данными; на блок: int индекс в чанке, UTF состояние, UTF NBT (пусто — нет).
 * HTW1 (старый, без биомов) читается. Столбец, не менявшийся после генерации, не пишется вовсе.
 */
public final class TallLoader implements ChunkLoader {

    private static final Logger LOG = LoggerFactory.getLogger("tall");
    private static final int MAGIC = 0x48545731;                       // HTW1
    private static final int MAGIC2 = 0x48545732;                      // HTW2: + биомы секций
    private final Path dir;
    /** Чем создавать столбец: TallChunk (tall на .htw) или CubeColumn (ленивая миграция в регионы кубов). */
    private final Factory factory;

    /** Создать пустой столбец высокой карты. */
    @FunctionalInterface
    public interface Factory { TallColumn create(Instance instance, int x, int z); }

    public TallLoader(Path dir) { this(dir, TallChunk::new); }

    public TallLoader(Path dir, Factory factory) { this.dir = dir; this.factory = factory; }

    private Path file(int x, int z) { return dir.resolve(x + "." + z + ".htw"); }

    /** Есть файл .htw столбца (CubeRegionLoader: не убирать заголовок, иначе вернётся старое из .htw). */
    boolean has(int x, int z) { return Files.exists(file(x, z)); }

    @Override
    public Chunk loadChunk(Instance instance, int x, int z) {
        Path f = file(x, z);
        if (!Files.isRegularFile(f)) return null;                       // нет — сгенерировать
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(f))))) {
            int magic = in.readInt();
            if (magic != MAGIC && magic != MAGIC2) throw new IOException("не тот формат");
            boolean biomes = magic == MAGIC2;
            var biomeSer = Palette.biomeSerializer(MinecraftServer.getBiomeRegistry().size());
            TallColumn c = factory.create(instance, x, z);
            List<Section> secs = c.getSections();
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int idx = in.readShort();
                byte[] b = new byte[in.readInt()];
                in.readFully(b);
                byte[] bb = null;
                if (biomes) { bb = new byte[in.readInt()]; in.readFully(bb); }
                if (idx < 0 || idx >= secs.size()) continue;
                Palette p = NetworkBuffer.wrap(b, 0, b.length).read(Palette.BLOCK_SERIALIZER);
                var sec = c.materialize(idx);
                sec.blockPalette().copyFrom(p);
                if (bb != null) sec.biomePalette().copyFrom(NetworkBuffer.wrap(bb, 0, bb.length).read(biomeSer));
            }
            int m = in.readInt();
            for (int i = 0; i < m; i++) {
                int key = in.readInt();
                Block b = Block.fromState(in.readUTF());
                String nbt = in.readUTF();
                if (b == null) continue;
                if (!nbt.isEmpty()) b = b.withNbt(TagStringIO.tagStringIO().asCompound(nbt));
                BlockHandler h = MinecraftServer.getBlockManager().getHandler(b.key().asString());
                if (h != null) b = b.withHandler(h);
                c.lockWriteLock();                                          // запись в entries — под замком столбца
                try { c.setBlock(CoordConversion.chunkBlockIndexGetX(key), CoordConversion.chunkBlockIndexGetY(key),
                        CoordConversion.chunkBlockIndexGetZ(key), b); }
                finally { c.unlockWriteLock(); }
            }
            c.afterLoad();
            return c.chunk();
        } catch (Exception e) {
            LOG.error("столбец {} {} не прочитан ({}) — будет сгенерирован заново, файл → .broken", x, z, e.toString());
            try { Files.move(f, f.resolveSibling(f.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { }
            return null;
        }
    }

    /** Файл на столбец, общих структур нет — грузить и сохранять можно параллельно. */
    @Override public boolean supportsParallelLoading() { return true; }
    @Override public boolean supportsParallelSaving() { return true; }

    @Override
    public void saveChunk(Chunk chunk) {
        if (chunk instanceof TallColumn tc && !tc.isModified()) return;        // как сгенерирован/как на диске — писать нечего
        Path f = file(chunk.getChunkX(), chunk.getChunkZ());
        try {
            Files.createDirectories(dir);
            // снимок под замком столбца (setBlock Minestom — тоже под ним), запись и сжатие — без замка
            List<Integer> idxs = new java.util.ArrayList<>();
            List<Palette> blocks = new java.util.ArrayList<>(), biomes = new java.util.ArrayList<>();
            Map<Integer, Block> entries;
            synchronized (chunk) {
                if (chunk instanceof TallColumn tc) tc.st().modified = false;           // правки после снимка поднимут снова
                List<Section> secs = chunk.getSections();
                for (int i = 0; i < secs.size(); i++) {
                    Section s = secs.get(i);
                    if (s.blockPalette().count() == 0) continue;
                    idxs.add(i);
                    blocks.add(s.blockPalette().clone());
                    biomes.add(s.biomePalette().clone());
                }
                entries = chunk instanceof TallColumn tc ? tc.dataBlocks() : Map.of();
            }
            var biomeSer = Palette.biomeSerializer(MinecraftServer.getBiomeRegistry().size());
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int written = 0;
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bo))) {
                out.writeInt(MAGIC2);
                out.writeInt(idxs.size());
                for (int k = 0; k < idxs.size(); k++) {
                    Palette p = blocks.get(k), bp = biomes.get(k);
                    byte[] b = NetworkBuffer.makeArray(buf -> buf.write(Palette.BLOCK_SERIALIZER, p), MinecraftServer.process());
                    byte[] bb = NetworkBuffer.makeArray(buf -> buf.write(biomeSer, bp), MinecraftServer.process());
                    out.writeShort(idxs.get(k));
                    out.writeInt(b.length);
                    out.write(b);
                    out.writeInt(bb.length);
                    out.write(bb);
                    written++;
                }
                out.writeInt(entries.size());
                for (var e : entries.entrySet()) {
                    Block b = e.getValue();
                    out.writeInt(e.getKey());
                    out.writeUTF(b.state());
                    CompoundBinaryTag nbt = b.nbt();
                    out.writeUTF(nbt == null ? "" : TagStringIO.tagStringIO().asString(nbt));
                }
            }
            if (written == 0) { Files.deleteIfExists(f); return; }
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.write(tmp, bo.toByteArray());
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            if (chunk instanceof TallColumn tc) tc.st().modified = true;              // не записалось — попробовать в следующий раз
            LOG.error("столбец {} {} не сохранён: {}", chunk.getChunkX(), chunk.getChunkZ(), e.toString());
        }
    }
}
