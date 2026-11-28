package ink.hoka.odyssey.vessels;

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.display.ItemDisplayMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.sound.SoundEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Прилёт корабля (/hovessel call &lt;вид&gt; [игрок]): новый корабль игрока прилетает издалека — видимое событие.
 * <ol>
 *   <li>Грузятся кубы вокруг игрока, в фоне ищется место: в космосе — свободное рядом (корпус не в блоках, не накрывает
 *       игрока), на планете — точка зависания на «подушке» рядом с игроком ({@link Cushion#hoverY}).</li>
 *   <li>Грузятся (и держатся) кубы по возможным путям, в фоне ищется путь без блоков по клеткам корпуса
 *       ({@link ArrivalPlan}): космос — прямо из свободного пространства или в обход; планета — сверху.</li>
 *   <li>Полёт 8–12 с: носитель корабля двигается каждый тик (у всех плавно — интерполяция), хвост частиц и звук
 *       двигателя, торможение в конце. Пока летит — сесть и открыть меню нельзя; игрока на пути мягко отталкивает.</li>
 * </ol>
 * Пути нет — корабль появляется на месте варпом (и строка в лог); места нет — корабль не создаётся.
 */
final class Arrivals {

    private enum Phase { LOAD_AREA, FIND_SPOT, LOAD_PATHS, FIND_PATH, DONE }

    /** Вызов: пока ищем место и путь. */
    private static final class Call {
        final Player player;
        final String kind;
        final Instance inst;
        final boolean planet;
        final double[] at;
        final double lookYaw;
        final Object pin = new Object();
        final long started;
        Phase phase = Phase.LOAD_AREA;
        long waitUntil;
        volatile boolean busy;
        volatile double[] spot;
        volatile double spotYaw;
        volatile ArrivalPlan.Path path;
        volatile boolean failed;
        List<List<double[]>> loadPaths = List.of();

        Call(Player player, String kind, Instance inst, boolean planet, long now) {
            this.player = player;
            this.kind = kind;
            this.inst = inst;
            this.planet = planet;
            Pos p = player.getPosition();
            this.at = new double[]{p.x(), p.y() + 1, p.z()};
            this.lookYaw = p.yaw();
            this.started = now;
        }
    }

    /** Полёт корабля к игроку. */
    static final class Flight {
        final List<double[]> pts;
        final double len, endYaw;
        final int total;
        final Player to;
        final Object pin;
        int t, waited;

        Flight(ArrivalPlan.Path p, int total, Player to, Object pin) {
            this.pts = p.points();
            this.len = p.length();
            this.endYaw = p.endYaw();
            this.total = total;
            this.to = to;
            this.pin = pin;
        }

        double[] end() { return pts.get(pts.size() - 1); }
    }

    private final VesselsModule mod;
    private final List<Call> calls = new ArrayList<>();

    Arrivals(VesselsModule mod) { this.mod = mod; }

    double distance() { return Math.max(40, mod.config().getDouble("call.distance", 250)); }

    // ───────────────────────── вызов ─────────────────────────

    /** Начать прилёт корабля вида kind к игроку. Вернуть текст ошибки или null. */
    String call(Player p, String kind) {
        VesselKind k = mod.kinds().get(kind);
        if (k == null) return "Нет вида «" + kind + "»";
        Instance inst = p.getInstance();
        if (inst == null || mod.server().mapName(inst).isEmpty()) return "Этот мир не из maps/ — здесь корабли не сохраняются";
        for (Call c : calls) if (c.player == p) return "К игроку уже летит корабль";
        Call c = new Call(p, kind, inst, mod.isPlanet(inst), mod.ticks());
        double r = k.radius();
        double h = r + (c.planet ? Cushion.Settings.DEFAULT.reach() + mod.clearance() + 8 : 16);
        mod.server().pinCubes(inst, new Vec(c.at[0] - h, c.at[1] - (c.planet ? mod.clearance() + r + 20 : h), c.at[2] - h),
                new Vec(c.at[0] + h, c.at[1] + (c.planet ? 80 : h), c.at[2] + h), c.pin);
        c.waitUntil = mod.ticks() + 100;
        calls.add(c);
        mod.client.message(p, "Корабль на подходе…", false);
        return null;
    }

    /** Каждый тик: вызовы (загрузка, поиск в фоне) и полёты. */
    void tick() {
        for (Call c : List.copyOf(calls)) step(c);
        for (Vessel v : List.copyOf(mod.vessels())) if (v.arrival != null) fly(v);
    }

    private void step(Call c) {
        long now = mod.ticks();
        if (!c.player.isOnline() || c.player.getInstance() != c.inst || now - c.started > 20 * 40) {
            drop(c, c.player.isOnline() ? "Корабль не смог прилететь (долго грузится мир) — попробуйте ещё раз" : null);
            return;
        }
        if (c.busy) return;
        VesselKind k = mod.kinds().get(c.kind);
        if (k == null) { drop(c, "Вид корабля пропал из config.yml"); return; }
        switch (c.phase) {
            case LOAD_AREA -> {
                double r = k.radius() + 8;
                if (now < c.waitUntil && !mod.server().areCubesLoaded(c.inst, new Vec(c.at[0] - r, c.at[1] - r, c.at[2] - r), new Vec(c.at[0] + r, c.at[1] + r, c.at[2] + r))) return;
                c.phase = Phase.FIND_SPOT;
                c.busy = true;
                mod.scheduler().async(() -> {
                    try { findSpot(c, k); } catch (RuntimeException e) { mod.logger().warn("прилёт: место не найдено: {}", e.toString()); }
                    c.busy = false;
                });
            }
            case FIND_SPOT -> {
                if (c.spot == null) {
                    drop(c, "Рядом нет места для корабля");
                    mod.logger().info("прилёт к {}: рядом нет места ({} {} {})", c.player.getUsername(), Math.round(c.at[0]), Math.round(c.at[1]), Math.round(c.at[2]));
                    return;
                }
                c.loadPaths = candidates(c, k);
                for (List<double[]> pts : c.loadPaths) pinPath(c, k, pts);
                c.waitUntil = now + 160;
                c.phase = Phase.LOAD_PATHS;
            }
            case LOAD_PATHS -> {
                if (now < c.waitUntil && !pathsLoaded(c, k)) return;
                c.phase = Phase.FIND_PATH;
                c.busy = true;
                mod.scheduler().async(() -> {
                    try { findPath(c, k); } catch (RuntimeException e) { mod.logger().warn("прилёт: путь не найден: {}", e.toString()); }
                    c.busy = false;
                });
            }
            case FIND_PATH -> launch(c, k);
            case DONE -> { }
        }
    }

    /** Место прибытия (в фоне): космос — свободное у игрока; планета — точка зависания на подушке рядом. */
    private void findSpot(Call c, VesselKind k) {
        double r = k.radius();
        List<double[]> dirs = ArrivalPlan.directions(c.lookYaw, 8);
        double yaw = c.lookYaw + 180;                                           // носом к игроку
        if (!c.planet) {
            c.spot = ArrivalPlan.spotNear(c.at, dirs, new double[]{r + 2, r + 6, r + 12}, new double[]{0, 3, -3, 6, 10},
                    yaw, (x, y, z, yw) -> free(c, k, x, y, z, yw), s -> ArrivalPlan.dist(s, c.at) >= r + 1.5);
            c.spotYaw = yaw;
            return;
        }
        Cushion.Settings cs = mod.cushion();
        double reach = cs.reach();
        int pad = (int) Math.ceil(r + 10 + r + reach + 2);                      // кольца мест до r + 10, корпус, поле до reach
        int x0 = (int) Math.floor(c.at[0]) - pad, z0 = (int) Math.floor(c.at[2]) - pad;
        int y0 = (int) Math.floor(c.at[1] - reach - 6), y1 = (int) Math.ceil(Math.min(topY(c.inst, k), c.at[1] + 70));
        DistField f = DistField.build(x0, y0, z0, 2 * pad + 1, Math.max(1, y1 - y0), 2 * pad + 1, cushionBlocks(c.inst));
        double[] offs = offsets(k, yaw);
        for (double rr : new double[]{r + 4, r + 10}) for (double[] d : dirs) {
            double x = c.at[0] + d[0] * rr, z = c.at[2] + d[2] * rr;
            double hy = Cushion.hoverY(f, offs, x, z, c.at[1] - 4, Math.min(y1 - reach, c.at[1] + 60), 0.5, cs);
            if (Double.isNaN(hy)) continue;
            c.spot = new double[]{x, hy, z};
            c.spotYaw = yaw;
            return;
        }
    }

    /** Возможные пути (для загрузки кубов): по ним же ищется путь. */
    private List<List<double[]>> candidates(Call c, VesselKind k) {
        List<List<double[]>> out = new ArrayList<>();
        List<double[]> dirs = ArrivalPlan.directions(c.lookYaw, 8);
        double dist = distance();
        double[] t = c.spot;
        if (c.planet) {
            double top = topY(c.inst, k), rise = Math.min(dist * 0.5, 140);
            for (double rr : new double[]{rise, rise * 1.5}) for (double[] d : dirs) {
                double y = Math.min(top, t[1] + rr);
                out.add(List.of(new double[]{t[0] + d[0] * dist * 0.6, y, t[2] + d[2] * dist * 0.6}, new double[]{t[0], Math.min(top, t[1] + 12), t[2]}, t));
            }
        } else {
            for (double[] d : dirs) out.add(List.of(new double[]{t[0] + d[0] * dist, t[1] + d[1] * dist, t[2] + d[2] * dist}, t));
            for (int i = 0; i < 2; i++) {                                       // обходы — для первых двух направлений
                double[] d = dirs.get(i), mid = {t[0] + d[0] * dist / 2, t[1], t[2] + d[2] * dist / 2};
                for (double up : new double[]{40, -40}) out.add(List.of(new double[]{t[0] + d[0] * dist, t[1] + up, t[2] + d[2] * dist},
                        new double[]{mid[0], mid[1] + up, mid[2]}, t));
            }
        }
        return out;
    }

    /** Путь (в фоне): первый свободный. */
    private void findPath(Call c, VesselKind k) {
        List<double[]> dirs = ArrivalPlan.directions(c.lookYaw, 8);
        ArrivalPlan.Free free = (x, y, z, yw) -> free(c, k, x, y, z, yw);
        double dist = distance();
        if (c.planet) {
            c.path = ArrivalPlan.planet(c.spot, dirs, dist, Math.min(dist * 0.5, 140), topY(c.inst, k), 12, free);
        } else {
            c.path = ArrivalPlan.space(c.spot, dirs, dist, 40, free);
        }
        if (c.path == null) c.failed = true;
    }

    /** Создать корабль и пустить в полёт (или варпом на место). */
    private void launch(Call c, VesselKind k) {
        c.phase = Phase.DONE;
        calls.remove(c);
        ArrivalPlan.Path p = c.path;
        double[] s = c.spot;
        if (p == null) {                                                        // пути нет — прыжком на место
            Vessel v = mod.create(c.kind, c.inst, s[0], s[1], s[2], Quat.yaw(c.spotYaw), c.player);
            mod.server().unpin(c.pin);
            if (v == null) { mod.client.message(c.player, "Корабль не создан", true); return; }
            warpFx(c.inst, s);
            mod.client.message(c.player, "Корабль прибыл (прыжком: путь закрыт)", false);
            mod.logger().info("корабль {} ({}) для {}: пути прилёта нет — появился варпом на {} {} {}", v.id, c.kind,
                    c.player.getUsername(), Math.round(s[0]), Math.round(s[1]), Math.round(s[2]));
            return;
        }
        double[] st = p.points().get(0);
        double[] d0 = ArrivalPlan.at(p.points(), 0);
        Vessel v = mod.create(c.kind, c.inst, st[0], st[1], st[2], rot(d0[3], d0[4], d0[5], p.endYaw()), c.player);
        if (v == null) { mod.server().unpin(c.pin); mod.client.message(c.player, "Корабль не создан", true); return; }
        int total = ArrivalPlan.duration(p.length(), mod.config().getDouble("call.speed", 25), 160, 240);
        v.arrival = new Flight(p, total, c.player, c.pin);
        c.inst.playSound(Sound.sound(SoundEvent.ITEM_ELYTRA_FLYING, Sound.Source.NEUTRAL, 3f, 0.6f), st[0], st[1], st[2]);
        mod.logger().info("корабль {} ({}) летит к {}: {} блоков, {} с", v.id, c.kind, c.player.getUsername(), Math.round(p.length()), total / 20);
    }

    private void drop(Call c, String why) {
        calls.remove(c);
        c.phase = Phase.DONE;
        mod.server().unpin(c.pin);
        if (why != null && c.player.isOnline()) mod.client.message(c.player, why, true);
    }

    // ───────────────────────── полёт ─────────────────────────

    private void fly(Vessel v) {
        Flight f = v.arrival;
        VesselKind k = mod.kinds().get(v.kind);
        Instance inst = v.carrier != null ? v.carrier.getInstance() : null;
        if (k == null || mod.instanceOf(v) == null) { finish(v, false); return; }
        if (inst == null) {                                                     // носитель ещё ставится — подождать (не вечно)
            if (++f.waited > 200) finish(v, false);
            return;
        }
        f.t++;
        double u = (double) f.t / f.total;
        double[] p = ArrivalPlan.at(f.pts, f.len * ArrivalPlan.ease(u));
        Quat want = u >= 1 ? Quat.yaw(f.endYaw) : rot(p[3], p[4], p[5], f.endYaw);
        v.rot = nlerp(v.rot, want, 0.5);
        v.x = p[0]; v.y = p[1]; v.z = p[2];
        move(v);
        // хвост: пламя и дым из сопел (корма), всем рядом; звук двигателя
        double[] tail = VesselMath.toWorld(0, 0, -k.half[2] - 0.3, v.pos(), v.rot);
        float fade = (float) Math.max(0.2, 1 - u);
        inst.sendGroupedPacket(new ParticlePacket(Particle.FLAME, tail[0], tail[1], tail[2], 0.4f, 0.3f, 0.4f, 0.03f, Math.round(6 * fade) + 1));
        inst.sendGroupedPacket(new ParticlePacket(Particle.SOUL_FIRE_FLAME, tail[0], tail[1], tail[2], 0.3f, 0.2f, 0.3f, 0.02f, Math.round(3 * fade) + 1));
        inst.sendGroupedPacket(new ParticlePacket(Particle.CLOUD, tail[0], tail[1], tail[2], 0.5f, 0.3f, 0.5f, 0.01f, 2));
        if (f.t % 20 == 1) inst.playSound(Sound.sound(SoundEvent.ENTITY_BLAZE_BURN, Sound.Source.NEUTRAL, 2.5f, 0.5f), v.x, v.y, v.z);
        push(v, k, inst);
        if (f.t >= f.total) finish(v, true);
    }

    /** Игрока на пути — мягко прочь (корабль не давит). */
    private void push(Vessel v, VesselKind k, Instance inst) {
        double r = k.radius() + 2;
        for (Player p : inst.getPlayers()) {
            Pos pp = p.getPosition();
            if (Math.abs(pp.x() - v.x) > r || Math.abs(pp.y() - v.y) > r || Math.abs(pp.z() - v.z) > r) continue;
            double d = VesselMath.distanceToBox(pp.x(), pp.y() + 0.9, pp.z(), v.pos(), v.rot, k.half);
            if (d > 0.6) continue;
            double dx = pp.x() - v.x, dz = pp.z() - v.z, l = Math.max(0.3, Math.sqrt(dx * dx + dz * dz));
            p.setVelocity(new Vec(dx / l * 6, 3, dz / l * 6));
            Beams.safe(p, 3000);
        }
    }

    /** Корабль долетел (или модуль выключается: сразу на место). */
    void finish(Vessel v, boolean arrived) {
        Flight f = v.arrival;
        if (f == null) return;
        v.arrival = null;
        double[] e = f.end();
        v.x = e[0]; v.y = e[1]; v.z = e[2];
        v.rot = Quat.yaw(f.endYaw);
        move(v);
        mod.server().unpin(f.pin);
        mod.save();
        if (arrived && f.to.isOnline()) {
            mod.client.message(f.to, "Корабль прибыл", false);
            f.to.sendMessage(Component.text("Корабль #" + v.id + " прибыл — ПКМ по нему: сесть, G: меню.", NamedTextColor.GREEN));
            Instance inst = v.carrier != null ? v.carrier.getInstance() : null;
            if (inst != null) inst.playSound(Sound.sound(SoundEvent.BLOCK_BEACON_DEACTIVATE, Sound.Source.NEUTRAL, 1.5f, 0.8f), v.x, v.y, v.z);
        }
    }

    /** Корабль убирают посреди полёта. */
    void cancel(Vessel v) {
        Flight f = v.arrival;
        if (f == null) return;
        v.arrival = null;
        mod.server().unpin(f.pin);
    }

    /** Модуль выключается: полёты — сразу на место, вызовы — прочь. */
    void stop() {
        for (Vessel v : List.copyOf(mod.vessels())) if (v.arrival != null) finish(v, false);
        for (Call c : List.copyOf(calls)) drop(c, null);
    }

    // ───────────────────────── мир ─────────────────────────

    /** Носитель — на место корабля (у всех плавно: интерполяция), положение — в vessels.json при сохранении. */
    private void move(Vessel v) {
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

    /** Поворот по направлению полёта: курс по горизонтали, наклон носа (не больше 20°). */
    /**
     * Поворот по направлению полёта: только курс (корпус ровный — так же, как путь проверялся на блоки); на отвесном
     * отрезке — курс fallback.
     */
    static Quat rot(double dx, double dy, double dz, double fallback) {
        double h = Math.sqrt(dx * dx + dz * dz);
        return Quat.yaw(h > 1e-3 ? ArrivalPlan.yawOf(dx, dz) : fallback);
    }

    static Quat nlerp(Quat a, Quat b, double t) {
        double dot = a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w();
        double s = dot < 0 ? -1 : 1;
        return new Quat(a.x() + (s * b.x() - a.x()) * t, a.y() + (s * b.y() - a.y()) * t, a.z() + (s * b.z() - a.z()) * t,
                a.w() + (s * b.w() - a.w()) * t).normalized();
    }

    /** Корабль с серединой (x, y, z) и курсом yaw: клетки корпуса не в твёрдом и не в незагруженном, не на игроке. */
    private boolean free(Call c, VesselKind k, double x, double y, double z, double yaw) {
        Instance inst = c.inst;
        int minY = inst.getCachedDimensionType().minY(), maxY = minY + inst.getCachedDimensionType().height();
        double dx = x - c.at[0], dy = y - c.at[1], dz = z - c.at[2];
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) < k.radius() + 1) return false;   // через игрока — нельзя
        Quat q = Quat.yaw(yaw);
        double[] pos = {x, y, z};
        for (int[] h : k.hull) {
            double[] w = VesselMath.toWorld(h[0] + 0.5, h[1] + 0.5, h[2] + 0.5, pos, q);
            int bx = (int) Math.floor(w[0]), by = (int) Math.floor(w[1]), bz = (int) Math.floor(w[2]);
            if (by < minY || by >= maxY) return false;
            if (!mod.server().isCubeLoaded(inst, bx, by, bz)) return false;
            Block b = inst.getBlock(bx, by, bz, Block.Getter.Condition.TYPE);
            if (!b.isAir() && (b.isSolid() || b.isLiquid())) return false;
        }
        return true;
    }

    /** Блоки для подушки на сервере: твёрдые и жидкости; незагруженные — пусто (не мешать зря). */
    DistField.Blocked cushionBlocks(Instance inst) {
        return (x, y, z) -> {
            if (!mod.server().isCubeLoaded(inst, x, y, z)) return false;
            Block b = inst.getBlock(x, y, z, Block.Getter.Condition.TYPE);
            return !b.isAir() && (b.isSolid() || b.isLiquid());
        };
    }

    /** Точки корпуса относительно середины при курсе yaw (мир, по 3 числа). */
    static double[] offsets(VesselKind k, double yaw) {
        Quat q = Quat.yaw(yaw);
        double[] out = new double[k.hull.size() * 3];
        for (int i = 0; i < k.hull.size(); i++) {
            int[] h = k.hull.get(i);
            double[] w = q.rotate(h[0] + 0.5, h[1] + 0.5, h[2] + 0.5);
            out[i * 3] = w[0]; out[i * 3 + 1] = w[1]; out[i * 3 + 2] = w[2];
        }
        return out;
    }

    private static double topY(Instance inst, VesselKind k) {
        int minY = inst.getCachedDimensionType().minY(), maxY = minY + inst.getCachedDimensionType().height();
        return maxY - k.radius() - 2;
    }

    /** Кубы по пути: кусками по 16 блоков, в ширину корабля + запас. */
    private void pinPath(Call c, VesselKind k, List<double[]> pts) {
        double r = k.radius() + 2;
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            double len = ArrivalPlan.dist(a, b);
            int n = Math.max(1, (int) Math.ceil(len / 16));
            for (int j = 0; j < n; j++) {
                double t0 = (double) j / n, t1 = (double) (j + 1) / n;
                double[] p0 = lerp(a, b, t0), p1 = lerp(a, b, t1);
                mod.server().pinCubes(c.inst, new Vec(Math.min(p0[0], p1[0]) - r, Math.min(p0[1], p1[1]) - r, Math.min(p0[2], p1[2]) - r),
                        new Vec(Math.max(p0[0], p1[0]) + r, Math.max(p0[1], p1[1]) + r, Math.max(p0[2], p1[2]) + r), c.pin);
            }
        }
    }

    private boolean pathsLoaded(Call c, VesselKind k) {
        double r = k.radius() + 1;
        for (List<double[]> pts : c.loadPaths) {
            for (int i = 1; i < pts.size(); i++) {
                double[] a = pts.get(i - 1), b = pts.get(i);
                int n = Math.max(1, (int) Math.ceil(ArrivalPlan.dist(a, b) / 16));
                for (int j = 0; j <= n; j++) {
                    double[] p = lerp(a, b, (double) j / n);
                    if (!mod.server().areCubesLoaded(c.inst, new Vec(p[0] - r, p[1] - r, p[2] - r), new Vec(p[0] + r, p[1] + r, p[2] + r))) return false;
                }
            }
        }
        return true;
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    /** Варп-появление: вспышка частиц и звук прыжка. */
    private static void warpFx(Instance inst, double[] s) {
        inst.sendGroupedPacket(new ParticlePacket(Particle.REVERSE_PORTAL, s[0], s[1], s[2], 3f, 2f, 3f, 0.2f, 160));
        inst.sendGroupedPacket(new ParticlePacket(Particle.END_ROD, s[0], s[1], s[2], 2f, 1.5f, 2f, 0.15f, 40));
        inst.playSound(Sound.sound(SoundEvent.ENTITY_ENDERMAN_TELEPORT, Sound.Source.NEUTRAL, 3f, 0.5f), s[0], s[1], s[2]);
        inst.playSound(Sound.sound(SoundEvent.BLOCK_BEACON_ACTIVATE, Sound.Source.NEUTRAL, 2f, 1.8f), s[0], s[1], s[2]);
    }
}
