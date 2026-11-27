package ink.hoka.odyssey.client.vessels;

import ink.hoka.odyssey.client.api.ClientContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Твёрдый корпус кораблей для своего игрока: по кораблю можно ходить и в него упираться (и в креатив-полёте).
 * Столкновения считает ядро мода (ClientContext.onCollision): тело корабля — его система (середина модели, поворот
 * корабля), фигуры — мелкие клетки корпуса по полблока ({@link Kind#fine}), ступенька 0.5 — игрок по ним шагает.
 * <p>
 * Стоит на корабле — корабль везёт его: перед движением игрока (onLocalTravel) ноги переносятся так же, как сдвинулся
 * и повернулся корабль за тик (вместе с курсом взгляда). Корабль влетел в игрока — тот же перенос: игрока толкает
 * корпус, а не протаскивает насквозь. Поза корабля для столкновений и переноса — на конец тика (снимается раз в тик).
 */
final class Decks {

    /** Корабль, как его видит этот класс: поза на конец тика (pos, rot) и вид. */
    interface Ship {
        int id();

        Kind kind();

        /** Поза на конец тика (pt = 1); нет носителя — false. */
        boolean poseNow(Vector3d pos, org.joml.Quaternionf rot);
    }

    /** Поза корабля в прошлом и этом тике. */
    private static final class Track {
        final Vector3d p0 = new Vector3d(), p1 = new Vector3d();
        final Quaterniond q0 = new Quaterniond(), q1 = new Quaterniond();
        boolean has0, has1;
        Kind kind;
    }

    private final Map<Integer, Track> tracks = new HashMap<>();
    /** На каком корабле стоит игрок (по концу прошлого тика), -1 — ни на каком. */
    private int standing = -1;
    boolean solid = true, carry = true;
    /** Свой корабль пилота — не тело (пилот сидит внутри корпуса). */
    int own = -1;

    // ───────────────────────── позы ─────────────────────────

    /** Раз в тик, перед движением игрока: новые позы кораблей. */
    void sample(Iterable<? extends Ship> ships) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        Vector3d p = new Vector3d();
        org.joml.Quaternionf q = new org.joml.Quaternionf();
        for (Ship s : ships) {
            if (s.kind() == null || !s.poseNow(p, q)) continue;
            Track t = tracks.computeIfAbsent(s.id(), k -> new Track());
            if (t.has1) { t.p0.set(t.p1); t.q0.set(t.q1); t.has0 = true; }
            t.p1.set(p);
            t.q1.set(q.x, q.y, q.z, q.w).normalize();
            t.has1 = true;
            if (t.kind != s.kind()) { t.kind = s.kind(); t.has0 = false; }
            seen.add(s.id());
        }
        tracks.keySet().retainAll(seen);
    }

    void clear() {
        tracks.clear();
        standing = -1;
    }

    // ───────────────────────── столкновения ─────────────────────────

    /** Тела кораблей рядом с областью (ядро спрашивает при движении сущности). */
    void collide(Entity e, AABB area, List<ClientContext.Body> out) {
        if (!solid || tracks.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (e != mc.player) return;                                             // остальные сущности двигает сервер
        Vec3 c = area.getCenter();
        double rad = Math.sqrt(area.getXsize() * area.getXsize() + area.getYsize() * area.getYsize() + area.getZsize() * area.getZsize()) / 2 + 1;
        for (var en : tracks.entrySet()) {
            Track t = en.getValue();
            if (!t.has1 || en.getKey() == own || t.kind == null || t.kind.fine.isEmpty()) continue;
            if (t.p1.distance(c.x, c.y, c.z) > t.kind.radius() + rad) continue;
            Matrix4d toWorld = new Matrix4d().translation(t.p1).rotate(t.q1);
            Kind k = t.kind;
            out.add(new ClientContext.Body() {
                @Override public org.joml.Matrix4fc toWorld() { return new Matrix4f(toWorld); }
                @Override public Matrix4d toWorldD() { return toWorld; }
                @Override public List<VoxelShape> shapes(AABB a) { return cells(k, a); }
            });
        }
    }

    /** Мелкие клетки корпуса в области (система корабля) — коробки по полблока. */
    static List<VoxelShape> cells(Kind k, AABB a) {
        List<VoxelShape> list = new ArrayList<>();
        java.util.Set<Long> f = k.fine;
        int x0 = (int) Math.floor(a.minX * 2), x1 = (int) Math.floor(a.maxX * 2);
        int y0 = (int) Math.floor(a.minY * 2), y1 = (int) Math.floor(a.maxY * 2);
        int z0 = (int) Math.floor(a.minZ * 2), z1 = (int) Math.floor(a.maxZ * 2);
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 20_000) return list;   // огромная область — не от игрока
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++)
            if (f.contains(Kind.fineKey(x, y, z))) list.add(Shapes.box(x / 2.0, y / 2.0, z / 2.0, (x + 1) / 2.0, (y + 1) / 2.0, (z + 1) / 2.0));
        return list;
    }

    // ───────────────────────── перенос ─────────────────────────

    /** Перед движением своего игрока: стоит на корабле или корабль в него влетел — сдвинуть вместе с кораблём. */
    void travel(LocalPlayer p) {
        if (!carry || own >= 0 || p.isSpectator() || p.isPassenger()) return;
        for (var en : tracks.entrySet()) {
            Track t = en.getValue();
            if (!t.has0 || !t.has1 || en.getKey() == own || t.kind == null) continue;
            if (t.p0.distanceSquared(t.p1) < 1e-10 && t.q0.equals(t.q1, 1e-7)) continue;   // корабль стоит
            Vec3 feet = p.position();
            Vector3d l0 = local(t.p0, t.q0, feet);
            boolean on = en.getKey() == standing;
            boolean hit = !on && intersects(t, p, t.p1, t.q1) && !intersects(t, p, t.p0, t.q0);
            if (!on && !hit) continue;
            Vector3d nf = new Quaterniond(t.q1).transform(new Vector3d(l0)).add(t.p1);
            double dyaw = Math.IEEEremainder(yaw(t.q1) - yaw(t.q0), Math.PI * 2);
            if (nf.distanceSquared(feet.x, feet.y, feet.z) > 64 * 64) continue;      // скачок (варп, телепорт) — не тащить
            p.setPos(nf.x, nf.y, nf.z);
            if (on && Math.abs(dyaw) > 1e-4) {
                float d = (float) Math.toDegrees(dyaw);
                p.setYRot(p.getYRot() + d);
                p.yRotO += d;
                p.setYHeadRot(p.getYHeadRot() + d);
                p.yBodyRot += d;
            }
            return;
        }
    }

    /** После движения: на каком корабле стоит (ноги над корпусом и на земле; в прыжке над палубой — тот же). */
    void travelEnd(LocalPlayer p) {
        int was = standing;
        standing = -1;
        if (!carry || own >= 0) return;
        for (var en : tracks.entrySet()) {
            Track t = en.getValue();
            if (!t.has1 || en.getKey() == own || t.kind == null || t.kind.fine.isEmpty()) continue;
            Vector3d l = local(t.p1, t.q1, p.position());
            if (Math.abs(l.x) > t.kind.half[0] + 1 || Math.abs(l.z) > t.kind.half[2] + 1 || Math.abs(l.y) > t.kind.half[1] + 3) continue;
            boolean onNow = p.onGround() && t.kind.standsOn(l.x, l.y, l.z, 0.35);
            boolean stay = en.getKey() == was && t.kind.standsOn(l.x, l.y, l.z, 2.5);     // подпрыгнул над палубой
            if (onNow || stay) { standing = en.getKey(); return; }
        }
    }

    /** Стоит ли игрок на этом корабле (для HUD/подсказок). */
    int standing() { return standing; }

    private static Vector3d local(Vector3d c, Quaterniond q, Vec3 w) {
        return new Quaterniond(q).conjugate().transform(new Vector3d(w.x - c.x, w.y - c.y, w.z - c.z));
    }

    /** Коробка игрока (мир) задевает мелкие клетки корабля в позе (c, q) — грубо: по коробке углов в системе корабля. */
    private static boolean intersects(Track t, LocalPlayer p, Vector3d c, Quaterniond q) {
        AABB b = p.getBoundingBox().deflate(0.05);
        double ax = Double.MAX_VALUE, ay = ax, az = ax, bx = -ax, by = -ax, bz = -ax;
        Quaterniond inv = new Quaterniond(q).conjugate();
        Vector3d v = new Vector3d();
        for (int i = 0; i < 8; i++) {
            v.set((i & 1) != 0 ? b.maxX : b.minX, (i & 2) != 0 ? b.maxY : b.minY, (i & 4) != 0 ? b.maxZ : b.minZ).sub(c);
            inv.transform(v);
            ax = Math.min(ax, v.x); ay = Math.min(ay, v.y); az = Math.min(az, v.z);
            bx = Math.max(bx, v.x); by = Math.max(by, v.y); bz = Math.max(bz, v.z);
        }
        return t.kind.fineIn(ax, ay, az, bx - 1e-6, by - 1e-6, bz - 1e-6);
    }

    /** Курс носа (рад, по-игровому: atan2(−x, z)). */
    private static double yaw(Quaterniond q) {
        Vector3d f = q.transform(new Vector3d(0, 0, 1));
        return Math.atan2(-f.x, f.z);
    }
}
