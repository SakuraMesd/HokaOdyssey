package ink.hoka.odyssey.planet.vale;

import ink.hoka.odyssey.gen.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Замер прогрузки окна игрока на hoka23 (vale@3): какие кубы получит клиент (окно ±(v+1) + полоса поверхности ±S,
 * дальность R столбцов), сколько из них пустых / однородных (метка сразу) / настоящих (генерация в GenPool), сколько
 * CPU на генерацию и сколько секунд до полной прогрузки при N потоках в порядке приоритета GenPool. Отдельно — индекс
 * столбца (то, что pregen кладёт в genidx; без него сервер считает это сам при загрузке столбца).
 * Запуск: gradle :generators:vale:test --tests '*ValeWindowBench*' -Phoka.bench=1
 */
@EnabledIfSystemProperty(named = "hoka.bench", matches = ".+")
class ValeWindowBench {

    static final int R = 32, V = 10, S = 128;

    record Scn(String name, int x, int y, int z) {}

    record Cube(int cx, int cy, int cz, int prio) {}

    /** Порядок GenPool ядра 0.62: кольцо Чебышёва с Y (до 64), в кольце впереди по взгляду (+Z) — раньше. */
    static int prioOld(int dx, int dy, int dz) {
        int ring = Math.min(64, Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))));
        return ring * 2 + (dz >= 0 ? 0 : 1);
    }

    /** Порядок ядра 0.63 — копия GenPool.ring (+ «впереди» раньше). */
    static int prioNew(int dx, int dy, int dz) {
        int h = Math.max(Math.abs(dx), Math.abs(dz)), ady = Math.abs(dy);
        int ring = Math.max(h, ady <= 2 ? 0 : (ady + 2) / 3);
        boolean ahead = dz >= 0;
        if (!ahead && ring > 2) ring += Math.min(8, ring / 3);
        return Math.min(127, ring) * 2 + (ahead ? 0 : 1);
    }

    @Test
    void bench() throws Exception {
        PlanetGenerator probe = ValeTestKit.gen();
        ValeGen vg = (ValeGen) probe;
        // гора: самая высокая точка на сетке 256 в ±4096
        int bx = 0, bz = 0, bh = Integer.MIN_VALUE;
        for (int x = -4096; x <= 4096; x += 256) for (int z = -4096; z <= 4096; z += 256) {
            int h = vg.surface(x, z);
            if (h > bh) { bh = h; bx = x; bz = z; }
        }
        int sh = vg.surface(0, 32);
        List<Scn> scns = List.of(new Scn("спавн", 0, sh + 2, 32), new Scn("гора " + bh, bx, bh + 2, bz));
        int cores = Runtime.getRuntime().availableProcessors();
        // прогрев JIT: кусок мира в стороне (иначе первый замер — интерпретатор)
        PlanetGenerator w = ValeTestKit.gen();
        GenArrayCube wb = new GenArrayCube(false);
        for (int cx = 5000; cx < 5024; cx++) for (int cz = 0; cz < 24; cz++) for (int cy = 15; cy <= 40; cy++)
            GenFingerprint.visible(w, ValeTestKit.BOUNDS, ValeTestKit.SEED, cx, cy, cz, wb).visHash();
        for (Scn s : scns) run(s, cores);
    }

    private void run(Scn s, int cores) throws Exception {
        System.out.printf(Locale.ROOT, "%n===== %s (%d, %d, %d), R=%d, окно ±%d, полоса ±%d =====%n", s.name(), s.x(), s.y(), s.z(), R, V + 1, S);
        int pcx = s.x() >> 4, pcy = s.y() >> 4, pcz = s.z() >> 4;
        // ── индекс столбцов (как GenIndexBuilder: высоты 3 видов + hasBlocks/uniform всех кубов) — холодный генератор ──
        PlanetGenerator gi = ValeTestKit.gen();
        int minCy = ValeTestKit.BOUNDS.minY() >> 4, maxCy = ValeTestKit.BOUNDS.maxY() >> 4;
        Map<Long, int[]> surf = new HashMap<>();
        long t0 = System.nanoTime();
        for (int cx = pcx - R - 1; cx <= pcx + R + 1; cx++) for (int cz = pcz - R - 1; cz <= pcz + R + 1; cz++) {
            int[] su = new int[256];
            for (int k = 0; k < 256; k++) {
                int x = (cx << 4) + (k & 15), z = (cz << 4) + (k >> 4);
                su[k] = gi.highest(x, z, ValeTestKit.BOUNDS.minY(), ValeTestKit.BOUNDS.maxY(), HeightKind.SURFACE);
                gi.highest(x, z, ValeTestKit.BOUNDS.minY(), ValeTestKit.BOUNDS.maxY(), HeightKind.MOTION);
                gi.highest(x, z, ValeTestKit.BOUNDS.minY(), ValeTestKit.BOUNDS.maxY(), HeightKind.SKY);
            }
            for (int cy = minCy; cy <= maxCy; cy++) if (gi.hasBlocks(cx, cy, cz)) gi.uniform(cx, cy, cz);
            surf.put(key(cx, cz), su);
        }
        long idxNs = System.nanoTime() - t0;
        int cols = (2 * R + 3) * (2 * R + 3);
        System.out.printf(Locale.ROOT, "индекс столбцов (genidx; без pregen — сервер при загрузке): %d столбцов, %.2f мс/столбец, %.1f с на 1 поток%n",
                cols, idxNs / 1e6 / cols, idxNs / 1e9);

        // ── кубы игроку ──
        List<Cube> gen = new ArrayList<>();
        int empty = 0, uni = 0, inWin = 0, inBand = 0;
        for (int cx = pcx - R; cx <= pcx + R; cx++) for (int cz = pcz - R; cz <= pcz + R; cz++) {
            int[] su = surf.get(key(cx, cz));
            int mn = Integer.MAX_VALUE, mx = PlanetGenerator.NONE;
            for (int v : su) if (v != PlanetGenerator.NONE) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
            int bLo = 1, bHi = 0;
            if (mx != PlanetGenerator.NONE) {
                int floor = mn - 32 * 16;
                for (int d = 0; d < 4; d++) {
                    int dx = d == 0 ? 1 : d == 1 ? -1 : 0, dz = d == 2 ? 1 : d == 3 ? -1 : 0;
                    int[] ns = surf.get(key(cx + dx, cz + dz));
                    for (int k = 0; k < 16; k++) {
                        int ox = dx == 1 ? 15 : dx == -1 ? 0 : k, oz = dz == 1 ? 15 : dz == -1 ? 0 : k;
                        int own = su[oz << 4 | ox];
                        if (own == PlanetGenerator.NONE) continue;
                        int nv = ns[((oz + dz) & 15) << 4 | ((ox + dx) & 15)];
                        if (nv < own) mn = Math.min(mn, Math.max(floor, nv + 1));
                    }
                }
                bLo = Math.max(pcy - S, Math.floorDiv(mn, 16));
                bHi = Math.min(pcy + S, Math.floorDiv(mx, 16));
            }
            int wLo = pcy - V - 1, wHi = pcy + V + 1;
            for (int cy = Math.max(minCy, Math.min(wLo, bLo)); cy <= Math.min(maxCy, Math.max(wHi, bHi)); cy++) {
                boolean w = cy >= wLo && cy <= wHi, b = cy >= bLo && cy <= bHi;
                if (!w && !b) continue;
                if (!gi.hasBlocks(cx, cy, cz)) { empty++; continue; }
                if (w) inWin++; else inBand++;
                if (gi.uniform(cx, cy, cz) >= 0) { uni++; continue; }
                gen.add(new Cube(cx, cy, cz, 0));
            }
        }
        System.out.printf(Locale.ROOT, "кубов с блоками: %d (окно %d, полоса вне окна %d); пустых (не шлются) %d; однородных (метка, сразу) %d; генерировать %d (%.1f на столбец)%n",
                inWin + inBand, inWin, inBand, empty, uni, gen.size(), gen.size() / (double) ((2 * R + 1) * (2 * R + 1)));

        for (int order = 0; order < 2; order++) {
            final int ord = order;
            List<Cube> q = new ArrayList<>();
            for (Cube c : gen) {
                int dx = c.cx() - pcx, dy = c.cy() - pcy, dz = c.cz() - pcz;
                q.add(new Cube(c.cx(), c.cy(), c.cz(), ord == 0 ? prioOld(dx, dy, dz) : prioNew(dx, dy, dz)));
            }
            q.sort(Comparator.comparingInt(Cube::prio));                       // стабильно: внутри кольца — порядок прихода меток
            for (int th : new int[]{4, Math.max(2, cores - 1)}) {
                measure((ord == 0 ? "порядок 0.62" : "порядок 0.63") + ", потоков " + th, q, th, pcx, pcy, pcz);
            }
        }
    }

    private void measure(String label, List<Cube> q, int threads, int pcx, int pcy, int pcz) throws Exception {
        PlanetGenerator g = ValeTestKit.gen();                                // холодные кэши — как при входе
        int n = q.size();
        long[] done = new long[n];
        AtomicInteger next = new AtomicInteger();
        AtomicLong cpu = new AtomicLong();
        long start = System.nanoTime();
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                GenArrayCube buf = new GenArrayCube(false);
                long my = 0;
                int k;
                while ((k = next.getAndIncrement()) < n) {
                    Cube c = q.get(k);
                    long a = System.nanoTime();
                    GenFingerprint.visible(g, ValeTestKit.BOUNDS, ValeTestKit.SEED, c.cx(), c.cy(), c.cz(), buf).visHash();
                    long b = System.nanoTime();
                    my += b - a;
                    done[k] = b - start;
                }
                cpu.addAndGet(my);
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        long wall = System.nanoTime() - start;
        // «рядом готово»: все кубы до 4 / 12 столбцов по горизонтали и ±3 куба по Y
        long near4 = 0, near12 = 0, half = 0;
        long[] sorted = done.clone();
        Arrays.sort(sorted);
        half = n == 0 ? 0 : sorted[n / 2];
        for (int k = 0; k < n; k++) {
            Cube c = q.get(k);
            int h = Math.max(Math.abs(c.cx() - pcx), Math.abs(c.cz() - pcz)), dy = Math.abs(c.cy() - pcy);
            if (h <= 4 && dy <= 3) near4 = Math.max(near4, done[k]);
            if (h <= 12) near12 = Math.max(near12, done[k]);
        }
        System.out.printf(Locale.ROOT, "  %-26s: всё за %5.1f с (CPU %.2f мс/куб), рядом ≤4 — %.2f с, ≤12 — %.1f с, половина — %.1f с%n",
                label, wall / 1e9, cpu.get() / 1e6 / Math.max(1, n), near4 / 1e9, near12 / 1e9, half / 1e9);
    }

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
}
