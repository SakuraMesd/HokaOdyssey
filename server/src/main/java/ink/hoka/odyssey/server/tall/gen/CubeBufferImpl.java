package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.worldgen.CubeBuffer;
import ink.hoka.odyssey.server.tall.CubeGen;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;

/**
 * Буфер куба для генератора модуля: пишет прямо в палитры новой секции (без @Internal GeneratorImpl Minestom).
 * Блоки с NBT/обработчиком — ещё и в specials (ключ — CoordConversion.chunkBlockIndex по локальным координатам,
 * как у CubeGen.Cube). Один буфер — один поток.
 */
final class CubeBufferImpl implements CubeBuffer {

    private final int cx, cy, cz;
    private final long seed;
    final Section section = new Section();
    final Int2ObjectOpenHashMap<Block> specials = new Int2ObjectOpenHashMap<>(0);

    CubeBufferImpl(int cx, int cy, int cz, long seed) { this.cx = cx; this.cy = cy; this.cz = cz; this.seed = seed; }

    @Override public int cx() { return cx; }
    @Override public int cy() { return cy; }
    @Override public int cz() { return cz; }
    @Override public long seed() { return seed; }

    private static boolean special(Block b) { return b.hasNbt() || b.handler() != null; }

    @Override
    public void set(int lx, int ly, int lz, Block b) {
        if ((lx | ly | lz) < 0 || lx > 15 || ly > 15 || lz > 15) throw new IndexOutOfBoundsException(lx + " " + ly + " " + lz);
        section.blockPalette().set(lx, ly, lz, b.stateId());
        int k = CoordConversion.chunkBlockIndex(lx, ly, lz);
        if (special(b)) specials.put(k, b);
        else if (!specials.isEmpty()) specials.remove(k);
    }

    @Override
    public void setAbs(int x, int y, int z, Block b) {
        if (x >> 4 != cx || y >> 4 != cy || z >> 4 != cz) return;              // мимо куба — отбросить
        set(x & 15, y & 15, z & 15, b);
    }

    @Override
    public Block get(int lx, int ly, int lz) {
        if (!specials.isEmpty()) {
            Block s = specials.get(CoordConversion.chunkBlockIndex(lx, ly, lz));
            if (s != null) return s;
        }
        int id = section.blockPalette().get(lx, ly, lz);
        Block b = id == 0 ? Block.AIR : Block.fromStateId(id);
        return b == null ? Block.AIR : b;
    }

    @Override
    public void fill(Block b) {
        specials.clear();
        if (special(b)) { fillY(0, 15, b); return; }
        section.blockPalette().fill(b.stateId());
    }

    @Override
    public void fillY(int ly0, int ly1, Block b) {
        int a = Math.max(0, ly0), e = Math.min(15, ly1);
        for (int y = a; y <= e; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) set(x, y, z, b);
    }

    @Override
    public void biome(int bx, int by, int bz, RegistryKey<Biome> k) {
        int id = MinecraftServer.getBiomeRegistry().getId(k);
        if (id < 0) throw new IllegalArgumentException("нет биома " + k);
        section.biomePalette().set(bx & 3, by & 3, bz & 3, id);
    }

    @Override
    public void fillBiome(RegistryKey<Biome> k) {
        int id = MinecraftServer.getBiomeRegistry().getId(k);
        if (id < 0) throw new IllegalArgumentException("нет биома " + k);
        section.biomePalette().fill(id);
    }

    CubeGen.Cube toCube() { return new CubeGen.Cube(section, specials); }
}
