package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.worldgen.HeightKind;
import ink.hoka.odyssey.api.worldgen.WorldGenerator;
import ink.hoka.odyssey.server.tall.CubeGen;
import ink.hoka.odyssey.server.tall.HeightIndex;

import java.util.*;
import java.util.concurrent.*;

/**
 * /hocubes gentest: проверка генератора модуля — детерминизм в 8 потоках (каждый куб дважды, в разном порядке),
 * честность hasBlocks=false (куб правда пуст), uniform (правда однороден), highest (сходится с кубами), мс на куб.
 */
public final class GenTest {

    private GenTest() {}

    public record Result(int cubes, int mismatches, int badHasBlocks, int badUniform, int badHighest, double msPerCube, List<String> first) {
        public boolean ok() { return mismatches == 0 && badHasBlocks == 0 && badUniform == 0 && badHighest == 0; }
    }

    public static Result run(ModuleCubeGen g, int n) throws Exception {
        Random r = new Random(12345);
        List<int[]> filled = new ArrayList<>(), empty = new ArrayList<>(), uni = new ArrayList<>();
        int half = g.bounds().size() > 0 ? Math.max(1, g.bounds().size() / 32) : 256;
        int span = g.maxCy - g.minCy + 1;
        for (int tries = 0; tries < n * 50 && filled.size() < n; tries++) {
            int cx = r.nextInt(2 * half) - half, cz = r.nextInt(2 * half) - half, cy = g.minCy + r.nextInt(Math.max(1, span));
            if (!g.inside(cx, cy, cz)) continue;
            if (!g.w.hasBlocks(cx, cy, cz)) { if (empty.size() < 64) empty.add(new int[]{cx, cy, cz}); continue; }
            if (g.w.uniform(cx, cy, cz) != null) { if (uni.size() < 64) uni.add(new int[]{cx, cy, cz}); continue; }
            filled.add(new int[]{cx, cy, cz});
        }
        List<String> first = new ArrayList<>();
        // детерминизм: два прохода по 8 потокам, второй — в обратном порядке
        int[] h1 = hashes(g, filled, false), h2 = hashes(g, filled, true);
        int mism = 0;
        for (int i = 0; i < filled.size(); i++) if (h1[i] != h2[i]) { mism++; note(first, "недетерминирован куб " + Arrays.toString(filled.get(i))); }
        long t0 = System.nanoTime();
        for (int[] c : filled) g.directCube(c[0], c[1], c[2]);
        double ms = filled.isEmpty() ? 0 : (System.nanoTime() - t0) / 1e6 / filled.size();
        int badHas = 0;
        for (int[] c : empty) if (!g.directCube(c[0], c[1], c[2]).empty()) { badHas++; note(first, "hasBlocks=false, а куб не пуст: " + Arrays.toString(c)); }
        int badUni = 0;
        for (int[] c : uni) {
            int st = g.w.uniform(c[0], c[1], c[2]).stateId();
            CubeGen.Cube d = g.directCube(c[0], c[1], c[2]);
            if (GenIndexBuilder.classify(d) != st && !(st == 0 && d.empty())) { badUni++; note(first, "uniform не сходится с generate: " + Arrays.toString(c)); }
        }
        int badHi = 0;
        HeightIndex.Kind[] ks = HeightIndex.Kind.values();
        for (int k = 0; k < 8; k++) {
            int x = r.nextInt(32 * half) - 16 * half, z = r.nextInt(32 * half) - 16 * half;
            for (HeightIndex.Kind kind : ks) {
                int lo = g.minCy << 4, hi = (g.maxCy << 4) + 15;
                int a = g.w.highest(x, z, lo, hi, HeightKind.values()[kind.ordinal()]);
                if (a == WorldGenerator.UNKNOWN) continue;
                int b = scanDirect(g, x, z, lo, hi, kind);
                if (a != b) { badHi++; note(first, "highest " + kind + " в " + x + " " + z + ": " + a + ", по кубам " + b); }
            }
        }
        return new Result(filled.size(), mism, badHas, badUni, badHi, ms, first);
    }

    private static void note(List<String> l, String s) { if (l.size() < 5) l.add(s); }

    private static int[] hashes(ModuleCubeGen g, List<int[]> cubes, boolean reverse) throws Exception {
        int[] out = new int[cubes.size()];
        ExecutorService ex = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int k = 0; k < cubes.size(); k++) {
                int i = reverse ? cubes.size() - 1 - k : k;
                int[] c = cubes.get(i);
                fs.add(ex.submit(() -> { out[i] = ModuleCubeGen.hash(g.directCube(c[0], c[1], c[2])); }));
            }
            for (Future<?> f : fs) f.get();
        } finally { ex.shutdownNow(); }
        return out;
    }

    /** Верх колонки по кубам, сгенерированным напрямую. */
    static int scanDirect(ModuleCubeGen g, int x, int z, int lo, int hi, HeightIndex.Kind kind) {
        for (int cy = hi >> 4; cy >= lo >> 4; cy--) {
            if (!g.inside(x >> 4, cy, z >> 4)) continue;
            var p = g.directCube(x >> 4, cy, z >> 4).section().blockPalette();
            if (p.count() == 0) continue;
            for (int y = Math.min(hi, (cy << 4) + 15); y >= Math.max(lo, cy << 4); y--) {
                int id = p.get(x & 15, y & 15, z & 15);
                if (id == 0) continue;
                int h = HeightIndex.contrib(HeightIndex.info(id), kind, y);
                if (h != HeightIndex.NONE) return h;
            }
        }
        return HeightIndex.NONE;
    }
}
