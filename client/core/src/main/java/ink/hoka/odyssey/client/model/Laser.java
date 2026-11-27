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
    private static final float DEFAULT_RANGE = 24;

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
        return p.pick(range(d), pt, false).getLocation();
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

    /** Луч: ядро и свечение (полосы лицом к глазу), вспышка в точке попадания. */
    private static void beam(PoseStack.Pose pose, VertexConsumer vc, Vector3f a, Vector3f b, Vector3f eye) {
        float flick = 0.85f + 0.15f * (float) Math.sin(System.currentTimeMillis() / 35.0);
        strip(pose, vc, a, b, eye, 0.06f * flick, 0x3C, 0x9E, 0xFF, 70);
        strip(pose, vc, a, b, eye, 0.025f * flick, 0x6F, 0xD3, 0xFF, 160);
        strip(pose, vc, a, b, eye, 0.009f, 0xE6, 0xFA, 0xFF, 255);
        spark(pose, vc, b, eye, 0.22f * flick, 0x6F, 0xD3, 0xFF, 120);
        spark(pose, vc, b, eye, 0.09f * flick, 0xFF, 0xFF, 0xFF, 220);
    }

    private static void strip(PoseStack.Pose pose, VertexConsumer vc, Vector3f a, Vector3f b, Vector3f eye, float w, int r, int g, int bl, int al) {
        Vector3f dir = new Vector3f(b).sub(a);
        Vector3f mid = new Vector3f(a).add(b).mul(0.5f).sub(eye);
        Vector3f side = dir.cross(mid, new Vector3f());
        if (side.lengthSquared() < 1e-10f) return;
        side.normalize(w);
        vc.addVertex(pose, a.x - side.x, a.y - side.y, a.z - side.z).setColor(r, g, bl, al);
        vc.addVertex(pose, a.x + side.x, a.y + side.y, a.z + side.z).setColor(r, g, bl, al);
        vc.addVertex(pose, b.x + side.x, b.y + side.y, b.z + side.z).setColor(r, g, bl, al);
        vc.addVertex(pose, b.x - side.x, b.y - side.y, b.z - side.z).setColor(r, g, bl, al);
    }

    private static void spark(PoseStack.Pose pose, VertexConsumer vc, Vector3f c, Vector3f eye, float s, int r, int g, int bl, int al) {
        Vector3f view = new Vector3f(c).sub(eye);
        if (view.lengthSquared() < 1e-8f) return;
        Vector3f up = Math.abs(view.y) > 0.9f * view.length() ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f u = view.cross(up, new Vector3f()).normalize(s), v = view.cross(u, new Vector3f()).normalize(s);
        vc.addVertex(pose, c.x - u.x - v.x, c.y - u.y - v.y, c.z - u.z - v.z).setColor(r, g, bl, al);
        vc.addVertex(pose, c.x + u.x - v.x, c.y + u.y - v.y, c.z + u.z - v.z).setColor(r, g, bl, al);
        vc.addVertex(pose, c.x + u.x + v.x, c.y + u.y + v.y, c.z + u.z + v.z).setColor(r, g, bl, al);
        vc.addVertex(pose, c.x - u.x + v.x, c.y - u.y + v.y, c.z - u.z + v.z).setColor(r, g, bl, al);
    }

    /** Обводка блока, который ломает луч (в проходе рамки игры). true — нарисована (рамку игры не рисуем). */
    public static boolean outline(PoseStack ps, MultiBufferSource buffers, Vec3 cam) {
        var mc = Minecraft.getInstance();
        if (!active || target == null || mc.level == null) return false;
        VoxelShape sh = mc.level.getBlockState(target).getShape(mc.level, target);
        if (sh.isEmpty()) sh = Shapes.block();
        ShapeRenderer.renderShape(ps, buffers.getBuffer(RenderTypes.lines()), sh,
                target.getX() - cam.x, target.getY() - cam.y, target.getZ() - cam.z, 0xFF6FD3FF,
                mc.getWindow().getAppropriateLineWidth() * 2f);
        return true;
    }
}
