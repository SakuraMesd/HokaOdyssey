package ink.hoka.odyssey.axiom;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.instance.palette.Palette;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Форматы Axiom без сервера: упаковка позиций, палитры, CBE, разбор set_buffer, zstd в отдельном загрузчике. */
class AxiomFormatTest {

    @BeforeAll
    static void zstd() throws Exception { ZstdCodec.init(); }

    @Test
    void terminators() {
        // BlockPos.asLong(-33554432, -2048, -33554432) обеими упаковками
        assertEquals(Packing.VANILLA_TERM, Packing.pack(-33554432, -2048, -33554432, false));
        assertEquals(Packing.TALL_TERM, Packing.pack(-33554432, -2048, -33554432, true));
        for (boolean t : new boolean[]{true, false}) {
            long l = Packing.pack(-1234, -900, 56789, t);
            assertEquals(-1234, Packing.x(l, t));
            assertEquals(-900, Packing.y(l, t));
            assertEquals(56789, Packing.z(l, t));
        }
        long l = Packing.pack(399_935, -15008, -8_388_607, true);
        assertEquals(399_935, Packing.x(l, true));
        assertEquals(-15008, Packing.y(l, true));
        assertEquals(-8_388_607, Packing.z(l, true));
    }

    @Test
    void varints() {
        Out o = new Out(4);
        int[] vs = {0, 1, 127, 128, 25565, -1, Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (int v : vs) o.writeVarInt(v);
        o.writeUtf("axiom:set_buffer").writeLong(-5L).writeFloat(0.25f);
        Buf b = new Buf(o.toByteArray());
        for (int v : vs) assertEquals(v, b.readVarInt());
        assertEquals("axiom:set_buffer", b.readUtf());
        assertEquals(-5L, b.readLong());
        assertEquals(0.25f, b.readFloat());
        assertEquals(0, b.remaining());
    }

    private static void roundTrip(int[] ids) {
        Out o = new Out();
        Palettes.write(o, ids);
        Buf b = new Buf(o.toByteArray());
        assertArrayEquals(ids, Palettes.read(b));
        assertEquals(0, b.remaining());
    }

    @Test
    void palettes() {
        Random r = new Random(1);
        int[] one = new int[4096];
        java.util.Arrays.fill(one, 7);
        roundTrip(one);
        int[] few = new int[4096];
        for (int i = 0; i < 4096; i++) few[i] = r.nextInt(3) * 100;
        roundTrip(few);
        int[] mid = new int[4096];
        for (int i = 0; i < 4096; i++) mid[i] = r.nextInt(200) + 1;
        roundTrip(mid);
        int[] many = new int[4096];
        for (int i = 0; i < 4096; i++) many[i] = r.nextInt(20000);
        roundTrip(many);
        // палитра Minestom → наш формат → обратно
        Palette p = Palette.blocks();
        p.set(1, 2, 3, 42);
        p.set(15, 15, 15, 9);
        Out o = new Out();
        Palettes.write(o, p);
        int[] got = Palettes.read(new Buf(o.toByteArray()));
        assertEquals(42, got[2 << 8 | 3 << 4 | 1]);
        assertEquals(9, got[15 << 8 | 15 << 4 | 15]);
        assertEquals(0, got[0]);
        // одно значение, палитра 5…8 бит и глобальная — сериализатором Minestom
        for (int kinds : new int[]{0, 100, 3000}) {
            Palette q = Palette.blocks();
            int[] want = new int[4096];
            for (int i = 0; i < 4096; i++) {
                int v = kinds == 0 ? 0 : 1 + (i * 7919) % kinds;
                want[i] = v;
                q.set(i & 15, i >> 8, (i >> 4) & 15, v);
            }
            Out oq = new Out();
            Palettes.write(oq, q);
            Buf bq = new Buf(oq.toByteArray());
            assertArrayEquals(want, Palettes.read(bq));
            assertEquals(0, bq.remaining());
        }
    }

    @Test
    void cbe() throws Exception {
        CompoundBinaryTag tag = CompoundBinaryTag.builder().putString("CustomName", "Сундук").putInt("x", 5).build();
        Out o = new Out();
        ZstdCodec.writeCbe(o, tag);
        CompoundBinaryTag back = ZstdCodec.readCbe(new Buf(o.toByteArray()), 1 << 20, true);
        assertEquals(tag, back);
    }

    @Test
    void setBuffer() throws Exception {
        Settings cfg = Settings.load(new ink.hoka.odyssey.api.ModuleConfig(new File("нет-такого.yml").toPath()));
        for (boolean tall : new boolean[]{true, false}) {
            int stone = 1;
            Out o = new Out();
            o.writeUtf("minecraft:overworld");
            UUID u = UUID.randomUUID();
            o.writeLong(u.getMostSignificantBits()).writeLong(u.getLeastSignificantBits());
            o.writeByte(0);
            int[] ids = new int[4096];
            java.util.Arrays.fill(ids, BlockBuffer.VOID_AIR);
            ids[5 << 8 | 6 << 4 | 7] = stone;
            o.writeLong(Packing.pack(-3, -900, 12, tall));
            Palettes.write(o, ids);
            o.writeVarInt(1);
            o.writeShort(7 | 5 << 4 | 6 << 8);                         // x | y<<4 | z<<8
            ZstdCodec.writeCbe(o, CompoundBinaryTag.builder().putString("a", "b").build());
            o.writeLong(tall ? Packing.TALL_TERM : Packing.VANILLA_TERM);
            o.writeVarInt(1000);
            Buf b = new Buf(o.toByteArray());
            BlockBuffer.Parsed p = BlockBuffer.parse(b, tall, cfg, true);
            assertEquals(0, b.remaining());
            assertEquals(1, p.total());
            assertEquals(1000, p.clientSends());
            BlockBuffer.Section s = p.sections().getFirst();
            assertEquals(-3, s.sx());
            assertEquals(-900, s.sy());
            assertEquals(12, s.sz());
            assertEquals(stone, s.id(5 << 8 | 6 << 4 | 7));
            assertEquals(-1, s.id(0));
            assertEquals("b", s.nbt().get(5 << 8 | 6 << 4 | 7).getString("a"));
        }
    }

    @Test
    void compactSection() {
        for (int kinds : new int[]{1, 2, 256, 257, 4096}) {
            int[] ids = new int[4096];
            for (int i = 0; i < 4096; i++) ids[i] = kinds == 4096 ? i - 1 : (i % kinds) * 3 - 1;
            BlockBuffer.Section s = BlockBuffer.Section.of(1, 2, 3, ids, java.util.Map.of());
            for (int i = 0; i < 4096; i++) assertEquals(ids[i], s.id(i));
            assertEquals(kinds == 1, s.single());
        }
    }

    @Test
    void tunnelZstd() {
        byte[] src = new byte[100_000];
        for (int i = 0; i < src.length; i++) src[i] = (byte) (i % 17);
        byte[] comp = com.github.luben.zstd.Zstd.compress(src);
        byte[] pad = new byte[comp.length + 10];
        System.arraycopy(comp, 0, pad, 5, comp.length);
        assertArrayEquals(src, ZstdCodec.decompress(pad, 5, comp.length, src.length));
    }

    /** zstd-jni из jar модуля в своём загрузчике (как ModuleClassLoader): нативная библиотека поднимается. */
    @Test
    void zstdInChildLoader() throws Exception {
        String jar = System.getProperty("axiom.jar");
        assumeJar(jar);
        for (int k = 0; k < 2; k++) {                                   // дважды — как перезагрузка модуля
            try (URLClassLoader cl = new URLClassLoader(new URL[]{new File(jar).toURI().toURL()}, ClassLoader.getPlatformClassLoader())) {
                Class<?> z = Class.forName("com.github.luben.zstd.Zstd", true, cl);
                assertSame(cl, z.getClassLoader());
                byte[] c = (byte[]) z.getMethod("compress", byte[].class).invoke(null, (Object) "hello hello hello".getBytes());
                assertTrue(c.length > 0);
            }
        }
    }

    private static void assumeJar(String jar) {
        org.junit.jupiter.api.Assumptions.assumeTrue(jar != null && new File(jar).isFile(), "нет axiom.jar");
    }
}
