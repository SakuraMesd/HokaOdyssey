package ink.hoka.odyssey.client.holo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import ink.hoka.odyssey.client.api.ClientContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Режим «двигать» из редактора: голограмма следует за прицелом (на блок под прицелом или на расстоянии), колёсико —
 * расстояние (слот хотбара возвращается на место), Shift — точно (мелкий шаг, без привязки), стрелки-гизмо по осям
 * X/Y/Z — навести и тянуть ЛКМ, G — следовать/стоять, Enter или ПКМ — поставить, Backspace — отменить.
 * Позиция пишется в черновик сессии (живой предпросмотр), отмена — одной записью в истории.
 */
final class Mover {

    private final HoloClientModule m;
    private boolean active, follow;
    private String id;
    private double dist = 3;
    private int slot = -1;
    private int axis = -1, hoverAxis = -1;
    private double grabS;
    private double[] grabPos, startPos;
    private boolean enterWas, gWas, backWas;
    static final double ARROW = 1.2;

    Mover(HoloClientModule m) { this.m = m; }

    boolean active() { return active; }

    String hint() {
        return "Двигать «" + id + "»: " + (follow ? "за прицелом, колёсико — расстояние " + String.format("%.1f", dist) : "стоит")
                + " · стрелки — тянуть ЛКМ · G — " + (follow ? "стоп" : "следовать") + " · Shift — точно · Enter/ПКМ — поставить · Backspace — отмена";
    }

    void start(String holoId) {
        JsonObject o = m.session.get(holoId);
        if (o == null) return;
        id = holoId;
        startPos = pos(o);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            Vec3 e = mc.player.getEyePosition();
            dist = Math.max(1.5, Math.min(12, Math.sqrt(sq(startPos[0] - e.x) + sq(startPos[1] - e.y) + sq(startPos[2] - e.z))));
            slot = mc.player.getInventory().getSelectedSlot();
        }
        m.session.change(null, d -> { });                                     // черновик есть (для отмены)
        m.session.change("move", d -> setPos(d, startPos));
        follow = true;
        active = true;
        axis = -1;
        mc.setScreen(null);
    }

    static double sq(double v) { return v * v; }

    static double[] pos(JsonObject o) {
        JsonArray p = o.getAsJsonArray("pos");
        return new double[]{p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble()};
    }

    static void setPos(JsonObject o, double[] p) {
        JsonArray a = new JsonArray();
        for (double v : p) a.add(Math.round(v * 1000) / 1000.0);
        o.add("pos", a);
    }

    void tick(Minecraft mc) {
        if (!active) return;
        if (mc.player == null || m.session.get(id) == null) { active = false; return; }
        var win = mc.getWindow();
        boolean enter = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_ENTER) || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_KP_ENTER);
        boolean g = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_G);
        boolean back = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_BACKSPACE);
        if (mc.screen == null) {
            if (enter && !enterWas) finish(true);
            if (g && !gWas) follow = !follow;
            if (back && !backWas) finish(false);
        }
        enterWas = enter;
        gWas = g;
        backWas = back;
        // колёсико: слот хотбара сменился — это расстояние; вернуть слот
        int s = mc.player.getInventory().getSelectedSlot();
        if (slot >= 0 && s != slot) {
            int d = s - slot;
            if (d > 4) d -= 9;
            if (d < -4) d += 9;
            boolean fine = shift();
            dist = Math.max(0.8, Math.min(24, dist - d * (fine ? 0.1 : 0.5)));
            mc.player.getInventory().setSelectedSlot(slot);
        }
        if (axis >= 0 && !mc.options.keyAttack.isDown()) axis = -1;
    }

    static boolean shift() {
        var win = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT_SHIFT) || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    void finish(boolean keep) {
        if (!active) return;
        active = false;
        axis = -1;
        if (!keep) m.session.change(null, d -> setPos(d, startPos));
        m.openEditor(id);
    }

    boolean click(boolean attack, boolean held) {
        if (!attack) { if (!held) finish(true); return true; }
        if (!held && hoverAxis >= 0) {
            axis = hoverAxis;
            grabPos = pos(m.session.get(id));
            grabS = Double.NaN;
            follow = false;
        }
        return true;
    }

    /** Кадр: следовать за прицелом / тянуть по оси; нарисовать стрелки. */
    void render(ClientContext.WorldFrame f, HoloClientModule.WorldPainter wp, Vec3 cam, Vec3 eye, Vec3 look) {
        if (!active) return;
        JsonObject o = m.session.get(id);
        if (o == null) return;
        double[] p = pos(o);
        boolean fine = shift();
        double step = fine ? 0.01 : 0.05;
        if (axis >= 0) {
            double s = axisParam(p, grabPos, axis, eye, look);
            if (Double.isNaN(grabS)) grabS = s;
            double[] n = grabPos.clone();
            n[axis] = grabPos[axis] + (s - grabS) * (fine ? 0.25 : 1);
            n[axis] = Math.round(n[axis] / step) * step;
            if (n[axis] != p[axis]) { m.session.change("move", d -> setPos(d, n)); p = n; }
        } else if (follow) {
            double[] n;
            double[] bt = HoloClientModule.blockTarget();
            double bd = bt == null ? 1e9 : Math.sqrt(sq(bt[0] - eye.x) + sq(bt[1] - eye.y) + sq(bt[2] - eye.z));
            if (bt != null && bd < dist) n = new double[]{bt[0] + bt[3] * 0.35, bt[1] + bt[4] * 0.6, bt[2] + bt[5] * 0.35};
            else n = new double[]{eye.x + look.x * dist, eye.y + look.y * dist, eye.z + look.z * dist};
            for (int i = 0; i < 3; i++) n[i] = Math.round(n[i] / step) * step;
            if (n[0] != p[0] || n[1] != p[1] || n[2] != p[2]) { m.session.change("move", d -> setPos(d, n)); p = n; }
        }
        // стрелки-гизмо: X красная, Y зелёная, Z синяя; наведённая/схваченная — ярче
        hoverAxis = -1;
        double bestD = 0.12;
        for (int a = 0; a < 3; a++) {
            double d = rayToSegment(eye, look, p, a);
            if (d < bestD) { bestD = d; hoverAxis = a; }
        }
        PoseStack ps = f.pose();
        ps.pushPose();
        ps.translate((float) (p[0] - cam.x), (float) (p[1] - cam.y), (float) (p[2] - cam.z));
        wp.begin(ps, null, Holo.Theme.SPACE);
        int[] cols = {0xFF5A5A, 0x6BFF7A, 0x5A9CFF};
        for (int a = 0; a < 3; a++) {
            boolean hot = a == axis || (axis < 0 && a == hoverAxis);
            int col = Paint.argb(hot ? 1f : 0.7f, hot ? Paint.mul(cols[a], 1.3f) : cols[a]);
            for (int i = 1; i <= 10; i++) {
                float t = (float) (ARROW * i / 10);
                float s = i == 10 ? 0.11f : 0.05f;
                wp.cube(a == 0 ? t : 0, a == 1 ? t : 0, a == 2 ? t : 0, s, col);
            }
        }
        wp.cube(0, 0, 0, 0.09f, Paint.argb(1f, 0xFFC46B));
        ps.popPose();
    }

    /** Параметр ближайшей к лучу точки на оси a через точку p. */
    static double axisParam(double[] p, double[] origin, int a, Vec3 e, Vec3 d) {
        double[] A = {a == 0 ? 1 : 0, a == 1 ? 1 : 0, a == 2 ? 1 : 0};
        double w0x = origin[0] - e.x, w0y = origin[1] - e.y, w0z = origin[2] - e.z;
        double b = A[0] * d.x + A[1] * d.y + A[2] * d.z;
        double dd = A[0] * w0x + A[1] * w0y + A[2] * w0z;
        double ee = d.x * w0x + d.y * w0y + d.z * w0z;
        double den = 1 - b * b;
        if (den < 1e-6) return 0;
        return (b * ee - dd) / den;
    }

    /** Расстояние от луча до отрезка оси a (длина ARROW) от точки p. */
    static double rayToSegment(Vec3 e, Vec3 d, double[] p, int a) {
        double s = Math.max(0, Math.min(ARROW, axisParam(p, p, a, e, d)));
        double qx = p[0] + (a == 0 ? s : 0), qy = p[1] + (a == 1 ? s : 0), qz = p[2] + (a == 2 ? s : 0);
        double t = Math.max(0, (qx - e.x) * d.x + (qy - e.y) * d.y + (qz - e.z) * d.z);
        double rx = e.x + d.x * t - qx, ry = e.y + d.y * t - qy, rz = e.z + d.z * t - qz;
        return Math.sqrt(rx * rx + ry * ry + rz * rz);
    }
}
