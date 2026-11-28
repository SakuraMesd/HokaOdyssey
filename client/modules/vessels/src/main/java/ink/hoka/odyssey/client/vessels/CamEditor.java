package ink.hoka.odyssey.client.vessels;

import com.google.gson.JsonObject;
import ink.hoka.odyssey.client.vessels.hud.EditorView;

/**
 * Редактор камер корабля (vessels-5, OP): камера отцеплена и летает свободно в системе корабля (корабль стоит).
 * Мышь — взгляд; WASD — лететь (по горизонту корабля), пробел / C — вверх / вниз; Shift — медленно (×0.2), Ctrl —
 * быстро (×4); колёсико — скорость; Alt+колёсико — FOV; Ctrl+колёсико или 1–6 — другая камера (1 — первое лицо, 2–6 —
 * виды F5 1–5); [ ] — плавность вида F5; V — вид F5 «смотреть на корабль» (вкл/выкл); R — сброс к сохранённому;
 * ЛКМ / Enter — сохранить (редактор остаётся: можно править другие камеры); ПКМ / Esc / Backspace — выйти.
 * Несохранённые правки других камер держатся, пока редактор открыт (в списке — «*»).
 * <p>
 * Камеру начинает с сохранённой точки (правка существующей камеры), иначе — с камеры корабля в деле (у корабля — от
 * вида), иначе — с камеры по умолчанию. Чистая логика (без классов игры) — тесты; клавиши даёт {@link Pilot}.
 */
final class CamEditor {

    /** Камер в списке: первое лицо + виды F5. */
    static final int SLOTS = 1 + 5;

    final boolean shipScope;
    final String key, title;
    /** Камеры области правки (сохранённые) и корабля в деле. */
    ShipCams.Set own, eff;
    /** Камеры по умолчанию (место пилота / «сзади»). */
    private final ShipCams.Cam defFirst, defChase;
    /** Какую камеру правим (0 — первое лицо, n — вид F5 n) и правки (null — не трогали). */
    int which;
    private final ShipCams.Cam[] drafts = new ShipCams.Cam[SLOTS];
    /** Текущая камера (система корабля) и прошлый тик — для плавной отрисовки. */
    ShipCams.Cam cur;
    double px, py, pz;
    /** Скорость полёта камеры, бл/с (колёсико). */
    double speed = 4;
    /** Строка состояния и когда (мс). */
    String status;
    boolean statusWarn;
    long statusAt;

    CamEditor(boolean shipScope, String key, String title, int which, ShipCams.Set own, ShipCams.Set eff, ShipCams.Cam defFirst, ShipCams.Cam defChase) {
        this.shipScope = shipScope;
        this.key = key;
        this.title = title;
        this.own = own;
        this.eff = eff;
        this.defFirst = defFirst;
        this.defChase = defChase;
        select(which);
    }

    // ───────────────────────── камеры ─────────────────────────

    /** Сохранённая камера области (или null). */
    ShipCams.Cam saved(int w) {
        if (w == 0) return own.first();
        return w - 1 < own.f5().size() ? own.f5().get(w - 1) : null;
    }

    /** Камера в деле у корабля (своя, от вида или null). */
    private ShipCams.Cam effective(int w) {
        if (w == 0) return eff.first();
        return w - 1 < eff.f5().size() ? eff.f5().get(w - 1) : null;
    }

    /** С чего начать камеру w: сохранённая → в деле → по умолчанию. Углы «смотреть на» — сразу в числа. */
    ShipCams.Cam base(int w) {
        ShipCams.Cam c = saved(w);
        if (c == null) c = effective(w);
        if (c == null) c = w == 0 ? defFirst : defChase;
        return c;
    }

    /** Можно ли править камеру w: первое лицо и виды F5 по порядку (не дальше «следующего нового»). */
    boolean available(int w) { return w >= 0 && w < SLOTS && (w == 0 || w <= own.f5().size() + 1); }

    /** Перейти к камере w (правки текущей остаются черновиком). */
    void select(int w) {
        if (cur != null) drafts[which] = changed(which) ? cur : null;
        int n = Math.clamp(w, 0, SLOTS - 1);
        while (!available(n)) n--;
        which = n;
        cur = drafts[n] != null ? drafts[n] : base(n);
        px = cur.x(); py = cur.y(); pz = cur.z();
    }

    /** Следующая/предыдущая доступная камера (по кругу). */
    void cycle(int dir) {
        int n = which;
        for (int i = 0; i < SLOTS; i++) {
            n = Math.floorMod(n + dir, SLOTS);
            if (available(n)) { select(n); return; }
        }
    }

    /** Правили ли камеру w (отличается от того, с чего начали). */
    boolean changed(int w) {
        ShipCams.Cam c = w == which ? cur : drafts[w];
        return c != null && !same(c, base(w));
    }

    private static boolean same(ShipCams.Cam a, ShipCams.Cam b) {
        return Math.abs(a.x() - b.x()) < 1e-4 && Math.abs(a.y() - b.y()) < 1e-4 && Math.abs(a.z() - b.z()) < 1e-4
                && Math.abs(a.yaw() - b.yaw()) < 1e-3 && Math.abs(a.pitch() - b.pitch()) < 1e-3 && Math.abs(a.fov() - b.fov()) < 1e-3
                && java.util.Arrays.equals(a.look(), b.look()) && Double.compare(a.smooth(), b.smooth()) == 0;
    }

    /** R — сброс текущей к сохранённой (нет — к камере в деле / по умолчанию). */
    void reset() {
        drafts[which] = null;
        cur = base(which);
        px = cur.x(); py = cur.y(); pz = cur.z();
        status("Сброшено к " + (saved(which) != null ? "сохранённой" : "по умолчанию"), false);
    }

    // ───────────────────────── управление ─────────────────────────

    /** Мышь: курс и наклон (взгляд «смотреть на корабль» мышь не крутит). */
    void mouse(double dyaw, double dpitch) {
        if (cur.look() != null) return;
        cur = cur.withAngles(wrap(cur.yaw() + dyaw), Math.clamp(cur.pitch() + dpitch, -90, 90));
    }

    /**
     * Тик: fwd/side/vert — −1…1 (W/S, A/D (+ влево), пробел/C), slow — Shift, fast — Ctrl. Летит по горизонту
     * корабля в сторону взгляда.
     */
    void move(int fwd, int side, int vert, boolean slow, boolean fast) {
        px = cur.x(); py = cur.y(); pz = cur.z();
        double k = speed * (slow ? 0.2 : 1) * (fast ? 4 : 1) / 20.0;
        double[] a = ShipCams.angles(cur);
        double yr = Math.toRadians(a[0]);
        double fx = -Math.sin(yr), fz = Math.cos(yr);                            // вперёд по горизонту (курс + вправо)
        double lx = fz, lz = -fx;                                                // влево: +X при курсе 0
        double dx = (fx * fwd + lx * side) * k, dz = (fz * fwd + lz * side) * k, dy = vert * k;
        if (dx != 0 || dy != 0 || dz != 0) cur = cur.withPos(cur.x() + dx, cur.y() + dy, cur.z() + dz);
    }

    /** Колёсико: скорость (×1.25 за щелчок, 0.5…40 бл/с). */
    void wheelSpeed(int notches) {
        speed = Math.clamp(speed * Math.pow(1.25, -notches), 0.5, 40);
        status(String.format(java.util.Locale.ROOT, "Скорость %.1f бл/с", speed), false);
    }

    /** Alt+колёсико: FOV (±2° за щелчок; было «как у игрока» — от fovNow). */
    void wheelFov(int notches, double fovNow) {
        double f = cur.fov() > 0 ? cur.fov() : fovNow;
        f = Math.clamp(f - notches * 2, 20, 130);
        cur = new ShipCams.Cam(cur.x(), cur.y(), cur.z(), cur.yaw(), cur.pitch(), f, cur.look(), cur.smooth());
    }

    /** [ ] — плавность вида F5 (0.05 шаг, 0.05…1; «по умолчанию» — от def). */
    void smooth(int dir, double def) {
        if (which == 0) { status("Плавность — только у видов F5", true); return; }
        double s = Double.isNaN(cur.smooth()) ? def : cur.smooth();
        s = Math.clamp(Math.round((s + dir * 0.05) * 100) / 100.0, 0.05, 1);
        cur = new ShipCams.Cam(cur.x(), cur.y(), cur.z(), cur.yaw(), cur.pitch(), cur.fov(), cur.look(), s);
    }

    /** V — вид F5 смотрит на середину корабля (вкл/выкл; выкл — углы остаются те, что были). */
    void toggleLook() {
        if (which == 0) { status("«Смотреть на корабль» — только у видов F5", true); return; }
        if (cur.look() != null) {
            double[] a = ShipCams.angles(cur);
            cur = new ShipCams.Cam(cur.x(), cur.y(), cur.z(), a[0], a[1], cur.fov(), null, cur.smooth());
            status("Взгляд — мышью", false);
        } else {
            cur = new ShipCams.Cam(cur.x(), cur.y(), cur.z(), cur.yaw(), cur.pitch(), cur.fov(), new double[]{0, 0, 0}, cur.smooth());
            status("Смотрит на середину корабля", false);
        }
    }

    /** C2S CAMSAVE: {which, cam}. */
    String saveJson() {
        JsonObject o = new JsonObject();
        o.addProperty("which", which);
        o.add("cam", cur.json());
        return o.toString();
    }

    /** S2C CAMSAVED: сохранённое стало «уже есть», черновик снят. */
    void saved(int w, ShipCams.Set own, ShipCams.Set eff, String msg) {
        this.own = own;
        this.eff = eff;
        if (w >= 0 && w < SLOTS) drafts[w] = null;
        if (w == which) { cur = base(w); }
        status(msg, false);
    }

    void status(String s, boolean warn) {
        status = s;
        statusWarn = warn;
        statusAt = System.currentTimeMillis();
    }

    private static double wrap(double d) {
        double r = ((d + 180) % 360 + 360) % 360 - 180;
        return r == -180 ? 180 : r;
    }

    // ───────────────────────── HUD ─────────────────────────

    /** Что показать в HUD редактора (fovNow — FOV игрока: подставить, если у камеры «как у игрока»). */
    EditorView view(double fovNow, double defSmooth, long now) {
        String[] names = new String[SLOTS];
        int[] st = new int[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            names[i] = i == 0 ? "1 Первое лицо" : (i + 1) + " F5-" + i;
            int s = 0;
            if (saved(i) != null) s |= EditorView.SAVED;
            else if (effective(i) != null) s |= EditorView.INHERITED;
            if (changed(i)) s |= EditorView.DIRTY;
            if (!available(i)) s |= EditorView.LOCKED;
            st[i] = s;
        }
        double[] a = ShipCams.angles(cur);
        float age = (now - statusAt) / 1000f;
        float alpha = status == null ? 0 : age < 3 ? 1 : Math.max(0, 1 - (age - 3) / 0.8f);
        return new EditorView(title, shipScope, names, st, which, cur.x(), cur.y(), cur.z(), a[0], a[1],
                cur.fov(), fovNow, speed, which == 0 ? Double.NaN : Double.isNaN(cur.smooth()) ? defSmooth : cur.smooth(),
                cur.look() != null, saved(which) != null, status, statusWarn, alpha);
    }
}
