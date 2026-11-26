package ink.hoka.odyssey.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.function.Predicate;

/**
 * 0.63.6: блоки секции целиком одним проходом — через её сетевую форму (PalettedContainer.write: биты, палитра,
 * упакованные индексы), как CubeHash. 4096 PalettedContainer.get (палитра + хранилище — два непрямых вызова на
 * клетку) дороже: это был горячий class_2841.method_12331 (PalettedContainer.get) в профиле полёта — из
 * TallHeight.topIn. Только главный поток (общие буферы; PalettedContainer — с потока, который им владеет).
 */
public final class SectionIds {

    private SectionIds() {}

    private static final ByteBuf RAW = Unpooled.buffer(8192);
    private static final FriendlyByteBuf BUF = new FriendlyByteBuf(RAW);
    private static final int[] IDS = new int[4096], TOPS = new int[256], OFF = new int[1];
    /** Предикат → признак по номеру состояния: 0 — не считали, 1 — да, 2 — нет. Один предикат (BLOCKS) на всё. */
    private static Predicate<BlockState> cachedFor;
    private static byte[] cache = new byte[0];

    /** Номера состояний блоков (y&lt;&lt;8|z&lt;&lt;4|x) в общий массив; null — форма не разобралась. */
    static int[] blocks(LevelChunkSection s) {
        try {
            BUF.clear();
            s.getStates().write(BUF);
            OFF[0] = RAW.arrayOffset() + RAW.readerIndex();
            CubeHash.container(RAW.array(), OFF, IDS, 8);
            return IDS;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Верхняя клетка (0..15) каждой колонки (z&lt;&lt;4|x), где p истинно; -1 — нет. Общий массив (до следующего вызова);
     * null — не разобралась форма (звать поклеточно).
     */
    static int[] tops(LevelChunkSection s, Predicate<BlockState> p) {
        int[] ids = blocks(s);
        if (ids == null) return null;
        if (cachedFor != p) { cachedFor = p; cache = new byte[0]; }
        int[] t = TOPS;
        java.util.Arrays.fill(t, -1);
        int left = 256;
        for (int y = 15; y >= 0 && left > 0; y--) {
            int base = y << 8;
            for (int c = 0; c < 256; c++) {
                if (t[c] >= 0) continue;
                if (test(ids[base | c], p)) { t[c] = y; left--; }
            }
        }
        return t;
    }

    private static boolean test(int id, Predicate<BlockState> p) {
        byte[] k = cache;
        if (id >= 0 && id < k.length && k[id] != 0) return k[id] == 1;
        BlockState st = Block.BLOCK_STATE_REGISTRY.byId(id);
        boolean r = st != null && p.test(st);
        if (id >= 0) {
            if (id >= k.length) cache = k = java.util.Arrays.copyOf(k, Math.max(id + 1, Math.max(1024, k.length * 2)));
            k[id] = (byte) (r ? 1 : 2);
        }
        return r;
    }
}
