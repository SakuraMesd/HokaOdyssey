package ink.hoka.odyssey.client.vessels;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ink.hoka.odyssey.client.api.ClientContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Эффекты кораблей у клиента: болты орудия (яркая полоса летит от ствола к цели с gun.bolt-speed — видно, что не
 * мгновенно), вспышка у ствола, искры и звук в точке попадания; варп — зарядка (частицы, звук) и «туннель» на экране
 * при прыжке (затемнение по краям и полосы звёзд от центра, ~1.3 с).
 */
final class Effects {

    /** Болт: откуда, куда, что задел (0 мимо, 1 блок, 2 сущность, 3 корабль, 4 незагружено), когда выпущен. */
    private static final class Bolt {
        final Vec3 from, to, dir;
        final double len;
        final int kind;
        final long born;
        boolean hit;
        long hitAt;

        Bolt(Vec3 from, Vec3 to, int kind) {
            this.from = from;
            this.to = to;
            Vec3 d = to.subtract(from);
            this.len = d.length();
            this.dir = len > 1e-6 ? d.scale(1 / len) : new Vec3(0, 0, 1);
            this.kind = kind;
            this.born = System.currentTimeMillis();
        }
    }

    private final List<Bolt> bolts = new ArrayList<>();
    double boltSpeed = 220;
    private long tunnelAt = -1, chargeAt = -1;
    private int chargeTicks;

    // ───────────────────────── орудие ─────────────────────────

    /** S2C SHOT: болт, звук выстрела и вспышка у ствола. */
    void shot(Vec3 from, Vec3 to, int kind) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        bolts.add(new Bolt(from, to, kind));
        if (bolts.size() > 200) bolts.remove(0);
        mc.level.playLocalSound(from.x, from.y, from.z, SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 0.55f, 1.7f + mc.level.random.nextFloat() * 0.2f, false);
        for (int i = 0; i < 4; i++) {
            var r = mc.level.random;
            mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, from.x, from.y, from.z, (r.nextDouble() - 0.5) * 0.3, (r.nextDouble() - 0.5) * 0.3, (r.nextDouble() - 0.5) * 0.3);
        }
    }

    /** Каждый тик: долетевшие болты — искры и звук; старые — убрать. */
    void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { bolts.clear(); return; }
        long now = System.currentTimeMillis();
        var r = mc.level.random;
        for (Iterator<Bolt> it = bolts.iterator(); it.hasNext(); ) {
            Bolt b = it.next();
            double went = (now - b.born) / 1000.0 * boltSpeed;
            if (!b.hit && went >= b.len) {
                b.hit = true;
                b.hitAt = now;
                if (b.kind >= 1 && b.kind <= 3) {
                    Vec3 back = b.to.subtract(b.dir.scale(0.15));
                    for (int i = 0; i < 14; i++)
                        mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, back.x, back.y, back.z, (r.nextDouble() - 0.5) * 0.6 - b.dir.x * 0.2,
                                (r.nextDouble() - 0.3) * 0.6 - b.dir.y * 0.2, (r.nextDouble() - 0.5) * 0.6 - b.dir.z * 0.2);
                    for (int i = 0; i < 3; i++) mc.level.addParticle(ParticleTypes.SMOKE, back.x, back.y, back.z, 0, 0.03, 0);
                    mc.level.playLocalSound(b.to.x, b.to.y, b.to.z, SoundEvents.SHULKER_BULLET_HIT, SoundSource.NEUTRAL, 0.8f, 1.3f + r.nextFloat() * 0.3f, false);
                }
            }
            if (b.hit && now - b.hitAt > 250) it.remove();
        }
        if (chargeAt >= 0 && mc.player != null) {                               // зарядка варпа — частицы вокруг
            Vec3 c = mc.player.getEyePosition();
            for (int i = 0; i < 6; i++) {
                double a = r.nextDouble() * Math.PI * 2, rad = 2 + r.nextDouble() * 4;
                mc.level.addParticle(ParticleTypes.REVERSE_PORTAL, c.x + Math.cos(a) * rad, c.y + (r.nextDouble() - 0.5) * 3, c.z + Math.sin(a) * rad, 0, 0, 0);
            }
        }
    }

    /** В мире: болты (лента лицом к камере), вспышки у ствола и в точке попадания. */
    void render(ClientContext.WorldFrame f) {
        if (bolts.isEmpty()) return;
        long now = System.currentTimeMillis();
        Vec3 cam = f.camera();
        MultiBufferSource src = f.buffers();
        VertexConsumer vc = src.getBuffer(RenderTypes.lightning());
        PoseStack.Pose pose = f.pose().last();
        Vector3f eye = new Vector3f();
        for (Bolt b : bolts) {
            double went = (now - b.born) / 1000.0 * boltSpeed;
            if (!b.hit || now - b.hitAt < 60) {
                double head = Math.min(b.len, went), tail = Math.max(0, went - 6);
                if (head > tail) {
                    Vector3f h = rel(b.from.add(b.dir.scale(head)), cam), t = rel(b.from.add(b.dir.scale(tail)), cam);
                    ribbon(pose, vc, t, h, eye, 0.22f, 0xFF, 0x7A, 0x2E, 70);
                    ribbon(pose, vc, t, h, eye, 0.09f, 0xFF, 0xC8, 0x6E, 170);
                    ribbon(pose, vc, t, h, eye, 0.03f, 0xFF, 0xF8, 0xE8, 255);
                    glow(pose, vc, h, eye, 0.28f, 0xFF, 0xB0, 0x50, 150);
                }
            }
            long age = now - b.born;
            if (age < 90) glow(pose, vc, rel(b.from, cam), eye, 0.6f * (1 - age / 90f) + 0.2f, 0xFF, 0xD0, 0x80, 200);
            if (b.hit && b.kind >= 1 && b.kind <= 3) {
                float k = 1 - (now - b.hitAt) / 250f;
                if (k > 0) glow(pose, vc, rel(b.to, cam), eye, 0.9f * k + 0.1f, 0xFF, 0xE0, 0xA0, (int) (230 * k));
            }
        }
        if (src instanceof MultiBufferSource.BufferSource bs) bs.endBatch(RenderTypes.lightning());
    }

    private static Vector3f rel(Vec3 p, Vec3 cam) { return new Vector3f((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)); }

    /** Прямая лента a→b лицом к глазу: середина — цвет, края — прозрачные. */
    private static void ribbon(PoseStack.Pose pose, VertexConsumer vc, Vector3f a, Vector3f b, Vector3f eye, float w, int r, int g, int bl, int al) {
        Vector3f dir = new Vector3f(b).sub(a);
        Vector3f view = new Vector3f(a).add(b).mul(0.5f).sub(eye);
        Vector3f s = dir.cross(view, new Vector3f());
        if (s.lengthSquared() < 1e-10f) s.set(0, 1, 0);
        s.normalize(w);
        quad(pose, vc, new Vector3f(a).sub(s), a, b, new Vector3f(b).sub(s), r, g, bl, 0, al, al, 0);
        quad(pose, vc, a, new Vector3f(a).add(s), new Vector3f(b).add(s), b, r, g, bl, al, 0, 0, al);
    }

    /** Круглая вспышка лицом к глазу. */
    private static void glow(PoseStack.Pose pose, VertexConsumer vc, Vector3f c, Vector3f eye, float rad, int r, int g, int bl, int al) {
        Vector3f view = new Vector3f(c).sub(eye);
        if (view.lengthSquared() < 1e-8f) return;
        Vector3f up = Math.abs(view.y) > 0.9f * view.length() ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f u = view.cross(up, new Vector3f()).normalize(rad), v = view.cross(u, new Vector3f()).normalize(rad);
        int n = 12;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            Vector3f e0 = new Vector3f(c).add(new Vector3f(u).mul((float) Math.cos(t0))).add(new Vector3f(v).mul((float) Math.sin(t0)));
            Vector3f e1 = new Vector3f(c).add(new Vector3f(u).mul((float) Math.cos(t1))).add(new Vector3f(v).mul((float) Math.sin(t1)));
            quad(pose, vc, c, e0, e1, c, r, g, bl, al, 0, 0, al);
        }
    }

    /** Двусторонний четырёхугольник с прозрачностью по вершинам (у прохода lightning свет складывается). */
    private static void quad(PoseStack.Pose pose, VertexConsumer vc, Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3,
                             int r, int g, int b, int a0, int a1, int a2, int a3) {
        Vector3f[] q = {p0, p1, p2, p3, p3, p2, p1, p0};
        int[] al = {a0, a1, a2, a3, a3, a2, a1, a0};
        for (int i = 0; i < 8; i++) vc.addVertex(pose, q[i].x, q[i].y, q[i].z).setColor(r, g, b, al[i]);
    }

    // ───────────────────────── варп ─────────────────────────

    /** S2C WARP: 1 — зарядка (ticks), 0 — отмена, 2 — прыжок. */
    void warp(int what, int ticks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Vec3 p = mc.player.getEyePosition();
        switch (what) {
            case 1 -> {
                chargeAt = System.currentTimeMillis();
                chargeTicks = Math.max(1, ticks);
                mc.level.playLocalSound(p.x, p.y, p.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.NEUTRAL, 0.8f, 0.6f, false);
                mc.level.playLocalSound(p.x, p.y, p.z, SoundEvents.PORTAL_TRIGGER, SoundSource.NEUTRAL, 0.35f, 1.6f, false);
            }
            case 0 -> {
                chargeAt = -1;
                mc.level.playLocalSound(p.x, p.y, p.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.NEUTRAL, 0.7f, 1.2f, false);
            }
            case 2 -> {
                chargeAt = -1;
                tunnelAt = System.currentTimeMillis();
                mc.level.playLocalSound(p.x, p.y, p.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 1f, 0.5f, false);
                mc.level.playLocalSound(p.x, p.y, p.z, SoundEvents.BEACON_ACTIVATE, SoundSource.NEUTRAL, 0.8f, 1.8f, false);
            }
            default -> { }
        }
    }

    /** Доля зарядки варпа (0…1) или −1 — не заряжается. */
    float charge() {
        if (chargeAt < 0) return -1;
        return Math.min(1f, (System.currentTimeMillis() - chargeAt) / (chargeTicks * 50f));
    }

    void stopWarp() { chargeAt = -1; }

    /**
     * Туннель на экране: при зарядке — лёгкое затемнение краёв и редкие полосы, при прыжке ~1.3 с — тёмные края и
     * полосы звёзд от центра (быстро разлетаются), в начале — белая вспышка.
     */
    void tunnel(GuiGraphics g) {
        long now = System.currentTimeMillis();
        float jump = tunnelAt < 0 ? -1 : (now - tunnelAt) / 1300f;
        if (jump > 1) { tunnelAt = -1; jump = -1; }
        float ch = charge();
        if (jump < 0 && ch < 0) return;
        int w = g.guiWidth(), h = g.guiHeight();
        float power = jump >= 0 ? (float) Math.sin(Math.PI * Math.min(1, jump * 1.4)) : ch * 0.35f;
        vignette(g, w, h, power);
        int n = jump >= 0 ? 90 : 24;
        float cx = w / 2f, cy = h / 2f, maxR = (float) Math.hypot(cx, cy);
        float t = (now % 100_000L) / 1000f;
        for (int i = 0; i < n; i++) {
            long seed = i * 2654435761L;
            float ang = (float) ((seed % 6283) / 1000.0);
            float speed = 0.6f + (seed >>> 8) % 100 / 100f;
            float phase = ((seed >>> 16) % 1000) / 1000f;
            float u = (phase + t * speed * (jump >= 0 ? 1.6f : 0.5f)) % 1f;
            float r0 = maxR * (0.08f + u * u), len = maxR * (jump >= 0 ? 0.35f : 0.08f) * u;
            int a = (int) (255 * power * Math.min(1, u * 3));
            if (a < 8) continue;
            int col = (a << 24) | (jump >= 0 ? 0xD8E8FF : 0xB890FF);
            float dx = (float) Math.cos(ang), dy = (float) Math.sin(ang);
            for (float s = 0; s < len; s += 2) {
                int x = Math.round(cx + dx * (r0 + s)), y = Math.round(cy + dy * (r0 + s));
                if (x < 0 || y < 0 || x >= w || y >= h) break;
                g.fill(x, y, x + 1, y + 1, col);
            }
        }
        if (jump >= 0 && jump < 0.12f) {                                       // вспышка в момент прыжка
            int a = (int) (200 * (1 - jump / 0.12f));
            g.fill(0, 0, w, h, (a << 24) | 0xE8F0FF);
        }
    }

    /** Тёмные края экрана (рамками, к центру — прозрачнее). */
    private static void vignette(GuiGraphics g, int w, int h, float power) {
        int steps = 12;
        for (int i = 0; i < steps; i++) {
            int a = (int) (200 * power * (1 - i / (float) steps));
            if (a <= 0) continue;
            int col = a << 24 | 0x05040A;
            int mx = (int) (w * 0.04f * i), my = (int) (h * 0.04f * i), sx = Math.max(1, (int) (w * 0.04f)), sy = Math.max(1, (int) (h * 0.04f));
            g.fill(mx, my, w - mx, my + sy, col);
            g.fill(mx, h - my - sy, w - mx, h - my, col);
            g.fill(mx, my + sy, mx + sx, h - my - sy, col);
            g.fill(w - mx - sx, my + sy, w - mx, h - my - sy, col);
        }
    }

    void clear() {
        bolts.clear();
        tunnelAt = chargeAt = -1;
    }
}
