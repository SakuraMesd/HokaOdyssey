package ink.hoka.odyssey.planet.vale;

import ink.hoka.odyssey.gen.GenCube;

import java.util.Arrays;

/**
 * Готовый объект (процедурное дерево/гриб/цветок или повёрнутый .hobj): блоки относительно якоря, отсортированы по
 * (dy, dz, dx) — для вставки берётся только слой куба. Пишет только в воздух (как GenPlacer без replace). Колонки
 * (для highest) — по запросу. Неизменяем; ленивый индекс колонок — гонка безвредна.
 */
final class Shape {

    static final int H = 64, V = 512;               // |dx|,|dz| ≤ 64; dy в [−256, 256]

    final int n;
    /** (dy + V) << 16 | (dz + 128) << 8 | (dx + 128), по возрастанию. */
    final int[] pos;
    final int[] st;
    final byte[] fl;
    final int minDx, maxDx, minDz, maxDz, minDy, maxDy;
    private volatile int[][] cols;

    private Shape(int[] pos, int[] st, byte[] fl) {
        this.n = pos.length;
        this.pos = pos;
        this.st = st;
        this.fl = fl;
        int a = Integer.MAX_VALUE, b = Integer.MIN_VALUE, c = Integer.MAX_VALUE, d = Integer.MIN_VALUE;
        for (int p : pos) {
            int dx = dx(p), dz = dz(p);
            a = Math.min(a, dx); b = Math.max(b, dx); c = Math.min(c, dz); d = Math.max(d, dz);
        }
        minDx = n == 0 ? 0 : a; maxDx = n == 0 ? 0 : b; minDz = n == 0 ? 0 : c; maxDz = n == 0 ? 0 : d;
        minDy = n == 0 ? 0 : dy(pos[0]);
        maxDy = n == 0 ? 0 : dy(pos[n - 1]);
    }

    static int dx(int p) { return (p & 0xFF) - 128; }
    static int dz(int p) { return ((p >> 8) & 0xFF) - 128; }
    static int dy(int p) { return (p >>> 16) - V; }

    /** Вставить в куб часть с якорем (ax, ay, az) — только в воздух. */
    void paste(GenCube out, int ax, int ay, int az) {
        int x0 = out.x0(), y0 = out.y0(), z0 = out.z0();
        if (ax + maxDx < x0 || ax + minDx > x0 + 15 || az + maxDz < z0 || az + minDz > z0 + 15) return;
        if (ay + maxDy < y0 || ay + minDy > y0 + 15) return;
        int from = lower((y0 - ay + V) << 16), to = lower((y0 + 16 - ay + V) << 16);
        for (int i = from; i < to; i++) {
            int p = pos[i];
            int lx = ax + dx(p) - x0, lz = az + dz(p) - z0;
            if ((lx | lz) < 0 || lx > 15 || lz > 15) continue;
            int ly = ay + dy(p) - y0;
            if (out.get(lx, ly, lz) != 0) continue;
            out.set(lx, ly, lz, st[i]);
        }
    }

    private int lower(int key) {
        if (key <= 0) return 0;
        int lo = 0, hi = n;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (pos[m] < key) lo = m + 1; else hi = m; }
        return lo;
    }

    /** Тот же объект в пределах |dx|,|dz| ≤ lim (опоры перепроверяются); влезает — он сам. */
    Shape clip(int lim, Pal pal) {
        if (-minDx <= lim && maxDx <= lim && -minDz <= lim && maxDz <= lim) return this;
        B b = new B(pal);
        for (int i = 0; i < n; i++) b.put(dx(pos[i]), dy(pos[i]), dz(pos[i]), st[i]);
        return b.build(lim);
    }

    /** Блоки колонки (dx, dz): номера по убыванию dy; нет — пустой массив. */
    int[] column(int dx, int dz) {
        if (dx < minDx || dx > maxDx || dz < minDz || dz > maxDz) return EMPTY;
        int[][] c = cols;
        if (c == null) cols = c = buildCols();
        int[] r = c[(dz - minDz) * (maxDx - minDx + 1) + dx - minDx];
        return r == null ? EMPTY : r;
    }

    static final int[] EMPTY = new int[0];

    private int[][] buildCols() {
        int w = maxDx - minDx + 1, d = maxDz - minDz + 1;
        int[] cnt = new int[w * d];
        for (int p : pos) cnt[(dz(p) - minDz) * w + dx(p) - minDx]++;
        int[][] c = new int[w * d][];
        for (int i = 0; i < c.length; i++) if (cnt[i] > 0) c[i] = new int[cnt[i]];
        Arrays.fill(cnt, 0);
        for (int i = n - 1; i >= 0; i--) {                                    // сверху вниз
            int k = (dz(pos[i]) - minDz) * w + dx(pos[i]) - minDx;
            c[k][cnt[k]++] = i;
        }
        return c;
    }

    // ───────────── сборка ─────────────

    /**
     * Сборщик: открытая адресация ключ → состояние (порядок вставки не важен — в конце сортировка по ключу).
     * put — поверх, add — только в пустое. Вне лимита (|dx|,|dz| ≤ 64, |dy| ≤ 256) — молча отбрасывается.
     */
    static final class B {
        final Pal pal;
        private int[] keys = new int[1024], vals = new int[1024];
        private int size;

        B(Pal pal) { this.pal = pal; }

        static int key(int x, int y, int z) { return (y + V) << 16 | (z + 128) << 8 | (x + 128); }

        private static boolean ok(int x, int y, int z) { return x >= -H && x <= H && z >= -H && z <= H && y >= -256 && y <= 256; }

        private int slot(int k) {
            int m = keys.length - 1, i = (k * 0x9E3779B1) >>> 7 & m;
            while (keys[i] != 0 && keys[i] != k) i = (i + 1) & m;
            return i;
        }

        private void grow() {
            int[] ok = keys, ov = vals;
            keys = new int[ok.length * 2];
            vals = new int[ok.length * 2];
            for (int i = 0; i < ok.length; i++) if (ok[i] != 0) { int s = slot(ok[i]); keys[s] = ok[i]; vals[s] = ov[i]; }
        }

        void put(int x, int y, int z, int st) {
            if (!ok(x, y, z) || st == 0) return;
            if (size * 2 >= keys.length) grow();
            int k = key(x, y, z), s = slot(k);
            if (keys[s] == 0) { keys[s] = k; size++; }
            vals[s] = st;
        }

        void add(int x, int y, int z, int st) {
            if (!ok(x, y, z) || st == 0) return;
            if (size * 2 >= keys.length) grow();
            int k = key(x, y, z), s = slot(k);
            if (keys[s] != 0) return;
            keys[s] = k; vals[s] = st; size++;
        }

        int get(int x, int y, int z) {
            if (!ok(x, y, z)) return 0;
            int s = slot(key(x, y, z));
            return keys[s] == 0 ? 0 : vals[s];
        }

        boolean has(int x, int y, int z) { return get(x, y, z) != 0; }

        int size() { return size; }

        /** Эллипсоид (add) с рваным краем: доля дыр на краю — holes. */
        void blob(double cx, double cy, double cz, double rx, double ry, double rz, int st, Rnd r, double holes) {
            for (int y = (int) Math.floor(cy - ry); y <= (int) Math.ceil(cy + ry); y++)
                for (int z = (int) Math.floor(cz - rz); z <= (int) Math.ceil(cz + rz); z++)
                    for (int x = (int) Math.floor(cx - rx); x <= (int) Math.ceil(cx + rx); x++) {
                        double dx = (x - cx) / rx, dy = (y - cy) / ry, dz = (z - cz) / rz;
                        double d = dx * dx + dy * dy + dz * dz;
                        if (d > 1) continue;
                        if (d > 0.6 && r.next() < holes) continue;
                        add(x, y, z, st);
                    }
        }

        /** Толстый отрезок (put): шары радиуса rad от (x0,y0,z0) до (x1,y1,z1), радиус от rad0 к rad1. */
        void limb(double x0, double y0, double z0, double x1, double y1, double z1, double rad0, double rad1, int st) {
            double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
            double len = StrictMath.sqrt(dx * dx + dy * dy + dz * dz);
            int steps = Math.max(1, (int) Math.ceil(len * 2));
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                double px = x0 + dx * t, py = y0 + dy * t, pz = z0 + dz * t, rad = rad0 + (rad1 - rad0) * t;
                if (rad < 0.6) { put((int) Math.floor(px + 0.5), (int) Math.floor(py + 0.5), (int) Math.floor(pz + 0.5), st); continue; }
                int rr = (int) Math.ceil(rad);
                for (int a = -rr; a <= rr; a++) for (int b = -rr; b <= rr; b++) for (int c = -rr; c <= rr; c++) {
                    double qx = Math.floor(px + 0.5) + a - px, qy = Math.floor(py + 0.5) + b - py, qz = Math.floor(pz + 0.5) + c - pz;
                    if (qx * qx + qy * qy + qz * qz <= rad * rad + 0.25)
                        put((int) Math.floor(px + 0.5) + a, (int) Math.floor(py + 0.5) + b, (int) Math.floor(pz + 0.5) + c, st);
                }
            }
        }

        /** Свисающая лиана/мох вниз от (x, y, z) длиной len (только в пустое; упёрлась — стоп). Конец — tip. Ответ — сколько поставлено. */
        int hang(int x, int y, int z, int len, int body, int tip) {
            for (int i = 0; i < len; i++) {
                if (has(x, y - i, z)) return i;
                add(x, y - i, z, i == len - 1 || has(x, y - i - 1, z) ? tip : body);
            }
            return len;
        }

        Shape build() { return build(H); }

        /**
         * Готовый объект: блоки дальше lim по X/Z отбрасываются (охват слоя), затем снимаются растения без опоры по ванильным
         * правилам (лиана — сбоку полная грань или лиана сверху с той же стороной; ягодные/плакучие лозы и спороцвет — сверху
         * прочный блок, листва не годится; бледный мох — сверху полная грань; вьюны вверх — снизу прочный блок). Опора вне
         * объекта (рельеф) — только снизу у вьюнов: их ставят от высоты рельефа колонки.
         */
        Shape build(int lim) {
            int[] k = new int[size];
            int j = 0;
            for (int key : keys) if (key != 0) k[j++] = key;
            Arrays.sort(k);
            for (int key : k) {                                               // лимит охвата
                if (Math.abs(dx(key)) > lim || Math.abs(dz(key)) > lim) vals[slot(key)] = 0;
            }
            for (int i = k.length - 1; i >= 0; i--) prune(k[i], false);       // сверху вниз: висячие и лианы
            for (int i = 0; i < k.length; i++) prune(k[i], true);             // снизу вверх: вьюны
            int m = 0;
            for (int key : k) if (vals[slot(key)] != 0) m++;
            int[] kk = new int[m], st = new int[m];
            byte[] fl = new byte[m];
            j = 0;
            for (int key : k) {
                int v = vals[slot(key)];
                if (v == 0) continue;
                kk[j] = key; st[j] = v; fl[j] = (byte) pal.flags(v); j++;
            }
            return new Shape(kk, st, fl);
        }

        private int at(int x, int y, int z) { return get(x, y, z); }

        private void prune(int key, boolean up) {
            int s = slot(key), v = vals[s];
            if (v == 0) return;
            int need = pal.need(v);
            if (need == 0 || (need == Pal.N_UP) != up) return;
            int x = dx(key), y = dy(key), z = dz(key), sd = pal.side(v);
            boolean ok = switch (need) {
                case Pal.N_HANG -> { int a = at(x, y + 1, z); yield (pal.hold(a) & 2) != 0 || (pal.need(a) == Pal.N_HANG && pal.side(a) == sd); }
                case Pal.N_MOSS -> { int a = at(x, y + 1, z); yield (pal.hold(a) & 1) != 0 || pal.need(a) == Pal.N_MOSS; }
                case Pal.N_PROPAGULE -> { int a = at(x, y + 1, z); yield a != 0 && (pal.hold(a) & 1) != 0; }
                case Pal.N_UP -> {
                    if (!has(x, y - 1, z)) yield true;                       // снизу не объект — рельеф (вьюны ставят от земли)
                    int a = at(x, y - 1, z);
                    yield (pal.hold(a) & 2) != 0 || (pal.need(a) == Pal.N_UP && pal.side(a) == sd);
                }
                case Pal.N_COCOA -> pal.jungleLog(at(x + fx(sd), y, z + fz(sd)));
                default -> {                                                  // лиана / налёт: хоть одна сторона держится
                    boolean any = false;
                    for (int b = 0; b < 6 && !any; b++) {
                        if ((sd & (1 << b)) == 0) continue;
                        int a = b < 4 ? at(x + fx(1 << b), y, z + fz(1 << b)) : at(x, y + (b == 4 ? 1 : -1), z);
                        if ((pal.hold(a) & 1) != 0) any = true;
                        else if (need == Pal.N_VINE && b < 4) {
                            int u = at(x, y + 1, z);
                            any = pal.need(u) == Pal.N_VINE && (pal.side(u) & (1 << b)) != 0;
                        }
                    }
                    yield any;
                }
            };
            if (!ok) vals[s] = 0;
        }

        private static int fx(int bit) { return bit == 1 ? 1 : bit == 2 ? -1 : 0; }

        private static int fz(int bit) { return bit == 4 ? 1 : bit == 8 ? -1 : 0; }
    }

    /** Случайность строителя (SplitMix64 от сида ячейки). */
    static final class Rnd {
        private long s;

        Rnd(long seed) { s = seed; }

        long nextLong() {
            s += 0x9E3779B97F4A7C15L;
            long z = s;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        double next() { return (nextLong() >>> 11) * 0x1.0p-53; }

        int nextInt(int bound) { return (int) (((nextLong() >>> 33) * bound) >>> 31); }

        /** [a, b]. */
        int range(int a, int b) { return a + nextInt(b - a + 1); }

        double range(double a, double b) { return a + (b - a) * next(); }

        boolean chance(double p) { return next() < p; }
    }

    // ───────────── направления (литералы cos/sin, без Math.cos) ─────────────

    private static final double[] C = new double[17], S = new double[17];

    static {
        double[] c = {1, 0.9238795325112867, 0.7071067811865476, 0.38268343236508984, 0};
        double[] s = {0, 0.3826834323650898, 0.7071067811865476, 0.9238795325112867, 1};
        for (int k = 0; k <= 16; k++) {
            int q = k & 3, quad = (k >> 2) & 3;
            double a = q == 0 ? c[0] : c[q], b = q == 0 ? s[0] : s[q];
            // поворот на quad × 90°
            double x = a, y = b;
            for (int i = 0; i < quad; i++) { double t = x; x = -y; y = t; }
            C[k] = x; S[k] = y;
        }
    }

    /** cos/sin угла turn ∈ [0, 1) оборота (линейно между 16 опорными, нормировано). Ответ — в out[0], out[1]. */
    static void dir(double turn, double[] out) {
        double t = (turn - Math.floor(turn)) * 16;
        int i = (int) Math.floor(t);
        double f = t - i;
        double x = C[i] * (1 - f) + C[i + 1] * f, y = S[i] * (1 - f) + S[i + 1] * f;
        double l = StrictMath.sqrt(x * x + y * y);
        out[0] = x / l;
        out[1] = y / l;
    }
}
