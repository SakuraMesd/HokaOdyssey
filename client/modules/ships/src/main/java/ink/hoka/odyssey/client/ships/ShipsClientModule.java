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
            for (Other o : others.values()) o.mesh.tickLids();                 // крышки сундуков кораблей
            tickDeck();
            if (pilot != null) pilot.mesh.tickLids();
            if (pilot != null) pilot.tick();
            if (gravityHooks) gravity.select(gravityShips(), pilot != null);
            deck();
            if (pilot == null) target.tick(targets()); else target.tick(java.util.List.of());
        });
        ctx.onMouse((dy, dp) -> pilot != null ? pilot.mouse(dy, dp) : gravity.mouse(dy, dp));
        try {                                                                   // ядро 0.43+: колёсико по блоку корабля
            ctx.onPick(ctrl -> target.pick(ctrl));
        } catch (LinkageError old) {
        }
        ctx.onCamera((s, pt) -> {
            if (pilot != null) { pilot.camera(s, pt); gravity.resetView(); }
            else {
                float w = deckW0 + (deckW - deckW0) * pt;
                if (!gravityHooks) deckCamera(s, pt);                           // старое ядро — только глаза по палубе
                gravity.camera(s, pt, w);
            }
            seatBlend(s);
        });
        try {                                                                   // гравитация корабля — ядро 0.21+
            ctx.onInput(in -> gravity.input(in));
            ctx.onLocalTravel(p -> gravity.travel(p));
            ctx.onLocalTravelEnd(p -> gravity.travelEnd(p));
            gravityHooks = true;
            try {                                                               // ядро 0.36+: в системе корабля — весь тик игрока
                ctx.onLocalTick(p -> gravity.frameEnter(p), p -> gravity.frameExit(p));
                gravity.wholeTick = true;
            } catch (LinkageError old) {
                ctx.logger().warn("ядро мода старше 0.36 — на корабле ноги и частицы бега не как у игры до обновления мода");
            }
        } catch (LinkageError e) {
            ctx.logger().warn("ядро мода старше 0.21 — гравитации внутри корабля нет до обновления мода");
        }
        try {                                                                   // клики по кораблю — ядро 0.25+
            ctx.onClick((attack, held) -> pilot == null && target.click(attack, held));
        } catch (LinkageError e) {
            ctx.logger().warn("ядро мода старше 0.25 — двери, сундуки и стройка на корабле не работают до обновления мода");
        }
        try {                                                                   // рамка выделения как у игры — ядро 0.26+
            ctx.onOutline((ps, buffers, cam, pt) -> pilot == null && target.outline(ps, buffers, cam, pt));
        } catch (LinkageError e) {
            ctx.logger().warn("ядро мода старше 0.26 — рамки выделения на корабле нет до обновления мода");
        }
        try {                                                                   // модели игроков — по палубе корабля (ядро 0.22+)
            ctx.onEntityTilt(this::tilt);
            try {                                                               // ядро 0.37+: своя модель — углы палубы; частицы верфи — на корабле
                ctx.onEntityPose((e, pt) -> e == Minecraft.getInstance().player ? gravity.pose(pt) : null);
                ctx.setYardResolver(this::yardToWorld);
                try { ctx.setLocalView(pt -> gravity.localView()); gravity.handLocal = true; } catch (LinkageError old) { }
            } catch (LinkageError old) {
                ctx.logger().warn("ядро мода старше 0.37 — частицы на корабле и поза модели по-старому до обновления мода");
            }
        } catch (LinkageError e) {
            ctx.logger().warn("ядро мода старше 0.22 — игроки на корабле рисуются ровно по миру");
        }
        ctx.onHud((g, d) -> { if (pilot != null) pilot.hud(g, d); else gravity.hud(g); });
        try {
            ctx.onCollision(this::collide);
        } catch (LinkageError e) {                                              // ядро старше 0.13 — без столкновений
            ctx.logger().warn("ядро мода без столкновений (нужно 0.13+) — по кораблям ходить нельзя до перезапуска игры");
        }
    }

    @Override
    public void disable() {
        var me = Minecraft.getInstance().player;
        if (me != null && gravity != null) gravity.frameExit(me);               // выгрузка посреди тика — вернуть игрока в мир
        if (pilot != null) pilot.end();
        pilot = null;
        for (Other o : others.values()) o.mesh.release();
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
                    if (in.available() > 0 && in.readUnsignedByte() == 1)          // корабль в верфи: где его плот
                        o.mesh.yard = new int[]{in.readInt(), in.readInt(), in.readInt()};
                    if (old != null) { o.last = old.last; old.mesh.release(); } // тот же корабль — не терять «где был»
                    others.put(anchor, o);
                }
                case 2 -> { Other gone = others.remove(in.readInt()); if (gone != null) gone.mesh.release(); }   // DETACH
                case 3 -> {                                                     // START — ты пилот
                    camBlend = true;                                            // с палубы в кабину — камера доезжает
                    int anchor = in.readInt();
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    float baseYaw = in.readFloat(), seatH = in.readFloat();
                    byte[] js = new byte[in.readInt()];
                    in.readFully(js);
                    JsonObject cfg = JsonParser.parseString(new String(js, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (pilot != null) pilot.end();
                    int mode = in.readUnsignedByte();
                    if (mode == 1) {                                            // корабль из верфи: сетка — та же, что у модели на якоре
                        in.readInt(); in.readInt(); in.readInt();
                        Other o = others.get(anchor);
                        ShipMesh shared = o != null ? o.mesh : new ShipMesh(new int[0][], seatH, baseYaw);
                        pilot = new Pilot(ctx, anchor, x, y, z, baseYaw, cfg, shared, o == null);
                    } else pilot = new Pilot(ctx, anchor, x, y, z, baseYaw, seatH, cfg, parts(in));
                    ctx.logger().info("полёт: блоков {}", pilot.mesh.cells.size());
                }
                case 4 -> {                                                     // END
                    Pilot was = pilot;
                    if (pilot != null) { gravity.seedUp(pilot.cameraRotation().transform(new org.joml.Vector3f(0, 1, 0))); pilot.end(); }
                    pilot = null;
                    if (was != null) standUp(was);                              // слезли с кресла — на свободную клетку рядом
                    camBlend = true;                                            // из кабины на сиденье — камера доезжает
                    pilot = null;
                    snapDeck = true;                                            // из кабины — сразу «на палубе», без перехода
                }
                case 5 -> {                                                     // CORRECT
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    float yaw = in.readFloat(), pitch = in.readFloat(), roll = in.readFloat();
                    if (pilot != null) pilot.correct(x, y, z, yaw, pitch, roll);
                }
                case 8 -> {                                                     // LID — крышка сундука корабля
                    int anchor = in.readInt();
                    int dx = in.readShort(), dy = in.readShort(), dz = in.readShort(), n = in.readUnsignedByte();
                    Other o = others.get(anchor);
                    if (o != null) o.mesh.lid(dx, dy, dz, n);
                    if (pilot != null && pilot.anchor == anchor && (o == null || pilot.mesh != o.mesh)) pilot.mesh.lid(dx, dy, dz, n);
                    Minecraft mc = Minecraft.getInstance();                     // звук — там, где сундук на корабле
                    if (o != null && mc.level != null && mc.level.getEntity(anchor) instanceof Display d && d.renderState() != null) {
                        org.joml.Vector3f w = ship(d, o).place().transformPosition(new org.joml.Vector3f(dx, dy - o.mesh.seatH + 0.5f, dz));
                        var snd = n > 0 ? net.minecraft.sounds.SoundEvents.CHEST_OPEN : net.minecraft.sounds.SoundEvents.CHEST_CLOSE;
                        if (n <= 1) mc.level.playLocalSound(w.x, w.y, w.z, snd, net.minecraft.sounds.SoundSource.BLOCKS, 0.5f,
                                mc.level.random.nextFloat() * 0.1f + 0.9f, false);
                    }
                }
                case 7 -> {                                                     // LEVEL — есть ли здесь автовыравнивание
                    boolean ok = in.readUnsignedByte() != 0;
                    if (pilot != null) pilot.levelAllowed(ok);
                }
                case 6 -> {                                                     // CELL — клетка корабля изменилась
                    int anchor = in.readInt();
                    int dx = in.readShort(), dy = in.readShort(), dz = in.readShort(), id = in.readInt();
                    Other o = others.get(anchor);
                    if (o != null) o.mesh.update(dx, dy, dz, id);
                    if (pilot != null && pilot.anchor == anchor && (o == null || pilot.mesh != o.mesh)) pilot.mesh.update(dx, dy, dz, id);
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
        org.joml.Matrix4d yard = entity == mc.player ? gravity.yardFrame() : null;
        if (yard != null) {                                                     // шаг в верфи: земля мира — тело (у корабля на земле)
            org.joml.Matrix4d worldToYard = new org.joml.Matrix4d(yard);
            var level = mc.level;
            out.add(new ClientContext.Body() {
                @Override public org.joml.Matrix4fc toWorld() { return new org.joml.Matrix4f(worldToYard); }
                @Override public org.joml.Matrix4d toWorldD() { return worldToYard; }
                @Override public java.util.List<net.minecraft.world.phys.shapes.VoxelShape> shapes(AABB a) {
                    java.util.List<net.minecraft.world.phys.shapes.VoxelShape> list = new java.util.ArrayList<>();
                    if (a.minX >= 399_000) return list;                         // «мир» в самой верфи — пусто
                    for (var sh : level.getBlockCollisions(null, a)) list.add(sh);
                    return list;
                }
            });
            return;                                                             // корабли в мире — не в этой системе
        }
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

    // ── посадка в кресло / выход: камера доезжает до нового места и поворота, а не прыгает ──
    private boolean camBlend;
    private org.joml.Vector3d camPos, offPos;
    private org.joml.Quaternionf camRot, offRot;
    private long camFrame;

    private void seatBlend(ink.hoka.odyssey.client.api.CameraState s) {
        long now = System.nanoTime();
        double dt = camFrame == 0 ? 0 : Math.min(0.1, (now - camFrame) / 1e9);
        camFrame = now;
        org.joml.Quaternionf rot;
        try {
            rot = s.rotation != null ? new org.joml.Quaternionf(s.rotation) : null;
        } catch (LinkageError old) {
            return;                                                             // ядро до 0.21
        }
        if (rot == null) rot = new org.joml.Quaternionf().rotationYXZ((float) Math.PI - (float) Math.toRadians(s.yaw), (float) -Math.toRadians(s.pitch),
                (float) Math.toRadians(s.roll));
        org.joml.Vector3d pos = new org.joml.Vector3d(s.x, s.y, s.z);
        if (camBlend && camPos != null && camRot != null && camPos.distance(pos) < 8) {
            offPos = new org.joml.Vector3d(camPos).sub(pos);                    // откуда прыгнули бы
            offRot = new org.joml.Quaternionf(camRot).mul(new org.joml.Quaternionf(rot).conjugate());
        }
        camBlend = false;
        if (offPos != null) {
            double k = Math.exp(-dt * 9);                                       // ~0.3 с до 95%
            offPos.mul(k);
            offRot = new org.joml.Quaternionf().slerp(offRot, (float) k);
            if (offPos.lengthSquared() < 1e-6 && Math.abs(offRot.w) > 0.99999f) { offPos = null; offRot = null; }
            else {
                pos.add(offPos);
                rot = new org.joml.Quaternionf(offRot).mul(rot);
                s.x = pos.x; s.y = pos.y; s.z = pos.z;
                s.rotation = rot;
            }
        }
        camPos = pos;
        camRot = rot;
    }

    /** Модель игрока внутри корабля — по его палубе (свой — по гравитации корабля, чужие — по коробке корабля). */
    private org.joml.Quaternionfc tilt(Entity e, float pt) {
        if (!(e instanceof net.minecraft.world.entity.player.Player pl) || pl.isPassenger() || others.isEmpty()) return null;
        Minecraft mc = Minecraft.getInstance();
        if (pl == mc.player) return gravity.modelTilt(pt);
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

    /**
     * Слезли с кресла пилота: встать на свободную клетку из 8 вокруг кресла (на 1/100 блока над её полом). Ставит мод —
     * сразу в системе корабля (верфи): поза, по которой посадил сервер, отстаёт от той, что была у пилота, и игрока
     * заталкивало в блоки. Нет свободной — на само кресло.
     */
    private void standUp(Pilot was) {
        Minecraft mc = Minecraft.getInstance();
        ShipMesh m = was.mesh;
        if (mc.player == null || mc.level == null || m.yard == null) return;
        Other o = others.get(was.anchor);
        if (o == null || o.mesh != m || !(mc.level.getEntity(was.anchor) instanceof Display d) || d.renderState() == null) return;
        int[] c = m.seatExit();
        int dx = c != null ? c[0] : 0, dy = c != null ? c[1] : 1, dz = c != null ? c[2] : 0;
        org.joml.Vector3f w = ship(d, o).place().transformPosition(new org.joml.Vector3f(dx, dy - m.seatH + 0.01f, dz));
        var p = mc.player;
        p.setPos(w.x, w.y, w.z);
        p.xo = p.xOld = w.x; p.yo = p.yOld = w.y; p.zo = p.zOld = w.z;
        p.setDeltaMovement(Vec3.ZERO);
        p.resetFallDistance();
        gravity.seedDeck(m, new org.joml.Vector3d(m.yard[0] + 0.5 + dx, m.yard[1] + dy + 0.01, m.yard[2] + 0.5 + dz));
    }

    /** Точка верфи → корабль в мире (кадр pt): чей плот рядом с точкой, тот и везёт. */
    private org.joml.Matrix4d yardToWorld(double x, double y, double z, float pt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        for (Other o : others.values()) {
            int[] yd = o.mesh.yard;
            if (yd == null || Math.abs(x - yd[0]) > 160 || Math.abs(z - yd[2]) > 128) continue;
            if (!(mc.level.getEntity(o.anchor) instanceof Display d) || d.renderState() == null) continue;
            return new org.joml.Matrix4d(ship(d, o).placeAt(pt)).translate(-(yd[0] + 0.5), -(yd[1] + o.mesh.seatH), -(yd[2] + 0.5));
        }
        return null;
    }

    private ShipGravity.Ship ship(Display d, Other o) {
        if (pilot != null && pilot.anchor == o.anchor && pilot.mesh == o.mesh) return pilot.pose();   // свой летящий — по позе пилота
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
        deckW = Math.max(0, Math.min(1, deckW + (found != null ? 0.1f : -0.1f)));
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
        w = w * w * (3 - 2 * w);                                                // плавный разгон и остановка
        var p = Minecraft.getInstance().player;
        if (p == null) return;
        double eye = s.y - net.minecraft.util.Mth.lerp(pt, p.yo, p.getY());    // высота глаз (плавная при приседе)
        org.joml.Vector3f u = deckRot.transform(new org.joml.Vector3f(0, (float) eye, 0));
        s.x += u.x * w;
        s.y += (u.y - eye) * w;
        s.z += u.z * w;
    }

    /**
     * Сущности палубы (предметы) — живут в верфи корабля (сервер показывает их нам), рисуем их на корабле: смещение от
     * клетки пилота в верфи — то же, что от пилота на корабле. Игра сама их не рисует (далеко от камеры).
     */
    private void deckEntities(Other o, PoseStack ps, ClientContext.WorldFrame f, int light) {
        int[] yd = o.mesh.yard;
        Minecraft mc = Minecraft.getInstance();
        if (yd == null || mc.level == null) return;
        net.minecraft.client.renderer.state.CameraRenderState cam;
        try { cam = f.cameraState(); } catch (LinkageError e) { return; }
        double ox = yd[0] + 0.5, oy = yd[1] + o.mesh.seatH, oz = yd[2] + 0.5, r = o.mesh.radius + 3;
        var list = mc.level.getEntities((Entity) null, new AABB(ox - r, oy - r, oz - r, ox + r, oy + r, oz + r),
                en -> !(en instanceof net.minecraft.world.entity.player.Player));
        if (list.isEmpty()) return;
        var disp = mc.getEntityRenderDispatcher();
        float pt = f.partialTick();
        ps.pushPose();
        ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(o.mesh.turn));
        for (Entity en : list) {
            try {
                keepTicking(en);                                                // отрисовка Entity Culling только что пометила «вне камеры»
                if (en.tickCount == 0) { en.xOld = en.getX(); en.yOld = en.getY(); en.zOld = en.getZ(); }
                var st = disp.extractEntity(en, pt);
                st.lightCoords = light;                                         // свет — где корабль
                st.shadowPieces.clear();
                disp.submit(st, cam, st.x - ox, st.y - oy, st.z - oz, ps, f.submits());
            } catch (Exception ignored) {
            }
        }
        ps.popPose();
    }


    /** Entity Culling (в сборке) не тикает «невидимые» сущности — а палубные для него за 400 000 блоков. Его пропуск. */
    private static java.lang.reflect.Method cullSet, cullCamera;
    private static boolean cullChecked;

    /** Снять с сущности флаги «скрыта» и «вне камеры» Entity Culling (по ним он не тикает её следующий тик). */
    static void keepTicking(Entity en) {
        if (!cullChecked) {
            cullChecked = true;
            try {
                Class<?> c = Class.forName("dev.tr7zw.entityculling.versionless.access.Cullable");
                cullSet = c.getMethod("setCulled", boolean.class);
                cullCamera = c.getMethod("setOutOfCamera", boolean.class);
            } catch (Throwable ignored) { }
        }
        if (cullSet == null || !cullSet.getDeclaringClass().isInstance(en)) return;
        try { cullSet.invoke(en, false); cullCamera.invoke(en, false); } catch (Throwable ignored) { }
    }

    /**
     * Сущности палубы двигает сама игра (столбцы верфи «тикают»: ядро 0.38 onChunkLoaded); Entity Culling из сборки
     * пропускал бы их (для него они за 400 000 блоков) — снимаем его пометки каждый тик.
     */
    private void tickDeck() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || others.isEmpty()) return;
        for (Other o : others.values()) {
            int[] yd = o.mesh.yard;
            if (yd == null) continue;
            double ox = yd[0] + 0.5, oy = yd[1] + o.mesh.seatH, oz = yd[2] + 0.5, r = o.mesh.radius + 3;
            for (Entity en : mc.level.getEntities((Entity) null, new AABB(ox - r, oy - r, oz - r, ox + r, oy + r, oz + r),
                    en -> !(en instanceof net.minecraft.world.entity.player.Player))) {
                keepTicking(en);                                                // Entity Culling — не пропускать её тики
            }
        }
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
            org.joml.Matrix4f at = new org.joml.Matrix4f(ship(d, o).placeAt(pt)).rotateY((float) -Math.toRadians(o.mesh.turn));
            org.joml.Vector3f t = at.getTranslation(new org.joml.Vector3f());
            Vec3 pos = new Vec3(t.x, t.y, t.z);
            float r = o.mesh.radius;
            if (!f.visible(new AABB(pos.x - r, pos.y - r, pos.z - r, pos.x + r, pos.y + r, pos.z + r))) continue;
            PoseStack ps = f.pose();
            ps.pushPose();
            ps.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
            ps.mulPose(at.setTranslation(0, 0, 0));                             // поворот корабля (курс, наклон, крен)
            o.mesh.draw(ps, f.buffers(), LevelRenderer.getLightColor(mc.level, BlockPos.containing(pos.add(0, 1, 0))), f);
            o.mesh.drawEntities(ps, f, LevelRenderer.getLightColor(mc.level, BlockPos.containing(pos.add(0, 1, 0))));
            deckEntities(o, ps, f, LevelRenderer.getLightColor(mc.level, BlockPos.containing(pos.add(0, 1, 0))));
            ps.popPose();
        }
    }
}
