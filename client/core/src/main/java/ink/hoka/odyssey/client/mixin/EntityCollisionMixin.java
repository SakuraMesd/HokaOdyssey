package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ink.hoka.odyssey.client.Hooks;
import ink.hoka.odyssey.client.api.ClientContext;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Тела модулей (ClientContext.onCollision — корабли под любым углом): после того как игра (и Lithium, если стоит)
 * посчитала столкновения с миром, движение ограничивается ещё и телами. Считается в системе тела, где его блоки
 * стоят ровно по сетке: коробка сущности (от ног вверх по «вверх» тела) и движение переводятся туда, ограничиваются
 * по правилам игры (вертикаль, потом большая горизонталь) с подъёмом на ступеньку, результат — обратно в мир.
 * <p>
 * Флаги столкновения игра выводит из сравнения «просили / получили» по осям мира — на наклонном теле поворот меняет
 * все оси, и игра решала бы, что сущность стоит на земле прямо в воздухе у стены (прыжки по стене, медленное
 * падение). Поэтому, если тело вмешалось, флаги («стоит», «упёрся», гашение скорости) ставятся по системе тела.
 */
@Mixin(Entity.class)
public abstract class EntityCollisionMixin {

    @Shadow public abstract AABB getBoundingBox();
    @Shadow public abstract boolean onGround();
    @Shadow public abstract float maxUpStep();
    @Shadow public abstract net.minecraft.world.level.Level level();
    @Shadow public abstract Vec3 getDeltaMovement();
    @Shadow public abstract void setDeltaMovement(Vec3 v);

    /** Тело вмешалось в этот move: флаги — наши. */
    @Unique private boolean hoka$mine;
    /** Упёрся по вертикали (палуба снизу / потолок сверху — в системе тела, или мир), упёрся вбок. */
    @Unique private boolean hoka$vert, hoka$horiz;
    /** Упёрся в пол (по «низу» тела, или мира) — «стоит на земле»: покачивание камеры, шаги, прыжок. */
    @Unique private boolean hoka$below;
    /** Поворот тела, которое упёрло вбок, и по каким его осям упёрло. */
    @Unique private Matrix3d hoka$rot;
    @Unique private boolean hoka$bx, hoka$bz;

    @Inject(method = "move", at = @At("HEAD"))
    private void hoka$moveStart(MoverType type, Vec3 movement, CallbackInfo ci) {
        hoka$mine = false;
    }

    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void hoka$bodies(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        if (!Hooks.hasCollision() || !level().isClientSide()) return;
        Vec3 v = cir.getReturnValue();
        if (v.lengthSqr() == 0) return;                                         // мир и так не пустил
        Entity self = (Entity) (Object) this;
        AABB box = getBoundingBox();
        float step = maxUpStep();
        List<ClientContext.Body> bodies = Hooks.bodies(self, box.expandTowards(v).inflate(0.5 + step));
        if (bodies.isEmpty()) return;
        // мир сам упёр по вертикали/вбок — это тоже идёт во флаги
        hoka$vert = v.y != movement.y;
        hoka$below = hoka$vert && movement.y < 0;
        hoka$horiz = !net.minecraft.util.Mth.equal(v.x, movement.x) || !net.minecraft.util.Mth.equal(v.z, movement.z);
        hoka$rot = null;
        hoka$bx = hoka$bz = false;
        boolean touched = false;
        Vec3 res = v;
        for (ClientContext.Body b : bodies) {
            Vec3 r = through(b, box, res, step);
            if (r != null) { res = r; touched = true; }
        }
        if (!touched) return;
        hoka$mine = true;
        if (!res.equals(v)) cir.setReturnValue(res);
    }

    /**
     * Движение v (мир) коробки box, ограниченное телом b; null — тело не мешает. Ставит флаги hoka$*.
     * Всё в double: погрешность float заводила внутрь блоков.
     */
    @Unique
    private Vec3 through(ClientContext.Body b, AABB box, Vec3 v, float step) {
        Matrix4d toWorld = new Matrix4d(b.toWorldD()), toLocal = new Matrix4d(toWorld).invert();
        Matrix3d rot = toWorld.get3x3(new Matrix3d()), inv = new Matrix3d(rot).transpose();
        // коробка в системе тела — от ног вверх по «вверх» тела (стоит на палубе прямо): на наклонном корабле рост
        // коробки (встал из приседа) идёт вверх, а не вбок в стену. Чуть меньше (как у игры): касание ≠ «внутри».
        Vector3d c = toLocal.transformPosition(new Vector3d((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2));
        double hx = box.getXsize() / 2 - SKIN, hz = box.getZsize() / 2 - SKIN;
        AABB lb = new AABB(c.x - hx, c.y + SKIN, c.z - hz, c.x + hx, c.y + box.getYsize() - SKIN, c.z + hz);
        Vec3 l = loc(inv, v.x, v.y, v.z);
        List<VoxelShape> shapes = b.shapes(lb.expandTowards(l).inflate(0.2).expandTowards(0, step, 0).expandTowards(0, -step, 0));
        if (shapes.isEmpty()) return null;
        Vector3d up = rot.transform(new Vector3d(0, 1, 0));
        // уже в палубе (игра опускает на 0.15 при вставании из приседа, сверяясь только с блоками мира) — поднять
        double lift = 0;
        for (VoxelShape sh : shapes) for (AABB a : sh.toAabbs())
            if (a.intersects(lb) && a.maxY - lb.minY <= 0.6 && a.minY <= lb.minY + 0.05
                    && c.x >= a.minX && c.x <= a.maxX && c.z >= a.minZ && c.z <= a.maxZ)      // под серединой ног, не стена
                lift = Math.max(lift, a.maxY - lb.minY);
        if (lift > 0 && up.y > 0.5) {
            lb = lb.move(0, lift, 0);
            Vec3 lh = v.y < 0 ? loc(inv, v.x, 0, v.z) : l;
            lh = new Vec3(lh.x, Math.max(lh.y, 0), lh.z);
            Vec3 out = clip(lh, lb, shapes);
            flags(rot, lh, out);
            hoka$vert = true;                                                   // стоит на палубе
            hoka$below = true;
            return world(rot, out.add(0, lift, 0));
        }
        Vec3 out = clip(l, lb, shapes);
        if (out.equals(l)) return null;                                         // тело не мешает
        // тяжесть — прижать к палубе: если вниз по «вниз» тела упираемся в палубу, вертикаль мира — по «вниз» тела
        // (на наклонной палубе не съезжаешь); в воздухе у стены — падаем как обычно, вниз по миру
        if (v.y < 0 && up.y > 0.5 && !((Entity) (Object) this).isNoGravity()) {
            Vec3 h = loc(inv, v.x, 0, v.z);
            Vec3 lr = new Vec3(h.x, h.y + v.y, h.z);
            Vec3 outr = clip(lr, lb, shapes);
            if (outr.y > lr.y + 1e-7) { l = lr; out = outr; }
        }
        boolean blocked = Math.abs(out.x - l.x) > 1e-7 || Math.abs(out.z - l.z) > 1e-7;
        boolean grounded = onGround() || (l.y < 0 && out.y > l.y + 1e-7);
        if (blocked && grounded && step > 0) {                                  // ступенька
            Vec3 upOnly = clip(new Vec3(0, step, 0), lb, shapes);
            Vec3 h = clip(new Vec3(l.x, 0, l.z), lb.move(0, upOnly.y, 0), shapes);
            if (h.horizontalDistanceSqr() > out.horizontalDistanceSqr() + 1e-7) {
                Vec3 down = clip(new Vec3(0, -upOnly.y + Math.min(0, l.y), 0), lb.move(h.x, upOnly.y, h.z), shapes);
                out = new Vec3(h.x, upOnly.y + down.y, h.z);
            }
        }
        flags(rot, l, out);
        return world(rot, out);
    }

    /** Флаги по системе тела: упёрлись ли по её вертикали и по её горизонтальным осям. */
    @Unique
    private void flags(Matrix3d rot, Vec3 l, Vec3 out) {
        if (Math.abs(out.y - l.y) > 1e-7) hoka$vert = true;
        if (l.y < 0 && out.y > l.y + 1e-7) hoka$below = true;                  // упёрлись вниз по телу — стоим
        boolean bx = Math.abs(out.x - l.x) > 1e-7, bz = Math.abs(out.z - l.z) > 1e-7;
        if (bx || bz) {
            hoka$horiz = true;
            if (hoka$rot == null) { hoka$rot = rot; hoka$bx = bx; hoka$bz = bz; }
        }
    }

    @Unique
    private static Vec3 loc(Matrix3d inv, double x, double y, double z) {
        Vector3d r = inv.transform(new Vector3d(x, y, z));
        return new Vec3(r.x, r.y, r.z);
    }

    @Unique
    private static Vec3 world(Matrix3d rot, Vec3 l) {
        Vector3d w = rot.transform(new Vector3d(l.x, l.y, l.z));
        return new Vec3(w.x, w.y, w.z);
    }

    // ── флаги move: если вмешалось тело — по системе тела, а не по сравнению осей мира ──

    @Inject(method = "move", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;horizontalCollision:Z",
            opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER, ordinal = 1))
    private void hoka$horizontal(MoverType type, Vec3 movement, CallbackInfo ci) {
        if (hoka$mine) ((Entity) (Object) this).horizontalCollision = hoka$horiz;
    }

    @Inject(method = "move", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;verticalCollision:Z",
            opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER, ordinal = 1))
    private void hoka$vertical(MoverType type, Vec3 movement, CallbackInfo ci) {
        if (hoka$mine) ((Entity) (Object) this).verticalCollision = hoka$vert;
    }

    @Inject(method = "move", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;verticalCollisionBelow:Z",
            opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER, ordinal = 1))
    private void hoka$below(MoverType type, Vec3 movement, CallbackInfo ci) {
        if (hoka$mine) ((Entity) (Object) this).verticalCollisionBelow = hoka$below;
    }

    /** Упёрся вбок о тело: гасить скорость только по упёршимся осям тела (стена корабля косая к осям мира). */
    @WrapOperation(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(DDD)V"))
    private void hoka$stopAtWall(Entity self, double x, double y, double z, Operation<Void> op) {
        if (!hoka$mine || hoka$rot == null) { op.call(self, x, y, z); return; }
        Vec3 dm = getDeltaMovement();
        Vec3 l = loc(new Matrix3d(hoka$rot).transpose(), dm.x, dm.y, dm.z);
        l = new Vec3(hoka$bx ? 0 : l.x, l.y, hoka$bz ? 0 : l.z);
        Vec3 w = world(hoka$rot, l);
        setDeltaMovement(new Vec3(w.x, dm.y, w.z));
    }

    /** «Приземлился» (гасит вертикальную скорость) — только если правда упёрся по вертикали. */
    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;updateEntityMovementAfterFallOn(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;)V"))
    private void hoka$fallOn(Block block, BlockGetter level, Entity e, Operation<Void> op) {
        if (hoka$mine && !hoka$vert) return;
        op.call(block, level, e);
    }

    @Unique private static final double SKIN = 1e-3;

    /** Движение v коробки box, ограниченное фигурами: Y, затем большая горизонталь, затем меньшая — как у игры. */
    @Unique
    private static Vec3 clip(Vec3 v, AABB box, List<VoxelShape> shapes) {
        double x = v.x, y = v.y, z = v.z;
        if (y != 0) {
            y = Shapes.collide(Direction.Axis.Y, box, shapes, y);
            if (y != 0) box = box.move(0, y, 0);
        }
        boolean zFirst = Math.abs(x) < Math.abs(z);
        if (zFirst && z != 0) {
            z = Shapes.collide(Direction.Axis.Z, box, shapes, z);
            if (z != 0) box = box.move(0, 0, z);
        }
        if (x != 0) {
            x = Shapes.collide(Direction.Axis.X, box, shapes, x);
            if (!zFirst && x != 0) box = box.move(x, 0, 0);
        }
        if (!zFirst && z != 0) z = Shapes.collide(Direction.Axis.Z, box, shapes, z);
        return new Vec3(x, y, z);
    }
}
