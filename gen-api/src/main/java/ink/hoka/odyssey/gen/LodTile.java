package ink.hoka.odyssey.gen;

/**
 * Плитка дальней прорисовки (LOD): n×n ячеек со стороной {@code step} блоков, угол (x0, z0). Генератор заполняет
 * верх каждой ячейки — каким мир выглядит издалека: высота верхнего блока (земля, кроны, снег), его состояние,
 * уровень воды над ним и биом (для оттенка травы/листвы/воды). Ячейка (i, j) — блоки [x0+i·step, x0+(i+1)·step) по X,
 * то же по Z с j. Брать можно любую точку ячейки (обычно центр) — это вид издалека, не точная копия.
 */
public final class LodTile {

    /** Над ячейкой нет воды. */
    public static final short NO_WATER = Short.MIN_VALUE;

    public final int x0, z0, step, n;
    /** Y верхнего непрозрачного блока ячейки. */
    public final short[] top;
    /** Состояние этого блока (номер {@link GenIds}); 0 — ячейка пуста (воздух до низа мира). */
    public final int[] state;
    /** Y поверхности воды над ячейкой или {@link #NO_WATER}. */
    public final short[] water;
    /** Номер биома (как в {@link PlanetGenerator#columnBiome}), −1 — первый из реестра. */
    public final short[] biome;
    /**
     * Кроны над ячейкой (по желанию генератора): доля площади под кронами 0…255, высота верха крон (Y) и состояние
     * листвы. Издалека лес — это смесь земли и крон по доле, а не сплошной ковёр: так цвет совпадает с ближним миром.
     */
    public final byte[] cover;
    public final short[] canopy;
    public final int[] canopyState;

    public LodTile(int x0, int z0, int step, int n) {
        if (step < 1 || n < 1 || n > 256) throw new IllegalArgumentException("step " + step + ", n " + n);
        this.x0 = x0;
        this.z0 = z0;
        this.step = step;
        this.n = n;
        top = new short[n * n];
        state = new int[n * n];
        water = new short[n * n];
        biome = new short[n * n];
        java.util.Arrays.fill(water, NO_WATER);
        java.util.Arrays.fill(biome, (short) -1);
        cover = new byte[n * n];
        canopy = new short[n * n];
        canopyState = new int[n * n];
    }

    /** Кроны ячейки (i, j): доля 0…1, верх крон Y, состояние листвы. */
    public void canopy(int i, int j, double share, int topY, int leavesState) {
        int k = j * n + i;
        cover[k] = (byte) Math.max(0, Math.min(255, (int) Math.round(share * 255)));
        canopy[k] = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, topY));
        canopyState[k] = leavesState;
    }

    /** Доля крон 0…1. */
    public double cover(int k) { return (cover[k] & 0xFF) / 255.0; }

    /** Центр ячейки (i, j) — мировые X и Z. */
    public int cx(int i) { return x0 + i * step + (step >> 1); }

    public int cz(int j) { return z0 + j * step + (step >> 1); }

    /** Записать ячейку (i, j). water — {@link #NO_WATER}, если воды нет. */
    public void set(int i, int j, int topY, int stateId, int waterY, int biomeId) {
        int k = j * n + i;
        top[k] = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, topY));
        state[k] = stateId;
        water[k] = waterY == NO_WATER ? NO_WATER : (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, waterY));
        biome[k] = (short) biomeId;
    }
}
