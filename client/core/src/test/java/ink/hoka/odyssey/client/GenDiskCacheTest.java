package ink.hoka.odyssey.client;

import ink.hoka.odyssey.gen.GenArrayCube;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Дисковый кэш генерации: туда-обратно, переоткрытие, другой ключ, битый хвост, вытеснение по объёму. */
class GenDiskCacheTest {

    static GenArrayCube cube(int cx, int cy, int cz, int kinds, long seed) {
        GenArrayCube c = new GenArrayCube(false).reset(cx, cy, cz, 1);
        Random r = new Random(seed);
        for (int i = 0; i < 4096; i++) c.blocks[i] = i < 2048 ? 1 + r.nextInt(kinds) : 0;
        for (int i = 0; i < 64; i++) c.biomes[i] = r.nextInt(5);
        return c;
    }

    @Test
    void roundTrip(@TempDir Path dir) throws Exception {
        GenDiskCache d = new GenDiskCache(dir, 64L << 20);
        d.open("vale@3/a");
        int[][] at = {{0, 0, 0}, {-1, 5, 3}, {17, -40, -9}, {100, 120, 100}};
        int[] kinds = {1, 7, 300, 2000};
        for (int k = 0; k < at.length; k++) {
            GenArrayCube c = cube(at[k][0], at[k][1], at[k][2], kinds[k], k);
            d.put(at[k][0], at[k][1], at[k][2], c, c.visHash());
        }
        d.flush();
        GenArrayCube got = new GenArrayCube(false);
        for (int k = 0; k < at.length; k++) {
            GenArrayCube c = cube(at[k][0], at[k][1], at[k][2], kinds[k], k);
            got.reset(at[k][0], at[k][1], at[k][2], 1);
            assertTrue(d.get(at[k][0], at[k][1], at[k][2], got), "куб " + k);
            assertArrayEquals(c.blocks, got.blocks);
            assertArrayEquals(c.biomes, got.biomes);
        }
        got.reset(1, 1, 1, 1);
        assertFalse(d.get(1, 1, 1, got));
        d.close();

        // переоткрытие: с диска
        GenDiskCache d2 = new GenDiskCache(dir, 64L << 20);
        d2.open("vale@3/a");
        got.reset(-1, 5, 3, 1);
        assertTrue(d2.get(-1, 5, 3, got));
        assertEquals(cube(-1, 5, 3, 7, 1).visHash(), got.visHash());
        // другой ключ — пусто
        d2.open("vale@3/b");
        got.reset(-1, 5, 3, 1);
        assertFalse(d2.get(-1, 5, 3, got));
        d2.close();
    }

    @Test
    void brokenTailAndBadHash(@TempDir Path dir) throws Exception {
        GenDiskCache d = new GenDiskCache(dir, 64L << 20);
        d.open("k");
        GenArrayCube a = cube(0, 0, 0, 9, 1), b = cube(1, 0, 0, 9, 2);
        d.put(0, 0, 0, a, a.visHash());
        d.put(1, 0, 0, b, 12345L);                                     // хэш не тот — при чтении промах
        d.flush();
        d.close();
        Path f;
        try (Stream<Path> s = Files.walk(dir)) { f = s.filter(p -> p.toString().endsWith(".hgc")).findFirst().orElseThrow(); }
        byte[] all = Files.readAllBytes(f);
        Files.write(f, java.util.Arrays.copyOf(all, all.length + 10));  // мусорный хвост (оборванная запись)
        GenDiskCache d2 = new GenDiskCache(dir, 64L << 20);
        d2.open("k");
        GenArrayCube got = new GenArrayCube(false).reset(0, 0, 0, 1);
        assertTrue(d2.get(0, 0, 0, got));
        got.reset(1, 0, 0, 1);
        assertFalse(d2.get(1, 0, 0, got));
        assertEquals(1, d2.bad.get());
        // запись после битого хвоста — файл обрезается, новая запись читается
        GenArrayCube c = cube(2, 0, 0, 9, 3);
        d2.put(2, 0, 0, c, c.visHash());
        d2.flush();
        got.reset(2, 0, 0, 1);
        assertTrue(d2.get(2, 0, 0, got));
        d2.close();
    }

    @Test
    void evictsOldRegions(@TempDir Path dir) throws Exception {
        GenDiskCache d = new GenDiskCache(dir, 1L << 20);               // 1 МБ
        d.open("k");
        for (int i = 0; i < 400; i++) {                                 // ~2.5 КБ на куб × 400 — больше предела
            GenArrayCube c = cube(i * 16, 0, 0, 3000, i);
            d.put(i * 16, 0, 0, c, c.visHash());
            if (i % 50 == 49) { d.flush(); Thread.sleep(20); }          // разное время обращения у пачек
        }
        d.flush();
        d.limit(1L << 20);
        d.flush();
        assertTrue(d.bytes() <= 1L << 20, "объём " + d.bytes());
        assertTrue(d.evictedFiles.get() > 0);
        GenArrayCube got = new GenArrayCube(false).reset(399 * 16, 0, 0, 1);
        assertTrue(d.get(399 * 16, 0, 0, got), "свежий куб остался");
        d.close();
    }
}
