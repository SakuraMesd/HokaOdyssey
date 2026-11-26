package ink.hoka.odyssey.gen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Кодек протокола генерации на клиенте (op 20/21/22/23, wc op 8/9): туда-обратно. */
class GenWireTest {

    @Test
    void defRoundTrip() {
        var d = new GenWire.Def(7, "noise-test", 2, "gen/noise-test@2-abcdef12.jar", "abcdef12" + "0".repeat(56), -123456789L,
                "{\"amp\":20}", -64, 255, 0, 27914, 0x1234L, -5L, 99L, 1);
        assertEquals(d, GenWire.decodeDef(GenWire.encodeDef(d)));
        assertEquals(GenWire.Def.off(), GenWire.decodeDef(GenWire.encodeDef(GenWire.Def.off())));
    }

    @Test
    void cubesRoundTrip() {
        var a = new GenWire.Col(-3, 1_000_000, List.of(GenWire.Cube.gen(-2), new GenWire.Cube(0, 0x7FL, -1, 0),
                new GenWire.Cube(1, 0, 5, 3), GenWire.Cube.gen(9), new GenWire.Cube(20, -1L, 0x1234, 0)));
        var b = new GenWire.Col(4, -4, List.of(GenWire.Cube.gen(-900)));
        var e = new GenWire.Col(0, 0, List.of());
        byte[] m = GenWire.encodeCubes(42, List.of(a, b, e));
        assertEquals(GenWire.OP_CUBES, m[0]);
        var r = GenWire.decodeCubes(m);
        assertEquals(42, r.slot());
        assertEquals(3, r.cols().size());
        var ra = r.cols().get(0);
        assertEquals(-3, ra.cx());
        assertEquals(1_000_000, ra.cz());
        assertEquals(a.cubes(), ra.cubes(), "кубы по возрастанию sy, флаги на месте");
        assertEquals(b.cubes(), r.cols().get(1).cubes());
        assertTrue(r.cols().get(2).cubes().isEmpty());
    }

    @Test
    void secretsRoundTrip() {
        var s = new GenWire.Secret(GenWire.pos(-5, -900, 77), new int[]{0, 4095, 17}, new int[]{1, 2, 30000},
                new int[]{3}, new String[]{"{Items:[]}"});
        var r = GenWire.decodeSecrets(GenWire.encodeSecrets(List.of(s))).get(0);
        assertEquals(s.pos(), r.pos());
        assertArrayEquals(s.idx(), r.idx());
        assertArrayEquals(s.state(), r.state());
        assertArrayEquals(s.dataIdx(), r.dataIdx());
        assertArrayEquals(s.snbt(), r.snbt());
    }

    @Test
    void statusSampleCheck() {
        assertEquals(new GenWire.Status(3, 2, 4), GenWire.decodeStatus(GenWire.encodeStatus(new GenWire.Status(3, 2, 4))));
        var sm = GenWire.decodeSample(GenWire.encodeSample(new GenWire.Sample(9, new long[]{1, 2}, new long[]{-1, 5})));
        assertEquals(9, sm.slot());
        assertArrayEquals(new long[]{1, 2}, sm.pos());
        assertArrayEquals(new long[]{-1, 5}, sm.hash());
        long[][] c = GenWire.decodeCheck(GenWire.encodeCheck(new long[]{7}, new long[]{8}));
        assertArrayEquals(new long[]{7}, c[0]);
        assertArrayEquals(new long[]{8}, c[1]);
    }

    @Test
    void posMatchesSectionPos() {
        for (int[] p : new int[][]{{0, 0, 0}, {-1, -1, -1}, {2097151, 524287, -2097152}, {-938, -938, 1234}}) {
            long l = GenWire.pos(p[0], p[1], p[2]);
            assertEquals(p[0], GenWire.posX(l));
            assertEquals(p[1], GenWire.posY(l));
            assertEquals(p[2], GenWire.posZ(l));
        }
    }

    @Test
    void jsonRoundTripAndCanonical() {
        Map<String, Object> o = GenJson.parseObject("{\"b\": [1, 2.5, \"x\\\"y\"], \"a\": {\"n\": -3, \"t\": true, \"z\": null}}");
        String w = GenJson.write(o);
        assertEquals("{\"a\":{\"n\":-3,\"t\":true,\"z\":null},\"b\":[1,2.5,\"x\\\"y\"]}", w, "ключи по алфавиту");
        assertEquals(w, GenJson.write(GenJson.parse(w)));
        assertEquals("minecraft:oak_log[axis=y]", GenIds.normalize("oak_log[ axis = y ]"));
        assertEquals("minecraft:a[b=1,c=2]", GenIds.normalize("minecraft:a[c=2,b=1]"));
    }

    @Test
    void manifest() {
        var m = GenManifest.parse("{\"id\":\"mars\",\"version\":3,\"provider\":\"x.Y\"}".getBytes());
        assertEquals("mars@3", m.tag());
        assertThrows(IllegalArgumentException.class, () -> GenManifest.parse("{\"id\":\"Mars!\",\"version\":3,\"provider\":\"x.Y\"}".getBytes()));
    }
}
