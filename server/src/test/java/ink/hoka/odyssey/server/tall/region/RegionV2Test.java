package ink.hoka.odyssey.server.tall.region;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** RegionFile v2 (HCR2: сектор 256, raw deflate) и совместимость с v1 (HCR1: 4 КБ, gzip) в одной папке. */
class RegionV2Test {

    @org.junit.jupiter.api.BeforeAll
    static void init() { net.minestom.server.MinecraftServer.init(); }

    @TempDir
    Path tmp;

    @AfterEach
    void back() { RegionFile.createV2 = true; }

    private static byte[] rnd(int len, long seed) { byte[] b = new byte[len]; new Random(seed).nextBytes(b); return b; }

    @Test
    void v1StaysV1AndNewIsV2() throws Exception {
        Path p1 = tmp.resolve("old.hcr"), p2 = tmp.resolve("new.hcr");
        RegionFile.createV2 = false;                                           // как старое ядро
        try (RegionFile f = RegionFile.open(p1, 4096, true)) {
            assertEquals(1, f.version());
            f.write(3, "старый".getBytes());
        }
        RegionFile.createV2 = true;
        try (RegionFile f = RegionFile.open(p1, 4096, true)) {                // открыли новым ядром — остаётся v1
            assertEquals(1, f.version());
            assertEquals(RegionFile.SECTOR, f.sectorSize());
            assertArrayEquals("старый".getBytes(), f.read(3));
            f.write(4, rnd(5000, 1));
        }
        try (RegionFile f = RegionFile.open(p1, 4096, false)) { assertArrayEquals(rnd(5000, 1), f.read(4)); }
        try (RegionFile f = RegionFile.open(p2, 4096, true)) {
            assertEquals(2, f.version());
            assertEquals(RegionFile.SECTOR_V2, f.sectorSize());
            f.write(0, new byte[4096]);                                       // однородный куб: сжимается в один сектор 256
            f.write(7, rnd(3000, 2));
            long[] u = f.usage();
            assertTrue(u[0] <= 256 + 13 * 256, "секторов: " + u[0]);
        }
        try (RegionFile f = RegionFile.open(p2, 4096, false)) {
            assertEquals(2, f.version());
            assertArrayEquals(new byte[4096], f.read(0));
            assertArrayEquals(rnd(3000, 2), f.read(7));
        }
        // v2: заголовок на 4096 записей ≈ 48 КБ, а запись — сектор 256 (v1: таблица 52 КБ, запись 4 КБ)
        assertTrue(Files.size(p2) < 52 * 1024 + 4096, "v2 файл: " + Files.size(p2));
    }

    @Test
    void v2Corruption() throws Exception {
        Path p = tmp.resolve("bad2.hcr");
        byte[] data = rnd(3000, 7);
        long pos;
        try (RegionFile f = RegionFile.open(p, 16, true)) {
            f.write(1, data);
            f.write(2, data);
            pos = Files.size(p) - 300;                                         // внутри данных последней записи
        }
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.seek(pos);
            int b = raf.read();
            raf.seek(pos);
            raf.write(b ^ 0x5A);
        }
        try (RegionFile f = RegionFile.open(p, 16, false)) {
            assertArrayEquals(data, f.read(1));
            assertThrows(RegionFile.Corrupt.class, () -> f.read(2));
        }
        Path q = tmp.resolve("sector.hcr");
        try (RegionFile f = RegionFile.open(q, 16, true)) { f.write(0, data); }
        try (RandomAccessFile raf = new RandomAccessFile(q.toFile(), "rw")) { raf.seek(8); raf.writeInt(100); }   // не степень двойки
        assertThrows(RegionFile.Corrupt.class, () -> RegionFile.open(q, 16, false));
    }

    @Test
    void uniformCubeSize() throws Exception {
        // однородный куб CubeCodec + v2: одна запись 256 байт вместо 4 КБ
        var pal = net.minestom.server.instance.palette.Palette.blocks();
        pal.fill(1);
        byte[] cube = CubeCodec.encode(pal, net.minestom.server.instance.palette.Palette.biomes(), java.util.Map.of(), 0);
        Path p = tmp.resolve("u.hcr");
        try (RegionFile f = RegionFile.open(p, 4096, true)) {
            f.write(0, cube);
            assertEquals(256, f.usage()[0]);
            System.out.printf("однородный куб: %d Б сырых, запись %d Б на диске (v1 было бы 4096)%n", cube.length, f.usage()[0]);
        }
    }
}
