package ink.hoka.odyssey.client.vessels;

/**
 * Поле расстояний до блоков (без Minestom и игры — один и тот же файл у сервера и клиентского модуля, сверяется
 * тестом). Коробка мира [x0, x0+nx) × [y0, y0+ny) × [z0, z0+nz) по блокам: в каждой клетке — расстояние (блоки) от её
 * середины до середины ближайшего занятого блока (точное евклидово: преобразование Фельценшвальба — Хуттенлохера,
 * три прохода по осям, линейное время). Между серединами — трилинейно. Блоков в коробке нет — расстояние «далеко»
 * ({@link #FAR}). Строится один раз на кусок пути корабля ({@link Cache}), дальше — только чтение.
 */
final class DistField {

    /** Что в блоке: занят ли (для подушки — твёрдый или жидкость). */
    interface Blocked {
        boolean at(int x, int y, int z);

        /**
         * Быстро про весь кусок 16³ (sx, sy, sz — номера кусков, блок >> 4): 0 — пусто целиком, 1 — занято целиком,
         * −1 — смотреть по блокам (по умолчанию).
         */
        default int section(int sx, int sy, int sz) { return -1; }
    }

    static final double FAR = 1e4;
    private static final double INF = 1e12;

    final int x0, y0, z0, nx, ny, nz;
    private final float[] d;

    private DistField(int x0, int y0, int z0, int nx, int ny, int nz, float[] d) {
        this.x0 = x0; this.y0 = y0; this.z0 = z0;
        this.nx = nx; this.ny = ny; this.nz = nz;
        this.d = d;
    }

    /** Построить для коробки блоков [x0, x0+nx) … (обход — столбцами: x, z, затем y — чтение мира кусками подряд). */
    static DistField build(int x0, int y0, int z0, int nx, int ny, int nz, Blocked b) {
        int n = nx * ny * nz;
        double[] g = new double[n];
        int sxs = Math.floorDiv(x0, 16), sys = Math.floorDiv(y0, 16), szs = Math.floorDiv(z0, 16);
        int snx = Math.floorDiv(x0 + nx - 1, 16) - sxs + 1, sny = Math.floorDiv(y0 + ny - 1, 16) - sys + 1, snz = Math.floorDiv(z0 + nz - 1, 16) - szs + 1;
        byte[] sec = new byte[snx * sny * snz];                                 // 0 — не спрашивали, 1 пусто, 2 занято, 3 по блокам
        boolean any = false;
        for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++) for (int j = 0; j < ny; j++) {
            int wx = x0 + i, wy = y0 + j, wz = z0 + k;
            int si = ((Math.floorDiv(wx, 16) - sxs) * sny + (Math.floorDiv(wy, 16) - sys)) * snz + (Math.floorDiv(wz, 16) - szs);
            if (sec[si] == 0) {
                int s = b.section(wx >> 4, wy >> 4, wz >> 4);
                sec[si] = (byte) (s == 0 ? 1 : s == 1 ? 2 : 3);
            }
            boolean full = sec[si] == 2 || (sec[si] == 3 && b.at(wx, wy, wz));
            g[(i * ny + j) * nz + k] = full ? 0 : INF;
            any |= full;
        }
        float[] out = new float[n];
        if (!any) {
            java.util.Arrays.fill(out, (float) FAR);
            return new DistField(x0, y0, z0, nx, ny, nz, out);
        }
        int m = Math.max(nx, Math.max(ny, nz));
        double[] f = new double[m], r = new double[m], zz = new double[m + 1];
        int[] v = new int[m];
        for (int i = 0; i < nx; i++) for (int j = 0; j < ny; j++) {              // по z
            int base = (i * ny + j) * nz;
            for (int k = 0; k < nz; k++) f[k] = g[base + k];
            line(f, nz, r, v, zz);
            for (int k = 0; k < nz; k++) g[base + k] = r[k];
        }
        for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++) {              // по y
            for (int j = 0; j < ny; j++) f[j] = g[(i * ny + j) * nz + k];
            line(f, ny, r, v, zz);
            for (int j = 0; j < ny; j++) g[(i * ny + j) * nz + k] = r[j];
        }
        for (int j = 0; j < ny; j++) for (int k = 0; k < nz; k++) {              // по x
            for (int i = 0; i < nx; i++) f[i] = g[(i * ny + j) * nz + k];
            line(f, nx, r, v, zz);
            for (int i = 0; i < nx; i++) g[(i * ny + j) * nz + k] = r[i];
        }
        for (int i = 0; i < n; i++) out[i] = g[i] >= INF / 2 ? (float) FAR : (float) Math.min(FAR, Math.sqrt(g[i]));
        return new DistField(x0, y0, z0, nx, ny, nz, out);
    }

    /** Одномерное преобразование: квадрат расстояния до ближайшего нуля (нижняя огибающая парабол). */
    private static void line(double[] f, int n, double[] d, int[] v, double[] z) {
        int k = -1;
        for (int q = 0; q < n; q++) {
            if (f[q] >= INF) continue;                                          // «пусто» — не парабола
            double s = -INF;
            while (k >= 0) {
                s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
                if (s <= z[k]) k--;
                else break;
            }
            k++;
            v[k] = q;
            z[k] = k == 0 ? -INF : s;
            z[k + 1] = INF;
        }
        if (k < 0) { for (int q = 0; q < n; q++) d[q] = INF; return; }
        int j = 0;
        for (int q = 0; q < n; q++) {
            while (z[j + 1] < q) j++;
            double dq = q - v[j];
            d[q] = dq * dq + f[v[j]];
        }
    }

    /** Расстояние в клетке (i, j, k); вне коробки — FAR. */
    double cell(int i, int j, int k) {
        if (i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz) return FAR;
        return d[(i * ny + j) * nz + k];
    }

    /** Есть ли точка внутри коробки с запасом pad блоков (поле там верное до pad). */
    boolean covers(double x, double y, double z, double pad) {
        return x - pad >= x0 && y - pad >= y0 && z - pad >= z0 && x + pad <= x0 + nx && y + pad <= y0 + ny && z + pad <= z0 + nz;
    }

    /** Расстояние от точки мира до середины ближайшего занятого блока (трилинейно между серединами клеток). */
    double at(double x, double y, double z) {
        double fx = x - x0 - 0.5, fy = y - y0 - 0.5, fz = z - z0 - 0.5;
        int i = (int) Math.floor(fx), j = (int) Math.floor(fy), k = (int) Math.floor(fz);
        double tx = fx - i, ty = fy - j, tz = fz - k;
        double c000 = cell(i, j, k), c100 = cell(i + 1, j, k), c010 = cell(i, j + 1, k), c110 = cell(i + 1, j + 1, k);
        double c001 = cell(i, j, k + 1), c101 = cell(i + 1, j, k + 1), c011 = cell(i, j + 1, k + 1), c111 = cell(i + 1, j + 1, k + 1);
        double c00 = c000 + (c100 - c000) * tx, c10 = c010 + (c110 - c010) * tx, c01 = c001 + (c101 - c001) * tx, c11 = c011 + (c111 - c011) * tx;
        double c0 = c00 + (c10 - c00) * ty, c1 = c01 + (c11 - c01) * ty;
        return c0 + (c1 - c0) * tz;
    }

    /** Куда расстояние растёт быстрее всего (от блоков), не единичный; out — 3 числа. */
    void grad(double x, double y, double z, double[] out) {
        double e = 0.5;
        out[0] = (at(x + e, y, z) - at(x - e, y, z)) / (2 * e);
        out[1] = (at(x, y + e, z) - at(x, y - e, z)) / (2 * e);
        out[2] = (at(x, y, z + e) - at(x, y, z - e)) / (2 * e);
    }

    /**
     * Поле, которое следует за кораблём: строится заново, только когда точки корпуса вышли из коробки с запасом reach
     * (поле верно до reach от корпуса); новая коробка — с запасом margin вперёд, чтобы строить редко.
     */
    static final class Cache {
        DistField field;
        int margin = 6;
        /** Сколько раз строилось (тесты, отладка). */
        int builds;
        /** Больше стольких клеток — не строить (огромный корабль): подушки нет. */
        int maxCells = 600_000;

        /** probes — точки корпуса в мире по 3 числа. null — не построить (слишком велико). */
        DistField get(double[] probes, double reach, Blocked b) {
            double[] box = bounds(probes);
            if (box == null) return null;
            if (field != null && field.covers(box[0], box[1], box[2], reach) && field.covers(box[3], box[4], box[5], reach)) return field;
            double pad = reach + margin;
            int x0 = (int) Math.floor(box[0] - pad), y0 = (int) Math.floor(box[1] - pad), z0 = (int) Math.floor(box[2] - pad);
            int x1 = (int) Math.ceil(box[3] + pad), y1 = (int) Math.ceil(box[4] + pad), z1 = (int) Math.ceil(box[5] + pad);
            long cells = (long) (x1 - x0) * (y1 - y0) * (z1 - z0);
            if (cells > maxCells || cells <= 0) return null;
            field = build(x0, y0, z0, x1 - x0, y1 - y0, z1 - z0, b);
            builds++;
            return field;
        }
    }

    /** Габарит точек (по 3 числа): minX minY minZ maxX maxY maxZ; пусто — null. */
    static double[] bounds(double[] p) {
        if (p == null || p.length < 3) return null;
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i + 2 < p.length; i += 3) for (int a = 0; a < 3; a++) {
            if (!Double.isFinite(p[i + a])) return null;
            b[a] = Math.min(b[a], p[i + a]);
            b[a + 3] = Math.max(b[a + 3], p[i + a]);
        }
        return b;
    }
}
