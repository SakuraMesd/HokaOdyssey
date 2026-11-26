package ink.hoka.odyssey.server.tall;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import net.minestom.server.network.packet.server.play.UpdateLightPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 0.57: свет считает клиент — сервер не шлёт op 11/14/16, кольцо ±1 идёт блоками, op 18 HEIGHTS2, skyVer. */
class CubeStreamerClientLightTest {

    static Instance inst;

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        var key = MinecraftServer.getDimensionTypeRegistry().register(Key.key("hoka:tall_client_light_test"),
                TallWorld.tall(DimensionType.builder().build(), TallWorld.MIN_Y, TallWorld.HEIGHT, true, 0));
        inst = MinecraftServer.getInstanceManager().createInstanceContainer(key);
    }

    /** Соединение-заглушка: копит отправленные пакеты. */
    static final class Conn extends PlayerConnection {
        final List<SendablePacket> sent = new ArrayList<>();
        @Override public void sendPacket(SendablePacket packet) { sent.add(packet); }
        @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 0); }

        /** op канала hoka_odyssey:w по порядку; UpdateLightPacket — как −1. */
        List<Integer> ops() {
            List<Integer> r = new ArrayList<>();
            for (SendablePacket s : sent) {
                if (s instanceof PluginMessagePacket pm && pm.channel().equals(CubeStreamer.CHANNEL)) r.add((int) pm.data()[0]);
                else if (s instanceof UpdateLightPacket) r.add(-1);
            }
            return r;
        }
    }

    static Player player(Conn conn, String core) {
        Player p = new Player(conn, new GameProfile(UUID.randomUUID(), "t" + core.replace('.', '_')));
        Handshake.putForTest(p.getUuid(), core);
        return p;
    }

    private static int idx(int sec) { return sec - (TallWorld.MIN_Y >> 4); }

    private static CubeColumn column(int cx, int cz) {
        CubeColumn c = CubeColumn.generated(inst, cx, cz);
        c.lockWriteLock();
        try {
            c.setBlock(3, 7 * 16 + 5, 3, Block.STONE);                      // кольцо сверху (окно 2±4 → 7)
            c.setBlock(3, -3 * 16 + 5, 3, Block.STONE);                     // кольцо снизу (−3)
        } finally { c.unlockWriteLock(); }
        return c;
    }

    @Test
    void versionGate() {
        assertTrue(Handshake.atLeast("0.57.0", Handshake.CLIENT_LIGHT_CORE));
        assertTrue(Handshake.atLeast("0.57.1+1.21.11", Handshake.CLIENT_LIGHT_CORE));
        assertFalse(Handshake.atLeast("0.56.9", Handshake.CLIENT_LIGHT_CORE));
        Conn a = new Conn(), b = new Conn();
        assertTrue(CubeStreamer.clientLight(player(a, "0.57.0")));
        Player old = player(b, "0.56.0");
        assertFalse(CubeStreamer.clientLight(old));
        assertTrue(CubeStreamer.haloOp(old), "0.56 — кольцо светом, как раньше");
    }

    @Test
    void newCoreGetsRingBlocksAndNoLight() {
        CubeColumn c = column(2, 2);
        Conn conn = new Conn();
        Player p = player(conn, "0.57.0");
        BitSet mask = CubeStreamer.updateForTest(p, c, 2, 4);
        List<Integer> ops = conn.ops();
        assertFalse(ops.contains(11) || ops.contains(14) || ops.contains(16) || ops.contains(-1), "свет не шлётся: " + ops);
        assertTrue(ops.contains(2), "кубы идут op 2");
        assertTrue(mask.get(idx(7)) && mask.get(idx(-3)), "кольцо ±1 отправлено блоками");
        assertFalse(mask.get(idx(8)), "за кольцом — нет");
        // правки/пересвет столбца ядру 0.57 ничего не шлют
        conn.sent.clear();
        CubeStreamer.relight(p, c);
        BitSet all = new BitSet();
        all.set(0, 4096);
        CubeStreamer.relightCubes(p, c, all);
        assertTrue(conn.sent.isEmpty(), "пересвет не шлётся");
    }

    @Test
    void oldCoreUnchanged() {
        CubeColumn c = column(3, 3);
        Conn conn = new Conn();
        Player p = player(conn, "0.56.0");
        BitSet mask = CubeStreamer.updateForTest(p, c, 2, 4);
        List<Integer> ops = conn.ops();
        assertTrue(ops.contains(11), "0.56 — свет op 11: " + ops);
        assertFalse(mask.get(idx(7)), "0.56 — кольцо блоками не шлётся");
        int first2 = ops.indexOf(2), first11 = ops.indexOf(11);
        assertTrue(first2 >= 0 && first2 < first11, "op 2, затем op 11 (как раньше)");
    }

    @Test
    void heights2RoundTrip() {
        List<int[]> cols = new ArrayList<>();
        int[] flat = new int[514];                                           // всё одинаково, небо = движение
        flat[0] = 5; flat[1] = -7;
        for (int i = 0; i < 256; i++) { flat[2 + i] = 41; flat[258 + i] = 41; }
        int[] mixed = new int[514];                                          // движение разное, небо одно (остров выше)
        mixed[0] = -1; mixed[1] = 2;
        for (int i = 0; i < 256; i++) { mixed[2 + i] = 30 + (i & 7); mixed[258 + i] = 2000; }
        int[] both = new int[514];                                           // всё разное
        both[0] = 100000; both[1] = -100000;
        for (int i = 0; i < 256; i++) { both[2 + i] = -15008 + i; both[258 + i] = 15000 - i; }
        cols.add(flat); cols.add(mixed); cols.add(both);
        for (int k = 0; k < 70; k++) cols.add(flat.clone());                 // > 64 — два пакета
        List<byte[]> out = CubeStreamer.encodeHeights2(cols);
        assertEquals(2, out.size());
        List<int[]> got = new ArrayList<>();
        List<Integer> flags = new ArrayList<>();
        for (byte[] a : out) {
            NetworkBuffer b = NetworkBuffer.wrap(a, 0, a.length);
            assertEquals(18, (int) b.read(NetworkBuffer.BYTE));
            int n = b.read(NetworkBuffer.VAR_INT);
            for (int j = 0; j < n; j++) {
                int[] e = new int[514];
                e[0] = b.read(NetworkBuffer.INT);
                e[1] = b.read(NetworkBuffer.INT);
                int f = b.read(NetworkBuffer.BYTE);
                flags.add(f);
                if ((f & 1) != 0) { int v = b.read(NetworkBuffer.SHORT); for (int i = 0; i < 256; i++) e[2 + i] = v; }
                else for (int i = 0; i < 256; i++) e[2 + i] = b.read(NetworkBuffer.SHORT);
                if ((f & 4) != 0) System.arraycopy(e, 2, e, 258, 256);
                else if ((f & 2) != 0) { int v = b.read(NetworkBuffer.SHORT); for (int i = 0; i < 256; i++) e[258 + i] = v; }
                else for (int i = 0; i < 256; i++) e[258 + i] = b.read(NetworkBuffer.SHORT);
                got.add(e);
            }
            assertEquals(0, b.readableBytes(), "пакет разобран целиком");
        }
        assertEquals(cols.size(), got.size());
        for (int k = 0; k < cols.size(); k++) assertArrayEquals(cols.get(k), got.get(k), "столбец " + k);
        assertEquals(1 | 4, flags.get(0));
        assertEquals(2, flags.get(1));
        assertEquals(0, flags.get(2));
    }

    @Test
    void heightsEntryHasSky() {
        CubeColumn c = CubeColumn.generated(inst, 6, 6);
        c.lockWriteLock();
        try { c.setBlock(1, 3000, 1, Block.STONE); } finally { c.unlockWriteLock(); }
        int[] e = CubeStreamer.heights(c);
        int col = 1 << 4 | 1;
        assertEquals(3001, e[258 + col], "небо 15 с Y над камнем");
        assertEquals(3001, e[2 + col], "движение — тоже");
    }

    /** skyVer: стекло задерживает движение, но не небо; лист — и то и другое; снос — обратно. */
    @Test
    void skyVerTracksSkyOnly() {
        CubeColumn c = CubeColumn.generated(inst, 7, 7);
        HeightIndex h = c.st().heights;
        int sv = h.skyVer, mv = h.motionVer;
        c.lockWriteLock();
        try {
            c.setBlock(4, 2500, 4, Block.GLASS);
            assertEquals(sv, h.skyVer, "стекло — небо не сменилось");
            assertNotEquals(mv, h.motionVer, "стекло — движение сменилось");
            c.setBlock(5, 2500, 5, Block.OAK_LEAVES);
            assertNotEquals(sv, h.skyVer, "листва — небо сменилось");
            int sv2 = h.skyVer;
            c.setBlock(5, 2500, 5, Block.AIR);
            assertNotEquals(sv2, h.skyVer, "снесли — небо назад");
        } finally { c.unlockWriteLock(); }
    }

    /** Таб: строка сети — новым ядром «игра отвечает за», старым — оценка. */
    @Test
    void tabLine() {
        String a = plain(ink.hoka.odyssey.server.DebugTabAccess.line(130, 150.0, 12, 40, 0, 2000));
        assertTrue(a.contains("Сеть 130 мс") && a.contains("игра отвечает за 12 мс") && a.contains("макс 40 за 5 с") && a.contains("раз в 2 с"), a);
        String b = plain(ink.hoka.odyssey.server.DebugTabAccess.line(130, 175.0, -1, 0, 0, 10000));
        assertTrue(b.contains("задержка клиента ≈ 45 мс") && b.contains("раз в 10 с"), b);
    }

    static String plain(Component c) {
        StringBuilder sb = new StringBuilder();
        if (c instanceof TextComponent t) sb.append(t.content());
        for (Component ch : c.children()) sb.append(plain(ch));
        return sb.toString();
    }
}
