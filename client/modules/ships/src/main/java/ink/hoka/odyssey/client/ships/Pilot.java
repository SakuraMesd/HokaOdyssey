package ink.hoka.odyssey.client.ships;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import ink.hoka.odyssey.client.api.CameraState;
import ink.hoka.odyssey.client.api.ClientContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Полёт пилота — целиком здесь, без задержки на сервер: мышь ведёт прицел, нос корабля доводится к нему (повороты
 * в осях самого корабля — без пределов: петли, полёт вверх ногами); W/S — ход с инерцией, A/D — крен (бочка),
 * пробел/Shift — вверх/вниз по «вверх» корабля, Ctrl+W — форсаж; столкновения с миром; камера из кабины или сзади
 * (F5) — полным поворотом (кватернион); HUD. Положение 20 раз в секунду уходит серверу — он проверяет и показывает
 * корабль остальным; не принял — присылает, куда вернуться.
 * Ориентация корабля — кватернион q (система корабля → мир, нос в системе корабля — +Z, правый борт — −X, верх — +Y).
 * Серверу уходят углы O = Ry(−курс)·Rx(наклон)·Rz(крен) (как Rot.quat) — ими представим любой поворот.
 */
final class Pilot {

    private final ClientContext ctx;
    private final JsonObject cfg;
    final ShipMesh mesh;
    final int anchor;
    // состояние (тик) и прошлое — для плавной отрисовки между тиками
    private double x, y, z, px, py, pz, vx, vy, vz;
    private final Quaternionf q = new Quaternionf(), pq = new Quaternionf();
    private double yawRate, pitchRate, rollRate;
    /** Прицел — отклонение от носа в осях корабля (°): вбок (как курс) и вверх/вниз (как наклон). */
    private float offYaw, offPitch;
    private int view;                       // 0 — кабина, 1 — сзади
    private boolean boost;
    private CameraType cameraBefore;
    // камера «сзади»: сглаженный поворот
    private Quaternionf chase;
    // для HUD: поворот камеры последнего кадра (в системе «как корабль»)
    private final Quaternionf camView = new Quaternionf();

    Pilot(ClientContext ctx, int anchor, double x, double y, double z, float baseYaw, float seatH, JsonObject cfg, int[][] parts) {
        this.ctx = ctx;
        this.cfg = cfg;
        this.anchor = anchor;
        this.mesh = new ShipMesh(parts, seatH, baseYaw);
        this.x = this.px = x;
        this.y = this.py = y;
        this.z = this.pz = z;
        q.set(orient(baseYaw, 0, 0));
        pq.set(q);
        Minecraft mc = Minecraft.getInstance();
        cameraBefore = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        ctx.hideHand(true);
    }

    void end() {
        ctx.hideHand(false);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {                                                // взгляд — туда же, куда смотрела кабина
            Vector3f f = camView.transform(new Vector3f(0, 0, 1));
            float yaw = (float) Math.toDegrees(Math.atan2(-f.x, f.z)), pitch = (float) Math.toDegrees(Math.asin(Math.clamp(-f.y, -1f, 1f)));
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            mc.player.yRotO = yaw;
            mc.player.xRotO = pitch;
            mc.player.setYHeadRot(yaw);
        }
        if (cameraBefore != null) mc.options.setCameraType(cameraBefore);
    }

    // ───────────────────────── настройки ─────────────────────────

    private double c(String section, String key, double def) {
        try {
            JsonObject s = cfg.getAsJsonObject(section);
            return s != null && s.has(key) ? s.get(key).getAsDouble() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private boolean cb(String section, String key, boolean def) {
        try {
            JsonObject s = cfg.getAsJsonObject(section);
            return s != null && s.has(key) ? s.get(key).getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }

    /** Доля пути к цели за тик, чтобы пройти 90% за t секунд. */
    static double rate(double seconds) {
        return seconds <= 0 ? 1 : 1 - Math.pow(0.1, 1 / (20 * seconds));
    }

    /** Новая скорость поворота (°/тик): к желаемой (доля оставшегося угла, не больше max), меняясь не быстрее accel. */
    static double approach(double rate, double diff, double k, double max, double accel) {
        if (Math.abs(diff) < 0.05 && Math.abs(rate) < accel) return diff;
        double want = Math.clamp(diff * k, -max, max);
        double next = rate + Math.clamp(want - rate, -accel, accel);
        if (Math.abs(next) > Math.abs(diff) && Math.signum(next) == Math.signum(diff)) next = diff;
        return next;
    }

    static Quaternionf orient(float yaw, float pitch, float roll) {
        return new Quaternionf().rotationY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch)).rotateZ((float) Math.toRadians(roll));
    }

    /** Углы {курс, наклон, крен} поворота o в виде O = Ry(−курс)·Rx(наклон)·Rz(крен) — любой поворот (и вверх ногами). */
    static float[] euler(Quaternionf o) {
        Vector3f f = o.transform(new Vector3f(0, 0, 1));
        float pitch = (float) Math.toDegrees(Math.asin(Math.clamp(-f.y, -1f, 1f)));
        float yaw = (float) Math.toDegrees(Math.atan2(-f.x, f.z));
        if (Math.abs(f.x) < 1e-5f && Math.abs(f.z) < 1e-5f) {                    // нос прямо вверх/вниз: курс — по «верху»
            Vector3f u = o.transform(new Vector3f(0, 1, 0));
            yaw = (float) Math.toDegrees(Math.atan2(u.x * Math.signum(f.y), -u.z * Math.signum(f.y)));
        }
        Quaternionf r = orient(yaw, pitch, 0).conjugate().mul(o);
        Vector3f u = r.transform(new Vector3f(0, 1, 0));
        float roll = (float) Math.toDegrees(Math.atan2(-u.x, u.y));
        return new float[]{yaw, pitch, roll};
    }

    // ───────────────────────── мышь ─────────────────────────

    /** Мышь двигает прицел (каждый кадр, без задержки); игрок не поворачивается. */
    boolean mouse(double dyaw, double dpitch) {
        double sens = c("aim", "sensitivity", 0.5);
        offYaw += (float) (dyaw * sens);
        offPitch += (float) (dpitch * sens);
        clampAim();
        return true;
    }

    private void clampAim() {
        float maxY = (float) c("aim", "max-yaw", 35), maxP = (float) c("aim", "max-pitch", 25);
        offYaw = Math.clamp(offYaw, -maxY, maxY);
        offPitch = Math.clamp(offPitch, -maxP, maxP);
    }

    // ───────────────────────── тик ─────────────────────────

    void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        // F5 — переключить вид (сама камера игры всегда «от первого лица», ставим её мы)
        if (mc.options.getCameraType() != CameraType.FIRST_PERSON) {
            view = 1 - view;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }
        px = x; py = y; pz = z;
        pq.set(q);

        // поворот носа к прицелу — в осях корабля (без пределов)
        double turnMax = c("flight", "turn-speed", 90) / 20.0, turnAcc = c("flight", "turn-accel", 360) / 400.0, k = c("flight", "turn-smoothing", 0.08);
        yawRate = approach(yawRate, offYaw, k, turnMax, turnAcc);
        pitchRate = approach(pitchRate, offPitch, k, turnMax, turnAcc);
        // крен — A/D (D — правый борт вниз), с набором и затуханием
        var o = mc.options;
        boolean free = mc.screen == null;
        int side = (o.keyRight.isDown() && free ? 1 : 0) - (o.keyLeft.isDown() && free ? 1 : 0);
        double rollMax = c("flight", "roll-speed", 120) / 20.0, rollAcc = c("flight", "roll-accel", 480) / 400.0;
        rollRate += Math.clamp(side * rollMax - rollRate, -rollAcc, rollAcc);
        Quaternionf nq = new Quaternionf(q)
                .rotateY((float) Math.toRadians(-yawRate))
                .rotateX((float) Math.toRadians(pitchRate))
                .rotateZ((float) Math.toRadians(rollRate)).normalize();

        // физика: скорость по осям корабля
        boolean fwdK = o.keyUp.isDown() && free, backK = o.keyDown.isDown() && free;
        int fwd = (fwdK ? 1 : 0) - (backK ? 1 : 0);
        int vert = (o.keyJump.isDown() && free ? 1 : 0) - (o.keyShift.isDown() && free ? 1 : 0);
        boost = fwdK && o.keySprint.isDown();
        double max = c("flight", "max-speed", 50) / 20.0;
        Vector3f f = nq.transform(new Vector3f(0, 0, 1)), r = nq.transform(new Vector3f(-1, 0, 0)), u = nq.transform(new Vector3f(0, 1, 0));
        double tF = fwd > 0 ? max * (boost ? c("flight", "boost", 1.4) : 1) : fwd < 0 ? -max * c("flight", "reverse", 0.3) : 0;
        double tU = vert * max * c("flight", "lift", 0.3);
        double vF = vx * f.x + vy * f.y + vz * f.z, vR = vx * r.x + vy * r.y + vz * r.z, vU = vx * u.x + vy * u.y + vz * u.z;
        double kF;
        if (fwd == 0) kF = rate(c("flight", "coast-time", 8));
        else if (tF * vF >= 0 && Math.abs(tF) >= Math.abs(vF)) kF = rate(boost ? c("flight", "boost-time", 2) : c("flight", "accel-time", 3));
        else kF = rate(c("flight", "brake-time", 1.5));
        double kG = rate(c("flight", "grip-time", 1));
        vF += (tF - vF) * kF;
        vR += (0 - vR) * kG;
        vU += (tU - vU) * kG;
        double nvx = f.x * vF + r.x * vR + u.x * vU, nvy = f.y * vF + r.y * vR + u.y * vU, nvz = f.z * vF + r.z * vR + u.z * vU;
        double cap = max * Math.max(1, c("flight", "boost", 1.4)) * 1.1, len = Math.sqrt(nvx * nvx + nvy * nvy + nvz * nvz);
        if (!Double.isFinite(len)) { nvx = nvy = nvz = 0; }
        else if (len > cap) { nvx *= cap / len; nvy *= cap / len; nvz *= cap / len; }

        // столкновения: новый поворот на месте, потом ход — целиком или по осям
        if (hits(x, y, z, nq)) {
            nq.set(q);
            yawRate = pitchRate = rollRate = 0;
            offYaw = offPitch = 0;
        } else {
            offYaw -= (float) yawRate;                                           // прицел стоит, нос подходит к нему
            offPitch -= (float) pitchRate;
        }
        q.set(nq);
        if (!hits(x + nvx, y + nvy, z + nvz, q)) { x += nvx; y += nvy; z += nvz; }
        else {
            if (!hits(x + nvx, y, z, q)) x += nvx; else nvx = 0;
            if (!hits(x, y + nvy, z, q)) y += nvy; else nvy = 0;
            if (!hits(x, y, z + nvz, q)) z += nvz; else nvz = 0;
        }
        vx = nvx; vy = nvy; vz = nvz;
        clampAim();

        // игрок — в точке пилота (по нему сервер грузит чанки), сам не двигается
        mc.player.setPos(x, y, z);
        mc.player.setDeltaMovement(Vec3.ZERO);
        send();
    }

    /** Корабль задевает блоки мира (по центрам внешних клеток). */
    private boolean hits(double px, double py, double pz, Quaternionf o) {
        Minecraft mc = Minecraft.getInstance();
        Vector3f v = new Vector3f();
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (float[] h : mesh.hull) {
            o.transform(v.set(h[0], h[1], h[2]));
            bp.set(Mth.floor(px + v.x), Mth.floor(py + v.y), Mth.floor(pz + v.z));
            if (!mc.level.isLoaded(bp)) return true;                            // мир не загружен — дальше не летим
            BlockState st = mc.level.getBlockState(bp);
            if (!st.isAir() && !st.getCollisionShape(mc.level, bp).isEmpty()) return true;
        }
        return false;
    }

    private void send() {
        float[] e = euler(q);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bo)) {
            out.writeByte(1);
            out.writeDouble(x);
            out.writeDouble(y);
            out.writeDouble(z);
            out.writeFloat(e[0]);
            out.writeFloat(e[1]);
            out.writeFloat(e[2]);
        } catch (IOException ignored) {
        }
        ctx.send(bo.toByteArray());
    }

    /** Сервер не принял положение — вернуться. */
    void correct(double cx, double cy, double cz, float cyaw, float cpitch, float croll) {
        x = px = cx; y = py = cy; z = pz = cz;
        q.set(orient(cyaw, cpitch, croll));
        pq.set(q);
        vx = vy = vz = 0;
        yawRate = pitchRate = rollRate = 0;
        offYaw = offPitch = 0;
    }

    // ───────────────────────── кадр: положение между тиками ─────────────────────────

    private double ix(float pt) { return Mth.lerp(pt, px, x); }
    private double iy(float pt) { return Mth.lerp(pt, py, y); }
    private double iz(float pt) { return Mth.lerp(pt, pz, z); }
    private Quaternionf iq(float pt) { return new Quaternionf(pq).slerp(q, pt); }

    /** Свой корабль — по своему положению (сервер его не двигает). */
    void render(ClientContext.WorldFrame f) {
        Minecraft mc = Minecraft.getInstance();
        float pt = f.partialTick();
        double sx = ix(pt), sy = iy(pt), sz = iz(pt);
        float r = mesh.radius;
        if (!f.visible(new AABB(sx - r, sy - r, sz - r, sx + r, sy + r, sz + r))) return;
        Vec3 cam = f.camera();
        PoseStack ps = f.pose();
        ps.pushPose();
        ps.translate(sx - cam.x, sy - cam.y, sz - cam.z);
        ps.mulPose(iq(pt));
        int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(sx, sy + 1, sz));
        mesh.draw(ps, f.buffers(), light);
        mesh.drawEntities(ps, f);
        ps.popPose();
    }

    /** Камера: из кабины (жёстко, по кораблю — с креном и вверх ногами) или сзади (мягко догоняет). */
    void camera(CameraState s, float pt) {
        double sx = ix(pt), sy = iy(pt), sz = iz(pt);
        Quaternionf o = iq(pt);
        Quaternionf view;
        if (this.view == 0) {
            view = o;
            Vector3f eye = o.transform(new Vector3f(0, (float) c("camera", "eye-height", 1.1), 0));
            s.x = sx + eye.x; s.y = sy + eye.y; s.z = sz + eye.z;
            chase = null;
        } else {
            float sm = (float) c("camera", "chase-smoothing", 0.15);
            if (chase == null) chase = new Quaternionf(o);
            chase.slerp(o, sm).normalize();
            double size = mesh.radius;
            double back = size * c("camera", "chase-per-size", 1.2), up = size * c("camera", "chase-height", 0.35);
            Vector3f f = chase.transform(new Vector3f(0, 0, 1)), u = chase.transform(new Vector3f(0, 1, 0));
            s.x = sx - f.x * back + u.x * up;
            s.y = sy - f.y * back + u.y * up;
            s.z = sz - f.z * back + u.z * up;
            view = new Quaternionf(chase).rotateX((float) Math.atan2(up, back) * 0.5f);   // чуть вниз, на корабль
        }
        camView.set(view);
        float[] e = euler(view);
        s.yaw = e[0];
        s.pitch = e[1];
        s.roll = -e[2];                                                         // крен камеры игры — с обратным знаком
        try {
            s.rotation = new Quaternionf(view).rotateY((float) Math.PI);       // камера игры смотрит по −Z
        } catch (LinkageError old) {                                            // ядро до 0.21 — только углы
        }
    }

    // ───────────────────────── HUD ─────────────────────────

    /** HUD — как был на шейдере (см. {@link ShipHud}): круг-компас, кольцо носа, прицел, полоски, числа. */
    void hud(GuiGraphics g, DeltaTracker d) {
        if (!cb("hud", "enabled", true)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.level == null) return;
        int w = g.guiWidth(), h = g.guiHeight();
        float pt = d.getGameTimeDeltaPartialTick(false);
        Quaternionf o = iq(pt);
        Vector3f noseDir = o.transform(new Vector3f(0, 0, 1));
        Vector3f aimDir = new Quaternionf(o).rotateY((float) Math.toRadians(-offYaw)).rotateX((float) Math.toRadians(offPitch)).transform(new Vector3f(0, 0, 1));
        float[] nose = project(noseDir, w, h), aim = project(aimDir, w, h);
        double spd = Math.sqrt(vx * vx + vy * vy + vz * vz) * 20;
        double full = c("flight", "max-speed", 50) * Math.max(1, c("flight", "boost", 1.4));
        int minY = mc.level.getMinY(), height = mc.level.getHeight();
        float[] cam = euler(camView), ship = euler(q);
        ShipHud.draw(g, new ShipHud.State(
                nose != null ? nose[0] : 0, nose != null ? nose[1] : 0, nose != null,
                aim != null ? aim[0] : 0, aim != null ? aim[1] : 0, aim != null,
                cam[0], cam[1], mc.options.fov().get(),
                (float) Math.clamp(spd / full, 0, 1), boost, (int) Math.round(spd),
                (float) Math.clamp((y - minY) / height, 0, 1), (int) Math.floor(y), Math.round(-ship[1])));
    }

    /** Направление (мир) → точка экрана по камере последнего кадра, или null — за спиной. */
    private float[] project(Vector3f dirWorld, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        Vector3f dir = new Quaternionf(camView).conjugate().transform(new Vector3f(dirWorld));
        if (dir.z <= 0.01f) return null;
        double fov = Math.toRadians(mc.options.fov().get());
        double f = (h / 2.0) / Math.tan(fov / 2);
        return new float[]{(float) (w / 2.0 - dir.x / dir.z * f), (float) (h / 2.0 - dir.y / dir.z * f)};
    }

}
