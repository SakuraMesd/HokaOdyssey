package ink.hoka.odyssey.client.block;

import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 0.73 Подмена номеров состояний: номер носителя (как шлёт сервер) ↔ блок мода из запаса ({@link PoolBlocks}). Читает
 * {@code Block.BLOCK_STATE_REGISTRY} (миксин IdMapperRemapMixin): чанки, кубы, обновления блоков, генерация на клиенте —
 * всё получает блок мода; обратно (getId) — номер носителя, так что на сервер и в сверки уходит то же, что раньше.
 * {@link #raw} — без подмены (таблица номеров генератора сверяется с сервером по именам носителей).
 */
public final class Remap {

    private Remap() {}

    private static volatile BlockState[] byId = new BlockState[0];
    private static volatile Map<BlockState, Integer> toId = Map.of();
    private static final ThreadLocal<Boolean> RAW = ThreadLocal.withInitial(() -> false);

    public static BlockState byId(int id) {
        BlockState[] a = byId;                                                   // горячий путь (палитры чанков): сначала дёшево
        if (id < 0 || id >= a.length) return null;
        BlockState s = a[id];
        return s == null || RAW.get() ? null : s;
    }

    /** Номер носителя для блока мода или −1. */
    public static int toId(Object st) {
        Map<BlockState, Integer> m = toId;
        if (!(st instanceof BlockState s) || !PoolBlocks.isPool(s.getBlock()) || m.isEmpty() || RAW.get()) return -1;
        Integer i = m.get(s);
        return i == null ? -1 : i;
    }

    static void set(Map<Integer, BlockState> map) {
        int max = -1;
        for (int k : map.keySet()) max = Math.max(max, k);
        BlockState[] a = new BlockState[max + 1];
        Map<BlockState, Integer> r = new IdentityHashMap<>();
        for (var e : map.entrySet()) { a[e.getKey()] = e.getValue(); r.put(e.getValue(), e.getKey()); }
        toId = r;
        byId = a;
    }

    /**
     * Уже загруженные чанки (пришли до списка блоков — в них носители): носитель → блок мода прямо в секциях. Поток
     * клиента. Пустые/общие пустые секции не трогаются (в них только воздух).
     */
    static void applyLoaded(Map<Integer, BlockState> map) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        var lvl = mc.level;
        if (lvl == null || mc.player == null || map.isEmpty()) return;
        Map<BlockState, BlockState> swap = new IdentityHashMap<>();
        for (var e : map.entrySet()) {
            BlockState carrier = raw(() -> net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(e.getKey()));
            if (carrier != null) swap.put(carrier, e.getValue());
        }
        int rd = mc.options.getEffectiveRenderDistance() + 2, pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z, n = 0;
        for (int cx = pcx - rd; cx <= pcx + rd; cx++) for (int cz = pcz - rd; cz <= pcz + rd; cz++) {
            var ch = lvl.getChunkSource().getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
            if (ch == null) continue;
            for (var sec : ch.getSections()) {
                if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(swap::containsKey)) continue;
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    BlockState to = swap.get(sec.getBlockState(x, y, z));
                    if (to != null) { sec.setBlockState(x, y, z, to, false); n++; }
                }
            }
        }
        if (n > 0) org.slf4j.LoggerFactory.getLogger("HokaOdyssey/blocks").info("блоки мода в загруженных чанках: {}", n);
    }

    static void clear() { byId = new BlockState[0]; toId = Map.of(); }

    /** Выполнить без подмены (номера как у сервера — носители). */
    public static <T> T raw(java.util.function.Supplier<T> fn) {
        boolean was = RAW.get();
        RAW.set(true);
        try { return fn.get(); } finally { RAW.set(was); }
    }
}
