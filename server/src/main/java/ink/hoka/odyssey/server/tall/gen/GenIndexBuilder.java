package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.worldgen.HeightKind;
import ink.hoka.odyssey.api.worldgen.WorldGenerator;
import ink.hoka.odyssey.server.tall.CubeGen;
import ink.hoka.odyssey.server.tall.HeightIndex;
import net.minestom.server.instance.Section;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Считает столбцы индекса pregen генератором модуля (без индекса): для каждого куба в границах — пусто /
 * однородный (state+biome) / смешанный; при heights — 3×256 высот по кубам сверху вниз. Выборочно (1 куб из
 * ~1000) проверяет детерминизм: тот же куб в другом потоке должен дать тот же хэш.
 */
public final class GenIndexBuilder {

    private final ModuleCubeGen g;
    private final boolean heights;
    /** Счётчики: сгенерировано кубов, нс на генерацию, столбцов. */
    public final AtomicLong cubes = new AtomicLong(), nanos = new AtomicLong(), columns = new AtomicLong();
    private final AtomicLong sample = new AtomicLong();
    /** Проверять детерминизм раз в столько сгенерированных кубов (0 — нет). */
    volatile int checkEvery = 997;

    public GenIndexBuilder(ModuleCubeGen g, boolean heights) { this.g = g; this.heights = heights; }

    /** Нужен ли «полный» индекс (с высотами): генератор не отвечает highest аналитически. */
    public static boolean needHeights(ModuleCubeGen g) {
        int top = (g.maxCy << 4) + 15, bot = g.minCy << 4;
        for (int k = 0; k < 4; k++) {
            int x = k * 5 - 7, z = 3 - k * 4;
            if (g.w.highest(x, z, bot, top, HeightKind.SKY) == WorldGenerator.UNKNOWN) return true;
        }
        return false;
    }

    /** Ошибка детерминизма (pregen останавливается). */
    public static final class NotDeterministic extends RuntimeException {
        NotDeterministic(String m) { super(m); }
    }

    /** Посчитать столбец (cx, cz) в палитру файла f. */
    public GenIndexFile.Col column(GenIndexFile f, int cx, int cz) {
        int n = g.maxCy - g.minCy + 1;
        if (n <= 0) { columns.incrementAndGet(); return new GenIndexFile.Col(0, 0, new int[0], heights ? emptyHeights() : null); }
        int[] kind = new int[n];                                       // палитра (0 — воздух) или −1 — смешанный
        Section[] mixed = heights ? new Section[n] : null;
        for (int i = n - 1; i >= 0; i--) {
            int cy = g.minCy + i;
            if (!g.rawHasBlocks(cx, cy, cz)) { kind[i] = 0; continue; }
            int u = g.rawUniform(cx, cy, cz);
            if (u >= 0) { kind[i] = f.pal(GenIndexFile.palKey(u, 0)); continue; }
            long t0 = System.nanoTime();
            CubeGen.Cube c = g.rawCube(cx, cy, cz);
            nanos.addAndGet(System.nanoTime() - t0);
            long seq = cubes.incrementAndGet();
            int ce = checkEvery;
            if (ce > 0 && sample.incrementAndGet() % ce == 0) checkDeterminism(cx, cy, cz, c);
            int k = classify(c);
            if (k == -2) { kind[i] = 0; continue; }                     // пустой
            if (k >= 0) { kind[i] = f.pal(GenIndexFile.palKey(k, c.section().biomePalette().get(0, 0, 0))); continue; }
            kind[i] = -1;
            if (mixed != null) mixed[i] = c.section();
        }
        int lo = -1, hi = -1;
        for (int i = 0; i < n; i++) if (kind[i] != 0) { if (lo < 0) lo = i; hi = i; }
        columns.incrementAndGet();
        if (lo < 0) return new GenIndexFile.Col(0, 0, new int[0], heights ? emptyHeights() : null);
        int[] runs = new int[(hi - lo + 1) * 3];
        int nr = 0;
        for (int i = lo; i <= hi; ) {
            if (kind[i] < 0) { i++; continue; }
            int j = i;
            while (j + 1 <= hi && kind[j + 1] == kind[i]) j++;
            runs[nr * 3] = g.minCy + i; runs[nr * 3 + 1] = j - i + 1; runs[nr * 3 + 2] = kind[i];
            nr++;
            i = j + 1;
        }
        short[][] hs = heights ? heights(f, kind, mixed, lo, hi) : null;
        return new GenIndexFile.Col(g.minCy + lo, hi - lo + 1, Arrays.copyOf(runs, nr * 3), hs);
    }

    private static short[][] emptyHeights() {
        short[][] h = new short[3][256];
        for (short[] a : h) Arrays.fill(a, (short) HeightIndex.NONE);
        return h;
    }

    /** −2 — пустой; stateId — однородный (без блоков с данными, биом один); −1 — смешанный. */
    static int classify(CubeGen.Cube c) {
        if (!c.specials().isEmpty()) return -1;
        var p = c.section().blockPalette();
        if (p.count() == 0) return -2;
        int v = p.get(0, 0, 0);
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) if (p.get(x, y, z) != v) return -1;
        var b = c.section().biomePalette();
        int bv = b.get(0, 0, 0);
        for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) if (b.get(x, y, z) != bv) return -1;
        return v;
    }

    private short[][] heights(GenIndexFile f, int[] kind, Section[] mixed, int lo, int hi) {
        short[][] h = emptyHeights();
        HeightIndex.Kind[] ks = HeightIndex.Kind.values();
        for (int k = 0; k < 3; k++) {
            int left = 256;
            boolean[] done = new boolean[256];
            for (int i = hi; i >= lo && left > 0; i--) {
                if (kind[i] == 0) continue;
                int y0 = (g.minCy + i) << 4;
                if (kind[i] > 0) {
                    int st = f.resolved(kind[i])[0];
                    int r = HeightIndex.contrib(HeightIndex.info(st), ks[k], y0 + 15);
                    if (r == HeightIndex.NONE) continue;
                    for (int c = 0; c < 256; c++) if (!done[c]) { h[k][c] = (short) r; done[c] = true; left--; }
                    continue;
                }
                var p = mixed[i].blockPalette();
                if (p.count() == 0) continue;
                for (int c = 0; c < 256; c++) {
                    if (done[c]) continue;
                    for (int ly = 15; ly >= 0; ly--) {
                        int id = p.get(c & 15, ly, c >> 4);
                        if (id == 0) continue;
                        int r = HeightIndex.contrib(HeightIndex.info(id), ks[k], y0 + ly);
                        if (r == HeightIndex.NONE) continue;
                        h[k][c] = (short) r; done[c] = true; left--;
                        break;
                    }
                }
            }
        }
        return h;
    }

    private void checkDeterminism(int cx, int cy, int cz, CubeGen.Cube first) {
        int a = ModuleCubeGen.hash(first);
        int[] b = new int[1];
        Throwable[] err = new Throwable[1];
        Thread t = Thread.ofPlatform().name("pregen-check").start(() -> {
            try { b[0] = ModuleCubeGen.hash(g.rawCube(cx, cy, cz)); } catch (Throwable e) { err[0] = e; }
        });
        try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        if (err[0] != null) throw new NotDeterministic("куб " + cx + " " + cy + " " + cz + ": повтор упал: " + err[0]);
        if (a != b[0]) throw new NotDeterministic("куб " + cx + " " + cy + " " + cz + " во втором потоке другой — генератор "
                + g.id() + " недетерминирован (общий кэш? Math.random?)");
    }
}
