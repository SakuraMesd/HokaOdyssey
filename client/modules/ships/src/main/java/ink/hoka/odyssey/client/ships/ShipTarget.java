package ink.hoka.odyssey.client.ships;

import com.mojang.blaze3d.vertex.PoseStack;
import ink.hoka.odyssey.client.api.ClientContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Куда смотрит игрок на стоящем корабле (его блоков в мире нет — игра их не видит): луч из глаз против блоков
 * корабля в его системе. Ближе, чем то, что нашла игра, — это и есть цель: рамка выделения вокруг блока, ПКМ/ЛКМ
 * уходят серверу (дверь, сундук, стройка), а игре клик не достаётся.
 */
final class ShipTarget {

    /** Корабль, по которому можно кликать: его якорь и положение. */
    record Cand(int anchor, ShipGravity.Ship ship) {}

    /** Цель: корабль, клетка, грань (в системе корабля), точка попадания в клетке (0..1). */
    record Hit(int anchor, ShipGravity.Ship ship, int dx, int dy, int dz, Direction face, Vec3 in) {}

    private final ClientContext ctx;
    private Hit hit;
    private int useDelay;

    ShipTarget(ClientContext ctx) { this.ctx = ctx; }

    /** Каждый тик: найти цель среди кораблей рядом. */
    void tick(Iterable<Cand> ships) {
        if (useDelay > 0) useDelay--;
        hit = null;
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        if (p == null || mc.level == null || p.isSpectator()) return;
        Vec3 eye, dir;
        if (mc.options.getCameraType() == CameraType.FIRST_PERSON) {            // по прицелу: камера (на корабле глаза по его «вверх»)
            var cam = mc.gameRenderer.getMainCamera();
            eye = cam.position();
            var f = cam.forwardVector();
            dir = new Vec3(f.x(), f.y(), f.z());
        } else {
            eye = p.getEyePosition(1f);
            dir = p.getViewVector(1f);
        }
        double reach = p.blockInteractionRange();
        double best = reach;
        HitResult vanilla = mc.hitResult;
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS) best = Math.min(best, vanilla.getLocation().distanceTo(eye));
        Vec3 to = eye.add(dir.scale(reach));
        for (Cand c : ships) {
            ShipMesh m = c.ship().mesh();
            Matrix4d toLocal = new Matrix4d(c.ship().place()).invert();
            Vector3d a = toLocal.transformPosition(new Vector3d(eye.x, eye.y, eye.z)), b = toLocal.transformPosition(new Vector3d(to.x, to.y, to.z));
            Vec3 la = new Vec3(a.x, a.y, a.z), lb = new Vec3(b.x, b.y, b.z);
            int x0 = (int) Math.floor(Math.min(a.x, b.x) + 0.5), x1 = (int) Math.floor(Math.max(a.x, b.x) + 0.5);
            int y0 = (int) Math.floor(Math.min(a.y, b.y) + m.seatH), y1 = (int) Math.floor(Math.max(a.y, b.y) + m.seatH);
            int z0 = (int) Math.floor(Math.min(a.z, b.z) + 0.5), z1 = (int) Math.floor(Math.max(a.z, b.z) + 0.5);
            for (int dy = y0; dy <= y1; dy++) for (int dz = z0; dz <= z1; dz++) for (int dx = x0; dx <= x1; dx++) {
                var sh = m.outline(dx, dy, dz);
                if (sh == null) continue;
                BlockHitResult r = sh.clip(la, lb, BlockPos.ZERO);
                if (r == null || r.getType() == HitResult.Type.MISS) continue;
                double d = r.getLocation().distanceTo(la);
                if (d >= best) continue;
                best = d;
                Vec3 l = r.getLocation();
                hit = new Hit(c.anchor(), c.ship(), dx, dy, dz, r.getDirection(),
                        new Vec3(l.x - (dx - 0.5), l.y - (dy - m.seatH), l.z - (dz - 0.5)));
            }
        }
    }

    boolean has() { return hit != null; }

    /** Рамка выделения вокруг блока-цели (как у игры). */
    void render(ClientContext.WorldFrame f) {
        Hit h = hit;
        if (h == null) return;
        var sh = h.ship().mesh().outline(h.dx(), h.dy(), h.dz());
        if (sh == null) return;
        Matrix4f m = h.ship().placeAt(f.partialTick());
        Vec3 cam = f.camera();
        PoseStack ps = f.pose();
        ps.pushPose();
        ps.translate(m.m30() - cam.x, m.m31() - cam.y, m.m32() - cam.z);
        ps.mulPose(m.getNormalizedRotation(new Quaternionf()));
        ShapeRenderer.renderShape(ps, f.buffers().getBuffer(RenderTypes.lines()), sh, 0, 0, 0, 0x66000000,
                Minecraft.getInstance().getWindow().getAppropriateLineWidth());
        ps.popPose();
    }

    /** Клик игрока: по кораблю — серверу, игре — нет. */
    boolean click(boolean attack, boolean held) {
        Hit h = hit;
        if (h == null) return false;
        var p = Minecraft.getInstance().player;
        if (p == null) return false;
        if (held) return true;                                                  // зажатая ЛКМ по кораблю — мир не ломать
        if (!attack) {
            if (useDelay > 0) return true;
            useDelay = 4;                                                       // как у игры: ПКМ с зажатой кнопкой — раз в 4 тика
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bo)) {
            out.writeByte(attack ? 5 : 4);
            out.writeInt(h.anchor());
            out.writeShort(h.dx());
            out.writeShort(h.dy());
            out.writeShort(h.dz());
            if (!attack) {
                out.writeByte(h.face().get3DDataValue());                       // down up north south west east
                out.writeFloat((float) h.in().x);
                out.writeFloat((float) h.in().y);
                out.writeFloat((float) h.in().z);
            }
        } catch (IOException ignored) {
        }
        ctx.send(bo.toByteArray());
        p.swing(InteractionHand.MAIN_HAND);
        return true;
    }
}
