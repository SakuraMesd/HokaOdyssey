package ink.hoka.odyssey.client.ships;

import ink.hoka.odyssey.client.api.CameraState;
import ink.hoka.odyssey.client.api.ClientContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/**
 * Гравитация корабля: внутри корабля игрока всегда тянет к его полу — под любым углом, хоть вверх ногами.
 * Пока игрок внутри, его движение считает этот класс в системе корабля (как игра: разгон, трение, прыжок, тяжесть
 * 0.08), взгляд — тоже в системе корабля (курс/наклон относительно палубы), камера повёрнута вместе с кораблём.
 * Игре остаётся только сдвинуть игрока (столкновения с кораблём — в ядре, в той же системе).
 * Вышел из корабля — скорость и взгляд отдаются игре как есть.
 */
final class ShipGravity {

    /** Корабль, в котором игрок: его система → мир (на конец тика и на кадр) и блоки. */
    interface Ship {
        Matrix4f place();

        Matrix4f placeAt(float partialTick);

        ShipMesh mesh();
    }

    private final ClientContext ctx;
    private Ship on;
    /** Ноги в системе корабля в прошлый тик (настоящий сдвиг = разница, со всеми упорами). */
    private Vector3d last;
    private boolean ground;
    /** Взгляд в системе корабля: курс и наклон относительно палубы (как у игры). */
    private float lyaw, lpitch;
    // ввод этого тика
    private float fwd, strafe;
    private boolean jump, sneak, sprint;
    /** Пауза между прыжками при зажатом пробеле (как у игры — 10 тиков). */
    private int jumpDelay;
    /** Пройдено по палубе с прошлого шага (звук шага — каждые ~1.7 блока, как у игры). */
    private double walked;

    ShipGravity(ClientContext ctx) { this.ctx = ctx; }

    boolean active() { return on != null; }

    boolean isOn(ShipMesh m) { return on != null && on.mesh() == m; }

    /** Поворот корабля, в котором игрок (для глаз), или null. */
    org.joml.Matrix3f rotation() { return on == null ? null : on.place().get3x3(new org.joml.Matrix3f()); }

    // ───────────────────────── вход / выход ─────────────────────────

    /** Точка (ноги, мир) внутри коробки блоков корабля с запасом m. */
    static boolean inside(Ship s, Vec3 pos, float m) {
        Vector3d lp = local(s.place(), pos);
        ShipMesh mesh = s.mesh();
        return lp.x > mesh.lo[0] - m && lp.x < mesh.hi[0] + m && lp.y > mesh.lo[1] - m - 0.5 && lp.y < mesh.hi[1] + m
                && lp.z > mesh.lo[2] - m && lp.z < mesh.hi[2] + m;
    }

    /** Поворот корабля, в котором игрок, на кадр (для модели игрока), или null. */
    Quaternionf shipRotation(float pt) {
        return on == null ? null : on.placeAt(pt).getNormalizedRotation(new Quaternionf());
    }

    /** Каждый тик: игрок внутри какого корабля (по коробке его блоков) — с запасом, чтобы не дёргало на краю. */
    void select(Iterable<Ship> ships, boolean piloting) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || piloting || p.isSpectator() || p.getAbilities().flying || p.isPassenger()) { leave(p, !piloting); return; }
        Ship found = null;
        for (Ship s : ships) {
            if (inside(s, p.position(), on != null && on.mesh() == s.mesh() ? 0.6f : 0.1f)) { found = s; break; }
        }
        if (found == null) { leave(p, true); return; }
        if (on == null || on.mesh() != found.mesh()) enter(p, found);
        else on = found;
        look(p);
        p.resetFallDistance();
        p.setNoGravity(true);                                                   // тяжесть — наша (ядро не прижимает второй раз)
    }

    private void enter(LocalPlayer p, Ship s) {
        if (on != null) leave(p, true);
        on = s;
        Matrix4d w = new Matrix4d(s.place());
        Matrix3d inv = w.get3x3(new Matrix3d()).transpose();
        // взгляд игры → в систему корабля
        Vector3d d = inv.transform(dir(p.getYRot(), p.getXRot()));
        lyaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        lpitch = (float) Math.toDegrees(Math.asin(Math.clamp(-d.y, -1, 1)));
        Vec3 dm = p.getDeltaMovement();
        Vector3d lv = inv.transform(new Vector3d(dm.x, dm.y, dm.z));
        last = local(s.place(), p.position()).sub(lv);
        ground = false;
        ctx.send(new byte[]{3, 1});                                             // серверу: внутри — вакуум не трогает
        ctx.logger().info("[гравитация корабля] внутри");
    }

    private void leave(LocalPlayer p, boolean keepSpeed) {
        if (on == null) return;
        if (p != null) p.setNoGravity(false);
        on = null;
        last = null;
        ctx.send(new byte[]{3, 0});
        ctx.logger().info("[гравитация корабля] снаружи");
    }

    // ───────────────────────── ввод и мышь ─────────────────────────

    /** Ввод игры — забрать себе (ходим сами, в системе корабля), игре — ничего (иначе пошла бы по миру). */
    void input(ClientInput in) {
        if (on == null) return;
        Input k = in.keyPresses;
        fwd = (k.forward() ? 1 : 0) - (k.backward() ? 1 : 0);
        strafe = (k.left() ? 1 : 0) - (k.right() ? 1 : 0);
        jump = k.jump();
        sneak = k.shift();
        sprint = k.sprint() && fwd > 0 && !sneak;
        in.keyPresses = new Input(false, false, false, false, false, sneak, false);   // присед — игре (поза), ход — наш
        ctx.setMove(in, 0, 0);
    }

    /** Мышь — взгляд в системе корабля. */
    boolean mouse(double dyaw, double dpitch) {
        if (on == null) return false;
        lyaw += (float) dyaw;
        lpitch = Math.clamp(lpitch + (float) dpitch, -90f, 90f);
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) look(p);
        return true;
    }

    /** Взгляд игрока в мире — из взгляда в системе корабля (для сервера, луча, модели игрока). */
    private void look(LocalPlayer p) {
        Matrix3d r = new Matrix4d(on.place()).get3x3(new Matrix3d());
        Vector3d d = r.transform(dir(lyaw, lpitch));
        float wy = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), wp = (float) Math.toDegrees(Math.asin(Math.clamp(-d.y, -1, 1)));
        wy = p.getYRot() + Mth.wrapDegrees(wy - p.getYRot());                    // без скачка через ±180
        p.setYRot(wy);
        p.setXRot(wp);
        p.setYHeadRot(wy);
    }

    // ───────────────────────── движение ─────────────────────────

    /** Игрока сдвинули не ходьбой (страховка вытолкнула из блока) — не считать это скоростью. */
    void moved(double wx, double wy, double wz) {
        if (on == null || last == null) return;
        Matrix3d inv = new Matrix4d(on.place()).get3x3(new Matrix3d()).transpose();
        last.add(inv.transform(new Vector3d(wx, wy, wz)));
    }

    /** Перед движением игрока: скорость в системе корабля (как у игры) → в мир. */
    void travel(LocalPlayer p) {
        if (on == null) return;
        Matrix4d w = new Matrix4d(on.place());
        Matrix3d rot = w.get3x3(new Matrix3d());
        Vector3d lp = local(on.place(), p.position());
        Vector3d disp = last == null ? new Vector3d() : new Vector3d(lp).sub(last);
        double fr = ground ? 0.546 : 0.91;                                      // трение прошлого тика (как у игры)
        double hx = disp.x * fr, hz = disp.z * fr, vy = (disp.y - 0.08) * 0.98;
        boolean g = grounded(lp, p.getBbHeight());
        if (jumpDelay > 0) jumpDelay--;
        if (!jump) jumpDelay = 0;
        if (g && jump && jumpDelay == 0) {
            jumpDelay = 10;
            vy = 0.42;
            if (sprint) {
                double a = Math.toRadians(lyaw);
                hx += -Math.sin(a) * 0.2;
                hz += Math.cos(a) * 0.2;
            }
        }
        // разгон от клавиш — как у игры: вперёд/вбок по курсу взгляда в системе корабля
        double fx = strafe, fz = fwd;
        double len = Math.sqrt(fx * fx + fz * fz);
        if (len > 1) { fx /= len; fz /= len; }                                  // по диагонали не быстрее (как у игры)
        fx *= 0.98; fz *= 0.98;
        if (sneak) { fx *= 0.3; fz *= 0.3; }
        double speed = g ? (sprint ? 0.13 : 0.1) : (sprint ? 0.026 : 0.02);
        double a = Math.toRadians(lyaw), s = Math.sin(a), c = Math.cos(a);
        hx += (fx * c - fz * s) * speed;
        hz += (fz * c + fx * s) * speed;
        // присед у края — как у игры: не сходить с палубы (уменьшать шаг, пока под ногами есть корабль)
        if (sneak && g) {
            double step = 0.05;
            while (hx != 0 && !grounded(new Vector3d(lp).add(hx, 0, 0), p.getBbHeight())) hx = Math.abs(hx) < step ? 0 : hx - Math.copySign(step, hx);
            while (hz != 0 && !grounded(new Vector3d(lp).add(0, 0, hz), p.getBbHeight())) hz = Math.abs(hz) < step ? 0 : hz - Math.copySign(step, hz);
            while (hx != 0 && hz != 0 && !grounded(new Vector3d(lp).add(hx, 0, hz), p.getBbHeight())) {
                hx = Math.abs(hx) < step ? 0 : hx - Math.copySign(step, hx);
                hz = Math.abs(hz) < step ? 0 : hz - Math.copySign(step, hz);
            }
        }
        // шаги: по пройденному по палубе, звук — блока корабля под ногами (игра ищет его в мире и не находит)
        if (g && !sneak) {
            walked += Math.sqrt(disp.x * disp.x + disp.z * disp.z) * 0.6;
            if (walked > 1) {
                walked = 0;
                ShipMesh m = on.mesh();
                var st = m.state((int) Math.round(lp.x), (int) Math.floor(lp.y + m.seatH - 0.05), (int) Math.round(lp.z));
                if (st != null) {
                    var snd = st.getSoundType();
                    Minecraft.getInstance().level.playLocalSound(p.getX(), p.getY(), p.getZ(), snd.getStepSound(),
                            net.minecraft.sounds.SoundSource.PLAYERS, snd.getVolume() * 0.15f, snd.getPitch(), false);
                }
            }
        }
        Vector3d wv = rot.transform(new Vector3d(hx, vy, hz));
        p.setDeltaMovement(wv.x, wv.y, wv.z);
        p.setSprinting(sprint);
        ground = g;
        last = lp;
    }

    /** Под ногами (в системе корабля) — блок корабля. */
    private boolean grounded(Vector3d lp, float height) {
        ShipMesh m = on.mesh();
        AABB probe = new AABB(lp.x - 0.299, lp.y - 0.05, lp.z - 0.299, lp.x + 0.299, lp.y + 0.0005, lp.z + 0.299);
        int x0 = (int) Math.floor(probe.minX + 0.5), x1 = (int) Math.floor(probe.maxX + 0.5);
        int y0 = (int) Math.floor(probe.minY + m.seatH), y1 = (int) Math.floor(probe.maxY + m.seatH);
        int z0 = (int) Math.floor(probe.minZ + 0.5), z1 = (int) Math.floor(probe.maxZ + 0.5);
        for (int dy = y0; dy <= y1; dy++) for (int dz = z0; dz <= z1; dz++) for (int dx = x0; dx <= x1; dx++) {
            var sh = m.shape(dx, dy, dz);
            if (sh == null) continue;
            for (AABB b : sh.toAabbs()) if (b.intersects(probe)) return true;
        }
        return false;
    }

    // ───────────────────────── камера ─────────────────────────

    /** Поворот камеры — вместе с кораблём (w — вес перехода 0..1). Положение глаз ставит ShipsClientModule. */
    void camera(CameraState s, float pt, float w) {
        if (on == null) return;
        if (s.detached) { orbit(s, pt); return; }
        if (w <= 0) return;
        Quaternionf ship = on.placeAt(pt).getNormalizedRotation(new Quaternionf());
        Quaternionf view = new Quaternionf(ship).rotateYXZ((float) Math.PI - (float) Math.toRadians(lyaw), (float) -Math.toRadians(lpitch), 0);
        try {
            if (w < 1) {
                Quaternionf game = new Quaternionf().rotationYXZ((float) Math.PI - (float) Math.toRadians(s.yaw), (float) -Math.toRadians(s.pitch), 0);
                view = game.slerp(view, w);
            }
            s.rotation = view;
        } catch (LinkageError old) {                                            // ядро до 0.21 — без полного поворота
        }
    }

    /**
     * Вид от третьего лица (F5): камера облетает игрока в системе корабля — от глаз (по «вверх» корабля) назад по
     * взгляду на 4 блока, ближе, если мешают блоки корабля или мира. Вид спереди — то же, развёрнутое к игроку.
     */
    private void orbit(CameraState s, float pt) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        Matrix4f m = on.placeAt(pt);
        Matrix4d md = new Matrix4d(m), toLocal = new Matrix4d(md).invert();
        Vec3 feet = p.getPosition(pt);
        Vector3d lf = toLocal.transformPosition(new Vector3d(feet.x, feet.y, feet.z));
        Vector3d leye = new Vector3d(lf.x, lf.y + p.getEyeHeight(), lf.z);
        boolean front = mc.options.getCameraType() == net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
        float yaw = front ? lyaw + 180 : lyaw, pitch = front ? -lpitch : lpitch;
        // камера смотрит по взгляду — значит стоит позади, против него
        Vector3d back = dir(yaw, pitch).negate();
        double want = 4.0 * p.getScale();
        double dist = shipClear(leye, back, want);
        Vector3d weye = md.transformPosition(new Vector3d(leye));
        Vector3d wback = md.transformDirection(new Vector3d(back));
        // мир: луч от глаз назад
        Vec3 from = new Vec3(weye.x, weye.y, weye.z), to = from.add(wback.x * dist, wback.y * dist, wback.z * dist);
        var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.VISUAL,
                net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) dist = Math.max(0, hit.getLocation().distanceTo(from) - 0.1);
        s.x = weye.x + wback.x * dist;
        s.y = weye.y + wback.y * dist;
        s.z = weye.z + wback.z * dist;
        Quaternionf ship = m.getNormalizedRotation(new Quaternionf());
        Quaternionf view = new Quaternionf(ship).rotateYXZ((float) Math.PI - (float) Math.toRadians(yaw), (float) -Math.toRadians(pitch), 0);
        try {
            s.rotation = view;
        } catch (LinkageError old) {
        }
    }

    /** Насколько можно отойти от точки from по dir (система корабля) до блоков корабля, не дальше max. */
    private double shipClear(Vector3d from, Vector3d dir, double max) {
        ShipMesh mesh = on.mesh();
        double r = 0.15;                                                        // толщина камеры
        for (double t = 0; t <= max; t += 0.05) {
            double x = from.x + dir.x * t, y = from.y + dir.y * t, z = from.z + dir.z * t;
            AABB box = new AABB(x - r, y - r, z - r, x + r, y + r, z + r);
            int x0 = (int) Math.floor(box.minX + 0.5), x1 = (int) Math.floor(box.maxX + 0.5);
            int y0 = (int) Math.floor(box.minY + mesh.seatH), y1 = (int) Math.floor(box.maxY + mesh.seatH);
            int z0 = (int) Math.floor(box.minZ + 0.5), z1 = (int) Math.floor(box.maxZ + 0.5);
            for (int dy = y0; dy <= y1; dy++) for (int dz = z0; dz <= z1; dz++) for (int dx = x0; dx <= x1; dx++) {
                var sh = mesh.shape(dx, dy, dz);
                if (sh == null) continue;
                for (AABB b : sh.toAabbs()) if (b.intersects(box)) return Math.max(0, t - 0.05);
            }
        }
        return max;
    }

    // ───────────────────────── вспомогательное ─────────────────────────

    private static Vector3d local(Matrix4f place, Vec3 pos) {
        return new Matrix4d(place).invert().transformPosition(new Vector3d(pos.x, pos.y, pos.z));
    }

    /** Направление взгляда по курсу/наклону (как у игры). */
    private static Vector3d dir(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch), cp = Math.cos(p);
        return new Vector3d(-Math.sin(y) * cp, -Math.sin(p), Math.cos(y) * cp);
    }
}
