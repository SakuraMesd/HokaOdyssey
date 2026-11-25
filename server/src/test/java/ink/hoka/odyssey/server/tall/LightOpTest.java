package ink.hoka.odyssey.server.tall;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.play.data.LightData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Шаг 14: op 11 LIGHT — кодирование и разбор (как у клиента) дают тот же свет; формульные кубы без массивов. */
class LightOpTest {

    @Test
    void roundTrip() {
        Random r = new Random(7);
        List<byte[]> sky = new ArrayList<>(), blk = new ArrayList<>();
        BitSet sm = new BitSet(), bm = new BitSet(), es = new BitSet();
        // 0 — всё 15, 1 — всё 0, 2 — ровная земля d=5, 3 — ступенька по колонкам, 4 — слой 16×16, 5 — шум, 7 — пустой бит (куб 6)
        byte[][] s = new byte[6][2048];
        java.util.Arrays.fill(s[0], (byte) 0xFF);
        for (int q = 0; q < 4096; q++) if ((q >> 8) >= 5) CubeLight.setNib(s[2], q, 15);
        for (int q = 0; q < 4096; q++) if ((q >> 8) >= (q & 255) % 17) CubeLight.setNib(s[3], q, 15);
        for (int q = 0; q < 4096; q++) CubeLight.setNib(s[4], q, (q & 255) % 16);
        r.nextBytes(s[5]);
        for (int i = 0; i < 6; i++) { sm.set(i + 1); sky.add(s[i]); }
        es.set(7);
        byte[] torch = new byte[2048];
        r.nextBytes(torch);
        bm.set(6); blk.add(torch);
        LightData l = new LightData(sm, bm, es, new BitSet(), sky, blk);
        List<Integer> idx = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8);
        List<byte[]> pk = LightOp.encode(3, -2, -100, l, idx, true);
        assertEquals(1, pk.size());
        NetworkBuffer b = NetworkBuffer.wrap(pk.get(0), 0, pk.get(0).length);
        assertEquals((byte) 11, b.read(NetworkBuffer.BYTE));
        assertEquals(9, b.read(NetworkBuffer.VAR_INT));
        int[] modes = new int[9];
        for (int i = 0; i < 9; i++) {
            assertEquals(CubeStreamer.pos(3, i - 100, -2), b.read(NetworkBuffer.LONG));
            int f = b.read(NetworkBuffer.BYTE) & 0xFF;
            modes[i] = f >> 2 & 3;
            byte[] got = readSky(b, f);
            byte[] want = i < 6 ? s[i] : i == 6 ? new byte[2048] : s[0];
            assertArrayEquals(want, got, "небо куба " + i);
            int bmode = f >> 4 & 3;
            if (i == 5) { assertEquals(3, bmode); assertArrayEquals(torch, b.read(NetworkBuffer.FixedRawBytes(2048)) ); }
            else assertEquals(1, bmode);
        }
        assertEquals(0, b.readableBytes());
        assertArrayEquals(new int[]{2, 1, 0, 0, 3, 3, 1, 2, 2}, modes);
    }

    /** Разбор неба как у клиента (CubeReceiver/ServerLight). */
    static byte[] readSky(NetworkBuffer b, int f) {
        byte[] a = new byte[2048];
        switch (f >> 2 & 3) {
            case 1 -> {}
            case 2 -> java.util.Arrays.fill(a, (byte) 0xFF);
            case 0 -> {
                int d = b.read(NetworkBuffer.BYTE) & 0xFF;
                byte[] prof = new byte[256];
                if (d == 0xFF) prof = b.read(NetworkBuffer.FixedRawBytes(256)); else java.util.Arrays.fill(prof, (byte) d);
                for (int q = 0; q < 4096; q++) if ((q >> 8) >= prof[q & 255]) CubeLight.setNib(a, q, 15);
            }
            default -> {
                if ((f & 0x40) != 0) {
                    byte[] layer = b.read(NetworkBuffer.FixedRawBytes(128));
                    for (int y = 0; y < 16; y++) System.arraycopy(layer, 0, a, y * 128, 128);
                } else a = b.read(NetworkBuffer.FixedRawBytes(2048));
            }
        }
        return a;
    }

    @Test
    void versions() {
        assertTrue(Handshake.atLeast("0.44.0", LightOp.CORE));
        assertTrue(Handshake.atLeast("0.44.1+1.21.11", LightOp.CORE));
        assertTrue(Handshake.atLeast("1.0", LightOp.CORE));
        assertFalse(Handshake.atLeast("0.43.1", LightOp.CORE));
        assertFalse(Handshake.atLeast("0.9.99", LightOp.CORE));
        assertFalse(Handshake.atLeast("?", LightOp.CORE));
        assertFalse(Handshake.atLeast(null, LightOp.CORE));
    }
}
