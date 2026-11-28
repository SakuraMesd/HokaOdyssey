package ink.hoka.odyssey.vessels;

import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Орудие корабля (ЛКМ пилота). Прицел — голубое перекрестие, которое пилот водит мышью (туда же доворачивает нос):
 * сервер ведёт луч от глаз пилота по этому направлению (до gun.range) и находит точку прицела, стволы вида корабля
 * (guns:, по очереди) бьют в эту точку — болты сходятся там, куда смотрит перекрестие. Попадание считает сервер:
 * блоки (незагруженный куб — промах), сущности, чужие корабли (по клеткам корпуса); свой корабль, пилот и стоящие на
 * своём корабле не задеваются. Урон — когда болт долетел (скорость gun.bolt-speed). Блоки по умолчанию не ломаются.
 * Чужой корабль теряет прочность (зона — у точки попадания, {@link HullDamage}). Бой (чужие корабли и игроки) —
 * по {@link PvpRule}: в режиме zones — только если и стрелок, и точка попадания в PvP-зоне survival; иначе болт
 * проходит сквозь корабль/игрока без урона (летит дальше). Мобы — как всегда.
 */
final class Combat {

    private final VesselsModule mod;

    Combat(VesselsModule mod) { this.mod = mod; }

    double range() { return mod.config().getDouble("gun.range", 300); }

    double cooldownSec() { return mod.config().getDouble("gun.cooldown", 0.5); }

    double damage() { return mod.config().getDouble("gun.damage", 6); }

    double boltSpeed() { return Math.max(20, mod.config().getDouble("gun.bolt-speed", 220)); }

    boolean breakBlocks() { return mod.config().getBoolean("gun.break-blocks", false); }

    /** Задержка между выстрелами корабля (тики) с улучшениями. */
    int reloadTicks(Upgrades.Stats st) { return Math.max(1, (int) Math.round(cooldownSec() * 20 * st.gunReload())); }

    /** C2S FIRE: направление прицела пилота (мир). */
    void fire(Player p, double dx, double dy, double dz) {
        Piloting pl = mod.pilotOf(p);
        if (pl == null) return;
        Vessel v = pl.vessel;
        VesselKind k = mod.kinds().get(v.kind);
        Instance inst = v.carrier != null ? v.carrier.getInstance() : null;
        if (k == null || inst == null) return;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(len) || len < 1e-6) return;
        double[] d = {dx / len, dy / len, dz / len};
        double[] nose = v.rot.rotate(0, 0, 1);                                  // прицел не дальше 90° от носа (он и так ≤ 35°)
        if (d[0] * nose[0] + d[1] * nose[1] + d[2] * nose[2] < 0) return;
        long now = mod.ticks();
        if (now < v.gunReady) return;
        Upgrades.Stats st = mod.stats(v);
        v.gunReady = now + reloadTicks(st);

        Set<Object> skip = new HashSet<>();
        skip.add(p);
        skip.addAll(mod.riders(v));
        double range = range();
        double[] eye = mod.seatWorld(v, k);
        // PvP-зоны: стрелок вне зоны — ни кораблей, ни игроков; в зоне — бьём только то, что тоже в зоне
        PvpRule.Mode mode = mod.zones.mode();
        boolean survival = mod.zones.present();
        String playerMode = survival ? mod.zones.playerMode() : null;
        boolean shooterIn = survival && mod.zones.in(inst, eye[0], eye[1], eye[2]);
        boolean ships = PvpRule.fight(mode, survival, shooterIn, true);
        boolean players = PvpRule.fightPlayer(mode, playerMode, shooterIn, true);
        if (!players) skip.addAll(inst.getPlayers());                          // болт игроков не задевает
        List<ShotRay.Target> targets = targets(inst, eye, range + k.radius() + 4, skip);
        List<ShotRay.Hull> hulls = ships ? hulls(v, inst, eye, range + k.radius()) : new ArrayList<>();
        ShotRay.Blocks blocks = blocks(inst);
        double[] aim = cast(eye, d, range, blocks, targets, hulls, inst, mode, survival, playerMode, shooterIn).point(eye, d);

        double[] g = k.guns.get(Math.floorMod(v.nextGun++, k.guns.size()));
        double[] from = VesselMath.toWorld(g[0], g[1], g[2], v.pos(), v.rot);
        double[] dir = {aim[0] - from[0], aim[1] - from[1], aim[2] - from[2]};
        double dl = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        if (dl < 0.5) dir = d;
        else { dir[0] /= dl; dir[1] /= dl; dir[2] /= dl; }
        dir = spread(dir, mod.effects(v).spread());                             // разбитый нос — болты разлетаются шире
        ShotRay.Hit hit = cast(from, dir, range, blocks, targets, hulls, inst, mode, survival, playerMode, shooterIn);
        double[] to = hit.point(from, dir);
        mod.client.shot(inst, v, from, to, hit.kind());

        int delay = (int) Math.round(hit.t() / boltSpeed() * 20);
        float dmg = (float) (damage() * st.gunDamage());
        if (hit.kind() == ShotRay.Kind.ENTITY && hit.ref() instanceof LivingEntity le) {
            mod.scheduler().later(() -> {
                if (le.isRemoved() || le.isDead() || le.getInstance() != inst) return;
                if (le.damage(Damage.fromPlayer(p, dmg)) || le.isDead()) mod.client.hit(p, le.isDead() ? 2 : 1);   // маркер у прицела
            }, Math.max(1, delay));
        } else if (hit.kind() == ShotRay.Kind.VESSEL && hit.ref() instanceof Vessel target) {
            double[] at = to.clone();
            mod.scheduler().later(() -> {                                       // болт долетел: урон кораблю (зона — у точки попадания)
                if (mod.vessel(target.id) != target) return;
                boolean dead = mod.hull.damage(target, dmg, at, -1, p, "огонь " + p.getUsername());
                if (!dead) mod.client.hit(p, 3);
            }, Math.max(1, delay));
        } else if (hit.kind() == ShotRay.Kind.BLOCK && breakBlocks()) {
            int bx = hit.bx(), by = hit.by(), bz = hit.bz();
            mod.scheduler().later(() -> {
                if (!mod.server().isCubeLoaded(inst, bx, by, bz)) return;
                Block b = inst.getBlock(bx, by, bz);
                if (b.isAir() || b.registry().hardness() < 0) return;           // бедрок и т.п. — нет
                inst.setBlock(bx, by, bz, Block.AIR);
            }, Math.max(1, delay));
        }
    }

    /**
     * Луч болта с PvP-зонами: попал в чужой корабль или игрока, а точка попадания вне зоны (режим zones) — эта цель
     * пропускается (болт летит сквозь), луч заново — пока не попадёт в разрешённое.
     */
    private ShotRay.Hit cast(double[] o, double[] d, double max, ShotRay.Blocks blocks, List<ShotRay.Target> targets,
                                    List<ShotRay.Hull> hulls, Instance inst, PvpRule.Mode mode, boolean survival, String playerMode,
                                    boolean shooterIn) {
        ShotRay.Hit hit = ShotRay.cast(o, d, max, blocks, targets, hulls);
        if (!survival || mode != PvpRule.Mode.ZONES) return hit;
        ZoneLink z = mod.zones;
        List<ShotRay.Target> ts = targets;
        List<ShotRay.Hull> hs = hulls;
        for (int i = 0, lim = targets.size() + hulls.size() + 1; i <= lim && z != null; i++) {   // целей конечно — до разрешённой
            boolean player = hit.kind() == ShotRay.Kind.ENTITY && hit.ref() instanceof Player;
            boolean vessel = hit.kind() == ShotRay.Kind.VESSEL;
            if (!player && !vessel) return hit;
            double[] at = hit.point(o, d);
            boolean in = z.in(inst, at[0], at[1], at[2]);
            boolean ok = player ? PvpRule.fightPlayer(mode, playerMode, shooterIn, in) : PvpRule.fight(mode, true, shooterIn, in);
            if (ok) return hit;
            Object ref = hit.ref();
            if (player) { ts = new ArrayList<>(ts); ts.removeIf(t -> t.ref() == ref); }
            else { hs = new ArrayList<>(hs); hs.removeIf(h -> h.ref() == ref); }
            hit = ShotRay.cast(o, d, max, blocks, ts, hs);
        }
        return hit;                                                             // сюда не доходит: каждая проверка убирает цель
    }

    /** Направление со случайным отклонением до spread градусов (0 — как есть). */
    static double[] spread(double[] d, double spreadDeg) {
        if (!(spreadDeg > 0)) return d;
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        double a = Math.toRadians(spreadDeg) * Math.sqrt(r.nextDouble()), phi = r.nextDouble() * Math.PI * 2;
        // базис, перпендикулярный d
        double[] u = Math.abs(d[1]) < 0.9 ? new double[]{d[2], 0, -d[0]} : new double[]{0, -d[2], d[1]};
        double ul = Math.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
        u[0] /= ul; u[1] /= ul; u[2] /= ul;
        double[] w = {d[1] * u[2] - d[2] * u[1], d[2] * u[0] - d[0] * u[2], d[0] * u[1] - d[1] * u[0]};
        double s = Math.sin(a), c = Math.cos(a), cp = Math.cos(phi), sp = Math.sin(phi);
        return new double[]{d[0] * c + (u[0] * cp + w[0] * sp) * s, d[1] * c + (u[1] * cp + w[1] * sp) * s, d[2] * c + (u[2] * cp + w[2] * sp) * s};
    }

    /** Живые сущности рядом (коробки в мире), кроме skip. */
    private static List<ShotRay.Target> targets(Instance inst, double[] c, double r, Set<Object> skip) {
        List<ShotRay.Target> out = new ArrayList<>();
        for (Entity e : inst.getEntities()) {
            if (!(e instanceof LivingEntity le) || le.isDead() || skip.contains(e)) continue;
            Pos p = e.getPosition();
            double dx = p.x() - c[0], dy = p.y() - c[1], dz = p.z() - c[2];
            if (dx * dx + dy * dy + dz * dz > r * r) continue;
            BoundingBox b = e.getBoundingBox();
            out.add(new ShotRay.Target(e, p.x() + b.minX(), p.y() + b.minY(), p.z() + b.minZ(), p.x() + b.maxX(), p.y() + b.maxY(), p.z() + b.maxZ()));
        }
        return out;
    }

    /** Чужие корабли рядом (свой не задеваем). */
    private List<ShotRay.Hull> hulls(Vessel self, Instance inst, double[] c, double r) {
        List<ShotRay.Hull> out = new ArrayList<>();
        for (Vessel o : mod.vessels()) {
            if (o == self || o.carrier == null || o.carrier.getInstance() != inst) continue;
            VesselKind k = mod.kinds().get(o.kind);
            if (k == null) continue;
            double dx = o.x - c[0], dy = o.y - c[1], dz = o.z - c[2];
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) > r + k.radius()) continue;
            out.add(new ShotRay.Hull(o, o.pos(), o.rot, k.half, k.hull));
        }
        return out;
    }

    private ShotRay.Blocks blocks(Instance inst) {
        int minY = inst.getCachedDimensionType().minY(), maxY = minY + inst.getCachedDimensionType().height();
        return (x, y, z) -> {
            if (y < minY || y >= maxY) return ShotRay.Blocks.AIR;
            if (!mod.server().isCubeLoaded(inst, x, y, z)) return ShotRay.Blocks.UNLOADED;
            Block b = inst.getBlock(x, y, z);
            return !b.isAir() && b.isSolid() ? ShotRay.Blocks.SOLID : ShotRay.Blocks.AIR;
        };
    }
}
