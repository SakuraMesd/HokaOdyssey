package ink.hoka.odyssey.server.tall;

import ink.hoka.odyssey.api.worldgen.GenSettings;
import ink.hoka.odyssey.gen.GenWire;
import ink.hoka.odyssey.server.tall.gen.GeneratorRegistry;
import ink.hoka.odyssey.server.tall.gen.ModuleCubeGen;
import ink.hoka.odyssey.server.tall.region.RegionFiles;
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import net.minestom.server.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.59: нетронутые кубы переносимого генератора (flat) — меткой op 21, а не op 2; зона вокруг игрока грузится;
 * тронутый куб — op 2 и снятие метки; ядро 0.58 — всё по-старому; «тронут» переживает сохранение и загрузку.
 */
class CubeStreamerClientGenTest {

    static InstanceContainer inst;
    static CubeRegionLoader loader;

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    static Path tmp;

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        var key = MinecraftServer.getDimensionTypeRegistry().register(Key.key("hoka:tall_client_gen_test"),
                TallWorld.tall(DimensionType.builder().build(), TallWorld.MIN_Y, TallWorld.HEIGHT, true, 0));
        inst = MinecraftServer.getInstanceManager().createInstanceContainer(key);
        loader = new CubeRegionLoader(tmp.resolve("map"), null);
        var flat = GeneratorRegistry.get("flat").provider();
        loader.setGen(new ModuleCubeGen(flat, new GenSettings("t", 0, flat.defaultBounds(), Map.of()), 1L));
        loader.setLazy(true);
        inst.setChunkLoader(loader);
        TallWorld.markCubes(inst);
    }

    static int idx(int sec) { return sec - (TallWorld.MIN_Y >> 4); }

    /** Игрок с ядром core; 0.59 — op 20 и ответ «ок». */
    static Player player(CubeStreamerClientLightTest.Conn conn, String core, boolean ok) {
        Player p = CubeStreamerClientLightTest.player(conn, core);
        // позиция — по умолчанию (0, 0, 0): куб игрока (0, 0, 0), зона ±1 — кубы Y −1…1 столбцов −1…1
        ClientGen.enter(p, inst);
        if (ok) ClientGen.onStatus(p, new GenWire.Status(ClientGen.slot(p), GenWire.ST_OK, 2));
        return p;
    }

    static List<byte[]> msgs(CubeStreamerClientLightTest.Conn c, int op) {
        List<byte[]> r = new ArrayList<>();
        for (SendablePacket s : c.sent)
            if (s instanceof PluginMessagePacket pm && pm.channel().equals(CubeStreamer.CHANNEL) && pm.data()[0] == op) r.add(pm.data());
        return r;
    }

    @Test
    void defSentAndSlotZeroWithoutGen() {
        var conn = new CubeStreamerClientLightTest.Conn();
        Player p = player(conn, "0.59.0", false);
        var d = GenWire.decodeDef(msgs(conn, 20).get(0));
        assertEquals("flat", d.id());
        assertEquals("builtin:flat", d.filePath(), "встроенный — без jar");
        assertNotEquals(0, d.slot());
        assertNull(ClientGen.active(p, inst), "без op 8 «ок» — старый путь");
        var old = new CubeStreamerClientLightTest.Conn();
        CubeStreamerClientLightTest.player(old, "0.58.0");
        ClientGen.enter(CubeStreamerClientLightTest.player(old, "0.58.0"), inst);
        assertTrue(msgs(old, 20).isEmpty(), "ядру 0.58 op 20 не шлём");
    }

    @Test
    void untouchedGoAsLabels() throws Exception {
        CubeColumn c = CubeColumn.generated(inst, 5, 5);           // вне зоны игрока
        assertTrue(c.isLazy());
        var conn = new CubeStreamerClientLightTest.Conn();
        Player p = player(conn, "0.59.0", true);
        assertNotNull(ClientGen.active(p, inst));
        conn.sent.clear();
        BitSet mask = CubeStreamer.updateForTest(p, c, 2, 4);
        assertTrue(msgs(conn, 2).isEmpty(), "блоков op 2 нет");
        var cubes = GenWire.decodeCubes(msgs(conn, 21).get(0));
        var col = cubes.cols().get(0);
        assertEquals(5, col.cx());
        List<Integer> ys = new ArrayList<>();
        for (var k : col.cubes()) ys.add(k.sy());
        assertEquals(List.of(0, 1, 2), ys, "земля flat: 3 непустых куба");
        assertEquals(Block.STONE.stateId(), col.cubes().get(1).uniformState(), "y 16…31 — сплошной камень: заливкой");
        assertEquals(-1, col.cubes().get(0).uniformState());
        for (int sy = 0; sy <= 2; sy++) {
            assertTrue(mask.get(CubeStreamer.genbBit(idx(sy))), "бит метки " + sy);
            assertFalse(c.ready(idx(sy)), "сервер куб не грузил " + sy);
            assertEquals("не в памяти", c.stateName(idx(sy)), "и не просил");
        }
        // повторный update — метки второй раз не шлются
        conn.sent.clear();
        CubeStreamer.updateForTest(p, c, 2, 4, mask);
        assertTrue(msgs(conn, 21).isEmpty() && msgs(conn, 2).isEmpty());
        // окно ушло вверх — метки вне окна снимаются op 4
        conn.sent.clear();
        CubeStreamer.updateForTest(p, c, 40, 4, mask);
        assertFalse(msgs(conn, 4).isEmpty(), "op 4 по меткам вне окна");
        assertFalse(mask.get(CubeStreamer.genbBit(idx(1))));
    }

    @Test
    void zoneRequestedAndHashed() throws Exception {
        CubeColumn c = CubeColumn.generated(inst, 0, 0);           // столбец игрока
        var conn = new CubeStreamerClientLightTest.Conn();
        Player p = player(conn, "0.59.0", true);
        CubeStreamer.updateForTest(p, c, 2, 4);
        assertNotEquals("не в памяти", c.stateName(idx(0)), "куб игрока (зона) запрошен");
        assertEquals("не в памяти", c.stateName(idx(2)), "куб вне зоны (y 32…47) — не просили");
        c.whenReady(idx(2), 0).get(20, TimeUnit.SECONDS);
        // куб в памяти и не тронут — метка с хэшем (бесплатная сверка): новый игрок
        var conn2 = new CubeStreamerClientLightTest.Conn();
        Player p2 = player(conn2, "0.59.0", true);
        conn2.sent.clear();
        BitSet mask = CubeStreamer.updateForTest(p2, c, 2, 4);
        var col = GenWire.decodeCubes(msgs(conn2, 21).get(0)).cols().get(0);
        var k2 = col.cubes().stream().filter(k -> k.sy() == 2).findFirst().orElseThrow();
        c.lockReadLock();
        try { assertEquals(c.cubeHash(idx(2)), k2.hash(), "хэш = CubeHash куба сервера"); } finally { c.unlockReadLock(); }
        assertTrue(mask.get(4096 + idx(2)), "куб в памяти — бит LIT: правило записи пускает");
    }

    @Test
    void touchedGoesAsBlocks() throws Exception {
        CubeColumn c = CubeColumn.generated(inst, 9, 9);
        var conn = new CubeStreamerClientLightTest.Conn();
        Player p = player(conn, "0.59.0", true);
        BitSet mask = CubeStreamer.updateForTest(p, c, 2, 4);
        assertTrue(mask.get(CubeStreamer.genbBit(idx(2))));
        c.whenReady(idx(2), 0).get(20, TimeUnit.SECONDS);
        assertFalse(c.touched(idx(2)));
        c.lockWriteLock();
        try { c.setBlock(9 * 16 + 3, 41, 9 * 16 + 3, Block.GOLD_BLOCK); } finally { c.unlockWriteLock(); }
        assertTrue(c.touched(idx(2)), "правка — куб тронут");
        conn.sent.clear();
        CubeStreamer.updateForTest(p, c, 2, 4, mask);
        assertFalse(mask.get(CubeStreamer.genbBit(idx(2))), "метка снята");
        assertFalse(msgs(conn, 2).isEmpty(), "тронутый — op 2");
        // «тронут» переживает сохранение и новую загрузку столбца (куб на диске)
        loader.save(c);
        RegionFiles.closeUnder(tmp.resolve("map"));
        CubeColumn again = (CubeColumn) loader.loadChunk(inst, 9, 9);
        assertTrue(again.touched(idx(2)), "с диска — тронут");
        assertFalse(again.touched(idx(1)), "соседний — нет");
        // новый игрок: тронутый куб — не меткой
        var conn3 = new CubeStreamerClientLightTest.Conn();
        Player p3 = player(conn3, "0.59.0", true);
        BitSet m3 = CubeStreamer.updateForTest(p3, again, 2, 4);
        assertFalse(m3.get(CubeStreamer.genbBit(idx(2))));
        assertTrue(m3.get(CubeStreamer.genbBit(idx(1))));
    }

    @Test
    void reenterSameGeneratorNoWait() {
        var conn = new CubeStreamerClientLightTest.Conn();
        Player p = player(conn, "0.59.0", true);
        int s1 = ClientGen.slot(p);
        ClientGen.enter(p, inst);                                    // снова в тот же мир (тот же генератор)
        assertNotEquals(s1, ClientGen.slot(p), "новый слот");
        assertNotNull(ClientGen.active(p, inst), "тот же генератор, клиент уже принял — без ожидания op 8");
        ClientGen.onStatus(p, new GenWire.Status(ClientGen.slot(p), GenWire.ST_OFF, 0));
        assertNull(ClientGen.active(p, inst));
        ClientGen.enter(p, inst);
        assertNull(ClientGen.active(p, inst), "клиент выключил — снова ждём op 8");
    }

    @Test
    void oldCoreUnchanged() throws Exception {
        CubeColumn c = CubeColumn.generated(inst, 12, 12);
        var conn = new CubeStreamerClientLightTest.Conn();
        Player p = CubeStreamerClientLightTest.player(conn, "0.58.0");
        ClientGen.enter(p, inst);
        BitSet mask = CubeStreamer.updateForTest(p, c, 2, 4);
        assertTrue(msgs(conn, 21).isEmpty(), "0.58 — меток нет");
        assertNotEquals("не в памяти", c.stateName(idx(2)), "0.58 — кубы окна запрошены");
        assertFalse(mask.get(CubeStreamer.genbBit(idx(2))));
    }
}
