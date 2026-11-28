package ink.hoka.odyssey.vessels;

import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.display.ItemDisplayMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.sound.SoundEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Посадка на планету вместе с кораблём и взлёт обратно в космос (vessels-6). Планеты знает модуль planets (сервис
 * PlanetsApi через {@link PlanetLink}); без него ничего не происходит.
 * <ol>
 *   <li><b>Вход.</b> Корабль с пилотом подлетел к планете ближе approach (по середине корабля минус его радиус) —
 *       экран пилота и стоящих на корабле темнеет, корабль замирает. На карте планеты выбирается случайная точка в
 *       круге ship-scatter у (ship-x, ship-z), грузятся кубы столбца от ship-y до ship-entry-y, в фоне ищется высота
 *       конца спуска (ship-y; рельеф ближе подушки — выше) и что путь сверху свободен. Нет — другая точка (до 4 раз).</li>
 *   <li><b>Перенос</b> (в полной темноте): пилот — {@code transfer} ядра (очередь переходов игрока); только когда
 *       он на карте, корабль меняет карту (vessels.json пишется сразу — после падения сервера корабль на одной из карт);
 *       носитель ставится, пилот снова садится, потом переносятся стоящие на корабле (на свои места на палубе).
 *       Не удалось перенести пилота — корабль и экипаж остаются, сообщение.</li>
 *   <li><b>Спуск</b> ({@link Glide#DESCENT}): с ship-entry-y до конца за ship-descent секунд, экран светлеет в начале,
 *       пламя и звук, торможение в конце; пилот не управляет (мод показывает «ВХОД В АТМОСФЕРУ»). Потом — управление.</li>
 *   <li><b>Взлёт.</b> На карте планеты выше ship-exit-y — то же в обратную сторону: в космос над гранью куба планеты по
 *       направлению полёта, в approach + 30 от поверхности (корпусом), носом от планеты, разгон ({@link Glide#EXIT}).
 *       Повторный вход — не раньше чем через cooldown, и корабль появляется в 30 блоках за зоной входа.</li>
 * </ol>
 * Планета без карты (или звезда) корабль не впускает — отталкивает, как planets игрока.
 */
final class Landings {

    static final int FADE_IN = 24, FADE_OUT = 34, HOLD = 400;

    enum Kind { ENTRY, EXIT }

    enum Phase { FADE, LOAD, FIND, MOVE, MOVING, PLACE, GLIDE }

    /** Переход корабля между картами. */
    static final class Trip {
        final Kind kind;
        final Vessel v;
        final Player pilot;
        final LandingMath.Planet planet;
        final String toMap;
        final Instance from, to;
        final LandingMath.Heights h;
        final Object pin = new Object();
        final long started;
        Phase phase = Phase.FADE;
        long phaseAt;
        Quat rot;
        /** Точка-кандидат: вход — {x, z}; выход — нормаль грани. */
        double[] spot;
        int[] face;
        Vec loLoad, hiLoad;
        int attempts;
        volatile boolean busy;
        volatile double[] start, end;
        volatile String why;
        /** Экипаж (стоят на корабле при переносе) и их места на палубе (система корабля). */
        final Map<Player, double[]> crew = new LinkedHashMap<>();
        Glide glide;
        int t;
        boolean acked;
        long blackAt;

        Trip(Kind kind, Vessel v, Player pilot, LandingMath.Planet planet, String toMap, Instance from, Instance to, LandingMath.Heights h, long now) {
            this.kind = kind;
            this.v = v;
            this.pilot = pilot;
            this.planet = planet;
            this.toMap = toMap;
            this.from = from;
            this.to = to;
            this.h = h;
            this.started = now;
            this.phaseAt = now;
            this.blackAt = now;
        }

        /** Пилот уже в пути / корабль уже на новой карте — сам переход не отменить. */
        boolean committed() { return phase == Phase.MOVING || phase == Phase.PLACE || phase == Phase.GLIDE; }

        /** Доля пройденного пути 0…1 (для HUD и частиц). */
        double progress() { return glide == null ? 0 : Math.min(1, (double) t / glide.ticks); }
    }

    private final VesselsModule mod;
    private final PlanetLink link;
    /** Что уже писали в лог про урезанные высоты (планета + карта + вид). */
    private final Set<String> logged = new HashSet<>();

    Landings(VesselsModule mod, PlanetLink link) {
        this.mod = mod;
        this.link = link;
    }

    // ───────────────────────── настройки ─────────────────────────

    boolean enabled() { return mod.config().getBoolean("planet-entry.enabled", true); }

    /** Повторный вход/взлёт — не раньше (тики). */
    int cooldownTicks() { return (int) Math.round(Math.max(1, mod.config().getDouble("planet-entry.cooldown", 10)) * 20); }

    /** Выход в космос: расстояние от поверхности (корпусом) сверх approach. */
    double exitMargin() { return Math.max(5, mod.config().getDouble("planet-entry.exit-margin", 30)); }

    int exitTicks() { return (int) Math.round(Math.max(0.5, mod.config().getDouble("planet-entry.exit-time", 2.5)) * 20); }

    /** HUD «ДО ОРБИТЫ»: показывать, когда до взлёта меньше стольких блоков. */
    double orbitHint() { return mod.config().getDouble("planet-entry.orbit-hint", 600); }

    /** Высоты посадки на карте inst для вида k (урезанное — в лог один раз). */
    LandingMath.Heights heights(Instance inst, VesselKind k, LandingMath.Planet pl) {
        int minY = inst.getCachedDimensionType().minY(), maxY = minY + inst.getCachedDimensionType().height();
        LandingMath.Heights h = LandingMath.heights(minY, maxY, k.radius(), pl.entryY(), pl.shipY(), pl.exitY());
        if (!h.notes().isEmpty() && logged.add(pl.id() + "/" + pl.target() + "/" + k.id))
            mod.logger().warn("планета {} (карта {}, высота {}…{}), корабль {}: {}", pl.id(), pl.target(), minY, maxY, k.id, String.join("; ", h.notes()));
        return h;
    }

    /** Высота взлёта на карте корабля (HUD «ДО ОРБИТЫ»); NaN — не карта планеты. */
    double orbitY(Vessel v) {
        if (!enabled() || v.carrier == null || v.carrier.getInstance() == null) return Double.NaN;
        LandingMath.Planet pl = link.byTarget(v.map);
        VesselKind k = mod.kinds().get(v.kind);
        if (pl == null || k == null) return Double.NaN;
        return heights(v.carrier.getInstance(), k, pl).exit();
    }

    // ───────────────────────── кого planets не трогает ─────────────────────────

    /** Пилот корабля, экипаж в переходе или стоящий на корабле с пилотом — их ведёт vessels (фильтр для planets). */
    boolean handles(Player p) {
        if (!enabled()) return false;
        for (Vessel v : mod.vessels()) {
            if (v.pilot != null && v.pilot.player == p) return true;
            Trip t = v.trip;
            if (t != null && (t.pilot == p || t.crew.containsKey(p))) return true;
        }
        for (Vessel v : mod.vessels()) if (v.pilot != null && mod.riders(v).contains(p)) return true;
        return false;
    }

    /** Корабль в переходе (пилота не снимать при смене карты, позы мода не принимать). */
    static boolean busy(Vessel v) { return v != null && v.trip != null; }

    // ───────────────────────── тик ─────────────────────────

    void tick() {
        long now = mod.ticks();
        if (now % 5 == 0 && enabled()) detect(now);
        for (Vessel v : List.copyOf(mod.vessels())) if (v.trip != null) step(v.trip, now);
    }

    /** Корабли с пилотом: подлетели к планете — вход; поднялись выше взлёта на карте планеты — выход. */
    private void detect(long now) {
        if (!link.present()) return;
        for (Vessel v : List.copyOf(mod.vessels())) {
            Piloting pl = v.pilot;
            if (pl == null || v.trip != null || v.arrival != null || now < v.tripReady) continue;
            if (v.carrier == null || v.carrier.isRemoved() || v.carrier.getInstance() == null) continue;
            VesselKind k = mod.kinds().get(v.kind);
            if (k == null) continue;
            Instance inst = v.carrier.getInstance();
            LandingMath.Planet near = link.near(v.map, v.x, v.y, v.z, k.radius());
            if (near != null) {
                if (!near.enterable()) {
                    push(v, k, near, near.star() ? "Слишком жарко — щиты не выдержат!" : "Посадка на эту планету пока недоступна");
                    continue;
                }
                startEntry(v, k, near, inst, now);
                continue;
            }
            LandingMath.Planet home = link.byTarget(v.map);
            if (home != null && v.y > heights(inst, k, home).exit()) startExit(v, k, home, inst, now);
        }
    }

    /** Отбросить корабль от планеты (звезда, нет карты, не вышло войти). */
    private void push(Vessel v, VesselKind k, LandingMath.Planet pl, String msg) {
        double dx = v.x - pl.x(), dy = v.y - pl.y(), dz = v.z - pl.z(), l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-6) { dx = 0; dy = 1; dz = 0; l = 1; }
        double d = pl.approach() * 0.6 + 20;
        mod.warp.cancel(v, null);
        v.x += dx / l * d; v.y += dy / l * d; v.z += dz / l * d;
        moveCarrier(v);
        mod.client.correct(v);
        v.tripReady = mod.ticks() + 20;
        if (msg != null && v.pilot != null) mod.client.message(v.pilot.player, msg, true);
    }

    // ───────────────────────── начало ─────────────────────────

    private void startEntry(Vessel v, VesselKind k, LandingMath.Planet pl, Instance from, long now) {
        Instance to = mod.server().map(pl.target()).orElse(null);
        if (to == null || to == from) {
            mod.logger().warn("планета {}: карта «{}» не найдена — корабль не впускаем", pl.id(), pl.target());
            push(v, k, pl, "Посадка на эту планету пока недоступна");
            return;
        }
        Trip t = new Trip(Kind.ENTRY, v, v.pilot.player, pl, pl.target(), from, to, heights(to, k, pl), now);
        t.rot = Quat.yaw(v.rot.yawDeg());
        begin(t, k);
        newSpot(t, k);
        mod.client.message(t.pilot, "Вход в атмосферу планеты…", false);
        mod.logger().info("корабль {} ({}): вход в планету {} → карта {}", v.id, t.pilot.getUsername(), pl.id(), pl.target());
    }

    private void startExit(Vessel v, VesselKind k, LandingMath.Planet pl, Instance from, long now) {
        Instance to = mod.server().map(pl.map()).orElse(null);
        if (to == null || to == from) { v.tripReady = now + cooldownTicks(); return; }
        Trip t = new Trip(Kind.EXIT, v, v.pilot.player, pl, pl.map(), from, to, heights(from, k, pl), now);
        Piloting p = v.pilot;
        t.face = LandingMath.exitFace(p.vx, p.vy, p.vz, v.rot.rotate(0, 0, 1));
        t.rot = LandingMath.noseAlong(t.face);
        begin(t, k);
        double r = k.radius();
        double[] at = LandingMath.exitPoint(pl, t.face, v.x, v.z, pl.approach() + exitMargin() + r);
        double len = Glide.exitLength(k.speed / 20.0, exitTicks()) + 40;   // путь разгона + запас на сдвиг
        double[] far = {at[0] + t.face[0] * len, at[1] + t.face[1] * len, at[2] + t.face[2] * len};
        t.spot = at;
        load(t, new Vec(Math.min(at[0], far[0]) - r - 2, Math.min(at[1], far[1]) - r - 2, Math.min(at[2], far[2]) - r - 2),
                new Vec(Math.max(at[0], far[0]) + r + 2, Math.max(at[1], far[1]) + r + 2, Math.max(at[2], far[2]) + r + 2));
        mod.client.message(t.pilot, "Выход на орбиту…", false);
        mod.logger().info("корабль {} ({}): взлёт с карты {} к планете {}", v.id, t.pilot.getUsername(), v.map, pl.id());
    }

    /** Общее начало: затемнение пилоту и стоящим на корабле, корабль замирает, варп — прочь. */
    private void begin(Trip t, VesselKind k) {
        Vessel v = t.v;
        v.trip = t;
        mod.warp.cancel(v, null);
        for (Player r : mod.riders(v)) t.crew.put(r, null);                      // заранее — planets их не тронет
        for (Player p : people(t)) link.fade(p, FADE_IN, HOLD, FADE_OUT);
        sound(t.from, v, SoundEvent.ITEM_FIRECHARGE_USE, 2f, 0.6f);
        mod.client.correct(v);
    }

    /** Новая точка входа: случайная в круге (внутри границы карты), кубы столбца — грузить. */
    private void newSpot(Trip t, VesselKind k) {
        LandingMath.Planet pl = t.planet;
        double r = k.radius(), reach = mod.cushion().reach();
        double[] xz = LandingMath.scatter(ThreadLocalRandom.current(), pl.shipX(), pl.shipZ(), pl.scatter());
        var wb = t.to.getWorldBorder();
        xz = LandingMath.insideBorder(xz[0], xz[1], wb.diameter(), wb.centerX(), wb.centerZ(), r + reach + 8);
        t.spot = xz;
        double pad = r + reach + 2;
        load(t, new Vec(xz[0] - pad, t.h.rest() - r - reach - 4, xz[1] - pad), new Vec(xz[0] + pad, t.h.entry() + r + 2, xz[1] + pad));
    }

    private void load(Trip t, Vec lo, Vec hi) {
        mod.server().unpin(t.pin);
        t.loLoad = lo;
        t.hiLoad = hi;
        mod.server().pinCubes(t.to, lo, hi, t.pin);
        t.phase = t.phase == Phase.FADE ? Phase.FADE : Phase.LOAD;
        t.phaseAt = mod.ticks();
    }

    // ───────────────────────── шаги ─────────────────────────

    private void step(Trip t, long now) {
        Vessel v = t.v;
        VesselKind k = mod.kinds().get(v.kind);
        if (k == null) { abort(t, "Вид корабля пропал из config.yml"); return; }
        if (!t.committed()) {
            if (v.pilot == null || v.pilot.player != t.pilot || !t.pilot.isOnline()) { abort(t, null); return; }
            v.pilot.lastPose = now;                                             // мод замер — не «молчит»
            if (now % 2 == 0) mod.client.correct(v);                            // корабль стоит, пока темнеет и грузится
            if (now - t.blackAt > HOLD - 60) keepBlack(t, now);
        }
        switch (t.phase) {
            case FADE -> {
                if (now - t.phaseAt < FADE_IN + 4) return;
                t.phase = Phase.LOAD;
                t.phaseAt = now;
            }
            case LOAD -> {
                if (!mod.server().areCubesLoaded(t.to, t.loLoad, t.hiLoad)) {
                    if (now - t.phaseAt > 20L * Math.max(5, mod.config().getInt("planet-entry.load-wait", 20)))
                        abort(t, t.kind == Kind.ENTRY ? "Карта планеты долго грузится — попробуйте ещё раз" : "Космос у планеты долго грузится — попробуйте ещё раз");
                    return;
                }
                t.phase = Phase.FIND;
                t.busy = true;
                t.start = null;
                mod.scheduler().async(() -> {
                    try {
                        if (t.kind == Kind.ENTRY) findEntry(t, k); else findExit(t, k);
                    } catch (RuntimeException e) {
                        t.why = e.toString();
                        mod.logger().warn("корабль {}: место перехода не найдено: {}", v.id, e.toString());
                    } finally {
                        t.busy = false;
                    }
                });
            }
            case FIND -> {
                if (t.busy) return;
                if (t.start == null) {
                    if (t.kind == Kind.ENTRY && ++t.attempts < 4) { t.phase = Phase.LOAD; newSpot(t, k); return; }
                    abort(t, t.kind == Kind.ENTRY ? "Не нашлось места для посадки — попробуйте ещё раз" : "У планеты нет места для выхода — попробуйте ещё раз");
                    if (t.why != null) mod.logger().info("корабль {}: переход не вышел: {}", v.id, t.why);
                    return;
                }
                t.phase = Phase.MOVE;
            }
            case MOVE -> movePilot(t, k);
            case MOVING -> {
                if (!t.pilot.isOnline() || now - t.phaseAt > 20 * 60) {               // вышел посреди переноса / перенос завис
                    mod.logger().warn("корабль {}: перенос пилота {} не закончился — корабль остаётся на карте {}", v.id, t.pilot.getUsername(), v.map);
                    t.phase = Phase.MOVE;
                    abort(t, null);
                }
            }
            case PLACE -> {
                if (now - t.phaseAt > 20 * 15 && (v.carrier == null || v.carrier.getInstance() == null)) {
                    mod.logger().warn("корабль {}: носитель на карте {} не поставлен за 15 с — спуск без него", v.id, t.toMap);
                    glideStart(t, k);
                }
            }
            case GLIDE -> glide(t, k, now);
        }
    }

    /** Экран держать чёрным, пока грузится (без мигания: затемнение с нуля тиков). */
    private void keepBlack(Trip t, long now) {
        t.blackAt = now;
        for (Player p : people(t)) link.fade(p, 0, HOLD, FADE_OUT);
    }

    /** Вход (в фоне): высота конца спуска над точкой и что путь сверху свободен. */
    private void findEntry(Trip t, VesselKind k) {
        double x = t.spot[0], z = t.spot[1], r = k.radius();
        Cushion.Settings cs = mod.cushion();
        double reach = cs.reach();
        DistField.Blocked b = mod.arrivals.cushionBlocks(t.to);
        int fx = (int) Math.ceil(r + reach + 1);
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int yTop = (int) Math.ceil(t.h.entry() + r + 1), yBot = (int) Math.floor(t.h.rest() - r - reach - 2);
        int top = Integer.MIN_VALUE;                                             // самый высокий блок под путём (с запасом подушки)
        for (int dx = -fx; dx <= fx; dx++) for (int dz = -fx; dz <= fx; dz++) {
            for (int y = yTop; y >= Math.max(yBot, top + 1); y--) if (b.at(bx + dx, y, bz + dz)) { top = Math.max(top, y); break; }
        }
        double calm = cs.clearance() + cs.soft() + 1;
        double rest;
        if (top == Integer.MIN_VALUE || top + 1 + calm + r + 1 < t.h.rest()) rest = t.h.rest();   // рельеф далеко внизу
        else {
            if (top >= yTop - 1) { t.why = "рельеф выше появления"; return; }
            int y0 = Math.max(yBot, top - (int) Math.ceil(2 * r + reach + 4)), y1 = Math.min(yTop + (int) Math.ceil(reach), top + (int) Math.ceil(2 * r + reach + 24));
            DistField f = DistField.build(bx - fx, y0, bz - fx, 2 * fx + 1, Math.max(1, y1 - y0), 2 * fx + 1, b);
            rest = LandingMath.restY(f, Arrivals.offsets(k, t.rot.yawDeg()), x, z, t.h.rest(), Math.min(t.h.entry(), y1 - r - reach), cs);
            if (Double.isNaN(rest)) { t.why = "над рельефом нет места (верх " + top + ")"; return; }
        }
        t.end = new double[]{x, rest, z};
        t.start = new double[]{x, Math.max(rest, t.h.entry()), z};
    }

    /** Выход (в фоне): у грани — свободно ли корпусу на пути разгона; занято — дальше от планеты (до 12 раз по 16). */
    private void findExit(Trip t, VesselKind k) {
        double len = Glide.exitLength(k.speed / 20.0, exitTicks());
        int[] n = t.face;
        var wb = t.to.getWorldBorder();
        double half = wb.diameter() / 2 - k.radius() - 2;
        int minY = t.to.getCachedDimensionType().minY(), maxY = minY + t.to.getCachedDimensionType().height();
        for (int i = 0; i < 12; i++) {
            double[] s = {t.spot[0] + n[0] * 16.0 * i, t.spot[1] + n[1] * 16.0 * i, t.spot[2] + n[2] * 16.0 * i};
            double[] e = {s[0] + n[0] * len, s[1] + n[1] * len, s[2] + n[2] * len};
            if (Math.abs(e[0] - wb.centerX()) > half || Math.abs(e[2] - wb.centerZ()) > half) { t.why = "за границей карты"; return; }
            if (e[1] > maxY - k.radius() - 2 || s[1] < minY + k.radius() + 2) { t.why = "за высотой карты"; return; }
            if (!pathFree(t, k, s, e)) continue;
            t.end = e;
            t.start = s;
            return;
        }
        t.why = "у грани всё занято";
    }

    private boolean pathFree(Trip t, VesselKind k, double[] s, double[] e) {
        double len = ArrivalPlan.dist(s, e);
        int n = Math.max(1, (int) Math.ceil(len / 2));
        for (int i = 0; i <= n; i++) {
            double u = (double) i / n;
            double[] c = {s[0] + (e[0] - s[0]) * u, s[1] + (e[1] - s[1]) * u, s[2] + (e[2] - s[2]) * u};
            if (!mod.server().isCubeLoaded(t.to, (int) Math.floor(c[0]), (int) Math.floor(c[1]), (int) Math.floor(c[2]))) return false;
            if (!mod.warp.free(t.to, k, c, t.rot)) return false;
        }
        return true;
    }

    /** Перенос пилота (корабль ещё на старой карте); на месте — корабль следом. */
    private void movePilot(Trip t, VesselKind k) {
        Vessel v = t.v;
        Player p = t.pilot;
        for (Player r : mod.riders(v)) t.crew.putIfAbsent(r, null);
        for (var e : List.copyOf(t.crew.entrySet())) {                          // места на палубе (кто ещё на корабле)
            Player r = e.getKey();
            if (!r.isOnline() || r.getInstance() != t.from || !mod.riders(v).contains(r)) { t.crew.remove(r); continue; }
            Pos rp = r.getPosition();
            t.crew.put(r, VesselMath.toLocal(rp.x(), rp.y(), rp.z(), v.pos(), v.rot));
        }
        t.phase = Phase.MOVING;
        t.phaseAt = mod.ticks();
        mod.client.end(p);                                                      // мод пилота: полёт — заново после переноса
        double[] eye = VesselMath.toWorld(k.seat[0], k.seat[1], k.seat[2], t.start, t.rot);
        Pos at = new Pos(eye[0], eye[1] - p.getEyeHeight(), eye[2], (float) t.rot.yawDeg(), 0f);
        Beams.safe(p, 30_000);
        mod.server().transfer(p, t.to, at).whenComplete((ok, err) -> mod.scheduler().run(() -> pilotArrived(t, k, err)));
    }

    private void pilotArrived(Trip t, VesselKind k, Throwable err) {
        Vessel v = t.v;
        if (v.trip != t) return;
        Player p = t.pilot;
        if (err != null || !p.isOnline() || p.getInstance() != t.to) {
            mod.logger().warn("корабль {}: пилот {} не перенесён ({}) — корабль остаётся", v.id, p.getUsername(),
                    err != null ? err.toString() : p.isOnline() ? "не на карте" : "вышел");
            t.phase = Phase.MOVE;                                               // ещё не «в пути»: abort вернёт полёт
            if (p.isOnline() && v.pilot != null && v.pilot.player == p && p.getInstance() == t.from) {
                abort(t, "Переход не удался — корабль остаётся на месте");
            } else {
                abort(t, null);
                if (v.pilot != null && v.pilot.player == p) mod.exit(p, false);
            }
            return;
        }
        relocate(v, t.toMap, t.start, t.rot);
        t.phase = Phase.PLACE;
        t.phaseAt = mod.ticks();
        mod.spawnCarrier(v, () -> { if (v.trip == t && t.phase == Phase.PLACE) glideStart(t, k); });
    }

    /** Корабль — на другую карту (данные): старый носитель прочь, vessels.json — сразу. */
    void relocate(Vessel v, String map, double[] at, Quat rot) {
        mod.client.detachToAll(v);
        if (v.carrier != null) v.carrier.remove();
        v.carrier = null;
        v.map = map;
        v.x = at[0]; v.y = at[1]; v.z = at[2];
        v.rot = rot;
        v.cushionField = null;
        v.cushionLast = Double.NaN;
        mod.save();
    }

    /** Корабль на новой карте: пилот снова за штурвалом (без управления), экран светлеет, экипаж — следом, путь. */
    private void glideStart(Trip t, VesselKind k) {
        Vessel v = t.v;
        int ticks = t.kind == Kind.ENTRY ? (int) Math.round(t.planet.descentSec() * 20) : exitTicks();
        t.glide = new Glide(t.kind == Kind.ENTRY ? Glide.DESCENT : Glide.EXIT, t.start, t.end, ticks);
        t.t = 0;
        t.phase = Phase.GLIDE;
        t.phaseAt = mod.ticks();
        Player p = t.pilot;
        if (p.isOnline() && v.pilot != null && v.pilot.player == p && p.getInstance() == t.to) {
            p.setAllowFlying(true);                                             // после смены измерения — снова
            p.setFlying(true);
            p.setFlyingSpeed(0f);
            p.setInvisible(true);
            double[] eye = mod.seatWorld(v, k);
            p.teleport(new Pos(eye[0], eye[1] - p.getEyeHeight(), eye[2], (float) v.rot.yawDeg(), 0f));
            v.pilot.lastPose = mod.ticks();
            mod.client.start(v);
            mod.client.state(v);
            mod.client.hull(v, false, -1);
            mod.client.cameras(v);
            mod.client.auto(v, t);
            link.fade(p, 0, 4, FADE_OUT);
            mod.pvpZone(v, v.pilot, true);
        }
        for (var e : List.copyOf(t.crew.entrySet())) moveRider(t, e.getKey(), e.getValue());
        sound(t.to, v, t.kind == Kind.ENTRY ? SoundEvent.ENTITY_BLAZE_SHOOT : SoundEvent.ENTITY_FIREWORK_ROCKET_LAUNCH, 3f, 0.5f);
    }

    /** Стоящий на корабле — на его место на палубе (корабль уже на новой карте). */
    private void moveRider(Trip t, Player r, double[] local) {
        if (local == null || !r.isOnline() || r.getInstance() != t.from) { t.crew.remove(r); return; }
        Vessel v = t.v;
        double[] w = VesselMath.toWorld(local[0], local[1], local[2], v.pos(), v.rot);
        Beams.safe(r, 40_000);
        mod.server().transfer(r, t.to, new Pos(w[0], w[1] + 0.05, w[2], r.getPosition().yaw(), r.getPosition().pitch()))
                .whenComplete((ok, err) -> mod.scheduler().run(() -> {
                    if (r.isOnline() && r.getInstance() == t.to) {
                        link.fade(r, 0, 4, FADE_OUT);
                        mod.client.warpFxTo(r, v, 2);
                        return;
                    }
                    t.crew.remove(r);
                    if (!r.isOnline()) return;
                    link.fade(r, 0, 2, FADE_OUT);
                    r.sendMessage(net.kyori.adventure.text.Component.text("Перенос вместе с кораблём не удался — вы остались на месте",
                            net.kyori.adventure.text.format.NamedTextColor.RED));
                    mod.logger().warn("корабль {}: {} не перенесён вместе с кораблём ({})", v.id, r.getUsername(), err != null ? err.toString() : "не на карте");
                }));
    }

    /** Тик пути: носитель — по кривой (у пилота мод ведёт по тем же числам), пламя, звук, экипаж — на палубе. */
    private void glide(Trip t, VesselKind k, long now) {
        Vessel v = t.v;
        Glide g = t.glide;
        t.t++;
        double[] p = g.at(t.t);
        v.x = p[0]; v.y = p[1]; v.z = p[2];
        v.rot = t.rot;
        moveCarrier(v);
        if (v.pilot != null) {
            Piloting pl = v.pilot;
            double[] vel = g.velocity(t.t);
            pl.vx = vel[0]; pl.vy = vel[1]; pl.vz = vel[2];
            pl.lastPose = now;
            if (!t.acked || t.t == 1) mod.client.correct(v);                     // старый мод (не ответил на AUTO) — ведём позами
        }
        fx(t, k);
        if (t.t % 4 == 0) keepCrew(t);
        if (t.t >= g.ticks) finish(t, true);
    }

    /** Экипаж отстал от палубы больше чем на 3 блока (старый мод, сорвался) — на место; падения нет. */
    private void keepCrew(Trip t) {
        Vessel v = t.v;
        for (var e : t.crew.entrySet()) {
            Player r = e.getKey();
            double[] l = e.getValue();
            if (l == null || !r.isOnline() || r.getInstance() != t.to) continue;
            double[] w = VesselMath.toWorld(l[0], l[1], l[2], v.pos(), v.rot);
            Pos rp = r.getPosition();
            double dx = rp.x() - w[0], dy = rp.y() - w[1], dz = rp.z() - w[2];
            if (dx * dx + dy * dy + dz * dz > 9) {
                r.teleport(new Pos(w[0], w[1] + 0.05, w[2], rp.yaw(), rp.pitch()));
                r.setVelocity(Vec.ZERO);
            }
            Beams.safe(r, 5000);
        }
    }

    /** Конец пути (arrived — долетел; иначе модуль выключается: сразу в конец). */
    private void finish(Trip t, boolean arrived) {
        Vessel v = t.v;
        v.trip = null;
        mod.server().unpin(t.pin);
        double[] e = t.glide != null ? t.glide.to : t.end;
        if (e != null) { v.x = e[0]; v.y = e[1]; v.z = e[2]; }
        v.rot = t.rot;
        v.tripReady = mod.ticks() + cooldownTicks();
        v.cushionLast = Double.NaN;
        moveCarrier(v);
        if (v.pilot != null && arrived) {
            Piloting pl = v.pilot;
            double[] vel = t.glide != null ? t.glide.velocity(t.glide.ticks) : new double[3];
            pl.vx = vel[0]; pl.vy = vel[1]; pl.vz = vel[2];
            pl.lastPose = mod.ticks();
            pl.strikes = 0;
            mod.client.correct(v);
            mod.client.autoEnd(v, vel);
            mod.client.state(v);
            mod.client.message(pl.player, t.kind == Kind.ENTRY ? "Атмосфера пройдена — высота " + Math.round(v.y) + ". Управление ваше"
                    : "Орбита планеты — управление ваше", false);
        }
        if (arrived) sound(t.to, v, t.kind == Kind.ENTRY ? SoundEvent.BLOCK_BEACON_DEACTIVATE : SoundEvent.BLOCK_BEACON_POWER_SELECT, 1.5f, 0.8f);
        mod.save();
    }

    /** Отменить переход до переноса пилота: корабль и экипаж остаются, экран светлеет, полёт — снова у пилота. */
    private void abort(Trip t, String why) {
        Vessel v = t.v;
        if (v.trip != t) return;
        v.trip = null;
        mod.server().unpin(t.pin);
        v.tripReady = mod.ticks() + cooldownTicks() / 2;
        for (Player p : people(t)) if (p.isOnline()) link.fade(p, 0, 2, FADE_OUT);
        if (v.pilot != null && v.pilot.player == t.pilot && t.pilot.isOnline()) {
            v.pilot.lastPose = mod.ticks();
            if (t.phase.ordinal() >= Phase.MOVE.ordinal()) {                    // мод уже получил END — полёт заново
                mod.client.start(v);
                mod.client.state(v);
                mod.client.hull(v, false, -1);
                mod.client.cameras(v);
            }
            if (why != null) mod.client.message(t.pilot, why, true);
        }
        VesselKind k = mod.kinds().get(v.kind);
        if (t.kind == Kind.ENTRY && k != null && v.carrier != null) push(v, k, t.planet, null);   // не войти снова тут же
    }

    /** Корабль убирают посреди перехода. */
    void cancel(Vessel v) {
        Trip t = v.trip;
        if (t == null) return;
        v.trip = null;
        mod.server().unpin(t.pin);
    }

    /** Модуль выключается: путь — сразу в конец; пилот в пути — корабль следом (на новую карту); иначе — отмена. */
    void stop() {
        for (Vessel v : List.copyOf(mod.vessels())) {
            Trip t = v.trip;
            if (t == null) continue;
            switch (t.phase) {
                case GLIDE, PLACE -> finish(t, false);
                case MOVING -> { relocate(v, t.toMap, t.end, t.rot); v.trip = null; mod.server().unpin(t.pin); }
                default -> abort(t, null);
            }
        }
    }

    // ───────────────────────── мир ─────────────────────────

    /** Пилот и экипаж перехода. */
    private static List<Player> people(Trip t) {
        List<Player> out = new ArrayList<>();
        out.add(t.pilot);
        out.addAll(t.crew.keySet());
        return out;
    }

    /** Носитель — на место корабля (у всех плавно: интерполяция). */
    private void moveCarrier(Vessel v) {
        mod.markDirty();
        if (v.carrier == null || v.carrier.getInstance() == null) return;
        v.carrier.teleport(new Pos(v.x, v.y, v.z));
        float[] r = v.rot.toFloats();
        int interp = mod.config().getInt("others-interp-ticks", 3);
        v.carrier.editEntityMeta(ItemDisplayMeta.class, m -> {
            m.setTransformationInterpolationStartDelta(0);
            m.setTransformationInterpolationDuration(interp);
            m.setLeftRotation(r);
        });
    }

    /** Пламя входа (под днищем и у носа, гаснет к концу) / выхлоп разгона (из кормы, растёт); звук. */
    private void fx(Trip t, VesselKind k) {
        Vessel v = t.v;
        Instance inst = v.carrier != null ? v.carrier.getInstance() : null;
        if (inst == null) return;
        double u = t.progress();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double[] h = k.half;
        if (t.kind == Kind.ENTRY) {
            double heat = Math.max(0, 1 - u * 1.15);
            int n = (int) Math.round(10 * heat);
            for (int i = 0; i < n; i++) {
                double[] w = VesselMath.toWorld((r.nextDouble() * 2 - 1) * h[0], -h[1] - 0.4, (r.nextDouble() * 2 - 1) * h[2], v.pos(), v.rot);
                inst.sendGroupedPacket(new ParticlePacket(i % 3 == 0 ? Particle.LAVA : Particle.FLAME, w[0], w[1], w[2], 0.3f, 0.2f, 0.3f, 0.05f, 2));
            }
            if (heat > 0.05) {
                double[] top = VesselMath.toWorld(0, h[1] + 1, 0, v.pos(), v.rot);
                inst.sendGroupedPacket(new ParticlePacket(Particle.CLOUD, top[0], top[1], top[2], (float) h[0], 1.5f, (float) h[2], 0.02f, (int) Math.round(6 * heat) + 1));
                inst.sendGroupedPacket(new ParticlePacket(Particle.LARGE_SMOKE, top[0], top[1] + 2, top[2], (float) h[0] * 0.6f, 2f, (float) h[2] * 0.6f, 0.01f, 3));
            }
            if (t.t % 15 == 1 && heat > 0.1) sound(inst, v, SoundEvent.ENTITY_BLAZE_BURN, (float) (1 + 2 * heat), 0.5f);
            if (t.t % 20 == 1) sound(inst, v, SoundEvent.ITEM_ELYTRA_FLYING, (float) (0.6 + heat), 0.7f);
        } else {
            double[] tail = VesselMath.toWorld(0, 0, -h[2] - 0.3, v.pos(), v.rot);
            int n = (int) Math.round(3 + 6 * u);
            inst.sendGroupedPacket(new ParticlePacket(Particle.FLAME, tail[0], tail[1], tail[2], 0.4f, 0.4f, 0.4f, 0.04f, n));
            inst.sendGroupedPacket(new ParticlePacket(Particle.SOUL_FIRE_FLAME, tail[0], tail[1], tail[2], 0.3f, 0.3f, 0.3f, 0.03f, n / 2 + 1));
            inst.sendGroupedPacket(new ParticlePacket(Particle.END_ROD, tail[0], tail[1], tail[2], 0.6f, 0.6f, 0.6f, 0.05f, 2));
            if (t.t % 20 == 1) sound(inst, v, SoundEvent.ENTITY_BLAZE_BURN, 2.5f, 0.6f);
        }
    }

    private static void sound(Instance inst, Vessel v, SoundEvent s, float vol, float pitch) {
        if (inst != null) inst.playSound(Sound.sound(s, Sound.Source.NEUTRAL, vol, pitch), v.x, v.y, v.z);
    }
}
