package ink.hoka.odyssey.client.vessels;

import com.google.gson.JsonObject;
import ink.hoka.odyssey.client.api.CameraState;
import ink.hoka.odyssey.client.api.ClientContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ink.hoka.odyssey.client.vessels.hud.Compass;
import ink.hoka.odyssey.client.vessels.hud.Hints;
import ink.hoka.odyssey.client.vessels.hud.HudView;
import ink.hoka.odyssey.client.vessels.hud.McGfx;
import ink.hoka.odyssey.client.vessels.hud.PilotHud;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Полёт пилота — целиком здесь, без задержки на сервер (как Pilot старых кораблей ships, те же клавиши): мышь ведёт
 * прицел, нос доводится к нему (повороты в осях корабля, без пределов); W/S — ход с разгоном, Ctrl+W — форсаж,
 * A/D — крен, пробел/Shift — вверх/вниз по «верху» корабля; F5 — кабина / сзади; F — выйти (ловит сервер).
 * Столкновения с блоками мира — по клеткам корпуса модели. Поза (середина модели + кватернион) 20 раз в секунду
 * уходит серверу; не принял — присылает, куда вернуться.
 */
final class Pilot {

    private final ClientContext ctx;
    final int vessel;
    final Kind kind;
    private final JsonObject common;
    // состояние (тик) и прошлое — для плавной отрисовки между тиками
    private double x, y, z, px, py, pz, vx, vy, vz;
    private final Quaternionf q = new Quaternionf(), pq = new Quaternionf();
    private double yawRate, pitchRate, rollRate;
    /** Прицел — отклонение от носа в осях корабля (°): вбок и вверх/вниз. */
    private float offYaw, offPitch;
    private int view;                       // 0 — кабина, 1 — сзади
    private boolean boost;
    private final CameraType cameraBefore;
    private Quaternionf chase;
    private final Quaternionf camView = new Quaternionf();
    /** Сводка корабля от сервера (топливо, перезарядки, варп) — общая с модулем. */
    final ShipState state;
    private final Effects fx;
    /** Тики полёта (часы клиента для перезарядок) и когда орудие снова готово. */
    private long tickNo, gunReadyAt;
    /** Строка от сервера в HUD и когда пришла (мс). */
    private String msg;
    private boolean msgWarn;
    private long msgAt;
    private boolean hudHidden;
    /** Ход в этом тике: 1 вперёд, 0 нет, −1 назад (тяга в HUD). */
    private int throttle;
    /** Подсказки клавиш: 10 с после посадки, потом — пока зажата H. */
    private final Hints hints = new Hints();
    /** Маркер попадания: когда пришло (мс, 0 — не было) и что (1 цель, 2 убийство, 3 корабль, 4 корабль разрушен). */
    private long hitAt;
    private int hitKind;
    /** По нам попали (мс, 0 — нет): встряска камеры и красный край экрана. */
    private long hurtAt;
    private float hurtPower;
    /** Удар о блоки: когда сообщали серверу последний раз (тик). */
    private long impactTick = -100;
    /** Подушка над планетой (null — космос) и поле расстояний до блоков. */
    Cushion.Settings cushion;
    private final DistField.Cache field = new DistField.Cache();

    Pilot(ClientContext ctx, int vessel, Kind kind, JsonObject common, double x, double y, double z, Quaternionf rot, ShipState state, Effects fx) {
        this.ctx = ctx;
        this.state = state;
        this.fx = fx;
        this.vessel = vessel;
        this.kind = kind;
        this.common = common;
        this.x = this.px = x;
        this.y = this.py = y;
        this.z = this.pz = z;
        q.set(rot).normalize();
        pq.set(q);
        camView.set(q);
        Minecraft mc = Minecraft.getInstance();
        cameraBefore = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        ctx.hideHand(true);
        hints.board(System.currentTimeMillis());
        if (c("hud", "hide-vanilla", 1) != 0) {
            try {                                                               // ядро 0.79+: свой HUD вместо ванильного
                ctx.hideHud(true);
                hudHidden = true;
            } catch (LinkageError old) {
                ctx.logger().warn("ядро мода старше 0.79 — ванильный HUD в кабине не прячется до обновления мода");
            }
        }
    }

    void end() {
        ctx.hideHand(false);
        if (hudHidden) try { ctx.hideHud(false); } catch (LinkageError ignored) { }
        fx.stopWarp();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {                                                // взгляд — туда же, куда смотрела камера
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
            JsonObject s = common.getAsJsonObject(section);
            if (s != null && s.has(key) && s.get(key).isJsonPrimitive() && s.get(key).getAsJsonPrimitive().isBoolean())
                return s.get(key).getAsBoolean() ? 1 : 0;
            return s != null && s.has(key) ? s.get(key).getAsDouble() : def;
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

    /** Скорость вдоль носа: к цели не быстрее разгона (тормоз — вдвое быстрее); без газа — гаснет за coast-time. */
    static double thrust(double v, double target, boolean throttle, double accelPerTick, double coastRate) {
        if (!throttle) return v + (0 - v) * coastRate;
        double step = target * v >= 0 && Math.abs(target) >= Math.abs(v) ? accelPerTick : accelPerTick * 2;
        return v + Math.clamp(target - v, -step, step);
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
        if (mc.options.getCameraType() != CameraType.FIRST_PERSON) {           // F5 — переключить вид (камеру ставим мы)
            view = 1 - view;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }
        px = x; py = y; pz = z;
        pq.set(q);
        tickNo++;

        // поворот носа к прицелу — в осях корабля
        double turnMax = kind.turnSpeed / 20.0, turnAcc = c("control", "turn-accel", 360) / 400.0, k = c("control", "turn-smoothing", 0.08);
        yawRate = approach(yawRate, offYaw, k, turnMax, turnAcc);
        pitchRate = approach(pitchRate, offPitch, k, turnMax, turnAcc);
        var o = mc.options;
        boolean free = mc.screen == null;
        int side = (o.keyRight.isDown() && free ? 1 : 0) - (o.keyLeft.isDown() && free ? 1 : 0);
        double rollMax = kind.rollSpeed / 20.0, rollAcc = c("control", "roll-accel", 480) / 400.0;
        rollRate += Math.clamp(side * rollMax - rollRate, -rollAcc, rollAcc);
        Quaternionf nq = new Quaternionf(q)
                .rotateY((float) Math.toRadians(-yawRate))
                .rotateX((float) Math.toRadians(pitchRate))
                .rotateZ((float) Math.toRadians(rollRate)).normalize();

        // ход: скорость по осям корабля
        boolean fwdK = o.keyUp.isDown() && free, backK = o.keyDown.isDown() && free;
        int fwd = (fwdK ? 1 : 0) - (backK ? 1 : 0);
        int vert = (o.keyJump.isDown() && free ? 1 : 0) - (o.keyShift.isDown() && free ? 1 : 0);
        boost = fwdK && o.keySprint.isDown() && free;
        throttle = fwd;
        hints.hold(free && com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), org.lwjgl.glfw.GLFW.GLFW_KEY_H), System.currentTimeMillis());
        double mul = state.known ? state.speedMul : 1;                         // улучшения «Двигатель+», аварийный ход
        double max = kind.speed * mul / 20.0, maxBoost = kind.boostSpeed * mul / 20.0;
        Vector3f f = nq.transform(new Vector3f(0, 0, 1)), r = nq.transform(new Vector3f(-1, 0, 0)), u = nq.transform(new Vector3f(0, 1, 0));
        double tF = fwd > 0 ? (boost ? maxBoost : max) : fwd < 0 ? -max * c("control", "reverse", 0.3) : 0;
        double tU = vert * max * c("control", "lift", 0.3);
        double vF = vx * f.x + vy * f.y + vz * f.z, vR = vx * r.x + vy * r.y + vz * r.z, vU = vx * u.x + vy * u.y + vz * u.z;
        double acc = kind.accel / 400.0;                                        // блоков/с² → блоков/тик за тик
        vF = thrust(vF, tF, fwd != 0, acc, rate(c("control", "coast-time", 8)));
        double kG = rate(c("control", "grip-time", 1));
        vR += (0 - vR) * kG;
        vU += (tU - vU) * kG;
        double nvx = f.x * vF + r.x * vR + u.x * vU, nvy = f.y * vF + r.y * vR + u.y * vU, nvz = f.z * vF + r.z * vR + u.z * vU;
        double cap = Math.max(max, maxBoost), len = Math.sqrt(nvx * nvx + nvy * nvy + nvz * nvz);
        if (!Double.isFinite(len)) { nvx = nvy = nvz = 0; }
        else if (len > cap) { nvx *= cap / len; nvy *= cap / len; nvz *= cap / len; }

        // подушка над планетой: плавно отталкивает от блоков ближе clearance (считается от формы корпуса)
        if (cushion != null && kind.hull.length > 0) {
            double[] nv = cushionPush(nq, nvx, nvy, nvz, cap);
            nvx = nv[0]; nvy = nv[1]; nvz = nv[2];
        }

        // столкновения: сначала сразу итог (поворот + ход); занято — поворот на месте, потом ход целиком или по осям
        double ox = x, oy = y, oz = z, wx = nvx, wy = nvy, wz = nvz;
        if (!hits(x + nvx, y + nvy, z + nvz, nq)) {
            offYaw -= (float) yawRate;                                           // прицел стоит, нос подходит к нему
            offPitch -= (float) pitchRate;
            q.set(nq);
            x += nvx; y += nvy; z += nvz;
        } else {
            if (hits(x, y, z, nq)) {
                nq.set(q);
                yawRate = pitchRate = rollRate = 0;
                offYaw = offPitch = 0;
            } else {
                offYaw -= (float) yawRate;
                offPitch -= (float) pitchRate;
            }
            q.set(nq);
            if (!hits(x + nvx, y + nvy, z + nvz, q)) { x += nvx; y += nvy; z += nvz; }
            else {
                if (!hits(x + nvx, y, z, q)) x += nvx; else nvx = 0;
                if (!hits(x, y + nvy, z, q)) y += nvy; else nvy = 0;
                if (!hits(x, y, z + nvz, q)) z += nvz; else nvz = 0;
            }
        }
        vx = nvx; vy = nvy; vz = nvz;
        clampAim();
        impact(wx, wy, wz, x - ox, y - oy, z - oz);

        // игрок — на месте пилота (по нему сервер грузит чанки), сам не двигается
        Vector3d eye = seatWorld(x, y, z, q);
        mc.player.setPos(eye.x, eye.y - mc.player.getEyeHeight(), eye.z);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.resetFallDistance();
        send();
    }

    /**
     * Удар о блоки: хотели сдвинуться на want, а сдвинулись на got (блоки не пустили) — потеря скорости больше
     * 6 бл/с — серверу (C2S IMPACT): урон считает он. Не чаще раза в 10 тиков.
     */
    private void impact(double wx, double wy, double wz, double gx, double gy, double gz) {
        double lx = wx - gx, ly = wy - gy, lz = wz - gz, lost = Math.sqrt(lx * lx + ly * ly + lz * lz) * 20;
        if (lost < 6 || tickNo - impactTick < 10) return;
        double sp = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (sp < 1e-6) return;
        impactTick = tickNo;
        float s = (float) lost, dx = (float) (wx / sp), dy = (float) (wy / sp), dz = (float) (wz / sp);
        ctx.send(new byte[]{7, b(s, 24), b(s, 16), b(s, 8), b(s, 0), b(dx, 24), b(dx, 16), b(dx, 8), b(dx, 0),
                b(dy, 24), b(dy, 16), b(dy, 8), b(dy, 0), b(dz, 24), b(dz, 16), b(dz, 8), b(dz, 0)});
    }

    /** Подушка: поле расстояний вокруг корабля (кэш, пересчёт при выходе из запаса) → скорость с отталкиванием. */
    private double[] cushionPush(Quaternionf nq, double nvx, double nvy, double nvz, double cap) {
        Minecraft mc = Minecraft.getInstance();
        float[] h = kind.hull;
        double[] probes = new double[h.length];
        for (int k = 0; k < h.length; k += 3) {
            nq.transform(hv.set(h[k], h[k + 1], h[k + 2]));
            probes[k] = x + hv.x; probes[k + 1] = y + hv.y; probes[k + 2] = z + hv.z;
        }
        DistField f = field.get(probes, cushion.reach(), new CushionBlocks(mc.level));
        if (f == null) return new double[]{nvx, nvy, nvz};
        return Cushion.apply(f, probes, nvx, nvy, nvz, cap, cushion);
    }

    /**
     * Блоки для подушки: твёрдый (мешает движению) или жидкость; незагруженный столбец — тоже (не подходить вслепую).
     * Кусок 16³ целиком из воздуха — сразу «пусто»; столбец берётся один раз подряд (поле читается столбцами).
     */
    private static final class CushionBlocks implements DistField.Blocked {
        private final net.minecraft.client.multiplayer.ClientLevel level;
        private final int minY, topY;
        private int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
        private net.minecraft.world.level.chunk.LevelChunk ch;
        private final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();

        CushionBlocks(net.minecraft.client.multiplayer.ClientLevel level) {
            this.level = level;
            this.minY = level.getMinY();
            this.topY = minY + level.getHeight();
        }

        private net.minecraft.world.level.chunk.LevelChunk chunk(int x, int z) {
            if (x != cx || z != cz) { cx = x; cz = z; ch = level.getChunkSource().getChunk(x, z, false); }
            return ch;
        }

        @Override
        public int section(int sx, int sy, int sz) {
            if ((sy << 4) + 15 < minY || (sy << 4) >= topY) return 0;
            var c = chunk(sx, sz);
            if (c == null) return 1;
            int i = c.getSectionIndexFromSectionY(sy);
            var secs = c.getSections();
            if (i < 0 || i >= secs.length || secs[i] == null || secs[i].hasOnlyAir()) return 0;
            return -1;
        }

        @Override
        public boolean at(int bx, int by, int bz) {
            if (by < minY || by >= topY) return false;
            var c = chunk(bx >> 4, bz >> 4);
            if (c == null) return true;
            var secs = c.getSections();
            int i = c.getSectionIndexFromSectionY(by >> 4);
            if (i < 0 || i >= secs.length || secs[i] == null || secs[i].hasOnlyAir()) return false;
            BlockState st = secs[i].getBlockState(bx & 15, by & 15, bz & 15);
            if (st.isAir()) return false;
            if (!st.getFluidState().isEmpty()) return true;
            return !st.getCollisionShape(level, mp.set(bx, by, bz)).isEmpty();
        }
    }

    /** Глаза пилота в мире. */
    private Vector3d seatWorld(double sx, double sy, double sz, Quaternionf o) {
        Vector3f s = o.transform(new Vector3f((float) kind.seat[0], (float) kind.seat[1], (float) kind.seat[2]));
        return new Vector3d(sx + s.x, sy + s.y, sz + s.z);
    }

    private final Vector3f hv = new Vector3f();
    private final BlockPos.MutableBlockPos hp = new BlockPos.MutableBlockPos();

    /**
     * Корабль задевает блоки мира (по серединам клеток корпуса). Сперва грубо — секции 16³ под коробкой повёрнутого
     * корпуса: все загружены и пустые — сразу «нет». Иначе по клеткам; незагруженный мир — «занято».
     */
    private boolean hits(double px, double py, double pz, Quaternionf o) {
        Minecraft mc = Minecraft.getInstance();
        var level = mc.level;
        float[] h = kind.hull;
        if (h.length == 0) return false;
        int minY = level.getMinY(), topY = minY + level.getHeight();
        float[] lo = kind.hullLo, hi = kind.hullHi;
        double ax = Double.MAX_VALUE, ay = ax, az = ax, bx = -ax, by = -ax, bz = -ax;
        for (int c = 0; c < 8; c++) {
            o.transform(hv.set((c & 1) != 0 ? hi[0] : lo[0], (c & 2) != 0 ? hi[1] : lo[1], (c & 4) != 0 ? hi[2] : lo[2]));
            ax = Math.min(ax, hv.x); ay = Math.min(ay, hv.y); az = Math.min(az, hv.z);
            bx = Math.max(bx, hv.x); by = Math.max(by, hv.y); bz = Math.max(bz, hv.z);
        }
        int x0 = Mth.floor(px + ax) - 1, y0 = Mth.floor(py + ay) - 1, z0 = Mth.floor(pz + az) - 1;
        int x1 = Mth.floor(px + bx) + 1, y1 = Mth.floor(py + by) + 1, z1 = Mth.floor(pz + bz) + 1;
        var chunks = level.getChunkSource();
        boolean detail = y0 < minY || y1 >= topY;
        int sy0 = Math.max(y0, minY) >> 4, sy1 = Math.min(y1, topY - 1) >> 4;
        for (int cx = x0 >> 4; cx <= x1 >> 4 && !detail; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4 && !detail; cz++) {
                net.minecraft.world.level.chunk.LevelChunk ch = chunks.getChunk(cx, cz, false);
                if (ch == null) { detail = true; break; }
                var secs = ch.getSections();
                for (int sy = sy0; sy <= sy1; sy++) {
                    int i = ch.getSectionIndexFromSectionY(sy);
                    if (i < 0 || i >= secs.length || secs[i] == null || !secs[i].hasOnlyAir()) { detail = true; break; }
                }
            }
        if (!detail) return false;
        for (int k = 0; k < h.length; k += 3) {
            o.transform(hv.set(h[k], h[k + 1], h[k + 2]));
            int bxp = Mth.floor(px + hv.x), byp = Mth.floor(py + hv.y), bzp = Mth.floor(pz + hv.z);
            if (byp < minY || byp >= topY) return true;
            net.minecraft.world.level.chunk.LevelChunk ch = chunks.getChunk(bxp >> 4, bzp >> 4, false);
            if (ch == null) return true;
            var secs = ch.getSections();
            int i = ch.getSectionIndexFromSectionY(byp >> 4);
            if (i < 0 || i >= secs.length) return true;
            var sec = secs[i];
            if (sec == null || sec.hasOnlyAir()) continue;
            BlockState st = sec.getBlockState(bxp & 15, byp & 15, bzp & 15);
            if (st.isAir()) continue;
            hp.set(bxp, byp, bzp);
            if (!st.getCollisionShape(level, hp).isEmpty()) return true;
        }
        return false;
    }

    private void send() {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bo)) {
            out.writeByte(1);
            out.writeDouble(x);
            out.writeDouble(y);
            out.writeDouble(z);
            out.writeFloat(q.x);
            out.writeFloat(q.y);
            out.writeFloat(q.z);
            out.writeFloat(q.w);
        } catch (IOException ignored) {
        }
        ctx.send(bo.toByteArray());
    }

    // ───────────────────────── орудие ─────────────────────────

    /** Направление прицела (мир): нос, повёрнутый на отклонение прицела мыши. */
    Vector3f aimDir() {
        return new Quaternionf(q).rotateY((float) Math.toRadians(-offYaw)).rotateX((float) Math.toRadians(offPitch)).transform(new Vector3f(0, 0, 1));
    }

    /** ЛКМ (и зажатая ЛКМ — каждый тик): орудие готово — выстрел по прицелу (попадание считает сервер). */
    void fire() {
        if (tickNo < gunReadyAt) return;
        gunReadyAt = tickNo + Math.max(1, state.gunReload);
        Vector3f d = aimDir();
        ctx.send(new byte[]{5, b(d.x, 24), b(d.x, 16), b(d.x, 8), b(d.x, 0), b(d.y, 24), b(d.y, 16), b(d.y, 8), b(d.y, 0),
                b(d.z, 24), b(d.z, 16), b(d.z, 8), b(d.z, 0)});
    }

    private static byte b(float f, int shift) { return (byte) (Float.floatToIntBits(f) >>> shift); }

    /** Строка от сервера (S2C MSG) — в HUD на 3 с. */
    void message(String text, boolean warn) {
        msg = text;
        msgWarn = warn;
        msgAt = System.currentTimeMillis();
    }

    long tickNo() { return tickNo; }

    /** Сервер не принял позу — вернуться. */
    void correct(double cx, double cy, double cz, Quaternionf rot) {
        x = px = cx; y = py = cy; z = pz = cz;
        q.set(rot).normalize();
        pq.set(q);
        vx = vy = vz = 0;
        yawRate = pitchRate = rollRate = 0;
        offYaw = offPitch = 0;
    }

    // ───────────────────────── кадр: поза между тиками ─────────────────────────

    /** Поза корабля на кадр: середина модели и поворот. */
    void poseAt(float pt, Vector3d pos, Quaternionf rot) {
        pos.set(Mth.lerp(pt, px, x), Mth.lerp(pt, py, y), Mth.lerp(pt, pz, z));
        rot.set(pq).slerp(q, pt);
    }

    /** Камера: из кабины (жёстко, по кораблю — с креном) или сзади (мягко догоняет). */
    void camera(CameraState s, float pt) {
        Vector3d pos = new Vector3d();
        Quaternionf o = new Quaternionf();
        poseAt(pt, pos, o);
        Quaternionf view;
        if (this.view == 0) {
            view = o;
            float eh = (float) c("camera", "eye-height", 0);
            Vector3f eye = o.transform(new Vector3f((float) kind.seat[0], (float) kind.seat[1] + eh, (float) kind.seat[2]));
            s.x = pos.x + eye.x; s.y = pos.y + eye.y; s.z = pos.z + eye.z;
            chase = null;
        } else {
            float sm = (float) c("camera", "chase-smoothing", 0.15);
            if (chase == null) chase = new Quaternionf(o);
            chase.slerp(o, sm).normalize();
            double size = kind.radius();
            double back = size * c("camera", "chase-per-size", 1.2), up = size * c("camera", "chase-height", 0.35);
            Vector3f f = chase.transform(new Vector3f(0, 0, 1)), u = chase.transform(new Vector3f(0, 1, 0));
            s.x = pos.x - f.x * back + u.x * up;
            s.y = pos.y - f.y * back + u.y * up;
            s.z = pos.z - f.z * back + u.z * up;
            view = new Quaternionf(chase).rotateX((float) Math.atan2(up, back) * 0.5f);   // чуть вниз, на корабль
        }
        float shake = hurtPower();
        if (shake > 0.01f) {                                                    // встряска: небольшая, быстро гаснет
            double t = System.currentTimeMillis() / 1000.0;
            float a = shake * 0.9f;
            view = new Quaternionf(view).rotateY((float) Math.toRadians(a * Math.sin(t * 61)))
                    .rotateX((float) Math.toRadians(a * Math.sin(t * 47 + 1.3))).rotateZ((float) Math.toRadians(a * 0.6 * Math.sin(t * 53 + 2.1)));
        }
        camView.set(view);
        float[] e = euler(view);
        s.yaw = e[0];
        s.pitch = e[1];
        s.roll = -e[2];                                                         // крен камеры игры — с обратным знаком
        s.rotation = new Quaternionf(view).rotateY((float) Math.PI);           // камера игры смотрит по −Z
    }

    static Quaternionf orient(float yaw, float pitch, float roll) {
        return new Quaternionf().rotationY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch)).rotateZ((float) Math.toRadians(roll));
    }

    /** Углы {курс, наклон, крен} поворота o в виде O = Ry(−курс)·Rx(наклон)·Rz(крен). */
    static float[] euler(Quaternionf o) {
        Vector3f f = o.transform(new Vector3f(0, 0, 1));
        float pitch = (float) Math.toDegrees(Math.asin(Math.clamp(-f.y, -1f, 1f)));
        float yaw = (float) Math.toDegrees(Math.atan2(-f.x, f.z));
        if (Math.abs(f.x) < 1e-5f && Math.abs(f.z) < 1e-5f) {
            Vector3f u = o.transform(new Vector3f(0, 1, 0));
            yaw = (float) Math.toDegrees(Math.atan2(u.x * Math.signum(f.y), -u.z * Math.signum(f.y)));
        }
        Quaternionf r = orient(yaw, pitch, 0).conjugate().mul(o);
        Vector3f u = r.transform(new Vector3f(0, 1, 0));
        float roll = (float) Math.toDegrees(Math.atan2(-u.x, u.y));
        return new float[]{yaw, pitch, roll};
    }

    // ───────────────────────── HUD ─────────────────────────

    /** Сервер: попадание (1 — по цели, 2 — убийство, 3 — по кораблю, 4 — корабль разрушен) — маркер у прицела. */
    void hit(int what) {
        hitAt = System.currentTimeMillis();
        hitKind = what;
    }

    /** Сервер: по нашему кораблю попали (или удар о блоки) — встряска камеры и красный край. */
    void hurt() {
        long now = System.currentTimeMillis();
        hurtPower = Math.min(1.6f, hurtPower() + 1f);
        hurtAt = now;
    }

    /** Сила встряски сейчас (0…1.6, гаснет за 0.6 с). */
    private float hurtPower() {
        if (hurtAt == 0) return 0;
        float k = 1 - (System.currentTimeMillis() - hurtAt) / 600f;
        return k <= 0 ? 0 : hurtPower * k * k;
    }

    /**
     * HUD ({@link PilotHud}): прицел и нос (проекция по камере кадра), курс носа, лесенка тангажа по камере (каждая
     * ступень — проекцией направления «курс камеры, угол a» и бокового вектора: знак крена не нужен), скорость и тяга,
     * высота (Y середины корабля), топливо, орудие, варп, сообщение, подсказки, маркер попадания.
     */
    void hud(GuiGraphics g, DeltaTracker d, PilotHud hudDraw, McGfx gfx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.level == null) return;
        try {
            if (!common.getAsJsonObject("hud").get("enabled").getAsBoolean()) return;
        } catch (Exception ignored) {
        }
        int w = g.guiWidth(), h = g.guiHeight();
        float pt = d.getGameTimeDeltaPartialTick(false);
        Quaternionf o = new Quaternionf(pq).slerp(q, pt);
        Vector3f noseDir = o.transform(new Vector3f(0, 0, 1));
        Vector3f aimDir = new Quaternionf(o).rotateY((float) Math.toRadians(-offYaw)).rotateX((float) Math.toRadians(offPitch)).transform(new Vector3f(0, 0, 1));
        float[] nose = project(noseDir, w, h), aim = project(aimDir, w, h);

        // лесенка: ступени через 10° по курсу камеры
        float camYaw = euler(camView)[0];
        double yr = Math.toRadians(camYaw);
        Vector3f fwd = new Vector3f((float) -Math.sin(yr), 0, (float) Math.cos(yr)), right = new Vector3f((float) -Math.cos(yr), 0, (float) -Math.sin(yr));
        float[] rungs = new float[19 * 5];
        int n = 0;
        for (int a = -90; a <= 90; a += 10) {
            double ar = Math.toRadians(a);
            Vector3f dir = new Vector3f(fwd).mul((float) Math.cos(ar)).add(0, (float) Math.sin(ar), 0);
            float[] p0 = project(dir, w, h), p1 = project(new Vector3f(dir).add(new Vector3f(right).mul(0.02f)), w, h);
            if (p0 == null || p1 == null) continue;
            float dx = p1[0] - p0[0], dy = p1[1] - p0[1], len = (float) Math.hypot(dx, dy);
            if (len < 1e-4f || Math.abs(p0[0]) > 1e5f || Math.abs(p0[1]) > 1e5f) continue;
            rungs[n++] = a; rungs[n++] = p0[0]; rungs[n++] = p0[1]; rungs[n++] = dx / len; rungs[n++] = dy / len;
        }
        rungs = java.util.Arrays.copyOf(rungs, n);

        double spd = Math.sqrt(vx * vx + vy * vy + vz * vz) * 20;
        double mul = state.known ? state.speedMul : 1;
        long now = System.currentTimeMillis();
        float age = (now - msgAt) / 1000f;
        float alpha = msg == null ? 0 : age < 2.5f ? 1 : Math.max(0, 1 - (age - 2.5f) / 0.8f);
        long gunLeft = Math.max(0, gunReadyAt - tickNo);
        float gunK = gunLeft <= 0 ? 1 : 1 - gunLeft / (float) Math.max(1, state.gunReload);
        float charge = fx.charge();
        long reloadLeft = Math.max(0, state.warpReloadEnd - tickNo);
        int warp = HudView.warpState(charge, state.warpPhase, reloadLeft, state.known, state.fuel, state.warpCost);
        float warpK = switch (warp) {
            case HudView.WARP_CHARGING -> charge;
            case HudView.WARP_RELOAD -> 1 - reloadLeft / (float) Math.max(1, state.warpReload);
            case HudView.WARP_NO_FUEL -> state.fuel / (float) Math.max(1, state.warpCost);
            default -> 1;
        };
        boolean bigHit = hitKind == 2 || hitKind == 4;
        float hitA = hitAt == 0 ? 0 : 1 - (now - hitAt) / (bigHit ? 700f : 420f);
        float hurtA = hurtAt == 0 ? 0 : Math.max(0, 1 - (now - hurtAt) / 700f);
        Vector3d pos = new Vector3d();
        poseAt(pt, pos, new Quaternionf());
        HudView v = new HudView(
                nose != null ? nose[0] : 0, nose != null ? nose[1] : 0, nose != null, aim != null ? aim[0] : 0, aim != null ? aim[1] : 0, aim != null,
                Compass.heading(euler(o)[0]), rungs,
                spd, kind.speed * mul, kind.boostSpeed * mul, throttle, boost,
                pos.y, (y - py) * 20,
                state.known, state.fuel, state.capacity, state.warpCost, state.empty,
                gunK, gunLeft / 20f,
                warp, warpK, (int) ((reloadLeft + 19) / 20),
                msg, msgWarn, alpha,
                hints.alpha(now), Math.max(0, hitA), hitKind,
                kind.id, kind.tris,
                state.hullKnown, state.hp, state.hpMax, state.zones.clone(), kind.triZone, hurtA);
        hudDraw.draw(gfx.frame(g), v, now);
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
