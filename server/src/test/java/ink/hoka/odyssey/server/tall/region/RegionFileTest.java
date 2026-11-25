package ink.hoka.odyssey.server.tall.region;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RegionFile (шаг 7): запись-чтение, перезапись, удаление, переоткрытие; повреждение → Corrupt, а не мусор.
 * Формат v1 (HCR1, сектор 4 КБ) — смещения в тестах на него; v2 — RegionV2Test.
 */
class RegionFileTest {

    @org.junit.jupiter.api.BeforeEach
    void v1() { RegionFile.createV2 = false; }

    @org.junit.jupiter.api.AfterEach
    void back() { RegionFile.createV2 = true; }

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tmp;

    private static byte[] rnd(int len, long seed) { byte[] b = new byte[len]; new Random(seed).nextBytes(b); return b; }

    @Test
    void writeReadReopen() throws Exception {
        Path p = tmp.resolve("r.0.0.0.hcr");
        byte[] small = "куб".getBytes(), big = rnd(20_000, 1);              // big — несжимаемый, несколько секторов
        try (RegionFile f = RegionFile.open(p, 4096, true)) {
            assertNull(f.read(5));
            f.write(5, small);
            f.write(4095, big);
            f.write(0, new byte[100_000]);                                 // сжимается до сектора
            assertArrayEquals(small, f.read(5));
            assertArrayEquals(big, f.read(4095));
            f.write(5, big);                                               // перезапись больше старой
            f.write(4095, small);                                          // и меньше
            f.remove(0);
            assertEquals(2, f.size());
        }
        try (RegionFile f = RegionFile.open(p, 4096, false)) {
            assertArrayEquals(big, f.read(5));
            assertArrayEquals(small, f.read(4095));
            assertNull(f.read(0));
            assertTrue(f.timestamp(5) > 0);
        }
        assertNull(RegionFile.open(tmp.resolve("нет.hcr"), 4096, false));
    }

    @Test
    void sectorsReused() throws Exception {
        Path p = tmp.resolve("reuse.hcr");
        try (RegionFile f = RegionFile.open(p, 1024, true)) {
            for (int k = 0; k < 50; k++) for (int i = 0; i < 20; i++) f.write(i, rnd(5000, k * 100L + i));
        }
        // 20 записей по 2 сектора + запас на копирование; без переиспользования было бы 50×20×2 секторов
        assertTrue(Files.size(p) < 200L * RegionFile.SECTOR, "файл разросся: " + Files.size(p));
    }

    @Test
    void corruptDataDetected() throws Exception {
        Path p = tmp.resolve("bad.hcr");
        byte[] data = rnd(3000, 7);
        long pos;
        try (RegionFile f = RegionFile.open(p, 4096, true)) {
            f.write(1, data);
            f.write(2, data);
            pos = Files.size(p) - RegionFile.SECTOR + 20;                  // внутри данных последней записи (№2)
        }
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.seek(pos);
            int b = raf.read();
            raf.seek(pos);
            raf.write(b ^ 0x5A);
        }
        try (RegionFile f = RegionFile.open(p, 4096, false)) {
            assertArrayEquals(data, f.read(1), "соседняя запись цела");
            assertThrows(RegionFile.Corrupt.class, () -> f.read(2));
        }
    }

    @Test
    void truncatedAndWrongMagic() throws Exception {
        Path p = tmp.resolve("cut.hcr");
        try (RegionFile f = RegionFile.open(p, 4096, true)) {
            f.write(0, rnd(100, 1));
            f.write(1, rnd(9000, 2));
        }
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) { raf.setLength(raf.length() - 2 * RegionFile.SECTOR); }
        try (RegionFile f = RegionFile.open(p, 4096, false)) {
            assertNotNull(f.read(0));
            assertNull(f.read(1), "строка таблицы за концом файла — записи нет");
        }
        Path q = tmp.resolve("magic.hcr");
        Files.write(q, new byte[RegionFile.SECTOR * 13]);
        assertThrows(RegionFile.Corrupt.class, () -> RegionFile.open(q, 4096, false));
    }

    @Test
    void columnHeaderRoundTrip() throws Exception {
        ColumnHeader h = new ColumnHeader(1876);
        h.onDisk.set(0); h.onDisk.set(938); h.onDisk.set(1875);
        h.modified.or(h.onDisk);
        h.heights[0][17] = -15009; h.heights[2][255] = 12000;
        h.biome = "minecraft:desert";
        h.tags.put("k", "v");
        ColumnHeader g = ColumnHeader.decode(h.encode());
        assertEquals(h.onDisk, g.onDisk);
        assertEquals(h.modified, g.modified);
        assertEquals(-15009, g.heights[0][17]);
        assertEquals(12000, g.heights[2][255]);
        assertEquals("minecraft:desert", g.biome);
        assertEquals("v", g.tags.get("k"));
        byte[] bad = h.encode();
        bad[0] = 9;
        assertThrows(RegionFile.Corrupt.class, () -> ColumnHeader.decode(bad));
    }

    @Test
    void headerCutWhileCreating() throws Exception {
        Path p = tmp.resolve("half.hcr");
        Files.write(p, new byte[100]);                                     // упали, пока писали таблицу нового файла
        try (RegionFile f = RegionFile.open(p, 4096, false)) {
            assertNull(f.read(0));
            f.write(0, rnd(50, 3));
        }
        try (RegionFile f = RegionFile.open(p, 4096, false)) { assertArrayEquals(rnd(50, 3), f.read(0)); }
        assertThrows(RegionFile.Corrupt.class, () -> ColumnHeader.decode(new byte[]{1, 0}));   // обрезанный заголовок — битый
    }

    /** Больше MAX файлов из многих потоков: вытеснение не даёт двум объектам писать в один файл. */
    @Test
    void evictionUnderLoad() throws Exception {
        Path d = tmp.resolve("many");
        int files = RegionFiles.MAX + 40, threads = 8, rounds = 6;
        java.util.concurrent.atomic.AtomicReference<Throwable> err = new java.util.concurrent.atomic.AtomicReference<>();
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int tt = t;
            ts[t] = Thread.ofPlatform().start(() -> {
                try {
                    for (int r = 0; r < rounds; r++) for (int i = 0; i < files; i++) {
                        int k = (i + tt * 37) % files;
                        byte[] v = rnd(3000 + r * 500, k * 100L + tt);
                        RegionFiles.use(d.resolve(k + ".hcr"), 16, true, f -> { f.write(tt, v); return null; });
                    }
                } catch (Throwable e) { err.compareAndSet(null, e); }
            });
        }
        for (Thread t : ts) t.join();
        if (err.get() != null) throw new AssertionError(err.get());
        RegionFiles.closeUnder(d);
        for (int k = 0; k < files; k++) try (RegionFile f = RegionFile.open(d.resolve(k + ".hcr"), 16, false)) {
            for (int t = 0; t < threads; t++) assertArrayEquals(rnd(3000 + (rounds - 1) * 500, k * 100L + t), f.read(t), "файл " + k + " запись " + t);
        }
    }
}
