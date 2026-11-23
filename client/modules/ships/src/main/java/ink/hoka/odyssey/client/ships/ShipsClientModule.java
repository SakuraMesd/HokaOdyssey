package ink.hoka.odyssey.client.ships;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Transformation;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Корабли. Свой корабль ведёт {@link Pilot} — физика, управление, камера, HUD здесь, без задержки; сервер проверяет.
 * Чужие корабли — на невидимых «якорях» (item display): сервер двигает якорь, здесь рисуются блоки корабля по его
 * сглаженному положению и повороту. Протокол — ShipsClient на сервере.
 */
public final class ShipsClientModule implements ClientModule {

    /** Чужой корабль: якорь, блоки и где он был прошлый тик (чтобы везти стоящих на нём). */
    private static final class Other {
        final int anchor;
        final ShipMesh mesh;
        org.joml.Matrix4f last;

        Other(int anchor, ShipMesh mesh) { this.anchor = anchor; this.mesh = mesh; }
    }

    private final Map<Integer, Other> others = new LinkedHashMap<>();
    private Pilot pilot;
    private ClientContext ctx;
    private ShipGravity gravity;
    private ShipTarget target;

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        ctx.onMessage(this::onMessage);
        ctx.onWorld(this::render);
        gravity = new ShipGravity(ctx);
        target = new ShipTarget(ctx);
        ctx.onTick(() -> {
            if (pilot != null) pilot.tick();
            carry();
            if (gravityHooks) gravity.select(gravityShips(), pilot != null);
            unstuck();
            deck();
            if (pilot == null) target.tick(targets()); else target.tick(java.util.List.of());
        });
        ctx.onMouse((dy, dp) -> pilot != null ? pilot.mouse(dy, dp) : gravity.mouse(dy, dp));
        ctx.onCamera((s, pt) -> {
            if (pilot != null) { pilot.camera(s, pt); return; }
            float w = deckW0 + (deckW - deckW0) * pt;
            deckCamera(s, pt);
            gravity.camera(s, pt, w);
        });
        try {                                                                   // гравитация корабля — ядро 0.21+
            ctx.onInput(in -> gravity.input(in));
            ctx.onLocalTravel(p -> gravity.travel(p));
            gravityHooks = true;
        } catch (LinkageError e) {
            ctx.logger().warn("ядро мода старше 0.21 — гравитации внутри корабля нет до обновления мода");
        }
        try {                                                                   // клики по кораблю — ядро 0.25+
            ctx.onClick((attack, held) -> pilot == null && target.click(attack, held));
        } catch (LinkageError e) {
            ctx.logger().warn("ядро мода старше 0.25 — двери, сундуки и стройка на корабле не работают до обновления мода");
        }
        try {                                                                   // модели игроков — по палубе корабля (ядро 0.22+)
            ctx.onEntityTilt(this::tilt);
        } catch (LinkageError e) {
            ctx.logger().warn("ядро мода старше 0.22 — игроки на корабле рисуются ровно по миру");
        }
        ctx.onHud((g, d) -> { if (pilot != null) pilot.hud(g, d); });
        try {
            ctx.onCollision(this::collide);
        } catch (LinkageError e) {                                              // ядро старше 0.13 — без столкновений
            ctx.logger().warn("ядро мода без столкновений (нужно 0.13+) — по кораблям ходить нельзя до перезапуска игры");
        }
    }

    @Override
    public void disable() {
        if (pilot != null) pilot.end();
        pilot = null;
        others.clear();
        ShipHud.release();
    }

    private void onMessage(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            switch (in.readUnsignedByte()) {
                case 1 -> {                                                     // ATTACH — чужой корабль
                    int anchor = in.readInt();
                    in.readInt();
                    float seatH = in.readFloat(), turn = in.readFloat();
                    Other old = others.get(anchor);
                    Other o = new Other(anchor, new ShipMesh(parts(in), seatH, turn));
                    if (old != null) o.last = old.last;                         // тот же корабль — не терять «где был»
                    others.put(anchor, o);
                }
                case 2 -> others.remove(in.readInt());                          // DETACH
                case 3 -> {                                                     // START — ты пилот
                    int anchor = in.readInt();
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    float baseYaw = in.readFloat(), seatH = in.readFloat();
                    byte[] js = new byte[in.readInt()];
                    in.readFully(js);
                    JsonObject cfg = JsonParser.parseString(new String(js, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (pilot != null) pilot.end();
                    pilot = new Pilot(ctx, anchor, x, y, z, baseYaw, seatH, cfg, parts(in));
                    ctx.logger().info("полёт: блоков {}", pilot.mesh.parts.length);
                }
                case 4 -> {                                                     // END
                    if (pilot != null) pilot.end();
                    pilot = null;
                    snapDeck = true;                                            // из кабины — сразу «на палубе», без перехода
                }
                case 5 -> {                                                     // CORRECT
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    float yaw = in.readFloat(), pitch = in.readFloat(), roll = in.readFloat();
                    if (pilot != null) pilot.correct(x, y, z, yaw, pitch, roll);
                }
                case 6 -> {                                                     // CELL — клетка корабля изменилась
                    int anchor = in.readInt();
                    int dx = in.readShort(), dy = in.readShort(), dz = in.readShort(), id = in.readInt();
                    Other o = others.get(anchor);
                    if (o != null) o.mesh.update(dx, dy, dz, id);
                }
                default -> { }
            }
        } catch (Exception e) {
            ctx.logger().warn("пакет кораблей не разобран: {}", e.toString());
        }
    }

    private static int[][] parts(DataInputStream in) throws java.io.IOException {
        int n = Math.min(in.readInt(), 200_000);
        int[][] parts = new int[n][];
        for (int i = 0; i < n; i++) parts[i] = new int[]{in.readShort(), in.readShort(), in.readShort(), in.readInt()};
        return parts;
    }

    /**
     * Столкновения с кораблями на якорях: тело корабля — его система (блоки ровно по сетке) и формы блоков в ней.
     * Ядро считает столкновение в этой системе: палуба гладкая при любом повороте, ступеньки — настоящей формы.
     */
    private void collide(Entity entity, AABB area, java.util.List<ClientContext.Body> out) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || others.isEmpty()) return;
        Vec3 c = area.getCenter();
        double rad = Math.sqrt(area.getXsize() * area.getXsize() + area.getYsize() * area.getYsize() + area.getZsize() * area.getZsize()) / 2 + 1.5;
        for (Other o : others.values()) {
            Entity e = mc.level.getEntity(o.anchor);
            if (!(e instanceof Display d) || d.renderState() == null) continue;
            Vec3 pos = d.position();
            if (pos.distanceTo(c) > o.mesh.radius + rad) continue;
            org.joml.Matrix4f toWorld = place(d, o);
            ShipMesh m = o.mesh;
            out.add(new ClientContext.Body() {
                @Override public org.joml.Matrix4fc toWorld() { return toWorld; }

                @Override public java.util.List<net.minecraft.world.phys.shapes.VoxelShape> shapes(AABB a) {
                    java.util.List<net.minecraft.world.phys.shapes.VoxelShape> list = new java.util.ArrayList<>();
                    int x0 = (int) Math.floor(a.minX + 0.5), x1 = (int) Math.floor(a.maxX + 0.5);
                    int y0 = (int) Math.floor(a.minY + m.seatH), y1 = (int) Math.floor(a.maxY + m.seatH);
                    int z0 = (int) Math.floor(a.minZ + 0.5), z1 = (int) Math.floor(a.maxZ + 0.5);
                    for (int dy = y0; dy <= y1; dy++) for (int dz = z0; dz <= z1; dz++) for (int dx = x0; dx <= x1; dx++) {
                        var sh = m.shape(dx, dy, dz);
                        if (sh != null) list.add(sh);
                    }
                    return list;
                }
            });
        }
    }

    private boolean gravityHooks;

    /** Модель игрока внутри корабля — по его палубе (свой — по гравитации корабля, чужие — по коробке корабля). */
    private org.joml.Quaternionfc tilt(Entity e, float pt) {
        if (!(e instanceof net.minecraft.world.entity.player.Player pl) || pl.isPassenger() || others.isEmpty()) return null;
        Minecraft mc = Minecraft.getInstance();
        if (pl == mc.player) return gravity.active() ? gravity.shipRotation(pt) : null;
        if (pl.getAbilities().flying || pl.isSpectator()) return null;
        Vec3 pos = pl.getPosition(pt);
        for (ShipGravity.Ship s : gravityShips(pos)) if (ShipGravity.inside(s, pos, 0.1f)) return s.placeAt(pt).getNormalizedRotation(new org.joml.Quaternionf());
        return null;
    }

    /** Корабли рядом — для гравитации корабля. */
    private java.util.List<ShipGravity.Ship> gravityShips() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? new java.util.ArrayList<>() : gravityShips(mc.player.position());
    }

    private java.util.List<ShipGravity.Ship> gravityShips(Vec3 near) {
        Minecraft mc = Minecraft.getInstance();
        java.util.List<ShipGravity.Ship> list = new java.util.ArrayList<>();
        if (mc.level == null) return list;
        for (Other o : others.values()) {
            Entity e = mc.level.getEntity(o.anchor);
            if (!(e instanceof Display d) || d.renderState() == null) continue;
            if (d.position().distanceTo(near) > o.mesh.radius + 4) continue;
            list.add(ship(d, o));
        }
        return list;
    }

    private static ShipGravity.Ship ship(Display d, Other o) {
        return new ShipGravity.Ship() {
            @Override public org.joml.Matrix4f place() { return ShipsClientModule.place(d, o); }

            @Override public org.joml.Matrix4f placeAt(float pt) {
                Vec3 pos = d.getPosition(pt);
                return new org.joml.Matrix4f().translation((float) pos.x, (float) pos.y, (float) pos.z)
                        .mul(new org.joml.Matrix4f(d.renderState().transformation().get(d.calculateInterpolationProgress(pt)).getMatrix()))
                        .rotateY((float) Math.toRadians(o.mesh.turn));
            }

            @Override public ShipMesh mesh() { return o.mesh; }
        };
    }

    /** Корабли рядом — для кликов (по ним ищется блок под прицелом). */
    private java.util.List<ShipTarget.Cand> targets() {
        Minecraft mc = Minecraft.getInstance();
        java.util.List<ShipTarget.Cand> list = new java.util.ArrayList<>();
        if (mc.level == null || mc.player == null) return list;
        for (Other o : others.values()) {
            Entity e = mc.level.getEntity(o.anchor);
            if (!(e instanceof Display d) || d.renderState() == null) continue;
            if (d.position().distanceTo(mc.player.position()) > o.mesh.radius + 8) continue;
            list.add(new ShipTarget.Cand(o.anchor, ship(d, o)));
        }
        return list;
    }

    /** Система корабля → мир на конец текущего тика (как его видят столкновения). */
    private static org.joml.Matrix4f place(Display d, Other o) {
        Vec3 pos = d.position();
        return new org.joml.Matrix4f().translation((float) pos.x, (float) pos.y, (float) pos.z)
                .mul(new org.joml.Matrix4f(d.renderState().transformation().get(d.calculateInterpolationProgress(1f)).getMatrix()))
                .rotateY((float) Math.toRadians(o.mesh.turn));
    }

    /**
     * Стоящих на корабле — везти вместе с ним: корабль сдвинулся/повернулся с прошлого тика — игрок тоже (точка
     * ног в системе корабля та же), и взгляд поворачивается на столько же.
     */
    private void carry() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || others.isEmpty()) return;
        var p = mc.player;
        for (Other o : others.values()) {
            Entity e = mc.level.getEntity(o.anchor);
            if (!(e instanceof Display d) || d.renderState() == null) { o.last = null; continue; }
            org.joml.Matrix4f now = place(d, o);
            org.joml.Matrix4f last = o.last;
            o.last = now;
            if (last == null || pilot != null || p.isPassenger()) continue;
            if (last.equals(now, 1e-6f)) continue;                              // корабль стоит — не трогать (без дрожи float)
            org.joml.Vector3d lpd = new org.joml.Matrix4d(last).invert().transformPosition(new org.joml.Vector3d(p.getX(), p.getY(), p.getZ()));
            org.joml.Vector3f lp = new org.joml.Vector3f((float) lpd.x, (float) lpd.y, (float) lpd.z);
            // стоит на корабле: под ногами (в системе корабля) — блок корабля, и игрок не летит/не падает далеко
            int dx = Math.round(lp.x), dz = Math.round(lp.z), dy = (int) Math.floor(lp.y + o.mesh.seatH - 0.05f);
            boolean inGravity = gravity != null && gravity.isOn(o.mesh);
            boolean on = inGravity || o.mesh.shape(dx, dy, dz) != null || o.mesh.shape(dx, dy - 1, dz) != null;
            if (!on || p.getAbilities().flying) continue;
            org.joml.Vector3d np = new org.joml.Matrix4d(now).transformPosition(new org.joml.Vector3d(lpd));
            double mx = np.x - p.getX(), my = np.y - p.getY(), mz = np.z - p.getZ();
            if (mx * mx + my * my + mz * mz < 1e-8) continue;
            if (mx * mx + my * my + mz * mz > 64) continue;                     // скачок — не наш случай
            p.setPos(np.x, np.y, np.z);
            // поворот взгляда — на поворот корабля вокруг вертикали
            org.joml.Vector3f f0 = last.transformDirection(new org.joml.Vector3f(0, 0, 1)), f1 = now.transformDirection(new org.joml.Vector3f(0, 0, 1));
            float a0 = (float) Math.toDegrees(Math.atan2(-f0.x, f0.z)), a1 = (float) Math.toDegrees(Math.atan2(-f1.x, f1.z));
            float dyaw = net.minecraft.util.Mth.wrapDegrees(a1 - a0);
            if (!inGravity && Math.abs(dyaw) > 1e-3f) {
                p.setYRot(p.getYRot() + dyaw);
                p.yRotO += dyaw;
                p.setYHeadRot(p.getYHeadRot() + dyaw);
            }
        }
    }

    /**
     * Страховка (как у игры для обычных блоков): игрок оказался внутри блоков корабля (резкий манёвр, присел/встал)
     * — вытолкнуть: вверх на палубу, если до неё меньше 0.75 блока, иначе в ближайшую сторону. Изнутри блок игрока
     * не держит, поэтому без этого он «тонет».
     */
    /** Коробка (система корабля) пересекает блоки корабля. */
    private static boolean blocked(ShipMesh m, AABB lb) {
        int x0 = (int) Math.floor(lb.minX + 0.5), x1 = (int) Math.floor(lb.maxX + 0.5);
        int y0 = (int) Math.floor(lb.minY + m.seatH), y1 = (int) Math.floor(lb.maxY + m.seatH);
        int z0 = (int) Math.floor(lb.minZ + 0.5), z1 = (int) Math.floor(lb.maxZ + 0.5);
        for (int dy = y0; dy <= y1; dy++) for (int dz = z0; dz <= z1; dz++) for (int dx = x0; dx <= x1; dx++) {
            var sh = m.shape(dx, dy, dz);
            if (sh == null) continue;
            for (AABB a : sh.toAabbs()) if (a.intersects(lb)) return true;
        }
        return false;
    }

    private void unstuck() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || others.isEmpty() || pilot != null) return;
        var p = mc.player;
        if (p.isSpectator() || p.getAbilities().flying || p.isPassenger()) return;
        AABB box = p.getBoundingBox();
        for (Other o : others.values()) {
            Entity e = mc.level.getEntity(o.anchor);
            if (!(e instanceof Display d) || d.renderState() == null) continue;
            if (d.position().distanceTo(box.getCenter()) > o.mesh.radius + 3) continue;
            org.joml.Matrix4d toWorld = new org.joml.Matrix4d(place(d, o)), toLocal = new org.joml.Matrix4d(toWorld).invert();
            // как в ядре: коробка от ног вверх по «вверх» корабля
            org.joml.Vector3d c = toLocal.transformPosition(new org.joml.Vector3d((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2));
            double hx = box.getXsize() / 2 - 0.01, hz = box.getZsize() / 2 - 0.01, hy = 0.01;
            AABB lb = new AABB(c.x - hx, c.y + 0.01, c.z - hz, c.x + hx, c.y + box.getYsize() - 0.01, c.z + hz);
            // пересечения с блоками корабля (в его системе)
            double up = 0, best = Double.MAX_VALUE;
            double[] push = null;
            boolean any = false;
            int x0 = (int) Math.floor(lb.minX + 0.5), x1 = (int) Math.floor(lb.maxX + 0.5);
            int y0 = (int) Math.floor(lb.minY + o.mesh.seatH), y1 = (int) Math.floor(lb.maxY + o.mesh.seatH);
            int z0 = (int) Math.floor(lb.minZ + 0.5), z1 = (int) Math.floor(lb.maxZ + 0.5);
            java.util.List<AABB> hit = new java.util.ArrayList<>();
            for (int dy = y0; dy <= y1; dy++) for (int dz = z0; dz <= z1; dz++) for (int dx = x0; dx <= x1; dx++) {
                var sh = o.mesh.shape(dx, dy, dz);
                if (sh == null) continue;
                for (AABB a : sh.toAabbs()) if (a.intersects(lb)) {
                    hit.add(a);
                    any = true;
                    // пол: блок прямо под серединой ног, верх чуть выше ног (стены сбоку — не пол)
                    boolean under = c.x >= a.minX && c.x <= a.maxX && c.z >= a.minZ && c.z <= a.maxZ;
                    if (under && a.maxY - lb.minY <= 0.6 && a.minY <= lb.minY + 0.05) up = Math.max(up, a.maxY - lb.minY);
                }
            }
            if (!any) continue;
            // в пол — поднять на палубу; в стену чуть-чуть (до трети блока) — назад, где свободно; иначе не трогать
            if (up > 0) { push = new double[]{0, up + 0.011, 0}; best = up; }
            else {
                double[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (double[] dv : dirs) {
                    double need = 0;
                    for (AABB a : hit) {
                        double n = dv[0] > 0 ? a.maxX - lb.minX : dv[0] < 0 ? lb.maxX - a.minX : dv[1] > 0 ? a.maxZ - lb.minZ : lb.maxZ - a.minZ;
                        need = Math.max(need, n);
                    }
                    double[] cand = {dv[0] * (need + 0.011), 0, dv[1] * (need + 0.011)};
                    if (need <= 0.34 && need < best && !blocked(o.mesh, lb.move(cand[0], 0, cand[2]))) { best = need; push = cand; }
                }
            }
            if (push == null || best > 2.5) continue;
            org.joml.Vector3d w = new org.joml.Vector3d(push[0], push[1], push[2]);
            toWorld.transformDirection(w);
            p.setPos(p.getX() + w.x, p.getY() + w.y, p.getZ() + w.z);
            if (gravity != null) gravity.moved(w.x, w.y, w.z);
            if (push[1] > 0) {                                                  // встал на палубу — не падать дальше
                var m = p.getDeltaMovement();
                p.setDeltaMovement(m.x, Math.max(0, m.y), m.z);
                p.setOnGround(true);
                p.resetFallDistance();
            }
            return;
        }
    }

    /** Корабль под ногами: поворот его системы (для глаз) и плавный вес перехода «на палубе / не на палубе». */
    private org.joml.Matrix3f deckRot;
    private float deckW, deckW0;
    private boolean snapDeck;

    /** Стоит ли игрок на корабле (под ногами в его системе — блоки корабля в пределах 4 клеток вниз). */
    private void deck() {
        Minecraft mc = Minecraft.getInstance();
        deckW0 = deckW;
        org.joml.Matrix3f found = gravity != null && gravity.active() ? gravity.rotation() : null;
        if (found == null && mc.level != null && mc.player != null && pilot == null && !others.isEmpty()) {
            var p = mc.player;
            for (Other o : others.values()) {
                Entity e = mc.level.getEntity(o.anchor);
                if (!(e instanceof Display d) || d.renderState() == null) continue;
                if (d.position().distanceTo(p.position()) > o.mesh.radius + 3) continue;
                org.joml.Matrix4f w = place(d, o);
                org.joml.Vector3f lp = new org.joml.Matrix4f(w).invert().transformPosition((float) p.getX(), (float) p.getY(), (float) p.getZ(), new org.joml.Vector3f());
                int dx = Math.round(lp.x), dz = Math.round(lp.z), dy = (int) Math.floor(lp.y + o.mesh.seatH - 0.05f);
                boolean on = false;
                for (int k = 0; k <= 4 && !on; k++) on = o.mesh.cells.containsKey(ShipMesh.key(dx, dy - k, dz));
                if (!on) continue;
                org.joml.Matrix3f r = w.get3x3(new org.joml.Matrix3f());
                if (r.transform(new org.joml.Vector3f(0, 1, 0)).y < 0.5f) continue;    // палуба вверх ногами — не наш случай
                found = r;
                break;
            }
        }
        if (found != null) deckRot = found;
        deckW = Math.max(0, Math.min(1, deckW + (found != null ? 0.25f : -0.25f)));
        if (snapDeck && found != null) { deckW = deckW0 = 1; snapDeck = false; }
        if (deckW == 0 && deckW0 == 0) deckRot = null;
    }

    /**
     * Глаза на палубе — по «вверх» корабля над ногами (как коробка столкновения), а не строго вверх по миру:
     * на корабле с креном иначе глаза уходят вбок дальше края коробки, и у стены видно сквозь блоки.
     */
    private void deckCamera(ink.hoka.odyssey.client.api.CameraState s, float pt) {
        if (s.detached || deckRot == null) return;
        float w = deckW0 + (deckW - deckW0) * pt;
        if (w <= 0) return;
        var p = Minecraft.getInstance().player;
        if (p == null) return;
        double eye = s.y - net.minecraft.util.Mth.lerp(pt, p.yo, p.getY());    // высота глаз (плавная при приседе)
        org.joml.Vector3f u = deckRot.transform(new org.joml.Vector3f(0, (float) eye, 0));
        s.x += u.x * w;
        s.y += (u.y - eye) * w;
        s.z += u.z * w;
    }

    private void render(ClientContext.WorldFrame f) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (pilot != null) pilot.render(f);
        if (others.isEmpty()) return;
        float pt = f.partialTick();
        Vec3 cam = f.camera();
        for (Other o : others.values()) {
            Entity e = mc.level.getEntity(o.anchor);
            if (!(e instanceof Display d) || d.renderState() == null) continue;
            Vec3 pos = d.getPosition(pt);
            float r = o.mesh.radius;
            if (!f.visible(new AABB(pos.x - r, pos.y - r, pos.z - r, pos.x + r, pos.y + r, pos.z + r))) continue;
            Transformation tr = d.renderState().transformation().get(d.calculateInterpolationProgress(pt));
            PoseStack ps = f.pose();
            ps.pushPose();
            ps.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
            ps.mulPose(tr.getMatrix());                                         // поворот корабля (курс, наклон, крен)
            o.mesh.draw(ps, f.buffers(), LevelRenderer.getLightColor(mc.level, BlockPos.containing(pos.add(0, 1, 0))));
            o.mesh.drawEntities(ps, f);
            ps.popPose();
        }
        if (pilot == null) target.render(f);
    }
}
