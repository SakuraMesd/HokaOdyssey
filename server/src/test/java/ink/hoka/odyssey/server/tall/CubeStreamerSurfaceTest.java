package ink.hoka.odyssey.server.tall;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 0.62: полоса поверхности (горы целиком) — кубы поверхности выше/ниже окна идут ядру 0.62+, старым — нет. */
class CubeStreamerSurfaceTest {

    static Instance inst;

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        var key = MinecraftServer.getDimensionTypeRegistry().register(Key.key("hoka:tall_surface_test"),
                TallWorld.tall(DimensionType.builder().build(), TallWorld.MIN_Y, TallWorld.HEIGHT, true, 0));
        inst = MinecraftServer.getInstanceManager().createInstanceContainer(key);
    }

    private static int idx(int sec) { return sec - (TallWorld.MIN_Y >> 4); }

    private static Player player(String core) {
        Player p = new Player(new CubeStreamerClientLightTest.Conn(), new GameProfile(UUID.randomUUID(), "s" + core.replace('.', '_')));
        Handshake.putForTest(p.getUuid(), core);
        return p;
    }

    /** Столбец-«гора»: сплошь камень от y=0 до верха top (по всем клеткам), под ним — пусто. */
    private static CubeColumn mountain(int cx, int cz, int top) {
        CubeColumn c = CubeColumn.generated(inst, cx, cz);
        c.lockWriteLock();
        try {
            for (int y = 0; y <= top; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                c.setBlock(x, y, z, Block.STONE);
        } finally { c.unlockWriteLock(); }
        return c;
    }

    @Test
    void bandOnlyForNewCore() {
        CubeColumn c = mountain(40, 40, 60 * 16 + 3);                        // верх в кубе 60
        BitSet m = CubeStreamer.updateForTest(player("0.62.0"), c, 2, 4);
        assertTrue(m.get(idx(60)), "верх горы (куб 60) ушёл");
        assertTrue(m.get(idx(4)) && m.get(idx(7)), "окно ±(v+1) как было");
        // 0.63.4: соседей нет в памяти — их высоты по генератору (тестовая земля до y=40): отвесная стена горы видна до земли
        assertTrue(m.get(idx(30)) && m.get(idx(3)), "стена до земли соседа (генератор)");
        assertFalse(m.get(idx(-10)), "ниже земли соседа не шлётся");
        BitSet old = CubeStreamer.updateForTest(player("0.61.0"), c, 2, 4);
        assertFalse(old.get(idx(60)), "старое ядро — только окно");
        assertTrue(old.get(idx(4)));
    }

    @Test
    void cliffAddsSideCubes() {
        CubeColumn low = mountain(50, 50, 20 * 16);                          // соседний столбец ниже на 20 кубов
        CubeColumn high = mountain(51, 50, 40 * 16 + 8);
        assertNotNull(low);
        long b = CubeStreamer.band(high, (x, z) -> x == 50 && z == 50 ? low : null);
        assertEquals(40 * 16 + 8, (int) b, "верх полосы");
        assertEquals(20 * 16 + 1, (int) (b >> 32), "низ — над соседом: стена утёса видна сбоку");
        assertEquals(40 * 16 + 8, (int) (CubeStreamer.band(high, (x, z) -> null) >> 32), "без соседей — низ по своим клеткам");
        // соседей нет в тестовом инстансе — высоты края по генератору (земля до y=40): стена до неё
        BitSet m = CubeStreamer.updateForTest(player("0.62.0"), high, 2, 4);
        assertTrue(m.get(idx(40)) && m.get(idx(30)) && m.get(idx(15)));
    }

    /** Генератор-заглушка: высота SURFACE по столбцу (cx → y), остальное не нужно. */
    private static CubeGen heights(java.util.function.IntUnaryOperator byCx) {
        return new CubeGen() {
            @Override public void generateCube(int cx, int cy, int cz, net.minestom.server.instance.generator.GenerationUnit unit) {}
            @Override public int highest(int x, int z, int yFrom, int yTo, HeightIndex.Kind kind) { return byCx.applyAsInt(x >> 4); }
            @Override public net.minestom.server.registry.RegistryKey<net.minestom.server.world.biome.Biome> biomeAt(int x, int z) { return null; }
        };
    }

    @Test
    void tallWallWholeHeight() {
        // стена 900→1800: раньше низ обрезался на 32 куба (512 блоков) ниже — 1288, дыра 901…1287
        CubeColumn low = mountain(70, 70, 900);
        CubeColumn high = mountain(71, 70, 1800);
        long b = CubeStreamer.band(high, (x, z) -> x == 70 && z == 70 ? low : null);
        assertEquals(1800, (int) b);
        assertEquals(901, (int) (b >> 32), "сосед загружен: стена на всю высоту");
        // сосед НЕ загружен — высоты края генератором
        long g = CubeStreamer.band(high, (x, z) -> null, heights(cx -> cx == 70 ? 900 : 1800));
        assertEquals(901, (int) (g >> 32), "сосед не загружен: стена по генератору");
        // генератор не знает дёшево — как раньше, по своим клеткам (дошлёт heal, когда сосед загрузится)
        CubeGen unk = new CubeGen() {
            @Override public void generateCube(int cx, int cy, int cz, net.minestom.server.instance.generator.GenerationUnit unit) {}
            @Override public int highest(int x, int z, int yFrom, int yTo, HeightIndex.Kind kind) { return 0; }
            @Override public int highestCheap(int x, int z, int yFrom, int yTo, HeightIndex.Kind kind) { return UNKNOWN; }
            @Override public net.minestom.server.registry.RegistryKey<net.minestom.server.world.biome.Biome> biomeAt(int x, int z) { return null; }
        };
        assertEquals(1800, (int) (CubeStreamer.band(high, (x, z) -> null, unk) >> 32));
        // пропасть (у соседа пусто) — не глубже SURFACE_DEPTH кубов
        long v = CubeStreamer.band(high, (x, z) -> null, heights(cx -> cx == 70 ? HeightIndex.NONE : 1800));
        assertEquals(1800 - CubeStreamer.SURFACE_DEPTH * 16, (int) (v >> 32));
    }

    @Test
    void bandDroppedWhenFar() {
        CubeColumn c = mountain(60, 60, 60 * 16 + 3);
        Player p = player("0.62.0");
        BitSet m = CubeStreamer.updateForTest(p, c, 2, 4);
        assertTrue(m.get(idx(60)));
        // игрок ушёл глубоко вниз: полоса дальше предела — выгрузить
        CubeStreamer.updateForTest(p, c, 2 - CubeStreamer.SURFACE_MAX - 80, 4, m);
        assertFalse(m.get(idx(60)), "полоса за пределом ±S — выгружена");
    }

    @Test
    void spanClearOutside() {
        var sp = new CubeStreamer.Span(10, 20, 40, 45);
        BitSet m = new BitSet();
        m.set(0, 100);
        sp.clearOutside(m, 0, 100);
        for (int i = 0; i < 100; i++) assertEquals(sp.has(i), m.get(i), "бит " + i);
        assertEquals(6, sp.extra());
        var ov = new CubeStreamer.Span(10, 20, 18, 30);
        m.set(0, 100);
        ov.clearOutside(m, 0, 100);
        for (int i = 0; i < 100; i++) assertEquals(ov.has(i), m.get(i), "перекрытие, бит " + i);
        assertEquals(10, ov.extra());
        var below = new CubeStreamer.Span(50, 60, 5, 7);
        m.set(0, 100);
        below.clearOutside(m, 0, 100);
        for (int i = 0; i < 100; i++) assertEquals(below.has(i), m.get(i), "ниже, бит " + i);
    }
}
