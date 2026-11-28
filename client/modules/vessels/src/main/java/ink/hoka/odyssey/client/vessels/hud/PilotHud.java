package ink.hoka.odyssey.client.vessels.hud;

import java.util.HashMap;
import java.util.Map;

import static ink.hoka.odyssey.client.vessels.hud.Hud.AMBER;
import static ink.hoka.odyssey.client.vessels.hud.Hud.DARK;
import static ink.hoka.odyssey.client.vessels.hud.Hud.GRAY;
import static ink.hoka.odyssey.client.vessels.hud.Hud.RED;
import static ink.hoka.odyssey.client.vessels.hud.Hud.WHITE;
import static ink.hoka.odyssey.client.vessels.hud.Hud.argb;

/**
 * HUD пилота по мотивам авиа-HUD War Thunder (аркада): тонкие линии и цифры прямо поверх мира, плашки — только в
 * нижних углах. Сверху — лента компаса с курсом; слева — лента скорости и тяга (ФОРСАЖ — янтарным); справа — лента
 * высоты и вертикальная скорость; у центра — лесенка тангажа, прицел орудия (кружок с точкой, дуга перезарядки),
 * маркер носа, маркер попадания; слева внизу — силуэт корабля сверху и топливо (с ценой варпа); справа внизу — орудие
 * и варп; при зарядке варпа — крупная шкала снизу по центру; внизу — подсказки клавиш (гаснут).
 * <p>
 * Техника — как у старого HUD кораблей (ships/ShipHud): всё рисуется холстами в пикселях экрана (сглаживание, мягкая
 * тень, чётко на любом масштабе GUI); статичные слои — один раз (и при смене размера), меняющиеся (ленты, лесенка,
 * дуга перезарядки) — только когда поменялось то, что они показывают, и не чаще 60 раз в секунду. Текст — шрифтом игры.
 */
public final class PilotHud {

    /** Обзор ленты компаса (°), полувысоты лент: скорость (бл/с), высота (блоки). */
    static final float SPAN = 90;
    static final double SPEED_HALF = 30, ALT_HALF = 50;

    private final Map<String, Canvas> stat = new HashMap<>();
    private final Map<String, String> statSpec = new HashMap<>();
    private final Map<String, Dyn> dyn = new HashMap<>();
    private HudLayout layout;
    private float layoutW, layoutH, layoutGs;
    /** Перерисовывать меняющиеся слои при любом изменении, без предела 60 раз/с (превью). */
    public boolean eager;

    private static final class Dyn {
        Canvas c;
        long at = Long.MIN_VALUE;
        String spec;
        double[] last;
    }

    /** Раскладка последнего кадра (тесты). */
    public HudLayout layout() { return layout; }

    public void draw(Gfx g, HudView v, long nowMs) {
        float gs = g.scale();
        int W = g.width(), H = g.height();
        float small = Hud.crisp(0.75f, gs);
        if (layout == null || layoutW != W || layoutH != H || layoutGs != gs) {
            layout = new HudLayout(W, H, HudLayout.hintScale(gs), g::textWidth);
            layoutW = W; layoutH = H; layoutGs = gs;
        }
        HudLayout L = layout;

        if (v.hurt() > 0.01f) hurtEdge(g, W, H, Math.min(1, v.hurt()));
        center(g, v, L, gs, nowMs);
        compass(g, v, L, gs, small, nowMs);
        if (v.msg() != null && v.msgAlpha() > 0.02f) message(g, v, L);
        if (L.speed != null) { speedTape(g, v, L, gs, small, nowMs); altTape(g, v, L, gs, small, nowMs); }
        if (L.left != null) leftPanel(g, v, L.left, L, gs, small);
        if (L.right != null) rightPanel(g, v, L.right, gs, small);
        if (v.warp() == HudView.WARP_CHARGING && L.warp != null) warpBlock(g, v, L.warp, gs, small);
        if (v.hintsAlpha() > 0.02f && L.hints != null) hints(g, v, L, L.k);
    }

    // ───────────────────────── холсты ─────────────────────────

    /** Статичный холст: рисуется один раз (и заново, если поменялся spec — размер, масштаб, вид корабля). */
    private Canvas stat(String key, String spec, int w, int h, java.util.function.Consumer<Canvas> paint) {
        Canvas c = stat.get(key);
        if (c == null || !spec.equals(statSpec.get(key)) || c.w != w || c.h != h) {
            c = new Canvas(w, h);
            paint.accept(c);
            c.touch();
            stat.put(key, c);
            statSpec.put(key, spec);
        }
        return c;
    }

    /**
     * Меняющийся холст: перерисовать, если поменялся размер или показания (больше eps по любому числу), но не чаще
     * 60 раз в секунду.
     */
    private Canvas dyn(String key, int w, int h, long now, double eps, double[] vals, java.util.function.Consumer<Canvas> paint) {
        Dyn d = dyn.computeIfAbsent(key, k -> new Dyn());
        String spec = w + "x" + h;
        boolean fresh = d.c == null || !spec.equals(d.spec);
        boolean changed = d.last == null || d.last.length != vals.length;
        if (!changed) for (int i = 0; i < vals.length; i++) if (Math.abs(vals[i] - d.last[i]) > eps || Double.isNaN(vals[i]) != Double.isNaN(d.last[i])) { changed = true; break; }
        if (fresh) { d.c = new Canvas(w, h); d.spec = spec; }
        if (fresh || (changed && (eager || now - d.at >= 16))) {
            d.c.clear();
            paint.accept(d.c);
            d.c.touch();
            d.at = now;
            d.last = vals.clone();
        }
        return d.c;
    }

    // ───────────────────────── центр: прицел, нос, лесенка, попадание ─────────────────────────

    private void center(Gfx g, HudView v, HudLayout L, float gs, long now) {
        float cx = L.cx, cy = L.cy;
        if (L.ladderR > 0 && v.rungs() != null && v.rungs().length >= 5) ladder(g, v, L, gs, now);

        float nx = v.noseOn() ? v.noseX() : cx, ny = v.noseOn() ? v.noseY() : cy, ax = v.aimX(), ay = v.aimY();
        if (v.aimOn()) {                                                        // нос и прицел разошлись — пунктир между ними
            float dist = (float) Math.hypot(ax - nx, ay - ny), fade = Hud.smooth(10, 22, dist);
            if (fade > 0.02f && v.noseOn()) {
                int n = Math.max(2, (int) (dist / 5));
                for (int i = 1; i < n; i++) {
                    float t = i / (float) n, px = ax + (nx - ax) * t, py = ay + (ny - ay) * t;
                    if (Math.hypot(px - nx, py - ny) < 15 || Math.hypot(px - ax, py - ay) < 11) continue;
                    float d = 1 / gs;
                    g.fill(px - d, py - d, px + d, py + d, argb(GRAY, 0.7f * fade));
                }
            }
        }
        if (v.noseOn()) {
            int n = Hud.px(34, gs);
            g.image("nose", stat("nose", "" + n, n, n, c -> noseImage(c, gs)), nx - n / gs / 2, ny - n / gs / 2, 1);
        }
        if (v.aimOn()) {
            int n = Hud.px(22, gs);
            g.image("aim", stat("aim", "" + n, n, n, c -> aimImage(c, gs)), ax - n / gs / 2, ay - n / gs / 2, 1);
            if (v.gunReady() < 1) {                                             // перезарядка — дуга вокруг прицела
                float k = Math.clamp(v.gunReady(), 0, 1);
                Canvas arc = dyn("reload", n, n, now, 1 / 96.0, new double[]{k}, c -> {
                    float r = 8.2f * gs, t = Math.max(1.2f, 1.3f * gs);
                    c.ring(n / 2f, n / 2f, r, t, GRAY, 0.22f);
                    c.arc(n / 2f, n / 2f, r, t, 0, 360 * k, AMBER, 0.95f, true);
                });
                g.image("reload", arc, ax - n / gs / 2, ay - n / gs / 2, 1);
            }
            if (v.hit() > 0.01f) {
                int m = Hud.px(34, gs);
                int kind = Math.clamp(v.hitKind(), 1, 4);
                String key = "hit" + kind;
                g.image(key, stat(key, "" + m, m, m, c -> hitImage(c, gs, kind)), ax - m / gs / 2, ay - m / gs / 2, Math.min(1, v.hit()));
            }
        }
    }

    private void ladder(Gfx g, HudView v, HudLayout L, float gs, long now) {
        float R = L.ladderR, cx = L.cx, cy = L.cy;
        int n = Hud.px(2 * R, gs);
        float[] r = v.rungs();
        double[] vals = new double[r.length];
        for (int i = 0; i < r.length; i++) vals[i] = i % 5 >= 3 ? r[i] * 100 : r[i];
        Canvas c = dyn("ladder", n, n, now, 0.2, vals, cv -> paintLadder(cv, r, R, cx, cy, gs));
        g.image("ladder", c, cx - n / gs / 2, cy - n / gs / 2, 1);
        float small = Hud.crisp(0.75f, gs);                                     // цифры углов — у правого конца ступени
        for (int i = 0; i + 4 < r.length; i += 5) {
            int ang = Math.round(r[i]);
            if (ang == 0) continue;
            float half = rungHalf(ang, R);
            float ex = r[i + 1] + r[i + 3] * (half + 7), ey = r[i + 2] + r[i + 4] * (half + 7);
            float d = (float) Math.hypot(ex - cx, ey - cy);
            float a = 1 - Hud.smooth(R * 0.72f, R * 0.92f, d);
            if (a > 0.05f) g.text(Integer.toString(Math.abs(ang)), ex, ey - 4 * small, small, argb(GRAY, 0.8f * a), 0, true);
        }
    }

    static float rungHalf(int ang, float R) { return ang == 0 ? 0.88f * R : ang % 30 == 0 ? 0.5f * R : 0.38f * R; }

    private static void paintLadder(Canvas c, float[] r, float R, float cx, float cy, float gs) {
        float o = c.w / 2f, lim = R * 0.95f, gap = 0.3f * R, t = Math.max(1f, gs * 0.9f);
        for (int i = 0; i + 4 < r.length; i += 5) {
            int ang = Math.round(r[i]);
            float px = r[i + 1] - cx, py = r[i + 2] - cy, dx = r[i + 3], dy = r[i + 4];
            float dc = (float) Math.hypot(px, py);
            if (dc > lim) continue;
            float half = rungHalf(ang, R), a = (ang == 0 ? 0.85f : 0.55f) * (1 - Hud.smooth(R * 0.75f, lim, dc));
            float th = (ang == 0 ? 1.4f : 1f) * t;
            // отрезок p + s·d в круге радиуса lim: s в [s0, s1]
            float b = px * dx + py * dy, disc = b * b - (dc * dc - lim * lim);
            if (disc <= 0) continue;
            float sq = (float) Math.sqrt(disc), s0 = -b - sq, s1 = -b + sq;
            float nx = -dy, ny = dx;                                            // к горизонту: у «+» вниз по ступени, у «−» вверх
            float tip = (ang > 0 ? 1 : -1) * 3.2f;
            for (int side = -1; side <= 1; side += 2) {
                float a0 = side < 0 ? -half : gap, a1 = side < 0 ? -gap : half;
                float lo = Math.max(a0, s0), hi = Math.min(a1, s1);
                if (hi <= lo) continue;
                if (ang < 0) {                                                  // ниже горизонта — пунктир
                    float dash = 3.5f;
                    for (float s = lo; s < hi; s += dash * 2) seg(c, o, gs, px, py, dx, dy, s, Math.min(hi, s + dash), th, a);
                } else seg(c, o, gs, px, py, dx, dy, lo, hi, th, a);
                float end = side < 0 ? a0 : a1;
                if (ang != 0 && end >= s0 && end <= s1) {
                    float ex = px + dx * end, ey = py + dy * end;
                    c.line(o + ex * gs, o + ey * gs, o + (ex + nx * tip) * gs, o + (ey + ny * tip) * gs, th, GRAY, a, true);
                }
            }
        }
    }

    private static void seg(Canvas c, float o, float gs, float px, float py, float dx, float dy, float s0, float s1, float th, float a) {
        c.line(o + (px + dx * s0) * gs, o + (py + dy * s0) * gs, o + (px + dx * s1) * gs, o + (py + dy * s1) * gs, th, GRAY, a, true);
    }

    /** Маркер носа («птичка»): кружок, крылья, киль. */
    static void noseImage(Canvas c, float gs) {
        float x = c.w / 2f, y = c.h / 2f, t = Math.max(1.1f, 1.2f * gs);
        c.ring(x, y, 3.2f * gs, t, WHITE, 0.95f);
        c.arc(x, y + 1, 3.2f * gs, t + 1.8f, 0, 360, DARK, 0.35f, false);
        c.line(x - 5.5f * gs, y, x - 12f * gs, y, t, WHITE, 0.95f, true);
        c.line(x + 5.5f * gs, y, x + 12f * gs, y, t, WHITE, 0.95f, true);
        c.line(x - 12f * gs, y, x - 13.5f * gs, y + 2.5f * gs, t, WHITE, 0.95f, true);
        c.line(x + 12f * gs, y, x + 13.5f * gs, y + 2.5f * gs, t, WHITE, 0.95f, true);
        c.line(x, y - 5.5f * gs, x, y - 9f * gs, t, WHITE, 0.95f, true);
    }

    /** Прицел орудия: тонкий круг, точка, четыре риски наружу. */
    static void aimImage(Canvas c, float gs) {
        float x = c.w / 2f, y = c.h / 2f, t = Math.max(1f, 1.0f * gs);
        c.arc(x, y + 1, 5.2f * gs, t + 1.8f, 0, 360, DARK, 0.4f, false);
        c.ring(x, y, 5.2f * gs, t, WHITE, 0.95f);
        c.disk(x, y + 0.6f, 1.2f * gs, DARK, 0.4f);
        c.disk(x, y, 0.9f * gs, WHITE, 1f);
        for (int q = 0; q < 4; q++) {
            double a = Math.toRadians(90 * q);
            float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
            c.line(x + sx * 6.6f * gs, y + sy * 6.6f * gs, x + sx * 9.6f * gs, y + sy * 9.6f * gs, t, WHITE, 0.9f, true);
        }
    }

    /**
     * Маркер попадания: четыре косые черты вокруг прицела. kind: 1 — по цели (белые), 2 — убийство (красные,
     * длиннее), 3 — по кораблю (янтарные, с уголками), 4 — корабль разрушен (красные, длиннее, с уголками).
     */
    static void hitImage(Canvas c, float gs, int kind) {
        boolean big = kind == 2 || kind == 4, ship = kind >= 3;
        int col = big ? RED : ship ? AMBER : WHITE;
        float x = c.w / 2f, y = c.h / 2f, t = Math.max(1.3f, (big ? 1.8f : 1.4f) * gs);
        float r0 = 7.5f * gs, r1 = (big ? 15f : 12.5f) * gs;
        for (int q = 0; q < 4; q++) {
            double a = Math.toRadians(45 + 90 * q);
            float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
            c.line(x + sx * r0, y + sy * r0, x + sx * r1, y + sy * r1, t, col, 1f, true);
            if (ship) {                                                          // по кораблю — уголок на конце черты
                float ex = x + sx * r1, ey = y + sy * r1, k = 3f * gs;
                c.line(ex, ey, ex - sx * k + sy * k, ey - sy * k - sx * k, t, col, 1f, true);
                c.line(ex, ey, ex - sx * k - sy * k, ey - sy * k + sx * k, t, col, 1f, true);
            }
        }
    }

    /** Красный край экрана — по кораблю попали (гаснет): узкая полоса по краям, к середине — на нет. */
    static void hurtEdge(Gfx g, float w, float h, float a) {
        float band = Math.max(6, Math.min(w, h) * 0.07f);
        int steps = Math.max(4, Math.round(band));                              // кольца по пикселю интерфейса
        float st = band / steps;
        for (int i = 0; i < steps; i++) {
            float k = 1 - i / (float) steps;
            float al = 0.5f * a * k * k * k;
            if (al < 0.006f) continue;
            int col = argb(RED, al);
            float m = st * i;
            g.fill(m, m, w - m, m + st, col);
            g.fill(m, h - m - st, w - m, h - m, col);
            g.fill(m, m + st, m + st, h - m - st, col);
            g.fill(w - m - st, m + st, w - m, h - m - st, col);
        }
    }

    // ───────────────────────── компас ─────────────────────────

    private void compass(Gfx g, HudView v, HudLayout L, float gs, float small, long now) {
        HudLayout.Box b = L.compass;
        int w = Hud.px(b.w(), gs), h = Hud.px(b.h(), gs);
        float base = 18;
        Canvas frame = stat("compass", w + "x" + h, w, h, c -> {
            float cw = c.w, mid = cw / 2f;
            for (int x = 0; x < c.w; x++) {                                    // лёгкое затемнение под лентой, к краям — на нет
                float e = Math.min(x, c.w - 1 - x) / (cw * 0.18f);
                float a = 0.22f * Math.min(1, e);
                for (int y = 0; y < Math.round((base + 1) * gs); y++) c.put(x, y, DARK, a * Math.min(1, (y + 1) / (4 * gs)));
            }
            for (int x = 0; x < c.w; x++) {                                    // нижняя линия ленты
                float e = Math.min(1, Math.min(x, c.w - 1 - x) / (cw * 0.15f));
                c.put(x, Math.round(base * gs), GRAY, 0.55f * e);
                c.put(x, Math.round(base * gs) + 1, DARK, 0.3f * e);
            }
            float ty = (base + 1.2f) * gs, tw = 3.2f * gs;
            c.triangle(mid, ty, mid - tw, ty + tw * 1.3f, mid + tw, ty + tw * 1.3f, WHITE, 0.95f, true);
            float bw = 15 * gs, by = (base + 5.5f) * gs, bh = 9.5f * gs;
            c.roundRect(mid - bw, by, bw * 2, bh, 2 * gs, DARK, 0.55f);
            c.roundRectStroke(mid - bw, by, bw * 2, bh, 2 * gs, Math.max(1, 0.9f * gs), GRAY, 0.7f);
        });
        float ppd = b.w() / SPAN;
        Canvas ticks = dyn("compassTicks", w, h, now, 0.1, new double[]{v.heading()}, c -> {
            for (Compass.Mark m : Compass.marks(v.heading(), SPAN)) {
                float x = (b.w() / 2 + m.off() * ppd) * gs;
                float fade = 1 - Hud.smooth(0.62f, 1f, Math.abs(m.off()) / (SPAN / 2));
                float len = m.size() == 2 ? 6.5f : m.size() == 1 ? 4.5f : 2.5f;
                int col = m.size() == 2 && m.deg() % 90 == 0 ? WHITE : GRAY;
                c.line(x, base * gs, x, (base - len) * gs, Math.max(1f, (m.size() == 2 ? 1.2f : 0.9f) * gs), col,
                        (m.size() == 2 ? 0.95f : m.size() == 1 ? 0.7f : 0.45f) * fade, true);
            }
        });
        g.image("compassTicks", ticks, b.x(), b.y(), 1);
        g.image("compass", frame, b.x(), b.y(), 1);
        for (Compass.Mark m : Compass.marks(v.heading(), SPAN)) {
            if (m.label() == null) continue;
            float fade = 1 - Hud.smooth(0.6f, 0.95f, Math.abs(m.off()) / (SPAN / 2));
            if (fade < 0.05f) continue;
            boolean card = m.deg() % 90 == 0;
            float k = card ? 1 : small;
            g.text(m.label(), b.x() + b.w() / 2 + m.off() * ppd, b.y() + (card ? 2 : 3), k, argb(card ? WHITE : GRAY, (card ? 0.95f : 0.75f) * fade), 0, true);
        }
        g.text(Compass.readout(v.heading()), b.x() + b.w() / 2, b.y() + base + 7, 1, argb(WHITE, 0.95f), 0, true);
        if (v.pvp()) pvpTag(g, b.x() + b.w() / 2 + 19, b.y() + base + 5.5f, gs);
    }

    /** Метка «PvP-ЗОНА» справа от числа курса: янтарная рамка и текст (здесь корабли бьют друг друга). */
    static void pvpTag(Gfx g, float x, float y, float gs) {
        String t = "PvP-ЗОНА";
        float tw = g.textWidth(t), w = tw + 6, h = 9.5f, px = 1 / gs;
        g.fill(x, y, x + w, y + h, argb(DARK, 0.6f));
        int c = argb(AMBER, 0.9f);
        g.fill(x, y, x + w, y + px, c);
        g.fill(x, y + h - px, x + w, y + h, c);
        g.fill(x, y, x + px, y + h, c);
        g.fill(x + w - px, y, x + w, y + h, c);
        g.text(t, x + 3, y + 1.5f, 1, argb(AMBER, 1), -1, true);
    }

    private void message(Gfx g, HudView v, HudLayout L) {
        String s = v.msg();
        float a = Math.min(1, v.msgAlpha());
        float tw = g.textWidth(s), x = L.cx, y = L.msg.y() + 2;
        float bw = Math.min(L.msg.w(), tw + 12);
        g.fill(x - bw / 2, y - 2, x + bw / 2, y + 10, argb(DARK, 0.45f * a));
        g.fill(x - bw / 2, y + 10, x + bw / 2, y + 10 + 1 / g.scale(), argb(v.msgWarn() ? AMBER : GRAY, 0.5f * a));
        g.text(s, x, y, 1, argb(v.msgWarn() ? AMBER : WHITE, a), 0, true);
    }

    // ───────────────────────── ленты ─────────────────────────

    private void speedTape(Gfx g, HudView v, HudLayout L, float gs, float small, long now) {
        HudLayout.Box b = L.speed;
        tape(g, "spd", b, true, v.speed(), SPEED_HALF, 5, 10, 0, v.cruise(), v.boostMax(), gs, small, now,
                v.boost() ? AMBER : WHITE, Long.toString(Math.round(Math.max(0, v.speed()))));
        g.text("бл/с", b.right(), b.y() - 10, small, argb(GRAY, 0.6f), 1, true);
        float ty = b.bottom() + 4;
        double thr = v.throttle() > 0 ? (v.boost() ? v.boostMax() : v.cruise()) / Math.max(1e-6, v.cruise()) : v.throttle() < 0 ? -0.3 : 0;
        String pct = Math.round(thr * 100) + "%";
        float pw = g.textWidth(pct);
        g.text(pct, b.right(), ty, 1, argb(v.boost() ? AMBER : WHITE, 0.92f), 1, true);
        g.text("ТЯГА", b.right() - pw - 4, ty + (1 - small) * 7, small, argb(GRAY, 0.65f), 1, true);
        if (v.boost()) g.text("ФОРСАЖ", b.right(), ty + 11, 1, argb(AMBER, 0.95f), 1, true);
        else if (v.throttle() < 0) g.text("РЕВЕРС", b.right(), ty + 11, small, argb(GRAY, 0.7f), 1, true);
    }

    private void altTape(Gfx g, HudView v, HudLayout L, float gs, float small, long now) {
        HudLayout.Box b = L.alt;
        tape(g, "alt", b, false, v.alt(), ALT_HALF, 10, 50, Double.NaN, Double.NaN, Double.NaN, gs, small, now,
                WHITE, Long.toString(Math.round(v.alt())));
        g.text("высота", b.x(), b.y() - 10, small, argb(GRAY, 0.6f), -1, true);
        float ty = b.bottom() + 4;
        double vs = v.vspeed();
        String s = (vs >= 0.05 ? "+" : vs <= -0.05 ? "-" : "") + String.format(java.util.Locale.ROOT, "%.1f", Math.abs(vs));
        g.text("ВЕРТ", b.x(), ty + (1 - small) * 7, small, argb(GRAY, 0.65f), -1, true);
        g.text(s, b.x() + g.textWidth("ВЕРТ") * small + 4, ty, 1, argb(WHITE, 0.92f), -1, true);
    }

    /**
     * Лента: left — слева от прицела (риски у правого края, подписи левее), иначе зеркально. На ленте скорости —
     * янтарная полоса форсажа (от крейсерской до наибольшей).
     */
    private void tape(Gfx g, String key, HudLayout.Box b, boolean left, double value, double halfRange, double step, double labelBase,
                      double min, double cruise, double boostMax, float gs, float small, long now, int valueColor, String valueText) {
        int w = Hud.px(b.w(), gs), h = Hud.px(b.h(), gs);
        float half = b.h() / 2;
        double lstep = Tape.labelStep(labelBase, halfRange, half, 14);
        float spine = left ? b.w() - 1 : 1;
        Canvas ticks = dyn(key, w, h, now, halfRange / half / 4, new double[]{value}, c -> {
            float sx = spine * gs;
            for (int y = 0; y < c.h; y++) {                                    // ось ленты, к концам — на нет
                float e = 1 - Hud.smooth(0.75f, 1f, Math.abs(y - c.h / 2f) / (c.h / 2f));
                c.put(Math.round(sx), y, GRAY, 0.5f * e);
            }
            if (!Double.isNaN(cruise) && boostMax > cruise) {                   // полоса форсажа
                float y0 = (float) (half - (boostMax - value) / halfRange * half), y1 = (float) (half - (cruise - value) / halfRange * half);
                y0 = Math.clamp(y0, 0, b.h()); y1 = Math.clamp(y1, 0, b.h());
                if (y1 > y0) c.line(sx + (left ? -1.2f : 1.2f) * gs, y0 * gs, sx + (left ? -1.2f : 1.2f) * gs, y1 * gs, Math.max(1.5f, 1.6f * gs), AMBER, 0.75f, false);
            }
            for (Tape.Mark m : Tape.marks(value, halfRange, step, lstep, min)) {
                float y = (half - m.pos() * half) * gs;
                float fade = 1 - Hud.smooth(0.7f, 1f, Math.abs(m.pos()));
                float len = (m.major() ? 6 : 3.5f) * gs;
                c.line(sx, y, sx + (left ? -len : len), y, Math.max(1f, 0.9f * gs), m.major() ? WHITE : GRAY, (m.major() ? 0.9f : 0.55f) * fade, true);
            }
        });
        g.image(key, ticks, b.x(), b.y(), 1);
        for (Tape.Mark m : Tape.marks(value, halfRange, step, lstep, min)) {
            if (m.label() == null || Math.abs(m.pos() * half) < 9) continue;   // под рамкой значения — не подписывать
            float fade = 1 - Hud.smooth(0.65f, 0.95f, Math.abs(m.pos()));
            float y = b.y() + half - m.pos() * half - 3.5f * small;
            if (left) g.text(m.label(), b.x() + spine - 9, y, small, argb(GRAY, 0.8f * fade), 1, true);
            else g.text(m.label(), b.x() + spine + 9, y, small, argb(GRAY, 0.8f * fade), -1, true);
        }
        // рамка значения со стрелкой к оси
        int pw = Hud.px(b.w(), gs), ph = Hud.px(14, gs);
        Canvas box = stat(key + "Box", pw + "x" + ph, pw, ph, c -> {
            float bh = 11 * gs, y0 = (c.h - bh) / 2f, notch = 4 * gs, x0 = left ? 0.5f * gs : notch + 1.5f * gs, bw = b.w() * gs - notch - 2 * gs;
            c.softShadow(x0, y0, bw, bh, 2 * gs, 2.5f * gs, 0.6f * gs, 0.5f);
            c.roundRect(x0, y0, bw, bh, 2 * gs, DARK, 0.72f);
            c.roundRectStroke(x0, y0, bw, bh, 2 * gs, Math.max(1, 0.9f * gs), WHITE, 0.8f);
            float tipX = left ? x0 + bw + notch : x0 - notch, baseX = left ? x0 + bw - 0.5f : x0 + 0.5f, cy = c.h / 2f;
            c.triangle(tipX, cy, baseX, cy - notch * 0.9f, baseX, cy + notch * 0.9f, WHITE, 0.8f, false);
        });
        g.image(key + "Box", box, b.x(), b.y() + half - ph / gs / 2, 1);
        float boxW = b.w() - 6, tx = left ? b.x() + 0.5f + boxW / 2 : b.x() + 5.5f + boxW / 2;
        float vk = g.textWidth(valueText) <= boxW - 4 ? 1 : Hud.crisp(0.75f, gs);
        g.text(valueText, tx, b.y() + half - 4 * vk, vk, argb(valueColor, 1), 0, false);
    }

    // ───────────────────────── нижние углы ─────────────────────────

    /** Фон плашки: мягкая тень, тёмная полупрозрачная заливка, тонкая светлая рамка, уголки-акценты. */
    private Canvas panel(String key, HudLayout.Box b, float gs) {
        float m = 5;
        int w = Hud.px(b.w() + 2 * m, gs), h = Hud.px(b.h() + 2 * m, gs);
        return stat(key, w + "x" + h + "@" + gs, w, h, c -> paintPanel(c, m * gs, m * gs, b.w() * gs, b.h() * gs, gs, 0.5f));
    }

    /** Плашка в стиле HUD (общая с окном корабля). */
    public static void paintPanel(Canvas c, float x, float y, float w, float h, float gs, float fill) {
        float rad = 3 * gs, t = Math.max(1, 0.9f * gs);
        c.softShadow(x, y, w, h, rad, 4 * gs, 1 * gs, 0.45f);
        c.roundRect(x, y, w, h, rad, DARK, fill);
        c.roundRectStroke(x + t / 2, y + t / 2, w - t, h - t, rad, t, GRAY, 0.32f);
        float k = 6 * gs;                                                       // уголки поярче
        c.line(x + rad, y + t / 2, x + rad + k, y + t / 2, t, WHITE, 0.55f, false);
        c.line(x + t / 2, y + rad, x + t / 2, y + rad + k, t, WHITE, 0.55f, false);
        c.line(x + w - rad - k, y + h - t / 2, x + w - rad, y + h - t / 2, t, WHITE, 0.55f, false);
        c.line(x + w - t / 2, y + h - rad - k, x + w - t / 2, y + h - rad, t, WHITE, 0.55f, false);
    }

    private void drawPanel(Gfx g, String key, HudLayout.Box b, float gs) {
        g.image(key, panel(key, b, gs), b.x() - 5, b.y() - 5, 1);
    }

    private void leftPanel(Gfx g, HudView v, HudLayout.Box b, HudLayout L, float gs, float small) {
        drawPanel(g, "panelL", b, gs);
        float x = b.x() + 6, y = b.y(), iw = b.w() - 12;
        g.text("КОРПУС", x, y + 4, small, argb(GRAY, 0.6f), -1, true);
        if (v.kindName() != null) g.text(v.kindName(), x + iw, y + 4, small, argb(WHITE, 0.8f), 1, true);
        int sw = Hud.px(iw, gs), sh = Hud.px(L.silH, gs);
        float[] tris = v.tris();
        int[] tz = v.triZone();
        float[] zh = v.hullKnown() && v.zones() != null ? v.zones() : null;
        StringBuilder lv = new StringBuilder();                                 // перерисовать силуэт, когда зона сменила цвет
        if (zh != null) for (float z : zh) lv.append(Silhouette.level(z));
        Canvas sil = stat("sil", sw + "x" + sh + "#" + (tris == null ? 0 : System.identityHashCode(tris)) + "#" + lv, sw, sh,
                c -> { Canvas s = Silhouette.draw(tris, sw, sh, gs, tz, zh); copy(s, c); });
        g.image("sil", sil, x, y + 13, 1);
        // прочность: число и шкала (цвет — по доле)
        float hy = y + 14 + L.silH + 2;
        g.text("ПРОЧНОСТЬ", x, hy + (1 - small) * 7, small, argb(GRAY, 0.6f), -1, true);
        float hf = v.hullKnown() ? Hud.frac(v.hp(), v.hpMax()) : 1;
        int hc = Silhouette.color(hf);
        g.text(v.hullKnown() ? Math.round(v.hp()) + "/" + Math.round(v.hpMax()) : "—", x + iw, hy, 1, argb(hc, 0.95f), 1, true);
        thinBar(g, x, hy + L.hpH - 2, iw, hf, hc == WHITE ? GRAY : hc, gs);
        float fy = hy + L.hpH + 2;
        g.text("ТОПЛИВО", x, fy + (1 - small) * 7, small, argb(GRAY, 0.6f), -1, true);
        boolean low = v.fuelKnown() && v.fuel() < v.warpCost(), empty = v.fuelKnown() && v.fuel() <= 0;
        int col = empty ? RED : low ? AMBER : WHITE;
        String fs = v.fuelKnown() ? v.fuel() + "/" + v.capacity() : "—";
        g.text(fs, x + iw, fy, 1, argb(col, 0.95f), 1, true);
        float by = fy + 11;
        fuelBar(g, x, by, iw, 5, v, gs);
    }

    /** Шкала бака: дорожка, заливка, деления по слотам (4), отметка цены варпа. Общая с окном корабля. */
    public static void fuelBar(Gfx g, float x, float y, float w, float h, HudView v, float gs) {
        fuelBar(g, x, y, w, h, v.fuelKnown(), v.fuel(), v.capacity(), v.warpCost(), gs);
    }

    public static void fuelBar(Gfx g, float x, float y, float w, float h, boolean known, int fuel, int cap, int cost, float gs) {
        float px = 1 / gs;
        g.fill(x - px, y - px, x + w + px, y + h + px, argb(DARK, 0.6f));
        g.fill(x, y, x + w, y + h, argb(GRAY, 0.12f));
        float f = known ? Hud.frac(fuel, cap) : 0;
        boolean low = known && fuel < cost, empty = known && fuel <= 0;
        if (f > 0) {
            int top = empty ? RED : low ? AMBER : WHITE, bot = empty ? RED : low ? AMBER : GRAY;
            g.fill(x, y, x + w * f, y + h / 2, argb(top, 0.95f));
            g.fill(x, y + h / 2, x + w * f, y + h, argb(bot, 0.75f));
            g.fill(x + w * f - px, y - px, x + w * f, y + h + px, argb(WHITE, 0.95f));
        }
        for (int i = 1; i < 4; i++) {                                            // деления: 4 слота бака
            float sx = x + w * i / 4f;
            g.fill(sx, y, sx + px, y + h, argb(DARK, 0.7f));
        }
        if (known && cost > 0 && cap > 0) {                                      // цена варпа
            float mx = x + w * Hud.frac(cost, cap);
            g.fill(mx - px, y - 2.5f, mx + px, y + h + 2.5f, argb(AMBER, 0.95f));
        }
    }

    private void rightPanel(Gfx g, HudView v, HudLayout.Box b, float gs, float small) {
        drawPanel(g, "panelR", b, gs);
        float x = b.x() + 6, y = b.y(), iw = b.w() - 12;
        // орудие
        boolean ready = v.gunReady() >= 1;
        boolean gunBroken = (v.broken() & HudView.GUN) != 0, warpBroken = (v.broken() & HudView.WARP) != 0;
        g.text(gunBroken ? "НОС РАЗБИТ" : "ОРУДИЕ", x, y + 4 + (1 - small) * 7, small, gunBroken ? argb(AMBER, 0.95f) : argb(GRAY, 0.6f), -1, true);
        g.text(ready ? "ГОТОВО" : String.format(java.util.Locale.ROOT, "%.1f с", v.gunLeftSec()), x + iw, y + 4, 1, argb(ready ? WHITE : AMBER, 0.95f), 1, true);
        thinBar(g, x, y + 14, iw, Math.clamp(v.gunReady(), 0, 1), ready ? GRAY : AMBER, gs);
        // варп
        String s;
        int col;
        float k;
        switch (v.warp()) {
            case HudView.WARP_CHARGING -> { s = "ЗАРЯДКА " + Math.round(v.warpProgress() * 100) + "%"; col = WHITE; k = v.warpProgress(); }
            case HudView.WARP_AIMING -> { s = "НАВОДКА"; col = WHITE; k = 1; }
            case HudView.WARP_RELOAD -> { s = v.warpLeftSec() + " с"; col = AMBER; k = v.warpProgress(); }
            case HudView.WARP_NO_FUEL -> { s = "НЕТ ТОПЛ."; col = RED; k = v.warpProgress(); }
            default -> { s = "ГОТОВ"; col = WHITE; k = 1; }
        }
        if (warpBroken && v.warp() != HudView.WARP_CHARGING) { s = "ПОВРЕЖДЁН"; col = RED; k = 0; }   // варп-привод: разбит корпус
        g.text("ВАРП", x, y + 21 + (1 - small) * 7, small, warpBroken ? argb(RED, 0.9f) : argb(GRAY, 0.6f), -1, true);
        float room = iw - g.textWidth("ВАРП") * small - 4;
        if (g.textWidth(s) > room && v.warp() == HudView.WARP_CHARGING) s = Math.round(v.warpProgress() * 100) + "%";
        g.text(s, x + iw, y + 21, 1, argb(col, 0.95f), 1, true);
        thinBar(g, x, y + 31, iw, k, col == WHITE ? GRAY : col, gs);
    }

    private static void thinBar(Gfx g, float x, float y, float w, float k, int color, float gs) {
        float px = 1 / gs, h = Math.max(2 * px, 1.5f);
        g.fill(x, y, x + w, y + h, argb(GRAY, 0.14f));
        float f = Math.clamp(Float.isFinite(k) ? k : 0, 0, 1);
        if (f > 0) g.fill(x, y, x + w * f, y + h, argb(color, 0.9f));
    }

    private void warpBlock(Gfx g, HudView v, HudLayout.Box b, float gs, float small) {
        drawPanel(g, "panelW", b, gs);
        float k = Math.clamp(v.warpProgress(), 0, 1);
        String t = "ЗАРЯДКА ВАРПА  " + Math.round(k * 100) + "%";
        if (g.textWidth(t) > b.w() - 8) t = "ВАРП  " + Math.round(k * 100) + "%";
        g.text(t, b.x() + b.w() / 2, b.y() + 3, 1, argb(WHITE, 0.95f), 0, true);
        float x = b.x() + 8, w = b.w() - 16, y = b.y() + 13, px = 1 / gs;
        g.fill(x - px, y - px, x + w + px, y + 5 + px, argb(DARK, 0.6f));
        g.fill(x, y, x + w, y + 5, argb(GRAY, 0.12f));
        g.fill(x, y, x + w * k, y + 2.5f, argb(WHITE, 0.95f));
        g.fill(x, y + 2.5f, x + w * k, y + 5, argb(GRAY, 0.8f));
        for (int i = 1; i < 10; i++) g.fill(x + w * i / 10f, y + 5 + px, x + w * i / 10f + px, y + 6.5f, argb(GRAY, 0.4f));
        g.text("R — отмена", b.x() + b.w() / 2, b.y() + 20, small, argb(GRAY, 0.75f), 0, true);
    }

    private void hints(Gfx g, HudView v, HudLayout L, float k) {
        float a = Math.min(1, v.hintsAlpha());
        for (HudLayout.HintLine line : L.hintLines) {
            float x = line.box().x(), y = line.box().y() + 1;
            for (int i = line.from(); i < line.to(); i++) {
                String[] kv = Hints.KEYS[i];
                float kw = (g.textWidth(kv[0]) + 4) * k;
                g.fill(x, y, x + kw, y + 9 * k, argb(GRAY, 0.2f * a));
                g.fill(x, y + 9 * k, x + kw, y + 9 * k + 1 / g.scale(), argb(GRAY, 0.45f * a));
                g.text(kv[0], x + 2 * k, y + k, k, argb(WHITE, 0.95f * a), -1, false);
                g.text(kv[1], x + kw + 3 * k, y + k, k, argb(GRAY, 0.8f * a), -1, true);
                x += L.hintWidth(kv, g::textWidth) + L.hintGap();
            }
        }
    }

    private static void copy(Canvas from, Canvas to) {
        System.arraycopy(from.r, 0, to.r, 0, Math.min(from.r.length, to.r.length));
        System.arraycopy(from.g, 0, to.g, 0, Math.min(from.g.length, to.g.length));
        System.arraycopy(from.b, 0, to.b, 0, Math.min(from.b.length, to.b.length));
        System.arraycopy(from.a, 0, to.a, 0, Math.min(from.a.length, to.a.length));
    }
}
