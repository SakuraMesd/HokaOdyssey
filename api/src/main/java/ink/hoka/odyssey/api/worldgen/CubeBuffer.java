package ink.hoka.odyssey.api.worldgen;

import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;

/**
 * Куб 16×16×16, который заполняет генератор. Локальные координаты 0…15; {@code *Abs} — мировые, запись мимо куба
 * молча отбрасывается (так ставят деревья на стыке кубов: каждый куб рисует всё дерево, остаётся своя часть).
 * Сначала куб — воздух, биом — первый из реестра. Один буфер — один поток.
 */
public interface CubeBuffer {
    int cx();
    int cy();
    int cz();
    long seed();

    /** Мировые координаты угла куба. */
    default int x0() { return cx() << 4; }
    default int y0() { return cy() << 4; }
    default int z0() { return cz() << 4; }

    void set(int lx, int ly, int lz, Block b);

    /** Мировые координаты; вне куба — ничего. */
    void setAbs(int x, int y, int z, Block b);

    Block get(int lx, int ly, int lz);

    void fill(Block b);

    /** Слои ly0…ly1 (включительно, обрезается по 0…15). */
    void fillY(int ly0, int ly1, Block b);

    /** Биом ячейки 4×4×4 (bx, by, bz — 0…3). */
    void biome(int bx, int by, int bz, RegistryKey<Biome> k);

    void fillBiome(RegistryKey<Biome> k);
}
