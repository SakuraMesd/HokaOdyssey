package ink.hoka.odyssey.server.tall;

import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.heightmap.Heightmap;

/**
 * Карта высот CubeColumn (шаг 9): значения берутся из HeightIndex столбца, по секциям не спускается (Minestom-овский
 * refresh прошёл бы 1876 секций через getSection). Пересчёт — только HeightIndex (setBlock/afterLoad), тут ничего.
 * Семантика как у Minestom: getHeight — Y верхнего блока, нет блока — minY − 1 (= HeightIndex.NONE).
 */
final class CubeHeightmap extends Heightmap {

    private final CubeColumn column;
    private final HeightIndex.Kind kind;
    private final int minHeight, bits;

    CubeHeightmap(CubeColumn column, HeightIndex.Kind kind) {
        super(column);
        this.column = column;
        this.kind = kind;
        var dim = column.getInstance().getCachedDimensionType();
        this.minHeight = dim.minY() - 1;
        this.bits = Integer.SIZE - Integer.numberOfLeadingZeros(dim.height());   // как MathUtils.bitsToRepresent
    }

    @Override public Type type() { return kind == HeightIndex.Kind.MOTION ? Type.MOTION_BLOCKING : Type.WORLD_SURFACE; }

    @Override
    protected boolean checkBlock(Block block) {
        return HeightIndex.contrib(HeightIndex.info(block.stateId()), kind, 0) != HeightIndex.NONE;
    }

    // высоты ведёт HeightIndex — пересчёты Minestom ничего не делают
    @Override public void refresh(int x, int y, int z, Block block) { }
    @Override public void refresh(int startY) { }
    @Override public void refresh(int x, int z, int startY) { }
    @Override public void loadFrom(long[] data) { }

    @Override
    public int getHeight(int x, int z) {
        int y = column.st().heights.get(kind, x, z);
        return y == HeightIndex.NONE ? minHeight : y;                          // Minestom: нет блока — minY − 1
    }

    @Override
    public long[] getNBT() {
        short[] h = new short[256];
        for (int i = 0; i < 256; i++) {
            int y = column.st().heights.get(kind, i & 15, i >> 4);
            h[i] = (short) (Math.max(y, minHeight) - minHeight);
        }
        return encode(h, bits);
    }

    /** Как Heightmap.encode Minestom (он package-private). */
    private static long[] encode(short[] heights, int bitsPerEntry) {
        int perLong = 64 / bitsPerEntry;
        long[] data = new long[(heights.length + perLong - 1) / perLong];
        long mask = (1L << bitsPerEntry) - 1;
        for (int i = 0; i < heights.length; i++)
            data[i / perLong] |= (heights[i] & mask) << ((i % perLong) * bitsPerEntry);
        return data;
    }
}
