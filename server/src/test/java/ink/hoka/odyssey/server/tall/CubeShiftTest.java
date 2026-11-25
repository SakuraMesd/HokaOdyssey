package ink.hoka.odyssey.server.tall;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 0.56: порядок сдвига окна по Y, столбец вне перебора тика без тикающих блоков, счётчик высот MOTION (op 17). */
class CubeShiftTest {

    static Instance inst;

    static final BlockHandler TICKER = new BlockHandler() {
        @Override public Key getKey() { return Key.key("hoka:shift_test_ticker"); }
        @Override public boolean isTickable() { return true; }
    };

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        RegistryKey<DimensionType> key = MinecraftServer.getDimensionTypeRegistry().register(Key.key("hoka:tall_shift_test"),
                TallWorld.tall(DimensionType.builder().build(), TallWorld.MIN_Y, TallWorld.HEIGHT, true, 0));
        inst = MinecraftServer.getInstanceManager().createInstanceContainer(key);
    }

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    @Test
    void orderNearestFirst() {
        int pcx = -3, pcz = 7;
        long[] keys = new long[33 * 33];
        int n = 0;
        for (int dx = -16; dx <= 16; dx++) for (int dz = 16; dz >= -16; dz--) keys[n++] = key(pcx + dx, pcz + dz);
        long[] out = CubeStreamer.order(keys, n, pcx, pcz);
        assertEquals(n, out.length);
        Set<Long> seen = new HashSet<>();
        int last = -1;
        for (long k : out) {
            assertTrue(seen.add(k), "без повторов");
            int d = Math.max(Math.abs((int) (k >> 32) - pcx), Math.abs((int) k - pcz));
            assertTrue(d >= last, "ближние первыми");
            last = d;
        }
        assertEquals(key(pcx, pcz), out[0], "столбец игрока — первым");
        assertEquals(16, last);
    }

    @Test
    void orderFarKeysClamped() {
        long[] keys = {key(2_000_000, 0), key(0, 0), key(-2_000_000, 5)};
        long[] out = CubeStreamer.order(keys, 3, 0, 0);
        assertEquals(key(0, 0), out[0]);
        assertEquals(3, new HashSet<>(java.util.List.of(out[0], out[1], out[2])).size());
    }

    @Test
    void columnLeavesTickWhenNothingTicks() {
        CubeColumn c = CubeColumn.generated(inst, 11, -4);
        assertTrue(c.inTick(), "после загрузки — в переборе");
        c.tick(0);
        assertFalse(c.inTick(), "без тикающих блоков — вынут");
        long on = CubeColumn.tickOn;
        c.lockWriteLock();
        try { c.setBlock(11 * 16 + 1, 70, -4 * 16 + 2, Block.FURNACE.withHandler(TICKER)); } finally { c.unlockWriteLock(); }
        assertTrue(c.inTick(), "тикающий блок — обратно");
        assertEquals(on + 1, CubeColumn.tickOn);
        c.tick(0);
        assertTrue(c.inTick(), "есть что тикать — остаётся");
        c.lockWriteLock();
        try { c.setBlock(11 * 16 + 1, 70, -4 * 16 + 2, Block.STONE); } finally { c.unlockWriteLock(); }
        c.tick(0);
        assertFalse(c.inTick(), "тикающий убрали — снова вынут");
    }

    @Test
    void motionVersionCounts() {
        CubeColumn c = CubeColumn.generated(inst, -9, 13);
        int v0 = c.st().heights.motionVer;
        c.lockWriteLock();
        try { c.setBlock(-9 * 16, 9000, 13 * 16, Block.STONE); } finally { c.unlockWriteLock(); }
        assertNotEquals(v0, c.st().heights.motionVer, "верх вырос — версия MOTION сменилась");
        int[] h = CubeStreamer.heights(c);
        assertEquals(-9, h[0]);
        assertEquals(13, h[1]);
        assertEquals(9001, h[2], "как ванильная карта: верх + 1, индекс z*16+x");
        int v1 = c.st().heights.motionVer;
        c.lockWriteLock();
        try { c.setBlock(-9 * 16 + 3, 100, 13 * 16 + 3, Block.AIR); } finally { c.unlockWriteLock(); }
        assertEquals(v1, c.st().heights.motionVer, "воздух в воздух — без смены");
    }
}
