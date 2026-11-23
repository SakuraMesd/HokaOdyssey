package ink.hoka.odyssey.client.ships;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * HUD пилота. Элементы — картинки ровно в размер на экране (в пикселях экрана, без растягивания — чётко), со
 * сглаживанием и мягкой тенью; на экран — одной командой. Компас из двух слоёв: неподвижный (подложка, кольцо,
 * метка курса, «птичка») рисуется один раз, поворотный (риски, лесенка наклона) — только когда меняются курс или
 * наклон. Внизу вместо хотбара — затемнение, компас и полоски скорости/высоты; в центре — маркер носа и ромб прицела.
 */
final class ShipHud {

    private ShipHud() {}

    private static final int GRAY = 0xD9E2EE, WHITE = 0xFFFFFF, DARK = 0x05080F, AMBER = 0xFFC46B;

    record State(float noseX, float noseY, boolean noseOn, float aimX, float aimY, boolean aimOn,
                 float camYaw, float camPitch, double fovDeg,
                 float speedFill, boolean boost, int speed, float altFill, int alt, int pitchUp) {}

    // ───────────────────────── картинки ─────────────────────────

    private static final Identifier T_BASE = id("compass_base"), T_DIAL = id("compass_dial"), T_NOSE = id("nose"), T_AIM = id("aim");
    private static DynamicTexture base, dial, nose, aim;
    private static int nCompass, nNose, nAim, nDial;
    private static float drawnHead = Float.NaN, drawnEl = Float.NaN;
    private static Canvas dialCanvas;

    private static Identifier id(String n) { return Identifier.fromNamespaceAndPath("hoka_odyssey", "ships/" + n); }

    private static DynamicTexture put(Identifier id, DynamicTexture old, Canvas c) {
        var tm = Minecraft.getInstance().getTextureManager();
        if (old != null) tm.release(id);
        DynamicTexture t = new DynamicTexture(id::toString, c.n, c.n, false);
        c.to(t);
        tm.register(id, t);
        return t;
    }

    static void release() {
        var tm = Minecraft.getInstance().getTextureManager();
        if (base != null) tm.release(T_BASE);
        if (dial != null) tm.release(T_DIAL);
        if (nose != null) tm.release(T_NOSE);
        if (aim != null) tm.release(T_AIM);
        base = dial = nose = aim = null;
        nCompass = nNose = nAim = nDial = 0;
        drawnHead = Float.NaN;
        dialCanvas = null;
    }

    // ───────────────────────── кадр ─────────────────────────

    static void draw(GuiGraphics g, State s) {
        Minecraft mc = Minecraft.getInstance();
        float gs = (float) mc.getWindow().getGuiScale();
        int w = g.guiWidth(), h = g.guiHeight();
        float cx = w / 2f, cy = h / 2f;
        float head = s.camYaw - 180, el = -s.camPitch;

        // размеры (пиксели интерфейса) → картинки в пикселях экрана
        float dComp = Math.clamp(h * 0.2f, 52, 104), dNose = 28, dAim = 12;
        int nc = even(dComp * gs), nn = even(dNose * gs), na = even(dAim * gs);
        if (nc != nCompass) {
            nCompass = nc;
            base = put(T_BASE, base, compassBase(nc));
            dialCanvas = new Canvas(nc);
            drawnHead = Float.NaN;
        }
        if (nn != nNose) { nNose = nn; nose = put(T_NOSE, nose, noseImage(nn)); }
        if (na != nAim) { nAim = na; aim = put(T_AIM, aim, aimImage(na)); }
        if (Float.isNaN(drawnHead) || Math.abs(wrap(head - drawnHead)) > 0.25f || Math.abs(el - drawnEl) > 0.25f) {
            compassDial(dialCanvas, head, el);
            if (dial == null || nDial != nc) { dial = put(T_DIAL, dial, dialCanvas); nDial = nc; }
            else dialCanvas.to(dial);
            drawnHead = head;
            drawnEl = el;
        }

        // затемнение внизу — под приборами
        int barW = Math.round(Math.clamp(w * 0.26f, 90, 220)), gap = 26, barH = 5;
        float barY = h - 9 - barH;
        float ccy = barY - 13 - dComp / 2;
        int fadeTop = Math.round(ccy - dComp / 2 - 14);
        g.fillGradient(0, fadeTop, w, h, argb(DARK, 0f), argb(DARK, 0.55f));

        // прицел в центре
        float nx = s.noseOn ? s.noseX : cx, ny = s.noseOn ? s.noseY : cy, ax = s.aimX, ay = s.aimY;
        if (s.aimOn) {
            float maxR = h * 0.3f, dx = ax - cx, dy = ay - cy, L = (float) Math.hypot(dx, dy);
            if (L > maxR) { ax = cx + dx * maxR / L; ay = cy + dy * maxR / L; }
            float dist = (float) Math.hypot(ax - nx, ay - ny), fade = smooth(8, 20, dist);
            if (fade > 0.02f) {
                int n = Math.max(2, (int) (dist / 6));
                for (int i = 1; i < n; i++) {
                    float t = i / (float) n, px = ax + (nx - ax) * t, py = ay + (ny - ay) * t;
                    if (Math.hypot(px - nx, py - ny) < 14 || Math.hypot(px - ax, py - ay) < 7) continue;
                    blitDot(g, px, py, gs, argb(GRAY, 0.75f * fade));
                }
            }
        }
        if (s.noseOn) blitPx(g, T_NOSE, nx, ny, nn, gs);
        if (s.aimOn) blitPx(g, T_AIM, ax, ay, na, gs);

        // компас и полоски
        blitPx(g, T_BASE, cx, ccy, nc, gs);
        blitPx(g, T_DIAL, cx, ccy, nc, gs);
        bar(g, cx - gap - barW, barY, barW, barH, s.speedFill, true, s.boost && s.speedFill > 0.715f);
        bar(g, cx + gap, barY, barW, barH, s.altFill, false, false);

        // надписи — масштаб под целые пиксели экрана
        float small = crisp(0.75f, gs), big = crisp(1f, gs), tiny = crisp(0.6f, gs);
        float rr = dComp * 0.39f;                                           // радиус кольца в пикселях интерфейса
        String[] l = {"С", "В", "Ю", "З"};
        for (int b = 0; b < 4; b++) {
            double a = Math.toRadians(90 * b - head);
            float r = rr + 8;
            text(g, mc.font, l[b], cx + (float) Math.sin(a) * r, ccy - (float) Math.cos(a) * r - 4 * small, small,
                    b == 0 ? argb(WHITE, 0.95f) : argb(GRAY, 0.75f), null);
        }
        text(g, mc.font, (s.pitchUp > 0 ? "+" : "") + s.pitchUp + "°", cx, ccy + rr * 0.42f, small, argb(WHITE, 0.9f), null);
        float ty = barY - 11;
        text(g, mc.font, "СКОРОСТЬ", cx - gap - barW, ty + 2, tiny, argb(GRAY, 0.6f), false);
        text(g, mc.font, s.speed + (s.boost ? "  ФОРСАЖ" : ""), cx - gap, ty, big, s.boost ? argb(AMBER, 0.95f) : argb(WHITE, 0.92f), true);
        text(g, mc.font, "ВЫСОТА", cx + gap + barW, ty + 2, tiny, argb(GRAY, 0.6f), true);
        text(g, mc.font, String.valueOf(s.alt), cx + gap, ty, big, argb(WHITE, 0.92f), false);
    }

    private static int even(float v) { return Math.max(8, Math.round(v / 2) * 2); }

    /** Картинка n×n пикселей экрана по центру (x, y — пиксели интерфейса), один к одному. */
    private static void blitPx(GuiGraphics g, Identifier id, float x, float y, int n, float gs) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.scale(1 / gs, 1 / gs);
        pose.translate(Math.round(x * gs - n / 2f), Math.round(y * gs - n / 2f));
        g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0, 0, n, n, n, n);
        pose.popMatrix();
    }

    /** Точка пунктира — 2×2 пикселя экрана. */
    private static void blitDot(GuiGraphics g, float x, float y, float gs, int argb) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.scale(1 / gs, 1 / gs);
        int px = Math.round(x * gs), py = Math.round(y * gs);
        g.fill(px - 1, py - 1, px + 1, py + 1, argb);
        pose.popMatrix();
    }

    // ───────────────────────── рисунки (пиксели экрана) ─────────────────────────

    /** Неподвижная часть компаса: подложка, кольца, треугольник курса, «птичка». */
    private static Canvas compassBase(int n) {
        Canvas c = new Canvas(n);
        float x = n / 2f, y = n / 2f, r = n * 0.39f, t = Math.max(1.1f, n / 190f);
        c.disk(x, y, r + t, DARK, 0.45f);
        c.ring(x, y, r * 0.86f, r * 0.3f, DARK, 0.25f);                         // к краю — гуще
        c.ring(x, y, r, t * 1.2f, GRAY, 0.8f);
        c.ring(x, y, r + 4 * t, t * 0.8f, GRAY, 0.2f);
        float tri = Math.max(4, n * 0.035f);
        c.triangle(x, y - r - 2 * t, x - tri, y - r - 2 * t - tri * 1.6f, x + tri, y - r - 2 * t - tri * 1.6f, WHITE, 0.95f, true);
        float bw = r * 0.22f;
        c.line(x - bw, y, x - bw * 0.35f, y, t * 1.4f, WHITE, 0.95f, true);
        c.line(x + bw * 0.35f, y, x + bw, y, t * 1.4f, WHITE, 0.95f, true);
        c.disk(x, y, t * 1.3f, WHITE, 0.95f);
        return c;
    }

    /** Поворотная часть компаса: риски по курсу и лесенка наклона (только короткие отрезки — быстро). */
    private static void compassDial(Canvas c, float head, float el) {
        c.clear();
        int n = c.n;
        float x = n / 2f, y = n / 2f, r = n * 0.39f, t = Math.max(1.0f, n / 220f);
        for (int deg = 0; deg < 360; deg += 5) {
            boolean d90 = deg % 90 == 0, d30 = deg % 30 == 0, d10 = deg % 10 == 0;
            float len = (d90 ? 0.17f : d30 ? 0.11f : d10 ? 0.065f : 0.035f) * r;
            double a = Math.toRadians(deg - head);
            float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
            float r0 = r - 1.5f * t;
            c.line(x + sx * r0, y + sy * r0, x + sx * (r0 - len), y + sy * (r0 - len), (d90 ? 1.5f : 1f) * t, d90 ? WHITE : GRAY,
                    d90 ? 0.95f : d30 ? 0.7f : d10 ? 0.45f : 0.25f, false);
        }
        float per = r * 0.8f / 45f;
        for (int i = -9; i <= 9; i++) {
            float ly = y + (el - 10 * i) * per;
            if (Math.abs(ly - y) > r * 0.82f) continue;
            float half = (i == 0 ? 0.62f : Math.abs(i) % 3 == 0 ? 0.38f : 0.22f) * r;
            half = Math.min(half, (float) Math.sqrt(Math.max(0, (r - 3 * t) * (r - 3 * t) - (ly - y) * (ly - y))));
            float in = 0.3f * r;
            if (half <= in) continue;
            float a = i == 0 ? 0.85f : 0.45f, th = (i == 0 ? 1.3f : 1f) * t;
            if (i < 0) {
                float dash = 3.5f * t;
                for (float sx = in; sx < half; sx += dash * 2) {
                    float e = Math.min(half, sx + dash);
                    c.line(x + sx, ly, x + e, ly, th, GRAY, a, false);
                    c.line(x - e, ly, x - sx, ly, th, GRAY, a, false);
                }
            } else {
                c.line(x + in, ly, x + half, ly, th, GRAY, a, false);
                c.line(x - half, ly, x - in, ly, th, GRAY, a, false);
                if (i != 0) {                                                // «усики» к горизонту
                    c.line(x + half, ly, x + half, ly + 2.5f * t, th, GRAY, a, false);
                    c.line(x - half, ly, x - half, ly + 2.5f * t, th, GRAY, a, false);
                }
            }
        }
    }

    /** Маркер носа: кольцо из 4 дуг с засечками; кружок, «крылья», киль. */
    private static Canvas noseImage(int n) {
        Canvas c = new Canvas(n);
        float x = n / 2f, y = n / 2f, R = n * 0.42f, t = Math.max(1.1f, n / 60f);
        for (int q = 0; q < 4; q++) c.arc(x, y, R, t, 90 * q + 16, 90 * q + 74, GRAY, 0.6f, true);
        for (int q = 0; q < 4; q++) {
            double a = Math.toRadians(90 * q);
            float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
            c.line(x + sx * R, y + sy * R, x + sx * (R - n * 0.09f), y + sy * (R - n * 0.09f), t, GRAY, 0.75f, true);
        }
        float k = n / 64f;
        c.ring(x, y, 5.5f * k, t * 1.3f, WHITE, 0.95f);
        c.line(x - 8 * k, y, x - 18 * k, y, t * 1.3f, WHITE, 0.95f, true);
        c.line(x + 8 * k, y, x + 18 * k, y, t * 1.3f, WHITE, 0.95f, true);
        c.line(x, y - 8 * k, x, y - 14 * k, t * 1.3f, WHITE, 0.95f, true);
        return c;
    }

    /** Прицел мыши: ромб и точка. */
    private static Canvas aimImage(int n) {
        Canvas c = new Canvas(n);
        float x = n / 2f, y = n / 2f, r = n * 0.36f, t = Math.max(1.2f, n / 22f);
        c.line(x, y - r, x + r, y, t, WHITE, 0.95f, true);
        c.line(x + r, y, x, y + r, t, WHITE, 0.95f, true);
        c.line(x, y + r, x - r, y, t, WHITE, 0.95f, true);
        c.line(x - r, y, x, y - r, t, WHITE, 0.95f, true);
        c.disk(x, y, t * 0.9f, WHITE, 0.95f);
        return c;
    }

    // ───────────────────────── полоски и текст ─────────────────────────

    /** Полоска: тень, дорожка, заливка (toCenter — от правого края), риски через 10%. */
    private static void bar(GuiGraphics g, float x, float y, int w, int h, float value, boolean toCenter, boolean boost) {
        int x0 = Math.round(x), y0 = Math.round(y), x1 = x0 + w, y1 = y0 + h;
        g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, argb(DARK, 0.5f));
        g.fill(x0, y0, x1, y1, argb(GRAY, 0.14f));
        int len = Math.round(w * Math.clamp(value, 0, 1));
        if (len > 0) {
            int a = toCenter ? x1 - len : x0, b = toCenter ? x1 : x0 + len;
            g.fillGradient(a, y0, b, y1, boost ? argb(0xFFE2B0, 0.95f) : argb(WHITE, 0.95f), boost ? argb(AMBER, 0.85f) : argb(GRAY, 0.7f));
            int tip = toCenter ? a : b - 1;                                  // яркий кончик
            g.fill(tip, y0 - 1, tip + 1, y1 + 1, argb(WHITE, 0.95f));
        }
        for (int i = 1; i < 10; i++) {
            int tx = x0 + Math.round(w * i / 10f);
            g.fill(tx, y1 + 2, tx + 1, y1 + (i == 5 ? 5 : 4), argb(GRAY, i == 5 ? 0.55f : 0.3f));
        }
    }

    private static float crisp(float want, float gs) {
        return Math.max(1, Math.round(want * gs)) / gs;
    }

    private static void text(GuiGraphics g, Font font, String t, float x, float y, float k, int argb, Boolean right) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(k, k);
        int tw = font.width(t);
        g.drawString(font, t, right == null ? -tw / 2 : right ? -tw : 0, 0, argb, true);
        pose.popMatrix();
    }

    // ───────────────────────── холст со сглаживанием ─────────────────────────

    /** Холст n×n: цвет и прозрачность пикселей, рисование «поверх» с покрытием (сглаживание). Обходит только рамку фигуры. */
    private static final class Canvas {
        final int n;
        final float[] r, gr, b, a;

        Canvas(int n) {
            this.n = n;
            r = new float[n * n]; gr = new float[n * n]; b = new float[n * n]; a = new float[n * n];
        }

        void clear() { java.util.Arrays.fill(a, 0); }

        void put(int x, int y, int rgb, float alpha) {
            if (x < 0 || y < 0 || x >= n || y >= n || alpha <= 0.003f) return;
            alpha = Math.min(1, alpha);
            int i = y * n + x;
            float sr = ((rgb >> 16) & 255) / 255f, sg = ((rgb >> 8) & 255) / 255f, sb = (rgb & 255) / 255f;
            float da = a[i], na = alpha + da * (1 - alpha);
            if (na <= 0) return;
            r[i] = (sr * alpha + r[i] * da * (1 - alpha)) / na;
            gr[i] = (sg * alpha + gr[i] * da * (1 - alpha)) / na;
            b[i] = (sb * alpha + b[i] * da * (1 - alpha)) / na;
            a[i] = na;
        }

        void line(float x0, float y0, float x1, float y1, float th, int rgb, float alpha, boolean shadow) {
            if (shadow) line(x0, y0 + 1, x1, y1 + 1, th + 1.8f, DARK, alpha * 0.5f, false);
            float h = th / 2, dx = x1 - x0, dy = y1 - y0, l2 = dx * dx + dy * dy;
            int minX = (int) Math.floor(Math.min(x0, x1) - h - 1), maxX = (int) Math.ceil(Math.max(x0, x1) + h + 1);
            int minY = (int) Math.floor(Math.min(y0, y1) - h - 1), maxY = (int) Math.ceil(Math.max(y0, y1) + h + 1);
            for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f, py = y + 0.5f;
                float t = l2 < 1e-6f ? 0 : Math.clamp(((px - x0) * dx + (py - y0) * dy) / l2, 0, 1);
                float ex = x0 + dx * t - px, ey = y0 + dy * t - py;
                put(x, y, rgb, alpha * Math.clamp(h + 0.5f - (float) Math.sqrt(ex * ex + ey * ey), 0, 1));
            }
        }

        void arc(float cx, float cy, float rad, float th, float a0, float a1, int rgb, float alpha, boolean shadow) {
            if (shadow) arc(cx, cy + 1, rad, th + 1.8f, a0, a1, DARK, alpha * 0.5f, false);
            float h = th / 2, ro = rad + h + 1;
            boolean full = a1 - a0 >= 360;
            int x0 = (int) Math.floor(cx - ro), x1 = (int) Math.ceil(cx + ro), y0 = (int) Math.floor(cy - ro), y1 = (int) Math.ceil(cy + ro);
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                float px = x + 0.5f - cx, py = y + 0.5f - cy;
                float d = (float) Math.sqrt(px * px + py * py), cov = h + 0.5f - Math.abs(d - rad);
                if (cov <= 0) continue;
                if (!full) {
                    float ang = (float) Math.toDegrees(Math.atan2(px, -py));
                    if (ang < 0) ang += 360;
                    if ((((ang - a0) % 360) + 360) % 360 > a1 - a0) continue;
                }
                put(x, y, rgb, alpha * Math.min(1, cov));
            }
        }

        void ring(float cx, float cy, float rad, float th, int rgb, float alpha) { arc(cx, cy, rad, th, 0, 360, rgb, alpha, false); }

        void disk(float cx, float cy, float rad, int rgb, float alpha) {
            int x0 = (int) Math.floor(cx - rad - 1), x1 = (int) Math.ceil(cx + rad + 1), y0 = (int) Math.floor(cy - rad - 1), y1 = (int) Math.ceil(cy + rad + 1);
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                float px = x + 0.5f - cx, py = y + 0.5f - cy;
                put(x, y, rgb, alpha * Math.clamp(rad + 0.5f - (float) Math.sqrt(px * px + py * py), 0, 1));
            }
        }

        void triangle(float ax, float ay, float bx, float by, float cx, float cy, int rgb, float alpha, boolean shadow) {
            if (shadow) triangle(ax, ay + 1.5f, bx - 1, by + 1, cx + 1, cy + 1, DARK, alpha * 0.5f, false);
            int x0 = (int) Math.floor(Math.min(ax, Math.min(bx, cx))), x1 = (int) Math.ceil(Math.max(ax, Math.max(bx, cx)));
            int y0 = (int) Math.floor(Math.min(ay, Math.min(by, cy))), y1 = (int) Math.ceil(Math.max(ay, Math.max(by, cy)));
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

        void to(DynamicTexture t) {
            var img = t.getPixels();
            if (img == null) return;
            for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
                int i = y * n + x;
                int A = Math.round(Math.clamp(a[i], 0, 1) * 255);
                img.setPixel(x, y, A == 0 ? 0 : (A << 24) | (Math.round(r[i] * 255) << 16) | (Math.round(gr[i] * 255) << 8) | Math.round(b[i] * 255));
            }
            t.upload();
        }
    }

    // ───────────────────────── мелочи ─────────────────────────

    private static float wrap(float d) {
        d %= 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static int argb(int rgb, float a) {
        return (Math.round(Math.clamp(a, 0, 1) * 255) << 24) | (rgb & 0xFFFFFF);
    }
}
