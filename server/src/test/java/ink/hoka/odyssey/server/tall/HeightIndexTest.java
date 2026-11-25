package ink.hoka.odyssey.server.tall;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.data.LightData;
import net.minestom.server.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** HeightIndex (шаг 4): правка по блоку = полный пересчёт; skyTop по-игровому; вода гасит небо по 1. */
class HeightIndexTest {

    static Instance inst;

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        var key = MinecraftServer.getDimensionTypeRegistry().register(Key.key("hoka:tall_test"),
                TallWorld.tall(DimensionType.builder().build(), TallWorld.MIN_Y, TallWorld.HEIGHT, true, 0));
        inst = MinecraftServer.getInstanceManager().createInstanceContainer(key);
    }

    private static void assertSame(TallChunk c) {
        HeightIndex full = new HeightIndex();
        full.recompute(c.getSections(), c.getMinSection());
        for (var k : HeightIndex.Kind.values())
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                assertEquals(full.get(k, x, z), c.height(k, x, z), k + " " + x + " " + z);
    }

    @Test
    void generatedColumn() {
        TallChunk c = TallChunk.generated(inst, 0, 0);
        assertEquals(15000, c.height(HeightIndex.Kind.SKY, 0, 0));
        assertEquals(40, c.height(HeightIndex.Kind.SURFACE, 8, 8));
        assertEquals(HeightIndex.NONE, TallChunk.generated(inst, TallChunk.YARD_X0 >> 4, 0).height(HeightIndex.Kind.SURFACE, 0, 0));
        assertFalse(c.isModified());
    }

    @Test
    void incrementalEqualsFull() {
        TallChunk c = TallChunk.generated(inst, 5, 5);
        Random r = new Random(1);
        Block[] pal = {Block.AIR, Block.AIR, Block.STONE, Block.WATER, Block.OAK_LEAVES, Block.GLASS, Block.SNOW,
                Block.OAK_SLAB, Block.OAK_SLAB.withProperty("type", "top"), Block.TORCH, Block.COBWEB, Block.FARMLAND};
        for (int t = 0; t < 4000; t++) {
            int x = 80 + r.nextInt(4), z = 80 + r.nextInt(4);
            int y = t % 3 == 0 ? 30 + r.nextInt(30) : r.nextInt(3) == 0 ? 2000 + r.nextInt(40) : 35 + r.nextInt(10);
            set(c, x, y, z, pal[r.nextInt(pal.length)]);
        }
        assertSame(c);
        for (int x = 80; x < 84; x++) for (int z = 80; z < 84; z++) for (int y = 2060; y >= 0; y--) set(c, x, y, z, Block.AIR);
        assertSame(c);
        assertEquals(HeightIndex.NONE, c.height(HeightIndex.Kind.SURFACE, 80, 80));
    }

    @Test
    void vanillaSkyPredicate() {
        TallChunk c = TallChunk.generated(inst, 9, 9);
        set(c, 144, 41, 144, Block.WATER);
        assertEquals(41, c.height(HeightIndex.Kind.SKY, 0, 0));          // вода ослабляет небо
        assertEquals(41, c.height(HeightIndex.Kind.MOTION, 0, 0));
        set(c, 145, 41, 144, Block.GLASS);
        assertEquals(40, c.height(HeightIndex.Kind.SKY, 1, 0));          // стекло — нет
        assertEquals(41, c.height(HeightIndex.Kind.SURFACE, 1, 0));
        set(c, 146, 41, 144, Block.SNOW);
        assertEquals(40, c.height(HeightIndex.Kind.SKY, 2, 0));          // снег-слой сам светлый, под ним темно
        set(c, 147, 45, 144, Block.OAK_LEAVES);
        assertEquals(45, c.height(HeightIndex.Kind.SKY, 3, 0));
    }

    /** Пруд глубиной 3 над камнем: под водой небо гаснет по 1 за блок (14, 13, 12), сверху 15. */
    @Test
    void waterDimsSky() {
        TallChunk c = TallChunk.generated(inst, 12, 12);
        for (int x = 192; x < 208; x++) for (int z = 192; z < 208; z++) for (int y = 41; y <= 43; y++) set(c, x, y, z, Block.WATER);
        LightData l = c.light();
        int sec = (41 >> 4) - c.getMinSection();                          // секция 2 (y 32…47)
        assertTrue(l.skyMask().get(sec + 1));
        int n = 0;
        for (int b = l.skyMask().nextSetBit(0); b >= 0 && b < sec + 1; b = l.skyMask().nextSetBit(b + 1)) n++;
        byte[] a = l.skyLight().get(n);
        assertEquals(15, get(a, 8, 44 & 15, 8));
        assertEquals(14, get(a, 8, 43 & 15, 8));
        assertEquals(13, get(a, 8, 42 & 15, 8));
        assertEquals(12, get(a, 8, 41 & 15, 8));
        assertEquals(0, get(a, 8, 40 & 15, 8));                           // дёрн
    }

    /** Запись как у Instance.setBlock — под замком столбца. */
    private static void set(TallChunk c, int x, int y, int z, Block b) {
        c.lockWriteLock();
        try { c.setBlock(x, y, z, b); } finally { c.unlockWriteLock(); }
    }

    private static int get(byte[] arr, int x, int y, int z) {
        int i = y << 8 | z << 4 | x;
        return (arr[i >> 1] >> ((i & 1) * 4)) & 0xF;
    }
}
