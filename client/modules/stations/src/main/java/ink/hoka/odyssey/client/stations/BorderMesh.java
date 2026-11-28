package ink.hoka.odyssey.client.stations;

/**
 * Геометрия границ региона — без классов игры (её же рисует превью PNG в тестах). Всё выдаётся четырёхугольниками
 * в координатах ОТНОСИТЕЛЬНО КАМЕРЫ (мировая точка − камера считается в double, потом float): на кубовых картах
 * (cosmos, ±32 000 и граница 100 000) нет дрожания float. Цвет вершин — ARGB, смешивание — сложением (свечение).
 * <ul>
 *   <li><b>Рёбра</b> — тонкая лента лицом к камере (яркая сердцевина + мягкий ореол), ширина растёт с расстоянием
 *       (линия не рассыпается в пиксели). Видны до {@code edgeNear}, к {@code edgeFar} гаснут. По рёбрам медленно
 *       бегут блики (фаза — по x+y+z: блик непрерывно переходит с ребра на ребро).</li>
 *   <li><b>Плёнка</b> — грань рядом с игроком (ближе {@code filmRadius}): еле заметная заливка + сетка шестигранников,
 *       ярче всего в точке грани напротив игрока, к краю круга гаснет в ноль. Сетка привязана к миру (не плывёт за
 *       игроком), по ней идёт широкая медленная волна. Видна с обеих сторон (изнутри станции так же).</li>
 * </ul>
 */
public final class BorderMesh {

    /** Куда складывать четырёхугольники (4 вершины по порядку обхода). */
    public interface Sink {
        void quad(float x0, float y0, float z0, int c0, float x1, float y1, float z1, int c1,
                  float x2, float y2, float z2, int c2, float x3, float y3, float z3, int c3);
    }

    /** Настройки вида (одни на кадр). */
    public static final class Style {
        public double edgeNear = 40, edgeFar = 96;      // рёбра: полная яркость до near, ноль к far
        public double filmRadius = 50;                   // плёнка: проявляется ближе этого к грани
        public float edgeAlpha = 0.85f, glowAlpha = 0.22f;
        public float filmAlpha = 0.06f, hexAlpha = 0.24f;
        public double hexSize = 0.8;                     // радиус шестигранника, блоки
        public float hexWidth = 0.028f;
        public double hexWidthPerBlock = 0.0018;          // толщина линии на блок расстояния (не тоньше hexWidth)
        public double pulseSpeed = 2.5;                  // блоки в секунду — бег бликов по рёбрам
        public double tile = 4;                          // плёнка строится плитками (блоки); невидимые в кадре — пропуск
        public float minAlpha = 0.015f;                  // линии сетки прозрачнее этого не рисуются (край круга)
        /** Видна ли коробка в кадре (мировые координаты); null — рисовать всё. Ставит модуль перед кадром. */
        public Visible visible;
    }

    /** Отсечение по кадру (фрустум). */
    public interface Visible { boolean test(double x0, double y0, double z0, double x1, double y1, double z1); }

    private BorderMesh() {}

    /**
     * Коробка (мировые координаты граней блоков). camera — точка камеры, eye — глаза игрока (от них считается
     * близость плёнки; от третьего лица камера и глаза разные), t — секунды (анимация), bright — общий множитель
     * (0..1+; показ админа — ярче), film — рисовать ли плёнку, far — рёбра видны дальше обычного (показ, выделение).
     */
    public static void box(double x0, double y0, double z0, double x1, double y1, double z1, int rgb, float bright,
                           boolean film, boolean far, double camX, double camY, double camZ,
                           double eyeX, double eyeY, double eyeZ, double t, Style st, Sink out) {
        box(x0, y0, z0, x1, y1, z1, rgb, bright, film, far, camX, camY, camZ, eyeX, eyeY, eyeZ, t, st, out, null);
    }

    /** Точка внутри других коробок того же региона (стыки внутри станции не рисуются). */
    public interface Inside { boolean test(double x, double y, double z); }

    private static final double EPS = 0.05;

    /**
     * Как {@link #box}, но с соседями по региону: грань, целиком закрытая соседними коробками, и рёбра-швы (по обе
     * стороны ровная стена или внутри станции) не рисуются — внутри станции нет линий на стыках коробок.
     */
    public static void box(double x0, double y0, double z0, double x1, double y1, double z1, int rgb, float bright,
                           boolean film, boolean far, double camX, double camY, double camZ,
                           double eyeX, double eyeY, double eyeZ, double t, Style st, Sink out, Inside others) {
        double near = far ? Math.max(st.edgeNear, 400) : st.edgeNear, farD = far ? Math.max(st.edgeFar, 512) : st.edgeFar;
        double[] xs = {x0, x1}, ys = {y0, y1}, zs = {z0, z1};
        double[] lo = {x0, y0, z0}, hi = {x1, y1, z1};
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
            double[] mx = {(x0 + x1) / 2, ys[a], zs[b]}, my = {xs[a], (y0 + y1) / 2, zs[b]}, mz = {xs[a], ys[b], (z0 + z1) / 2};
            if (!seam(others, lo, hi, mx, 1, a, 2, b))
                edge(xs[0], ys[a], zs[b], xs[1], ys[a], zs[b], rgb, bright, near, farD, camX, camY, camZ, t, st, out);   // вдоль X
            if (!seam(others, lo, hi, my, 0, a, 2, b))
                edge(xs[a], ys[0], zs[b], xs[a], ys[1], zs[b], rgb, bright, near, farD, camX, camY, camZ, t, st, out);   // вдоль Y
            if (!seam(others, lo, hi, mz, 0, a, 1, b))
                edge(xs[a], ys[b], zs[0], xs[a], ys[b], zs[1], rgb, bright, near, farD, camX, camY, camZ, t, st, out);   // вдоль Z
        }
        if (!film) return;
        double[] cam = {camX, camY, camZ}, eye = {eyeX, eyeY, eyeZ};
        for (int k = 0; k < 3; k++) {
            if (!covered(others, k, lo[k], -1, lo, hi)) face(k, lo[k], lo, hi, rgb, bright, cam, eye, t, st, out);
            if (!covered(others, k, hi[k], 1, lo, hi)) face(k, hi[k], lo, hi, rgb, bright, cam, eye, t, st, out);
        }
    }

    /** Грань (ось k, плоскость, наружу — dir) целиком закрыта соседями: углы и середина снаружи грани — внутри соседей. */
    static boolean covered(Inside others, int k, double plane, int dir, double[] lo, double[] hi) {
        if (others == null) return false;
        int u = (k + 1) % 3, v = (k + 2) % 3;
        double[] p = new double[3];
        double[] us = {lo[u] + EPS, (lo[u] + hi[u]) / 2, hi[u] - EPS}, vs = {lo[v] + EPS, (lo[v] + hi[v]) / 2, hi[v] - EPS};
        for (double uu : us) for (double vv : vs) {
            p[k] = plane + dir * EPS; p[u] = uu; p[v] = vv;
            if (!others.test(p[0], p[1], p[2])) return false;
        }
        return true;
    }

    /**
     * Ребро-шов: вокруг ребра (середина m, перпендикулярные оси ia/ib, сторона коробки sa/sb: 0 — низ, 1 — верх) четыре
     * четверти; своя коробка — внутренняя. Если занято всё или ровно половина (стена продолжается) — это не край
     * станции, ребро не рисуем.
     */
    static boolean seam(Inside others, double[] lo, double[] hi, double[] m, int ia, int sa, int ib, int sb) {
        if (others == null) return false;
        double inA = sa == 0 ? EPS : -EPS, inB = sb == 0 ? EPS : -EPS;          // смещение внутрь своей коробки
        boolean[][] q = new boolean[2][2];                                       // [внутрь по a][внутрь по b]
        double[] p = m.clone();
        for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
            if (i == 1 && j == 1) { q[1][1] = true; continue; }                  // своя коробка
            p[ia] = m[ia] + (i == 1 ? inA : -inA);
            p[ib] = m[ib] + (j == 1 ? inB : -inB);
            q[i][j] = others.test(p[0], p[1], p[2]);
        }
        int n = (q[0][0] ? 1 : 0) + (q[0][1] ? 1 : 0) + (q[1][0] ? 1 : 0) + 1;
        if (n == 4) return true;
        if (n == 2) return q[0][1] || q[1][0];                                   // половина — плоская стена, шов
        return false;
    }

    /** Расстояние от точки до отрезка. */
    static double segDist(double ax, double ay, double az, double bx, double by, double bz, double px, double py, double pz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double l2 = dx * dx + dy * dy + dz * dz;
        double s = l2 < 1e-12 ? 0 : Math.clamp(((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / l2, 0, 1);
        double qx = ax + dx * s - px, qy = ay + dy * s - py, qz = az + dz * s - pz;
        return Math.sqrt(qx * qx + qy * qy + qz * qz);
    }

    /** Ближайшее расстояние от точки до коробки (0 — внутри). */
    public static double boxDist(double x0, double y0, double z0, double x1, double y1, double z1, double px, double py, double pz) {
        double dx = Math.max(Math.max(x0 - px, 0), px - x1), dy = Math.max(Math.max(y0 - py, 0), py - y1), dz = Math.max(Math.max(z0 - pz, 0), pz - z1);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** До грани коробки изнутри или снаружи (для плёнки: внутри коробки — до ближайшей стенки). */
    public static double shellDist(double x0, double y0, double z0, double x1, double y1, double z1, double px, double py, double pz) {
        double out = boxDist(x0, y0, z0, x1, y1, z1, px, py, pz);
        if (out > 0) return out;
        return Math.min(Math.min(Math.min(px - x0, x1 - px), Math.min(py - y0, y1 - py)), Math.min(pz - z0, z1 - pz));
    }

    static float smooth(double e0, double e1, double x) {
        double t = Math.clamp((x - e0) / (e1 - e0), 0, 1);
        return (float) (t * t * (3 - 2 * t));
    }

    private static int argb(int rgb, float a) {
        int al = (int) Math.round(Math.clamp(a, 0, 1) * 255);
        return (al << 24) | (rgb & 0xFFFFFF);
    }

    /** Цвет посветлее (к белому на k). */
    static int lighten(int rgb, float k) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        r += Math.round((255 - r) * k); g += Math.round((255 - g) * k); b += Math.round((255 - b) * k);
        return (r << 16) | (g << 8) | b;
    }

    // ───────────────────────────── рёбра ─────────────────────────────

    private static void edge(double ax, double ay, double az, double bx, double by, double bz, int rgb, float bright,
                             double near, double far, double cx, double cy, double cz, double t, Style st, Sink out) {
        double d = segDist(ax, ay, az, bx, by, bz, cx, cy, cz);
        if (d >= far) return;
        double len = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
        if (len < 1e-6) return;
        double step = d < 32 ? 1.5 : d < 96 ? 6 : 24;                      // вблизи мельче: блик и угасание плавные
        int n = Math.max(1, (int) Math.ceil(len / step));
        int core = lighten(rgb, 0.55f);
        double px = ax, py = ay, pz = az;
        float[] pa = vert(px, py, pz, cx, cy, cz, near, far, t, st);
        for (int i = 1; i <= n; i++) {
            double s = (double) i / n;
            double qx = ax + (bx - ax) * s, qy = ay + (by - ay) * s, qz = az + (bz - az) * s;
            float[] pb = vert(qx, qy, qz, cx, cy, cz, near, far, t, st);
            if (pa[3] > 0.002f || pb[3] > 0.002f) {
                float ax0 = (float) (px - cx), ay0 = (float) (py - cy), az0 = (float) (pz - cz);
                float bx0 = (float) (qx - cx), by0 = (float) (qy - cy), bz0 = (float) (qz - cz);
                ribbon(ax0, ay0, az0, bx0, by0, bz0, pa[4], pb[4], core, pa[3] * st.edgeAlpha * bright, pb[3] * st.edgeAlpha * bright, false, out);
                ribbon(ax0, ay0, az0, bx0, by0, bz0, pa[4] * 5, pb[4] * 5, rgb, pa[3] * st.glowAlpha * bright, pb[3] * st.glowAlpha * bright, true, out);
            }
            px = qx; py = qy; pz = qz;
            pa = pb;
        }
    }

    /** Точка ребра: {.., .., .., яркость (угасание × блик), полуширина сердцевины}. */
    private static float[] vert(double x, double y, double z, double cx, double cy, double cz, double near, double far, double t, Style st) {
        double dx = x - cx, dy = y - cy, dz = z - cz;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float fade = 1 - smooth(near, far, d);
        double ph = (x + y + z) / 24.0 - t * st.pulseSpeed / 24.0;           // блики через каждые 24 блока
        double c = 0.5 + 0.5 * Math.cos(ph * Math.PI * 2);
        float pulse = (float) (0.7 + 0.8 * Math.pow(c, 12));
        float w = (float) Math.max(0.018, d * 0.0016);                        // ~1.5 пикселя на любом расстоянии
        return new float[]{0, 0, 0, fade * pulse, w};
    }

    /**
     * Лента a→b лицом к камере (камера в начале координат). soft — края прозрачные (ореол), иначе — сплошная
     * сердцевина с мягким краем.
     */
    private static void ribbon(float ax, float ay, float az, float bx, float by, float bz, float wa, float wb,
                               int rgb, float alA, float alB, boolean soft, Sink out) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float mx = (ax + bx) * 0.5f, my = (ay + by) * 0.5f, mz = (az + bz) * 0.5f;
        float sx = dy * mz - dz * my, sy = dz * mx - dx * mz, sz = dx * my - dy * mx;   // dir × (mid − камера)
        float l = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (l < 1e-9f) return;
        sx /= l; sy /= l; sz /= l;
        int ca = argb(rgb, alA), cb = argb(rgb, alB), ea = argb(rgb, soft ? 0 : alA * 0.35f), eb = argb(rgb, soft ? 0 : alB * 0.35f);
        out.quad(ax - sx * wa, ay - sy * wa, az - sz * wa, ea, ax, ay, az, ca, bx, by, bz, cb, bx - sx * wb, by - sy * wb, bz - sz * wb, eb);
        out.quad(ax, ay, az, ca, ax + sx * wa, ay + sy * wa, az + sz * wa, ea, bx + sx * wb, by + sy * wb, bz + sz * wb, eb, bx, by, bz, cb);
    }

    // ───────────────────────────── плёнка ─────────────────────────────

    /** Грань с нормалью по оси k на уровне plane; lo/hi — коробка. */
    private static void face(int k, double plane, double[] lo, double[] hi, int rgb, float bright, double[] cam, double[] eye,
                             double t, Style st, Sink out) {
        double R = st.filmRadius;
        double dp = Math.abs(eye[k] - plane);
        if (dp >= R) return;
        int ku = k == 0 ? 2 : 0, kv = k == 1 ? 2 : 1;                       // оси на грани: X — (z, y), Y — (x, z), Z — (x, y)
        double rr = Math.sqrt(R * R - dp * dp);
        double eu = eye[ku], ev = eye[kv];
        double u0 = Math.max(lo[ku], eu - rr), u1 = Math.min(hi[ku], eu + rr);
        double v0 = Math.max(lo[kv], ev - rr), v1 = Math.min(hi[kv], ev + rr);
        if (u0 >= u1 || v0 >= v1) return;
        // чуть к камере: не спорить по глубине с блоками, стоящими ровно на границе (стены станции)
        double off = cam[k] > plane ? 0.012 : cam[k] < plane ? -0.012 : 0;
        double w = plane + off - cam[k];
        FaceCtx f = new FaceCtx(k, ku, kv, w, cam, eye, plane, R, rgb, bright, t, st, out);

        // плёнка — плитками tile×tile: невидимые в кадре и целиком дальше R от глаз пропускаем (у стены в кадре — малая
        // часть круга радиусом R, а строить весь круг каждый кадр — это тысячи лишних квадов)
        double T = st.tile, cell = Math.max(1, Math.floor(R / 16));
        double s6 = st.hexSize, hw = Math.sqrt(3) * s6, hh = 1.5 * s6;
        int hex = lighten(rgb, 0.35f);
        double[] a = new double[3], b = new double[3];
        for (double tu = Math.floor(u0 / T) * T; tu < u1; tu += T) {
            double ta = Math.max(tu, u0), tb = Math.min(tu + T, u1);
            for (double tv = Math.floor(v0 / T) * T; tv < v1; tv += T) {
                double tc = Math.max(tv, v0), td = Math.min(tv + T, v1);
                double nu = Math.clamp(eu, ta, tb) - eu, nv = Math.clamp(ev, tc, td) - ev;   // ближайшая точка плитки
                if (nu * nu + nv * nv + dp * dp >= R * R) continue;
                if (st.visible != null) {
                    a[k] = plane - 0.1; a[ku] = ta; a[kv] = tc;
                    b[k] = plane + 0.1; b[ku] = tb; b[kv] = td;
                    if (!st.visible.test(a[0], a[1], a[2], b[0], b[1], b[2])) continue;
                }
                // заливка: клетки (1 блок, при большом радиусе крупнее), прозрачность по вершинам
                for (double u = Math.floor(ta / cell) * cell; u < tb; u += cell) {
                    double ua = Math.max(u, ta), ub = Math.min(u + cell, tb);
                    for (double v = Math.floor(tc / cell) * cell; v < td; v += cell) {
                        double va = Math.max(v, tc), vb = Math.min(v + cell, td);
                        float a00 = f.film(ua, va), a10 = f.film(ub, va), a11 = f.film(ub, vb), a01 = f.film(ua, vb);
                        if (a00 + a10 + a11 + a01 < 0.004f) continue;
                        f.quad(ua, va, a00, ub, va, a10, ub, vb, a11, ua, vb, a01, rgb);
                    }
                }
                // шестигранники (остриём вверх): у каждого три ребра (верх-право, верх-лево, лево) — сетка без повторов;
                // отрезки обрезаются по плитке, так ребро на стыке плиток рисуется ровно один раз (двумя кусками)
                int j0 = (int) Math.floor((tc - s6) / hh), j1 = (int) Math.ceil((td + s6) / hh);
                for (int j = j0; j <= j1; j++) {
                    double shift = (j & 1) == 0 ? 0 : hw * 0.5;
                    int i0 = (int) Math.floor((ta - shift - hw) / hw), i1 = (int) Math.ceil((tb - shift + hw) / hw);
                    for (int i = i0; i <= i1; i++) {
                        double cu = i * hw + shift, cv = j * hh;
                        double pu = cu + s6 * COS[0], pv = cv + s6 * SIN[0];
                        for (int e = 1; e <= 3; e++) {
                            double qu = cu + s6 * COS[e], qv = cv + s6 * SIN[e];
                            f.line(pu, pv, qu, qv, ta, tc, tb, td, hex);
                            pu = qu; pv = qv;
                        }
                    }
                }
            }
        }
    }

    private static final double[] COS = new double[6], SIN = new double[6];
    static {
        for (int i = 0; i < 6; i++) { COS[i] = Math.cos(Math.toRadians(30 + 60 * i)); SIN[i] = Math.sin(Math.toRadians(30 + 60 * i)); }
    }

    /** Одна грань в кадре: перевод (u, v) → точка относительно камеры, яркость плёнки в точке. */
    private static final class FaceCtx {
        final int k, ku, kv;
        final double w, plane, R;
        final double[] cam, eye;
        final int rgb;
        final float bright;
        final double t;
        final Style st;
        final Sink out;

        FaceCtx(int k, int ku, int kv, double w, double[] cam, double[] eye, double plane, double R, int rgb, float bright,
                double t, Style st, Sink out) {
            this.k = k; this.ku = ku; this.kv = kv; this.w = w; this.cam = cam; this.eye = eye; this.plane = plane;
            this.R = R; this.rgb = rgb; this.bright = bright; this.t = t; this.st = st; this.out = out;
        }

        /** Близость к игроку 0..1 (1 — точка грани напротив глаз, 0 — на краю круга R). */
        float near(double u, double v) {
            double du = u - eye[ku], dv = v - eye[kv], dk = plane - eye[k];
            double d = Math.sqrt(du * du + dv * dv + dk * dk);
            double x = Math.clamp(1 - d / R, 0, 1);
            return (float) (x * x * (3 - 2 * x));
        }

        /** Медленная широкая волна по грани (бегущий блик). */
        float wave(double u, double v) {
            double c = 0.5 + 0.5 * Math.sin((u + v * 0.6) * 0.45 - t * 1.1);
            return (float) (0.75 + 0.9 * Math.pow(c, 6));
        }

        float film(double u, double v) { return near(u, v) * st.filmAlpha * bright * wave(u, v); }

        float hexA(double u, double v) { return near(u, v) * st.hexAlpha * bright * wave(u, v); }

        private final float[] q = new float[12];

        void point(double u, double v, float[] o, int at) {
            o[at + k] = (float) w;
            o[at + ku] = (float) (u - cam[ku]);
            o[at + kv] = (float) (v - cam[kv]);
        }

        void quad(double ua, double va, float a0, double ub, double vb0, float a1, double uc, double vc, float a2,
                  double ud, double vd, float a3, int color) {
            point(ua, va, q, 0); point(ub, vb0, q, 3); point(uc, vc, q, 6); point(ud, vd, q, 9);
            out.quad(q[0], q[1], q[2], argb(color, a0), q[3], q[4], q[5], argb(color, a1),
                    q[6], q[7], q[8], argb(color, a2), q[9], q[10], q[11], argb(color, a3));
        }

        /** Отрезок сетки (в плоскости грани), обрезанный по прямоугольнику [u0,u1]×[v0,v1]. */
        void line(double pu, double pv, double qu, double qv, double u0, double v0, double u1, double v1, int color) {
            double t0 = 0, t1 = 1, du = qu - pu, dv = qv - pv;
            double[] pp = {-du, du, -dv, dv}, qq = {pu - u0, u1 - pu, pv - v0, v1 - pv};
            for (int i = 0; i < 4; i++) {                                    // Лянг — Барски
                if (Math.abs(pp[i]) < 1e-12) { if (qq[i] < 0) return; continue; }
                double r = qq[i] / pp[i];
                if (pp[i] < 0) { if (r > t1) return; if (r > t0) t0 = r; }
                else { if (r < t0) return; if (r < t1) t1 = r; }
            }
            double au = pu + du * t0, av = pv + dv * t0, bu = pu + du * t1, bv = pv + dv * t1;
            float aa = hexA(au, av), ab = hexA(bu, bv);
            if (aa < st.minAlpha && ab < st.minAlpha) return;
            double l = Math.sqrt((bu - au) * (bu - au) + (bv - av) * (bv - av));
            if (l < 1e-6) return;
            // толщина растёт с расстоянием от камеры: вдали линия не тоньше ~1–2 пикселей (иначе рябит)
            double mu = (au + bu) / 2 - cam[ku], mv = (av + bv) / 2 - cam[kv];
            double dist = Math.sqrt(mu * mu + mv * mv + w * w);
            double h = Math.max(st.hexWidth, dist * st.hexWidthPerBlock) * 0.5, nu = -(bv - av) / l * h, nv = (bu - au) / l * h;
            quad(au - nu, av - nv, aa, bu - nu, bv - nv, ab, bu + nu, bv + nv, ab, au + nu, av + nv, aa, color);
        }
    }
}
