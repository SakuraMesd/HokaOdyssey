package ink.hoka.odyssey.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.71 Лазер предмета-модели (Аксиом): пока зажата ЛКМ — луч из дула в точку прицела «+» до первого блока; ломает
 * сервер (модуль mining: время, трещины всем, выпадение), здесь — луч, обводка блока-цели, лучи других игроков.
 * Протокол ядра op 11: C2S — byte активен; если да: byte есть блок, int x,y,z, float точка x,y,z. S2C — int сущность,
 * byte активен, float точка x,y,z (лучи других игроков).
 */
public final class Laser {

    private Laser() {}

    static final int OP = 11;
    private static final float DEFAULT_RANGE = 10;

    private static boolean active, sentActive;
    private static BlockPos target, sentTarget;
    private static int sinceSend;

    /** Лучи других игроков: сущность → точка попадания. */
    private static final Map<Integer, Vec3> OTHERS = new HashMap<>();
    /** Дуло в мире (записано при рисовании модели от 3-го лица в этом кадре): сущность → точка. */
    private static final Map<Integer, Vec3> MUZZLE = new HashMap<>();

    /** Держим предмет с лазером (ЛКМ — луч, а не удар/ломание). */
    public static boolean holding() {
        var p = Minecraft.getInstance().player;
        if (p == null || p.isSpectator()) return false;
        HeldModels.Def d = HeldModels.find(p.getMainHandItem());
        return d != null && d.laser;
    }

    static float range(HeldModels.Def d) { return d.range > 0 ? d.range : DEFAULT_RANGE; }

    /** Луч виден у себя: зажата ЛКМ или открыта вкладка «Луч» окна настройки (предпросмотр). */
    static boolean showSelf() { return active || HeldEditor.previewBeam(); }

    public static void tick() {
        var mc = Minecraft.getInstance();
        var p = mc.player;
        boolean want = false;
        BlockHitResult bh = null;
        Vec3 endAt = Vec3.ZERO;
        if (p != null && holding() && mc.screen == null && mc.options.keyAttack.isDown()) {
            want = true;
            HitResult r = p.pick(range(HeldModels.find(p.getMainHandItem())), 1f, false);
            if (r instanceof BlockHitResult b && r.getType() == HitResult.Type.BLOCK) bh = b;
            endAt = r.getLocation();
        }
        active = want;
        target = bh != null ? bh.getBlockPos() : null;
        sinceSend++;
        boolean changed = want != sentActive || (want && !java.util.Objects.equals(target, sentTarget));
        if (!changed && !(want && sinceSend >= 5)) return;
        final BlockHitResult fb = bh;
        final Vec3 at = endAt;
        final boolean fw = want;
        ink.hoka.odyssey.client.Net.sendOp(OP, out -> {
            out.writeByte(fw ? 1 : 0);
            if (!fw) return;
            out.writeByte(fb != null ? 1 : 0);
            BlockPos bp = fb != null ? fb.getBlockPos() : BlockPos.ZERO;
            out.writeInt(bp.getX());
            out.writeInt(bp.getY());
            out.writeInt(bp.getZ());
            out.writeFloat((float) at.x);
            out.writeFloat((float) at.y);
            out.writeFloat((float) at.z);
        });
        sentActive = want;
        sentTarget = target;
        sinceSend = 0;
    }

    /** S2C op 11. */
    public static void handle(DataInputStream in) throws IOException {
        int id = in.readInt();
        boolean on = in.readByte() != 0;
        Vec3 at = new Vec3(in.readFloat(), in.readFloat(), in.readFloat());
        Minecraft.getInstance().execute(() -> { if (on) OTHERS.put(id, at); else OTHERS.remove(id); });
    }

    public static void clear() { OTHERS.clear(); MUZZLE.clear(); active = sentActive = false; target = sentTarget = null; }

    /** Точка, куда бьёт свой луч (кадр): блок под «+» или конец дальности. */
    static Vec3 selfEnd(float pt) {
        var p = Minecraft.getInstance().player;
        if (p == null) return null;
        HeldModels.Def d = HeldModels.find(p.getMainHandItem());
        if (d == null) return null;
        var mc = Minecraft.getInstance();
        if (mc.options.getCameraType() != CameraType.FIRST_PERSON) return p.pick(range(d), pt, false).getLocation();
        // от первого лица — из камеры: её высота при приседании меняется плавно (у игрока — рывком), луч не прыгает
        var cam = mc.gameRenderer.getMainCamera();
        Vec3 from = cam.position();
        var f = cam.forwardVector();
        Vec3 to = from.add(f.x() * range(d), f.y() * range(d), f.z() * range(d));
        return mc.level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, p)).getLocation();
    }

    static void muzzle(int entity, Vec3 world) { MUZZLE.put(entity, world); }

    // ───────────────────────── рисование ─────────────────────────

    /** Свой луч от первого лица: в системе руки (root), начало — дуло в ней, конец — точка в системе камеры. */
    static void firstPerson(PoseStack ps, SubmitNodeCollector snc, Vector3f muzzleRoot, Matrix4f root) {
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 end = selfEnd(pt);
        if (end == null) return;
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f e = new Vector3f((float) (end.x - cam.position().x), (float) (end.y - cam.position().y), (float) (end.z - cam.position().z));
        cam.rotation().conjugate(new org.joml.Quaternionf()).transform(e);
        Vector3f eye = new Matrix4f(root).invert().transformPosition(new Vector3f());
        snc.submitCustomGeometry(ps, RenderTypes.lightning(), (pose, vc) -> beam(pose, vc, muzzleRoot, e, eye));
    }

    /** Лучи в мире: свой (не от первого лица) и других игроков. После сущностей. */
    public static void renderWorld(WorldRenderContext ctx) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) { MUZZLE.clear(); return; }
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 cam = Models.camera();
        Map<Integer, Vec3> beams = new HashMap<>(OTHERS);
        if (mc.player != null && showSelf() && mc.options.getCameraType() != CameraType.FIRST_PERSON) {
            Vec3 end = selfEnd(pt);
            if (end != null) beams.put(mc.player.getId(), end);
        } else if (mc.player != null) beams.remove(mc.player.getId());
        if (beams.isEmpty()) { MUZZLE.clear(); return; }
        MultiBufferSource src = ctx.consumers();
        VertexConsumer vc = src.getBuffer(RenderTypes.lightning());
        PoseStack.Pose pose = ctx.matrices().last();
        Vector3f eye = new Vector3f();
        for (var b : beams.entrySet()) {
            Entity e = mc.level.getEntity(b.getKey());
            if (e == null) continue;
            Vec3 s = MUZZLE.get(b.getKey());
            if (s == null) {                                                     // модель не рисовалась — от правого плеча
                Vec3 look = e.getViewVector(pt), eyeP = e.getEyePosition(pt);
                Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
                s = eyeP.add(right.scale(0.35)).add(look.scale(0.6)).add(0, -0.35, 0);
            }
            Vec3 t = b.getValue();
            beam(pose, vc, new Vector3f((float) (s.x - cam.x), (float) (s.y - cam.y), (float) (s.z - cam.z)),
                    new Vector3f((float) (t.x - cam.x), (float) (t.y - cam.y), (float) (t.z - cam.z)), eye);
        }
        if (src instanceof MultiBufferSource.BufferSource bs) bs.endBatch(RenderTypes.lightning());
        MUZZLE.clear();
    }

    /**
     * Луч: извивается (две волны поперёк, у дула и у цели — ровно), мягкое свечение (края прозрачные, свет
     * складывается), тонкая вторая «молния» вокруг, круглая вспышка в точке попадания.
     */
    private static void beam(PoseStack.Pose pose, VertexConsumer vc, Vector3f a, Vector3f b, Vector3f eye) {
        float t = (System.currentTimeMillis() % 100_000L) / 1000f;
        float flick = 0.88f + 0.12f * (float) Math.sin(t * 28);
        Vector3f[] main = path(a, b, 0.07f, t, 0);
        Vector3f[] wild = path(a, b, 0.16f, t * 1.7f, 2.1f);
        ribbon(pose, vc, main, eye, 0.10f * flick, 0x2E, 0x8C, 0xFF, 55);
        ribbon(pose, vc, main, eye, 0.04f * flick, 0x6F, 0xD3, 0xFF, 150);
        ribbon(pose, vc, main, eye, 0.014f, 0xEE, 0xFB, 0xFF, 255);
        ribbon(pose, vc, wild, eye, 0.012f, 0x8F, 0xDF, 0xFF, 110);
        glow(pose, vc, b, eye, 0.30f * flick, 0x3C, 0x9E, 0xFF, 120);
        glow(pose, vc, b, eye, 0.11f * flick, 0xFF, 0xFF, 0xFF, 230);
    }

    /** Точки извилистого луча: amp — размах (блоки), phase — сдвиг волн. Концы — ровно в a и b. */
    private static Vector3f[] path(Vector3f a, Vector3f b, float amp, float t, float phase) {
        Vector3f dir = new Vector3f(b).sub(a);
        float len = dir.length();
        int n = Math.max(8, Math.min(96, (int) (len * 12)));
        Vector3f d = new Vector3f(dir).normalize();
        Vector3f n1 = Math.abs(d.y) > 0.95f ? new Vector3f(1, 0, 0).cross(d) : new Vector3f(0, 1, 0).cross(d);
        n1.normalize();
        Vector3f n2 = new Vector3f(d).cross(n1).normalize();
        float a0 = amp * Math.min(1, len / 3);
        Vector3f[] out = new Vector3f[n + 1];
        for (int i = 0; i <= n; i++) {
            float u = i / (float) n, dist = u * len;
            float taper = (float) Math.sin(Math.PI * u);
            float w1 = (float) Math.sin(dist * 2.6f - t * 9 + phase) * a0 * taper;
            float w2 = (float) Math.sin(dist * 4.1f + t * 7 + phase * 1.7f) * a0 * 0.6f * taper;
            out[i] = new Vector3f(dir).mul(u).add(a).add(n1.x * w1 + n2.x * w2, n1.y * w1 + n2.y * w2, n1.z * w1 + n2.z * w2);
        }
        return out;
    }

    /** Лента вдоль точек лицом к глазу: середина — цвет, края — прозрачные (мягкое свечение). */
    private static void ribbon(PoseStack.Pose pose, VertexConsumer vc, Vector3f[] p, Vector3f eye, float w, int r, int g, int bl, int al) {
        Vector3f[] side = new Vector3f[p.length];
        Vector3f prev = null;
        for (int i = 0; i < p.length; i++) {
            Vector3f tan = new Vector3f(p[Math.min(p.length - 1, i + 1)]).sub(p[Math.max(0, i - 1)]).normalize();
            Vector3f view = new Vector3f(p[i]).sub(eye);
            Vector3f s = tan.cross(view, new Vector3f());
            if (s.lengthSquared() < 1e-6f * Math.max(1e-6f, view.lengthSquared())) s = prev != null ? new Vector3f(prev) : new Vector3f(0, 1, 0).cross(tan);
            if (prev != null && s.dot(prev) < 0) s.negate();                      // не перекручивать ленту
            s.normalize(w);
            side[i] = s;
            prev = s;
        }
        for (int i = 0; i + 1 < p.length; i++) {
            quad(pose, vc, new Vector3f(p[i]).sub(side[i]), p[i], p[i + 1], new Vector3f(p[i + 1]).sub(side[i + 1]), r, g, bl, 0, al, al, 0);
            quad(pose, vc, p[i], new Vector3f(p[i]).add(side[i]), new Vector3f(p[i + 1]).add(side[i + 1]), p[i + 1], r, g, bl, al, 0, 0, al);
        }
    }

    /** Круглая вспышка лицом к глазу: в центре — цвет, к краю — прозрачная. */
    private static void glow(PoseStack.Pose pose, VertexConsumer vc, Vector3f c, Vector3f eye, float rad, int r, int g, int bl, int al) {
        Vector3f view = new Vector3f(c).sub(eye);
        if (view.lengthSquared() < 1e-8f) return;
        Vector3f up = Math.abs(view.y) > 0.9f * view.length() ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f u = view.cross(up, new Vector3f()).normalize(rad), v = view.cross(u, new Vector3f()).normalize(rad);
        int n = 16;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            Vector3f e0 = new Vector3f(c).add(new Vector3f(u).mul((float) Math.cos(t0))).add(new Vector3f(v).mul((float) Math.sin(t0)));
            Vector3f e1 = new Vector3f(c).add(new Vector3f(u).mul((float) Math.cos(t1))).add(new Vector3f(v).mul((float) Math.sin(t1)));
            quad(pose, vc, c, e0, e1, c, r, g, bl, al, 0, 0, al);
        }
    }

    /** Двусторонний четырёхугольник с прозрачностью по вершинам (у прохода lightning отсечение задних граней). */
    private static void quad(PoseStack.Pose pose, VertexConsumer vc, Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3,
                             int r, int g, int b, int a0, int a1, int a2, int a3) {
        Vector3f[] q = {p0, p1, p2, p3, p3, p2, p1, p0};
        int[] al = {a0, a1, a2, a3, a3, a2, a1, a0};
        for (int i = 0; i < 8; i++) vc.addVertex(pose, q[i].x, q[i].y, q[i].z).setColor(r, g, b, al[i]);
    }

    /** Ребро рамки: лента лицом к глазу (камера — в 0), концы продлены на полширины — углы без щелей. */
    private static void edge(PoseStack.Pose pose, VertexConsumer vc, Vector3f a, Vector3f b, int alpha) {
        float w = 0.012f + 0.0025f * a.length();                                   // вдали толще — видно одинаково
        Vector3f d = new Vector3f(b).sub(a).normalize(w);
        Vector3f a2 = new Vector3f(a).sub(d), b2 = new Vector3f(b).add(d);
        Vector3f side = new Vector3f(b2).sub(a2).cross(new Vector3f(a2).add(b2).mul(0.5f), new Vector3f());
        if (side.lengthSquared() < 1e-12f) return;
        side.normalize(w);
        int c = 0x6FD3FF;
        Vector3f[] q = {new Vector3f(a2).sub(side), new Vector3f(a2).add(side), new Vector3f(b2).add(side), new Vector3f(b2).sub(side)};
        for (Vector3f v : q) vc.addVertex(pose, v.x, v.y, v.z).setColor((c >> 16) & 255, (c >> 8) & 255, c & 255, alpha);
    }

    /** Обводка блока, который ломает луч (в проходе рамки игры). true — нарисована (рамку игры не рисуем). */
    public static boolean outline(PoseStack ps, MultiBufferSource buffers, Vec3 cam) {
        var mc = Minecraft.getInstance();
        if (!active || target == null || mc.level == null) return false;
        VoxelShape sh = mc.level.getBlockState(target).getShape(mc.level, target);
        if (sh.isEmpty()) sh = Shapes.block();
        int alpha = (int) (120 + 25 * Math.sin(System.currentTimeMillis() / 160.0));          // ~50%, мягко пульсирует
        var bb = sh.bounds().inflate(0.004).move(target.getX() - cam.x, target.getY() - cam.y, target.getZ() - cam.z);
        float[] xs = {(float) bb.minX, (float) bb.maxX}, ys = {(float) bb.minY, (float) bb.maxY}, zs = {(float) bb.minZ, (float) bb.maxZ};
        VertexConsumer vc = buffers.getBuffer(RenderTypes.debugQuads());
        PoseStack.Pose pose = ps.last();
        for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
            edge(pose, vc, new Vector3f(xs[0], ys[i], zs[j]), new Vector3f(xs[1], ys[i], zs[j]), alpha);
            edge(pose, vc, new Vector3f(xs[i], ys[0], zs[j]), new Vector3f(xs[i], ys[1], zs[j]), alpha);
            edge(pose, vc, new Vector3f(xs[i], ys[j], zs[0]), new Vector3f(xs[i], ys[j], zs[1]), alpha);
        }
        if (buffers instanceof MultiBufferSource.BufferSource bs) bs.endBatch(RenderTypes.debugQuads());
        return true;
    }
}
