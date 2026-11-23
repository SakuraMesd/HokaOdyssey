package ink.hoka.odyssey.client;

import ink.hoka.odyssey.client.api.CameraState;
import ink.hoka.odyssey.client.api.ClientContext;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Крючки, которые модули вешают через {@link ClientContext}. Каждый помечен владельцем — при выгрузке модуля
 * снимается всё его. Вызываются из клиентского потока (тик, HUD, миксины мыши и камеры).
 */
public final class Hooks {

    private Hooks() {}

    record Owned<T>(String owner, T hook) {}

    static final List<Owned<Runnable>> TICK = new ArrayList<>();
    static final List<Owned<ClientContext.HudLayer>> HUD = new ArrayList<>();
    static final List<Owned<ClientContext.MouseHook>> MOUSE = new ArrayList<>();
    static final List<Owned<ClientContext.CameraHook>> CAMERA = new ArrayList<>();
    static final List<Owned<ClientContext.WorldHook>> WORLD = new ArrayList<>();
    static final List<Owned<ClientContext.TravelHook>> TRAVEL = new ArrayList<>();
    static final List<Owned<ClientContext.InputHook>> INPUT = new ArrayList<>();
    static final List<Owned<ClientContext.EntityTiltHook>> TILT = new ArrayList<>();
    static final List<Owned<ClientContext.ClickHook>> CLICK = new ArrayList<>();
    static final List<Owned<ClientContext.CollisionHook>> COLLISION = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Модули, которые просят не рисовать руку от первого лица (кабина корабля и т.п.). */
    static final java.util.Set<String> HIDE_HAND = new java.util.HashSet<>();

    public static boolean hideHand() { return !HIDE_HAND.isEmpty(); }

    /** Крен текущего кадра, радианы (миксин камеры подставляет его в поворот). */
    public static float roll;

    /** Полный поворот камеры текущего кадра от модуля (null — считать из углов). */
    public static org.joml.Quaternionf rotation;

    static void removeOwner(String owner) {
        TICK.removeIf(o -> o.owner().equals(owner));
        HUD.removeIf(o -> o.owner().equals(owner));
        MOUSE.removeIf(o -> o.owner().equals(owner));
        CAMERA.removeIf(o -> o.owner().equals(owner));
        WORLD.removeIf(o -> o.owner().equals(owner));
        TRAVEL.removeIf(o -> o.owner().equals(owner));
        INPUT.removeIf(o -> o.owner().equals(owner));
        TILT.removeIf(o -> o.owner().equals(owner));
        CLICK.removeIf(o -> o.owner().equals(owner));
        COLLISION.removeIf(o -> o.owner().equals(owner));
        HIDE_HAND.remove(owner);
    }

    static void clear() {
        TICK.clear();
        HUD.clear();
        MOUSE.clear();
        CAMERA.clear();
        WORLD.clear();
        TRAVEL.clear();
        INPUT.clear();
        TILT.clear();
        CLICK.clear();
        rotation = null;
        COLLISION.clear();
        HIDE_HAND.clear();
        roll = 0;
    }

    static void tick() {
        for (var o : List.copyOf(TICK)) safe(o.owner(), () -> o.hook().run());
    }

    static void hud(GuiGraphics g, DeltaTracker d) {
        for (var o : List.copyOf(HUD)) safe(o.owner(), () -> o.hook().render(g, d));
    }

    /** true — движение мыши забрано модулем (игрок не поворачивается). */
    public static boolean mouse(double yawDeg, double pitchDeg) {
        boolean taken = false;
        for (var o : List.copyOf(MOUSE)) {
            boolean[] r = {false};
            safe(o.owner(), () -> r[0] = o.hook().onMouse(yawDeg, pitchDeg));
            taken |= r[0];
        }
        return taken;
    }

    public static boolean hasMouse() { return !MOUSE.isEmpty(); }

    public static boolean hasCamera() { return !CAMERA.isEmpty(); }

    /** Перед движением своего игрока (миксин LivingEntity.travel). */
    public static void travel(net.minecraft.client.player.LocalPlayer p) {
        if (TRAVEL.isEmpty()) return;
        for (var o : List.copyOf(TRAVEL)) safe(o.owner(), () -> o.hook().before(p));
    }

    /** Клик игрока — модулям; true — забран (игра его не обрабатывает). */
    public static boolean click(boolean attack, boolean held) {
        if (CLICK.isEmpty()) return false;
        for (var o : List.copyOf(CLICK)) {
            boolean[] r = {false};
            safe(o.owner(), () -> r[0] = o.hook().click(attack, held));
            if (r[0]) return true;
        }
        return false;
    }

    /** Поворот модели сущности от модулей (первый не-null) или null. */
    public static org.joml.Quaternionfc tilt(net.minecraft.world.entity.Entity e, float pt) {
        if (TILT.isEmpty()) return null;
        for (var o : List.copyOf(TILT)) {
            org.joml.Quaternionfc[] r = {null};
            safe(o.owner(), () -> r[0] = o.hook().tilt(e, pt));
            if (r[0] != null) return r[0];
        }
        return null;
    }

    /** Рядом с сущностью есть тела модулей (корабли) — тогда ванильные проверки «по блокам мира» неверны. */
    public static boolean nearBodies(net.minecraft.world.entity.Entity e) {
        return hasCollision() && !bodies(e, e.getBoundingBox().inflate(1.5)).isEmpty();
    }

    /** Ввод своего игрока (миксин KeyboardInput.tick). */
    public static void input(net.minecraft.client.player.ClientInput in) {
        if (INPUT.isEmpty()) return;
        for (var o : List.copyOf(INPUT)) safe(o.owner(), () -> o.hook().apply(in));
    }

    /** Применить крючки камеры: state на входе — как насчитала игра, на выходе — что нарисовать. */
    public static void camera(CameraState state, float partialTick) {
        for (var o : List.copyOf(CAMERA)) safe(o.owner(), () -> o.hook().apply(state, partialTick));
    }

    /** Кадр мира: модули рисуют свои меши (после сущностей). */
    static void world(net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext ctx) {
        if (WORLD.isEmpty()) return;
        var mc = net.minecraft.client.Minecraft.getInstance();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var cam = ink.hoka.odyssey.client.model.Models.camera();
        var fr = ink.hoka.odyssey.client.model.Models.frustum();
        ClientContext.WorldFrame frame = new ClientContext.WorldFrame() {
            @Override public com.mojang.blaze3d.vertex.PoseStack pose() { return ctx.matrices(); }
            @Override public net.minecraft.client.renderer.MultiBufferSource buffers() { return ctx.consumers(); }
            @Override public net.minecraft.world.phys.Vec3 camera() { return cam; }
            @Override public float partialTick() { return pt; }
            @Override public boolean visible(net.minecraft.world.phys.AABB box) { return fr == null || fr.isVisible(box); }
            @Override public net.minecraft.client.renderer.SubmitNodeCollector submits() { return ctx.commandQueue(); }
            @Override public net.minecraft.client.renderer.state.CameraRenderState cameraState() {
                var c = mc.gameRenderer.getMainCamera();
                var s = new net.minecraft.client.renderer.state.CameraRenderState();
                s.pos = c.position();
                s.blockPos = c.blockPosition();
                s.entityPos = c.entity() != null ? c.entity().position() : c.position();
                s.orientation = new org.joml.Quaternionf(c.rotation());
                s.initialized = true;
                return s;
            }
        };
        for (var o : List.copyOf(WORLD)) safe(o.owner(), () -> o.hook().render(frame));
        if (ctx.consumers() instanceof net.minecraft.client.renderer.MultiBufferSource.BufferSource bs) bs.endBatch();
    }

    public static boolean hasCollision() { return !COLLISION.isEmpty(); }

    /** Тела столкновений от модулей рядом с областью (миксин Entity). */
    public static List<ClientContext.Body> bodies(net.minecraft.world.entity.Entity e, net.minecraft.world.phys.AABB area) {
        List<ClientContext.Body> out = new ArrayList<>();
        if (COLLISION.isEmpty() || e == null) return out;
        for (var o : COLLISION) safe(o.owner(), () -> o.hook().collect(e, area, out));
        return out;
    }

    /** Ошибка в модуле не должна ронять игру — пишем в лог и снимаем модуль. */
    private static void safe(String owner, Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            HokaOdysseyClient.LOG.error("Модуль {} упал в крючке — выгружаю", owner, e);
            ModuleManager.failed(owner);
        }
    }
}
