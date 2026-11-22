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

    /** Крен текущего кадра, радианы (миксин камеры подставляет его в поворот). */
    public static float roll;

    static void removeOwner(String owner) {
        TICK.removeIf(o -> o.owner().equals(owner));
        HUD.removeIf(o -> o.owner().equals(owner));
        MOUSE.removeIf(o -> o.owner().equals(owner));
        CAMERA.removeIf(o -> o.owner().equals(owner));
    }

    static void clear() {
        TICK.clear();
        HUD.clear();
        MOUSE.clear();
        CAMERA.clear();
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

    /** Применить крючки камеры: state на входе — как насчитала игра, на выходе — что нарисовать. */
    public static void camera(CameraState state, float partialTick) {
        for (var o : List.copyOf(CAMERA)) safe(o.owner(), () -> o.hook().apply(state, partialTick));
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
