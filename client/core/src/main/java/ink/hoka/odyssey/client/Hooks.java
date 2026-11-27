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

    public record Owned<T>(String owner, T hook) {}

    static final List<Owned<Runnable>> TICK = new ArrayList<>();
    static final List<Owned<ClientContext.HudLayer>> HUD = new ArrayList<>();
    static final List<Owned<ClientContext.MouseHook>> MOUSE = new ArrayList<>();
    static final List<Owned<ClientContext.CameraHook>> CAMERA = new ArrayList<>();
    static final List<Owned<ClientContext.WorldHook>> WORLD = new ArrayList<>();
    static final List<Owned<ClientContext.TravelHook>> TRAVEL = new ArrayList<>();
    static final List<Owned<ClientContext.TravelHook>> TRAVEL_END = new ArrayList<>();
    static final List<Owned<ClientContext.TravelHook>> TICK_ENTER = new ArrayList<>();
    static final List<Owned<ClientContext.EntityPoseHook>> POSE = new ArrayList<>();
    static final List<Owned<ClientContext.PickHook>> PICK = new ArrayList<>();
    static final List<Owned<ClientContext.EntityBoxHook>> BOX = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final List<Owned<ClientContext.EntityEyeHook>> EYE = new java.util.concurrent.CopyOnWriteArrayList<>();
    static volatile Owned<ClientContext.YardResolver> yardResolver;
    static volatile Owned<ClientContext.LocalViewHook> localView;
    static final List<Owned<ClientContext.TravelHook>> TICK_EXIT = new ArrayList<>();
    /** Звуки у своего игрока — из этой системы в мир (ClientContext.setSoundFrame), null — как есть. */
    public static volatile org.joml.Matrix4d soundFrame;
    static final List<Owned<ClientContext.InputHook>> INPUT = new ArrayList<>();
    static final List<Owned<ClientContext.EntityTiltHook>> TILT = new ArrayList<>();
    static final List<Owned<ClientContext.ClickHook>> CLICK = new ArrayList<>();
    static final List<Owned<ClientContext.OutlineHook>> OUTLINE = new ArrayList<>();
    /** В этом кадре рамку нарисовал модуль (второй вызов игры — для прозрачной рамки — тоже пропустить). */
    public static boolean outlineDrawn;
    static final List<Owned<ClientContext.CollisionHook>> COLLISION = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** 0.80 Оформление окон-контейнеров сервера. */
    static final List<Owned<ClientContext.ContainerHook>> CONTAINER = new ArrayList<>();

    /** Модули, которые просят не рисовать руку от первого лица (кабина корабля и т.п.). */
    static final java.util.Set<String> HIDE_HAND = new java.util.HashSet<>();

    /** Экран загрузки мира спрятан (чёрный) до этого момента, мс (0 — не спрятан). */
    private static volatile long hideLoadingUntil;
    public static void hideLoading(boolean hide) { hideLoadingUntil = hide ? System.currentTimeMillis() + 15_000 : 0; }
    public static boolean hideLoading() { return hideLoadingUntil > System.currentTimeMillis(); }

    public static boolean hideHand() { return !HIDE_HAND.isEmpty(); }

    /** 0.79 Модули, которые просят спрятать HUD игрока (хотбар, сердца, еда, броня, опыт, прицел; кабина корабля). */
    static final java.util.Set<String> HIDE_HUD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean hideHud() { return !HIDE_HUD.isEmpty(); }

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
        TRAVEL_END.removeIf(o -> o.owner().equals(owner));
        TICK_ENTER.removeIf(o -> o.owner().equals(owner));
        POSE.removeIf(o -> o.owner().equals(owner));
        PICK.removeIf(o -> o.owner().equals(owner));
        BOX.removeIf(o -> o.owner().equals(owner));
        EYE.removeIf(o -> o.owner().equals(owner));
        if (yardResolver != null && yardResolver.owner().equals(owner)) yardResolver = null;
        if (localView != null && localView.owner().equals(owner)) localView = null;
        TICK_EXIT.removeIf(o -> o.owner().equals(owner));
        soundFrame = null;
        INPUT.removeIf(o -> o.owner().equals(owner));
        TILT.removeIf(o -> o.owner().equals(owner));
        CLICK.removeIf(o -> o.owner().equals(owner));
        OUTLINE.removeIf(o -> o.owner().equals(owner));
        COLLISION.removeIf(o -> o.owner().equals(owner));
        if (CONTAINER.removeIf(o -> o.owner().equals(owner))) ink.hoka.odyssey.client.inv.SkinnedScreen.closeOf(owner);
        HIDE_HAND.remove(owner);
        HIDE_HUD.remove(owner);
    }

    static void clear() {
        TICK.clear();
        HUD.clear();
        MOUSE.clear();
        CAMERA.clear();
        WORLD.clear();
        TRAVEL.clear();
        TRAVEL_END.clear();
        TICK_ENTER.clear();
        POSE.clear();
        PICK.clear();
        BOX.clear();
        EYE.clear();
        yardResolver = null;
        localView = null;
        TICK_EXIT.clear();
        soundFrame = null;
        INPUT.clear();
        TILT.clear();
        CLICK.clear();
        OUTLINE.clear();
        rotation = null;
        COLLISION.clear();
        if (!CONTAINER.isEmpty()) ink.hoka.odyssey.client.inv.SkinnedScreen.closeOf(null);
        CONTAINER.clear();
        HIDE_HAND.clear();
        HIDE_HUD.clear();
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
    public static void travelEnd(net.minecraft.client.player.LocalPlayer p) {
        if (TRAVEL_END.isEmpty()) return;
        for (var o : List.copyOf(TRAVEL_END)) safe(o.owner(), () -> o.hook().before(p));
    }

    /** Начало тика своего игрока (миксин Player.tick HEAD). */
    public static void tickEnter(net.minecraft.client.player.LocalPlayer p) {
        if (TICK_ENTER.isEmpty()) return;
        for (var o : List.copyOf(TICK_ENTER)) safe(o.owner(), () -> o.hook().before(p));
    }

    /** Конец тика своего игрока (Player.tick TAIL) — до отправки позиции серверу. */
    public static void tickExit(net.minecraft.client.player.LocalPlayer p) {
        if (TICK_EXIT.isEmpty()) return;
        for (var o : List.copyOf(TICK_EXIT)) safe(o.owner(), () -> o.hook().before(p));
    }

    public static void travel(net.minecraft.client.player.LocalPlayer p) {
        if (TRAVEL.isEmpty()) return;
        for (var o : List.copyOf(TRAVEL)) safe(o.owner(), () -> o.hook().before(p));
    }

    /** Рамка выделения от модулей; true — нарисована (игре свою не рисовать). */
    public static boolean outline(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
                                  net.minecraft.world.phys.Vec3 camera, float pt) {
        if (OUTLINE.isEmpty()) return false;
        boolean drawn = false;
        for (var o : List.copyOf(OUTLINE)) {
            boolean[] r = {false};
            safe(o.owner(), () -> r[0] = o.hook().render(pose, buffers, camera, pt));
            drawn |= r[0];
        }
        return drawn;
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

    /** 0.80 Оформление окна-контейнера: {владелец, оформление} первого модуля, который узнал окно, или null. */
    public static Owned<ClientContext.ContainerSkin> containerSkin(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen, int id) {
        for (var o : List.copyOf(CONTAINER)) {
            Object[] r = {null};
            safe(o.owner(), () -> r[0] = o.hook().skin(screen, id));
            if (r[0] instanceof ClientContext.ContainerSkin s) return new Owned<>(o.owner(), s);
        }
        return null;
    }

    /** 0.80 Вызвать оформление окна; модуль упал — выгружается (окно закроет removeOwner). false — упал. */
    public static boolean skinSafe(String owner, Runnable r) {
        boolean[] ok = {false};
        safe(owner, () -> { r.run(); ok[0] = true; });
        return ok[0];
    }

    /** Колёсико мыши — модулям; true — забрал. */
    public static boolean pick(boolean ctrl) {
        for (var o : List.copyOf(PICK)) {
            boolean[] r = {false};
            safe(o.owner(), () -> r[0] = o.hook().pick(ctrl));
            if (r[0]) return true;
        }
        return false;
    }

    /** Поза модели от модулей (первая не-null) или null. */
    public static ClientContext.EntityPose pose(net.minecraft.world.entity.Entity e, float pt) {
        if (POSE.isEmpty()) return null;
        for (var o : List.copyOf(POSE)) {
            ClientContext.EntityPose[] r = {null};
            safe(o.owner(), () -> r[0] = o.hook().pose(e, pt));
            if (r[0] != null) return r[0];
        }
        return null;
    }

    public static boolean hasBox() { return !BOX.isEmpty(); }

    /** Рамка сущности от модулей (первая не-null) или null — как у игры (миксин Entity.makeBoundingBox). */
    public static net.minecraft.world.phys.AABB box(net.minecraft.world.entity.Entity e, net.minecraft.world.phys.Vec3 pos) {
        if (BOX.isEmpty() || e == null || pos == null) return null;
        for (var o : BOX) {
            net.minecraft.world.phys.AABB[] r = {null};
            safe(o.owner(), () -> r[0] = o.hook().box(e, pos));
            if (r[0] != null) return r[0];
        }
        return null;
    }

    public static boolean hasEye() { return !EYE.isEmpty(); }

    /** Сдвиг позиция → глаза от модулей (первый не-null) или null — как у игры (миксин Entity.getEyePosition). */
    public static net.minecraft.world.phys.Vec3 eye(net.minecraft.world.entity.Entity e) {
        if (EYE.isEmpty() || e == null) return null;
        for (var o : EYE) {
            net.minecraft.world.phys.Vec3[] r = {null};
            safe(o.owner(), () -> r[0] = o.hook().eye(e));
            if (r[0] != null) return r[0];
        }
        return null;
    }

    /** Взгляд своего игрока в системе модуля {курс, наклон} или null. */
    public static float[] localView(float pt) {
        var h = localView;
        if (h == null) return null;
        float[][] r = {null};
        safe(h.owner(), () -> r[0] = h.hook().view(pt));
        return r[0];
    }

    /** Верфь → мир для точки полосы верфи (кадр pt) или null. */
    public static org.joml.Matrix4d yardToWorld(double x, double y, double z, float pt) {
        var r = yardResolver;
        if (r == null) return null;
        org.joml.Matrix4d[] m = {null};
        safe(r.owner(), () -> m[0] = r.hook().toWorld(x, y, z, pt));
        return m[0];
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
