package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.server.tall.CubeGen;
import ink.hoka.odyssey.server.tall.HeightIndex;
import ink.hoka.odyssey.server.tall.region.ColumnHeader;
import ink.hoka.odyssey.server.tall.region.CubeCodec;
import ink.hoka.odyssey.server.tall.region.RegionFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * /hocubes size: что лежит на диске у карты и прикидка для карты N×N блоков при текущем генераторе.
 * Прикидка — по выборке 16×16 столбцов у 0,0: индекс pregen на столбец; для недетерминированного генератора —
 * непустые кубы × средний размер записи (с округлением до сектора v2); правки — на каждый 1% изменённых столбцов
 * (заголовок + 3 куба).
 */
public final class DiskEstimate {

    private DiskEstimate() {}

    static long dirBytes(Path d) {
        if (!Files.isDirectory(d)) return 0;
        long r = 0;
        try (var s = Files.list(d)) {
            for (Path f : (Iterable<Path>) s::iterator) try { r += Files.size(f); } catch (IOException ignored) { }
        } catch (IOException ignored) { }
        return r;
    }

    /** Байт на диске записи данных длины len в секторах sector (8 байт длина+CRC). */
    public static long recordBytes(long len, int sector) { return (len + 8 + sector - 1) / sector * sector; }

    /** Столбцов в карте N×N блоков. */
    public static long columns(long n) { long c = (n + 15) / 16; return c * c; }

    /** Индекс pregen для карты N×N: байт на столбец × столбцы + заголовки файлов (≈ 200 Б на регион). */
    public static long indexBytes(double perColumn, long n) {
        long cols = columns(n), regions = (cols + 1023) / 1024;
        return (long) (perColumn * cols) + regions * 200;
    }

    /** Правки: доля столбцов frac, у каждого заголовок headerBytes и cubes кубов по cubeBytes (уже с секторами). */
    public static long editsBytes(long n, double frac, int cubes, long cubeBytes, long headerBytes) {
        return (long) (columns(n) * frac * (headerBytes + (long) cubes * cubeBytes));
    }

    static long deflated(byte[] b) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Deflater d = new Deflater(6, true);
        try (var o = new DeflaterOutputStream(bo, d)) { o.write(b); } catch (IOException ignored) { }
        finally { d.end(); }
        return bo.size() + 1;
    }

    /** Факт на диске: строки отчёта. */
    public static List<String> fact(Path dir) {
        List<String> out = new ArrayList<>();
        long[] cols = files(dir.resolve("cols"), 1024), cubes = files(dir.resolve("cubes"), 4096);
        long idx = dirBytes(dir.resolve("genidx"));
        int idxFiles = count(dir.resolve("genidx"));
        out.add(String.format(Locale.ROOT, "на диске: заголовки %s (%d файлов, столбцов %d), кубы %s (%d файлов, кубов %d, в среднем %d Б/куб), индекс pregen %s (%d файлов)",
                mb(cols[0]), cols[1], cols[2], mb(cubes[0]), cubes[1], cubes[2], cubes[2] == 0 ? 0 : cubes[4] / cubes[2], mb(idx), idxFiles));
        long waste = cols[3] - cols[4] + cubes[3] - cubes[4];
        out.add(String.format(Locale.ROOT, "сектора: занято %s под %s данных (потери %s); файлов v1 %d, v2 %d — /hocubes compact переводит v1 в v2",
                mb(cols[3] + cubes[3]), mb(cols[4] + cubes[4]), mb(waste), cols[5] + cubes[5], cols[6] + cubes[6]));
        return out;
    }

    /** [байт файлов, файлов, записей, байт секторов, байт данных, v1, v2] по папке регионов. */
    private static long[] files(Path d, int entries) {
        long[] r = new long[7];
        if (!Files.isDirectory(d)) return r;
        try (var s = Files.list(d)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                if (!f.getFileName().toString().startsWith("r.")) continue;
                try {
                    r[0] += Files.size(f); r[1]++;
                    try (RegionFile rf = RegionFile.open(f, entries, false)) {
                        if (rf == null) continue;
                        r[2] += rf.size();
                        long[] u = rf.usage();
                        r[3] += u[0]; r[4] += u[1];
                        if (rf.version() == 1) r[5]++; else r[6]++;
                    }
                } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
        return r;
    }

    private static int count(Path d) {
        if (!Files.isDirectory(d)) return 0;
        try (var s = Files.list(d)) { return (int) s.count(); } catch (IOException e) { return 0; }
    }

    static String mb(long b) {
        if (b >= 1L << 40) return String.format(Locale.ROOT, "%.2f ТБ", b / (double) (1L << 40));
        if (b >= 1L << 30) return String.format(Locale.ROOT, "%.2f ГБ", b / (double) (1L << 30));
        if (b >= 1L << 20) return String.format(Locale.ROOT, "%.1f МБ", b / (double) (1L << 20));
        return String.format(Locale.ROOT, "%.1f КБ", b / 1024.0);
    }

    /** Прикидка для N×N блоков: строки отчёта. Выборка 16×16 столбцов у 0,0 (генерация — секунды). */
    public static List<String> estimate(ModuleCubeGen g, long n) throws IOException {
        List<String> out = new ArrayList<>();
        int side = 16;
        long t0 = System.nanoTime();
        if (g.deterministic()) {
            boolean heights = GenIndexBuilder.needHeights(g);
            GenIndexBuilder b = new GenIndexBuilder(g, heights);
            b.checkEvery = 0;
            GenIndexFile f = new GenIndexFile(new GenIndexFile.Header(g.provider.id(), g.provider.version(), g.settings.seed(),
                    0, g.minCy, g.maxCy, heights));
            for (int x = 0; x < side; x++) for (int z = 0; z < side; z++) f.put(x, z, b.column(f, x, z));
            double per = (f.encode().length - 200.0) / (side * side);
            double msCol = (System.nanoTime() - t0) / 1e6 / (side * side);
            out.add(String.format(Locale.ROOT, "генератор %s детерминированный: кубы на диск не пишутся; индекс %s ≈ %.1f Б/столбец → %d×%d: %s, pregen ≈ %.2f мс/столбец ⇒ %s CPU",
                    g.tag(), heights ? "full (с высотами)" : "lite", Math.max(0, per), n, n, mb(indexBytes(Math.max(0, per), n)), msCol,
                    Pregen.dur((long) (msCol * columns(n) / 1000))));
        } else {
            long cubes = 0, bytes = 0;
            for (int x = 0; x < side; x++) for (int z = 0; z < side; z++)
                for (int cy = g.minCy; cy <= g.maxCy; cy++) {
                    if (!g.hasBlocks(x, cy, z)) continue;
                    CubeGen.Cube c = g.cube(x, cy, z);
                    if (c.empty()) continue;
                    cubes++;
                    bytes += recordBytes(deflated(CubeCodec.encode(c.section().blockPalette(), c.section().biomePalette(), Map.of(), 0)), RegionFile.SECTOR_V2);
                }
            double perCol = (bytes + (double) side * side * 512) / (side * side);
            out.add(String.format(Locale.ROOT, "генератор %s НЕдетерминированный: каждый непустой куб на диск — %.1f кубов/столбец, %.0f Б/столбец → %d×%d: %s",
                    g.tag(), cubes / (double) (side * side), perCol, n, n, mb((long) (perCol * columns(n)))));
        }
        // правки: заголовок с настоящими высотами этого генератора (пустой жмётся в ~50 Б — оценка врала бы) и
        // средний смешанный куб генератора (правка = снимок всего куба)
        ColumnHeader hd = new ColumnHeader(ink.hoka.odyssey.server.tall.TallWorld.HEIGHT >> 4);
        int lo = g.minCy << 4, hi = (g.maxCy << 4) + 15;
        HeightIndex.Kind[] ks = HeightIndex.Kind.values();
        for (int k = 0; k < ks.length && k < hd.heights.length; k++)
            for (int c = 0; c < 256; c++) hd.heights[k][c] = (short) g.highest(c & 15, c >> 4, lo, hi, ks[k]);
        hd.onDisk.set(0, 3);
        hd.modified.set(0, 3);
        long header = recordBytes(deflated(hd.encode()) + 64, RegionFile.SECTOR_V2);
        long mixedBytes = 0;
        int mixed = 0;
        for (int x = 0; x < 4 && mixed < 24; x++) for (int z = 0; z < 4 && mixed < 24; z++)
            for (int cy = g.maxCy; cy >= g.minCy && mixed < 24; cy--) {
                if (!g.hasBlocks(x, cy, z) || g.uniform(x, cy, z) != null) continue;
                CubeGen.Cube c = g.cube(x, cy, z);
                if (c.empty()) continue;
                mixedBytes += recordBytes(deflated(CubeCodec.encode(c.section().blockPalette(), c.section().biomePalette(), Map.of(), 0)), RegionFile.SECTOR_V2);
                mixed++;
            }
        long cube = mixed > 0 ? Math.max(RegionFile.SECTOR_V2, mixedBytes / mixed) : recordBytes(600, RegionFile.SECTOR_V2);
        out.add(String.format(Locale.ROOT, "правки игроков: на каждый 1%% изменённых столбцов (по 3 куба) ≈ %s (v2: заголовок ~%d Б, куб ~%d Б; v1 — по 4 КБ: %s)",
                mb(editsBytes(n, 0.01, 3, cube, header)), header, cube, mb(editsBytes(n, 0.01, 3, 4096, 4096))));
        return out;
    }
}
