package ink.hoka.odyssey.vessels;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.WorldBorder;
import net.minestom.server.instance.block.Block;

import java.util.List;

/**
 * Варп-прыжок (R пилота): на warp.distance вперёд по носу, стоит warp.cost полного бака (× улучшения «Бак+»).
 * Зарядка warp.charge секунд (R ещё раз — отмена), за это время грузятся кубы в точке прыжка. Потом — безопасная
 * точка ({@link WarpMath}: граница карты, высота мира, сдвиг из блоков по клеткам корпуса), списание топлива,
 * перенос корабля, пилота и тех, кто стоит на корабле. Перезарядка — warp.cooldown (× «Варп-катушка»).
 */
final class WarpDrive {

    static final int IDLE = 0, CHARGING = 1, LOADING = 2;

    private final VesselsModule mod;

    WarpDrive(VesselsModule mod) { this.mod = mod; }

    double distance() { return mod.config().getDouble("warp.distance", 10000); }

    double costFraction() { return mod.config().getDouble("warp.cost", 0.25); }

    int chargeTicks() { return Math.max(1, (int) Math.round(mod.config().getDouble("warp.charge", 3) * 20)); }

    int reloadTicks(Upgrades.Stats st) { return (int) Math.round(mod.config().getDouble("warp.cooldown", 30) * 20 * st.warpReload()); }

    private double maxShift() { return mod.config().getDouble("warp.max-shift", 32); }

    private double minJump() { return mod.config().getDouble("warp.min-jump", 64); }

    /** C2S WARP: начать зарядку или отменить. */
    void toggle(Player p) {
        Piloting pl = mod.pilotOf(p);
        if (pl == null) return;
        Vessel v = pl.vessel;
        VesselKind k = mod.kinds().get(v.kind);
        Instance inst = v.carrier != null ? v.carrier.getInstance() : null;
        if (k == null || inst == null) return;
        if (v.warpPhase != IDLE) { cancel(v, "Варп отменён"); return; }
        if (mod.effects(v).warpBroken()) { mod.client.message(p, "Варп-привод повреждён — нужен ремонт корпуса", true); return; }
        long now = mod.ticks();
        if (now < v.warpReady) { mod.client.message(p, "Варп перезаряжается: " + secs(v.warpReady - now) + " с", true); return; }
        VesselsModule.Status s = mod.status(v);
        if (s.fuel() < s.warpCost()) { mod.client.message(p, "Мало топлива для варпа: нужно " + s.warpCost() + ", в баке " + s.fuel(), true); return; }
        double[] fwd = v.rot.rotate(0, 0, 1);
        WarpMath.Bounds b = bounds(inst, k);
        double t = WarpMath.reach(v.pos(), fwd, distance(), b);
        if (t < minJump()) { mod.client.message(p, "Варп: впереди граница карты", true); return; }
        double[] raw = {v.x + fwd[0] * t, v.y + fwd[1] * t, v.z + fwd[2] * t};
        v.warpTarget = raw;
        v.warpFrom = v.pos();
        v.warpDir = fwd;
        mod.server().loadCubes(inst, corner(raw, k, -1), corner(raw, k, 1));     // пока заряжается — грузится точка прыжка
        v.warpPhase = CHARGING;
        v.warpAt = now + chargeTicks();
        mod.client.warpFx(v, 1, chargeTicks());
        mod.client.state(v);
    }

    /** Отменить зарядку (пилот вышел, R ещё раз, не вышло). */
    void cancel(Vessel v, String why) {
        if (v.warpPhase == IDLE) return;
        v.warpPhase = IDLE;
        v.warpTarget = null;
        mod.client.warpFx(v, 0, 0);
        if (v.pilot != null && why != null) mod.client.message(v.pilot.player, why, true);
        mod.client.state(v);
    }

    /** Каждый тик для корабля с пилотом. */
    void tick(Vessel v) {
        if (v.warpPhase != IDLE && mod.effects(v).warpBroken()) { cancel(v, "Варп-привод повреждён"); return; }   // корпус разбился иначе (команда, конфиг)
        if (v.warpPhase == IDLE) return;
        long now = mod.ticks();
        if (v.warpPhase == CHARGING) {
            if (now < v.warpAt) return;
            v.warpPhase = LOADING;
            v.warpWaitUntil = now + Math.round(mod.config().getDouble("warp.wait-load", 10) * 20);
        }
        VesselKind k = mod.kinds().get(v.kind);
        Instance inst = v.carrier != null ? v.carrier.getInstance() : null;
        if (k == null || inst == null || v.warpTarget == null) { cancel(v, "Варп сорвался"); return; }
        double[] raw = v.warpTarget;
        double sh = maxShift() + 2;
        Vec lo = corner(raw, k, -1).sub(sh, sh, sh), hi = corner(raw, k, 1).add(sh, sh, sh);
        if (!mod.server().ensureCubes(inst, lo, hi)) {
            if (now > v.warpWaitUntil) cancel(v, "Варп: точка прыжка не загрузилась");
            return;
        }
        if (v.warpFrom == null || v.warpDir == null) { cancel(v, "Варп сорвался"); return; }
        WarpMath.Result r = WarpMath.target(v.warpFrom, v.warpDir, distance(), bounds(inst, k), minJump(), maxShift(), 1,
                (x, y, z) -> free(inst, k, new double[]{x, y, z}, v.rot));
        if (r.at() == null) { cancel(v, "Варп: " + r.why()); return; }
        VesselsModule.Status s = mod.status(v);
        if (!v.cargo.consumeFuel(s.warpCost())) { cancel(v, "Мало топлива для варпа"); return; }
        mod.cargoChanged(v);
        jump(v, k, inst, r.at());
    }

    /** Перенести корабль, пилота и стоящих на корабле. */
    private void jump(Vessel v, VesselKind k, Instance inst, double[] at) {
        List<Player> riders = mod.riders(v);
        double dx = at[0] - v.x, dy = at[1] - v.y, dz = at[2] - v.z;
        v.x = at[0]; v.y = at[1]; v.z = at[2];
        v.warpPhase = IDLE;
        v.warpTarget = null;
        v.warpReady = mod.ticks() + reloadTicks(mod.stats(v));
        if (v.carrier != null) v.carrier.teleport(new Pos(v.x, v.y, v.z));
        if (v.pilot != null) {
            Player p = v.pilot.player;
            double[] eye = mod.seatWorld(v, k);
            p.teleport(new Pos(eye[0], eye[1] - p.getEyeHeight(), eye[2], p.getPosition().yaw(), p.getPosition().pitch()));
            v.pilot.lastPose = mod.ticks();
            mod.client.correct(v);                                              // мод пилота — сразу на новое место
        }
        for (Player r : riders) r.teleport(r.getPosition().add(dx, dy, dz));
        mod.client.warpFx(v, 2, 0);
        for (Player r : riders) mod.client.warpFxTo(r, v, 2);
        mod.client.state(v);
        mod.save();
        mod.logger().info("корабль {}: варп на {} {} {}", v.id, Math.round(v.x), Math.round(v.y), Math.round(v.z));
    }

    /** Корабль в точке c (поворот q) не задевает твёрдых и незагруженных блоков — по всем клеткам корпуса. */
    boolean free(Instance inst, VesselKind k, double[] c, Quat q) {
        for (int[] h : k.hull) {
            double[] w = VesselMath.toWorld(h[0] + 0.5, h[1] + 0.5, h[2] + 0.5, c, q);
            int bx = (int) Math.floor(w[0]), by = (int) Math.floor(w[1]), bz = (int) Math.floor(w[2]);
            if (!mod.server().isCubeLoaded(inst, bx, by, bz)) return false;
            Block b = inst.getBlock(bx, by, bz);
            if (!b.isAir() && b.isSolid()) return false;
        }
        return true;
    }

    /** Рамка для середины корабля: граница карты и высота мира с запасом в радиус корабля + 2. */
    static WarpMath.Bounds bounds(Instance inst, VesselKind k) {
        WorldBorder wb = inst.getWorldBorder();
        int minY = inst.getCachedDimensionType().minY(), maxY = minY + inst.getCachedDimensionType().height();
        return WarpMath.Bounds.of(wb.diameter(), wb.centerX(), wb.centerZ(), minY, maxY, k.radius() + 2);
    }

    private static Vec corner(double[] c, VesselKind k, int s) {
        double r = k.radius() + 1;
        return new Vec(c[0] + s * r, c[1] + s * r, c[2] + s * r);
    }

    private static long secs(long ticks) { return (ticks + 19) / 20; }
}
