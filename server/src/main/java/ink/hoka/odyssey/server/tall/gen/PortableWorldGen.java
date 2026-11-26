package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.worldgen.CubeBuffer;
import ink.hoka.odyssey.api.worldgen.HeightKind;
import ink.hoka.odyssey.api.worldgen.WorldGenerator;
import ink.hoka.odyssey.gen.GenArrayCube;
import ink.hoka.odyssey.gen.GenContext;
import ink.hoka.odyssey.gen.PlanetGenerator;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;

/**
 * PlanetGenerator под старым WorldGenerator (на Block). ModuleCubeGen генерирует напрямую целыми числами (pg);
 * этот переходник — для старых путей (GenTest, uniform/columnBiome на Block).
 */
public final class PortableWorldGen implements WorldGenerator {

    public final PlanetGenerator pg;
    public final GenContext ctx;

    PortableWorldGen(PlanetGenerator pg, GenContext ctx) { this.pg = pg; this.ctx = ctx; }

    @Override
    public void generate(CubeBuffer out) {
        GenArrayCube c = new GenArrayCube(false).reset(out.cx(), out.cy(), out.cz(), out.seed());
        pg.generate(c);
        for (int i = 0; i < 4096; i++) {
            int v = c.blocks[i];
            if (v == 0) continue;
            Block b = Block.fromStateId(v);
            if (b != null) out.set(i & 15, i >> 8, (i >> 4) & 15, b);
        }
        var reg = MinecraftServer.getBiomeRegistry();
        for (int i = 0; i < 64; i++) {
            int v = c.biomes[i];
            if (v == 0) continue;
            var k = reg.getKey(v);
            if (k != null) out.biome(i & 3, i >> 4, (i >> 2) & 3, k);
        }
    }

    @Override public boolean hasBlocks(int cx, int cy, int cz) { return pg.hasBlocks(cx, cy, cz); }

    @Override
    public Block uniform(int cx, int cy, int cz) {
        int u = pg.uniform(cx, cy, cz);
        return u < 0 ? null : Block.fromStateId(u);
    }

    @Override
    public int highest(int x, int z, int yFrom, int yTo, HeightKind k) {
        return pg.highest(x, z, yFrom, yTo, ink.hoka.odyssey.gen.HeightKind.values()[k.ordinal()]);
    }

    @Override
    public RegistryKey<Biome> columnBiome(int x, int z) {
        int b = pg.columnBiome(x, z);
        return b < 0 ? null : MinecraftServer.getBiomeRegistry().getKey(b);
    }

    @Override public void close() { pg.close(); }
}
