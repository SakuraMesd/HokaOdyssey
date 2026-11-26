package ink.hoka.odyssey.client;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 0.57, свет на клиенте: сведения о состоянии блока для движка света (LocalLightEngine) — как у сервера (CubeLight.inf,
 * HeightIndex.info). Биты: 0–3 lightBlock, 4–9 полные грани формы затенения по направлениям 0…5 (+x −x +z −z −y +y;
 * у полного куба — 0, как у игры), 10–13 lightEmission, 14 — блок сам «не источник неба» (skyTop = y), 15 — только
 * нижняя грань полная (skyTop = y − 1), 16 — посчитано. Таблица по id состояния, гонка безвредна (значение одно).
 */
public final class LightInfo {

    private LightInfo() {}

    public static final int SKY_SELF = 1 << 14, SKY_BELOW = 1 << 15, LIGHT_MASK = 0x3FFF;
    private static final int DONE = 1 << 16;
    /** Направления движка: 0 +x, 1 −x, 2 +z, 3 −z, 4 −y, 5 +y (обратное — d ^ 1). */
    static final Direction[] DIRS = {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH, Direction.DOWN, Direction.UP};

    private static volatile int[] TABLE = new int[1 << 15];

    /** Все биты (с SKY_*); воздух — 0. Любой поток. */
    public static int info(BlockState st) {
        if (st == null || st.isAir()) return 0;
        int id = Block.BLOCK_STATE_REGISTRY.getId(st);
        int[] a = TABLE;
        if (id >= 0 && id < a.length) {
            int v = a[id];
            if (v != 0) return v & ~DONE;
        }
        int v = compute(st);
        if (id >= 0) {
            if (id >= a.length) { a = java.util.Arrays.copyOf(a, Integer.highestOneBit(id) << 1); TABLE = a; }
            a[id] = v | DONE;
        }
        return v;
    }

    /** Для массивов кубов: lightBlock, грани, свечение (14 бит). */
    public static short light(BlockState st) { return (short) (info(st) & LIGHT_MASK); }

    private static int compute(BlockState st) {
        int lb = Math.max(0, Math.min(15, st.getLightBlock()));
        int faces = 0;
        if (st.canOcclude() && st.useShapeForLightOcclusion())
            for (int d = 0; d < 6; d++) if (Block.isFaceFull(st.getFaceOcclusionShape(DIRS[d]), DIRS[d])) faces |= 1 << d;
        int v = lb;
        if (lb > 0 || (faces & (1 << 5)) != 0) v |= SKY_SELF;
        else if ((faces & (1 << 4)) != 0) v |= SKY_BELOW;
        if (faces == 63) faces = 0;
        v |= faces << 4;
        v |= Math.max(0, Math.min(15, st.getLightEmission())) << 10;
        return v;
    }

    static int lightBlock(int inf) { return inf & 15; }

    static int emission(int inf) { return (inf >> 10) & 15; }
}
