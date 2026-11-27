package ink.hoka.odyssey.client.vessels.hud;

import java.util.Arrays;

/**
 * Холст w×h в пикселях экрана: цвет и прозрачность каждого пикселя, рисование «поверх» с покрытием (сглаживание),
 * мягкие тени. Техника — как у HUD старых кораблей (ships/ShipHud), без классов игры: тот же холст рисует и HUD в игре
 * (картинкой один к одному), и превью в PNG (тесты). Обходит только рамку фигуры; линии — по строкам, только полосу
 * вокруг отрезка (длинные косые линии лесенки — быстро).
 */
public final class Canvas {

    public final int w, h;
    final float[] r, g, b, a;
    /** Растёт при каждой перерисовке ({@link #touch}) — по нему картинка в видеокарте обновляется. */
    int version;

    public Canvas(int w, int h) {
        this.w = Math.max(1, w);
        this.h = Math.max(1, h);
        int n = this.w * this.h;
        r = new float[n]; g = new float[n]; b = new float[n]; a = new float[n];
    }

    public void clear() { Arrays.fill(a, 0); }

    /** Рисунок поменялся — картинку перезалить. */
    public void touch() { version++; }

    public int version() { return version; }

    public void put(int x, int y, int rgb, float alpha) {
        if (x < 0 || y < 0 || x >= w || y >= h || alpha <= 0.003f) return;
        alpha = Math.min(1, alpha);
        int i = y * w + x;
        float sr = ((rgb >> 16) & 255) / 255f, sg = ((rgb >> 8) & 255) / 255f, sb = (rgb & 255) / 255f;
        float da = a[i], na = alpha + da * (1 - alpha);
        if (na <= 0) return;
        r[i] = (sr * alpha + r[i] * da * (1 - alpha)) / na;
        g[i] = (sg * alpha + g[i] * da * (1 - alpha)) / na;
        b[i] = (sb * alpha + b[i] * da * (1 - alpha)) / na;
        a[i] = na;
    }

    /** Пиксель (ARGB, без умножения на прозрачность). */
    public int argb(int x, int y) {
        int i = y * w + x;
        int A = Math.round(Math.clamp(a[i], 0, 1) * 255);
        if (A == 0) return 0;
        return (A << 24) | (Math.round(r[i] * 255) << 16) | (Math.round(g[i] * 255) << 8) | Math.round(b[i] * 255);
    }

    /** Прозрачность пикселя 0..1 (тесты: «пусто/не пусто»). */
    public float alpha(int x, int y) { return x < 0 || y < 0 || x >= w || y >= h ? 0 : a[y * w + x]; }

    /** Пиксель в формате памяти картинки игры (NativeImage RGBA: A B G R по байтам старшего к младшему). */
    int abgr(int i) {
        float av = a[i];
        int A = av <= 0 ? 0 : av >= 1 ? 255 : (int) (av * 255 + 0.5f);
        return A == 0 ? 0 : (A << 24) | ((int) (b[i] * 255 + 0.5f) << 16) | ((int) (g[i] * 255 + 0.5f) << 8) | (int) (r[i] * 255 + 0.5f);
    }

    // ───────────────────────── линии и дуги ─────────────────────────

    public void line(float x0, float y0, float x1, float y1, float th, int rgb, float alpha, boolean shadow) {
        if (shadow) line(x0, y0 + 1, x1, y1 + 1, th + 1.8f, Hud.DARK, alpha * 0.5f, false);
        float hh = th / 2, dx = x1 - x0, dy = y1 - y0, l2 = dx * dx + dy * dy, len = (float) Math.sqrt(l2);
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, y1) - hh - 1)), maxY = Math.min(h - 1, (int) Math.ceil(Math.max(y0, y1) + hh + 1));
        int bx0 = Math.max(0, (int) Math.floor(Math.min(x0, x1) - hh - 1)), bx1 = Math.min(w - 1, (int) Math.ceil(Math.max(x0, x1) + hh + 1));
        float nx = len < 1e-6f ? 0 : -dy / len, ny = len < 1e-6f ? 0 : dx / len, reach = hh + 1.5f;
        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            int sx0 = bx0, sx1 = bx1;
            if (Math.abs(nx) > 1e-3f) {                                         // полоса |(p − p0)·n| ≤ reach в этой строке
                float c = (py - y0) * ny;
                float xa = x0 + (-reach - c) / nx, xb = x0 + (reach - c) / nx;
                sx0 = Math.max(bx0, (int) Math.floor(Math.min(xa, xb)) - 1);
                sx1 = Math.min(bx1, (int) Math.ceil(Math.max(xa, xb)) + 1);
            }
            for (int x = sx0; x <= sx1; x++) {
                float px = x + 0.5f;
                float t = l2 < 1e-6f ? 0 : Math.clamp(((px - x0) * dx + (py - y0) * dy) / l2, 0, 1);
                float ex = x0 + dx * t - px, ey = y0 + dy * t - py;
                put(x, y, rgb, alpha * Math.clamp(hh + 0.5f - (float) Math.sqrt(ex * ex + ey * ey), 0, 1));
            }
        }
    }

    /** Дуга: углы в градусах по часовой от «12 часов». */
    public void arc(float cx, float cy, float rad, float th, float a0, float a1, int rgb, float alpha, boolean shadow) {
        if (shadow) arc(cx, cy + 1, rad, th + 1.8f, a0, a1, Hud.DARK, alpha * 0.5f, false);
        float hh = th / 2, ro = rad + hh + 1;
        boolean full = a1 - a0 >= 360;
        int x0 = (int) Math.floor(cx - ro), x1 = (int) Math.ceil(cx + ro), y0 = (int) Math.floor(cy - ro), y1 = (int) Math.ceil(cy + ro);
        for (int y = Math.max(0, y0); y <= Math.min(h - 1, y1); y++) for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
            float px = x + 0.5f - cx, py = y + 0.5f - cy;
            float d = (float) Math.sqrt(px * px + py * py), cov = hh + 0.5f - Math.abs(d - rad);
            if (cov <= 0) continue;
            if (!full) {
                float ang = (float) Math.toDegrees(Math.atan2(px, -py));
                if (ang < 0) ang += 360;
                if ((((ang - a0) % 360) + 360) % 360 > a1 - a0) continue;
            }
            put(x, y, rgb, alpha * Math.min(1, cov));
        }
    }

    public void ring(float cx, float cy, float rad, float th, int rgb, float alpha) { arc(cx, cy, rad, th, 0, 360, rgb, alpha, false); }

    public void disk(float cx, float cy, float rad, int rgb, float alpha) {
        int x0 = (int) Math.floor(cx - rad - 1), x1 = (int) Math.ceil(cx + rad + 1), y0 = (int) Math.floor(cy - rad - 1), y1 = (int) Math.ceil(cy + rad + 1);
        for (int y = Math.max(0, y0); y <= Math.min(h - 1, y1); y++) for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
            float px = x + 0.5f - cx, py = y + 0.5f - cy;
            put(x, y, rgb, alpha * Math.clamp(rad + 0.5f - (float) Math.sqrt(px * px + py * py), 0, 1));
        }
    }

    // ───────────────────────── заливки ─────────────────────────

    /** Треугольник (4 пробы на пиксель). */
    public void triangle(float ax, float ay, float bx, float by, float cx, float cy, int rgb, float alpha, boolean shadow) {
        if (shadow) triangle(ax, ay + 1.5f, bx - 1, by + 1, cx + 1, cy + 1, Hud.DARK, alpha * 0.5f, false);
        int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx)))), x1 = Math.min(w - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
        int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy)))), y1 = Math.min(h - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            int in = 0;
            for (int s = 0; s < 4; s++) {
                float px = x + 0.25f + 0.5f * (s & 1), py = y + 0.25f + 0.5f * (s >> 1);
                float d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by);
                float d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy);
                float d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay);
                if (!((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0))) in++;
            }
            put(x, y, rgb, alpha * in / 4f);
        }
    }

    /** Расстояние со знаком от точки до скруглённого прямоугольника (внутри — меньше нуля). */
    static float roundRectDist(float px, float py, float x, float y, float rw, float rh, float rad) {
        float cx = x + rw / 2, cy = y + rh / 2;
        float qx = Math.abs(px - cx) - (rw / 2 - rad), qy = Math.abs(py - cy) - (rh / 2 - rad);
        float ox = Math.max(qx, 0), oy = Math.max(qy, 0);
        return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - rad;
    }

    /** Скруглённый прямоугольник: заливка. */
    public void roundRect(float x, float y, float rw, float rh, float rad, int rgb, float alpha) {
        rad = Math.min(rad, Math.min(rw, rh) / 2);
        int x0 = Math.max(0, (int) Math.floor(x - 1)), x1 = Math.min(w - 1, (int) Math.ceil(x + rw + 1));
        int y0 = Math.max(0, (int) Math.floor(y - 1)), y1 = Math.min(h - 1, (int) Math.ceil(y + rh + 1));
        for (int yy = y0; yy <= y1; yy++) for (int xx = x0; xx <= x1; xx++) {
            float d = roundRectDist(xx + 0.5f, yy + 0.5f, x, y, rw, rh, rad);
            put(xx, yy, rgb, alpha * Math.clamp(0.5f - d, 0, 1));
        }
    }

    /** Скруглённый прямоугольник: рамка толщиной th по контуру (середина линии — на контуре, отступ inset внутрь). */
    public void roundRectStroke(float x, float y, float rw, float rh, float rad, float th, int rgb, float alpha) {
        rad = Math.min(rad, Math.min(rw, rh) / 2);
        int x0 = Math.max(0, (int) Math.floor(x - th - 1)), x1 = Math.min(w - 1, (int) Math.ceil(x + rw + th + 1));
        int y0 = Math.max(0, (int) Math.floor(y - th - 1)), y1 = Math.min(h - 1, (int) Math.ceil(y + rh + th + 1));
        float hh = th / 2;
        for (int yy = y0; yy <= y1; yy++) {
            boolean edgeRow = yy <= y + th + rad + 1 || yy >= y + rh - th - rad - 1;
            for (int xx = x0; xx <= x1; xx++) {
                if (!edgeRow && xx > x + th + 1 && xx < x + rw - th - 2) { xx = (int) (x + rw - th - 2); continue; }   // середина — пусто
                float d = roundRectDist(xx + 0.5f, yy + 0.5f, x, y, rw, rh, rad);
                put(xx, yy, rgb, alpha * Math.clamp(hh + 0.5f - Math.abs(d), 0, 1));
            }
        }
    }

    /** Мягкая тень под прямоугольником: густая у края, гаснет за blur пикселей; со сдвигом вниз dy. */
    public void softShadow(float x, float y, float rw, float rh, float rad, float blur, float dy, float alpha) {
        float yy0 = y + dy;
        int x0 = Math.max(0, (int) Math.floor(x - blur - 1)), x1 = Math.min(w - 1, (int) Math.ceil(x + rw + blur + 1));
        int y0 = Math.max(0, (int) Math.floor(yy0 - blur - 1)), y1 = Math.min(h - 1, (int) Math.ceil(yy0 + rh + blur + 1));
        for (int py = y0; py <= y1; py++) for (int px = x0; px <= x1; px++) {
            float d = roundRectDist(px + 0.5f, py + 0.5f, x, yy0, rw, rh, rad);
            if (d < -1) continue;                                              // под самой панелью — её рисуют сверху
            float t = Math.clamp(1 - d / blur, 0, 1);
            put(px, py, Hud.DARK, alpha * t * t);
        }
    }

    /** Вертикальный градиент прозрачности в прямоугольнике (затемнение под приборами). */
    public void gradient(float x, float y, float rw, float rh, int rgb, float aTop, float aBottom) {
        int x0 = Math.max(0, Math.round(x)), x1 = Math.min(w, Math.round(x + rw)), y0 = Math.max(0, Math.round(y)), y1 = Math.min(h, Math.round(y + rh));
        for (int yy = y0; yy < y1; yy++) {
            float t = rh <= 1 ? 0 : (yy + 0.5f - y) / rh;
            float al = aTop + (aBottom - aTop) * t;
            for (int xx = x0; xx < x1; xx++) put(xx, yy, rgb, al);
        }
    }

    /** Многоугольник (чётно-нечётное правило), 4×4 пробы на пиксель — для силуэта корабля. */
    public void polygon(float[] xs, float[] ys, int n, int rgb, float alpha) {
        if (n < 3) return;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) { minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]); minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]); }
        int x0 = Math.max(0, (int) Math.floor(minX)), x1 = Math.min(w - 1, (int) Math.ceil(maxX));
        int y0 = Math.max(0, (int) Math.floor(minY)), y1 = Math.min(h - 1, (int) Math.ceil(maxY));
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            int in = 0;
            for (int s = 0; s < 16; s++) {
                float px = x + 0.125f + 0.25f * (s & 3), py = y + 0.125f + 0.25f * (s >> 2);
                boolean inside = false;
                for (int i = 0, j = n - 1; i < n; j = i++) {
                    if ((ys[i] > py) != (ys[j] > py) && px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]) inside = !inside;
                }
                if (inside) in++;
            }
            put(x, y, rgb, alpha * in / 16f);
        }
    }

    /** Прозрачность по маске покрытия (0..1 на пиксель того же размера) — силуэт корабля. */
    public void mask(float[] cover, int rgb, float alpha) {
        for (int i = 0; i < cover.length && i < w * h; i++) if (cover[i] > 0) put(i % w, i / w, rgb, alpha * Math.min(1, cover[i]));
    }
}
