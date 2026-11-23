package ink.hoka.odyssey.client.api;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Всё, что ядро даёт клиентскому модулю. Всё зарегистрированное снимается при выгрузке модуля.
 * Все обратные вызовы — в клиентском потоке Minecraft.
 */
public interface ClientContext {

    /** id модуля (из client-module.json) — он же имя канала до серверного модуля. */
    String id();

    Logger logger();

    /** Папка модуля для своих файлов: {@code .minecraft/hoka_odyssey/data/<id>/}. */
    Path dataDir();

    // ───── связь с сервером (серверная сторона: ClientModsApi.send / on в модуле clientmods) ─────

    /** Отправить серверу байты от имени модуля (серверный модуль слушает тот же id). */
    void send(byte[] data);

    /** Сообщения сервера для этого модуля. */
    void onMessage(Consumer<byte[]> handler);

    // ───── файлы с сервера (мимо ресурсов игры: без пака и без перезагрузок) ─────

    /** Файл с сервера (путь как в ClientModsApi.putFile / папке module/clientmods/files). Пусто — нет или ещё качается. */
    java.util.Optional<byte[]> file(String path);

    /** Сейчас (если файл уже есть) и при каждом изменении файла; null — файл убрали. */
    void onFile(String path, Consumer<byte[]> listener);

    /**
     * Текстура из PNG-файла для рисования (GuiGraphics.blit, свои рендеры). id постоянный; поменяли файл на
     * сервере — картинка на экране обновится сама, без перезагрузки ресурсов. Пока файла нет — прозрачная.
     */
    net.minecraft.resources.Identifier texture(String path);

    // ───── крючки ─────

    /** Модель .bbmodel из файлов сервера в мире (рисует ядро, обновляется вместе с файлом). */
    ModelHandle model(String path);

    /** Каждый тик клиента (20 раз в секунду). */
    void onTick(Runnable r);

    /**
     * Рисовать в мире каждый кадр (после сущностей): свои меши, корабли, эффекты. Координаты — относительно камеры
     * ({@link WorldFrame#camera()}), буферы — {@link WorldFrame#buffers()}.
     */
    void onWorld(WorldHook hook);

    /** Рисовать поверх HUD каждый кадр. */
    void onHud(HudLayer layer);

    /**
     * Движение мыши (в градусах поворота, уже с чувствительностью игрока). Вернуть true — движение забрано модулем:
     * игрок не повернётся (так делается свой прицел мыши без задержки сервера).
     */
    void onMouse(MouseHook hook);

    /** Камера: после расчёта игрой — поменять курс, наклон, крен, положение. */
    void onCamera(CameraHook hook);

    /**
     * Прямо перед движением своего игрока в тике (LivingEntity.travel): сила, приложенная здесь к скорости, работает
     * в этом же тике (onTick вызывается уже после движения).
     */
    void onLocalTravel(TravelHook hook);

    /**
     * Ввод движения своего игрока (после того как игра прочла клавиши): можно поменять keyPresses и вектор хода
     * ({@link #setMove}). Учитывает переназначенные клавиши — читать input, а не GLFW.
     */
    void onInput(InputHook hook);

    /**
     * Поворот модели сущности (мир, вокруг её ног) — например, игрок на корабле стоит по палубе корабля. Вернуть null —
     * как у игры. Ядро само переводит поворот тела и головы модели в эту систему (взгляд остаётся тем же).
     */
    void onEntityTilt(EntityTiltHook hook);

    /**
     * Клик игрока (ПКМ — use, ЛКМ — attack; attack с held=true — ЛКМ зажата, каждый тик). Вернуть true — клик забран
     * модулем: игра его не обрабатывает (не ставит/не ломает блок мира, не бьёт). Так делаются клики по своим
     * объектам (блоки корабля и т.п.).
     */
    void onClick(ClickHook hook);

    /** Вектор хода ввода: x — вбок (+ влево), y — вперёд (+ вперёд), как у игры (−1..1). */
    void setMove(net.minecraft.client.player.ClientInput input, float x, float y);

    /** Не рисовать руку и предмет в ней от первого лица (кабина, свой вид). Снимается при выгрузке модуля. */
    void hideHand(boolean hide);

    /**
     * Свои тела для столкновений (корабли, платформы — под любым углом): игра спрашивает, что мешает сущности
     * в области area (мир); модуль кладёт в out тела. У тела своя система координат, в ней фигуры стоят ровно по
     * сетке — ядро считает столкновение в ней (палуба гладкая при любом повороте), со ступеньками.
     */
    void onCollision(CollisionHook hook);

    interface HudLayer { void render(GuiGraphics g, DeltaTracker delta); }

    interface MouseHook { boolean onMouse(double yawDeg, double pitchDeg); }

    interface CameraHook { void apply(CameraState cam, float partialTick); }

    interface TravelHook { void before(net.minecraft.client.player.LocalPlayer player); }

    interface InputHook { void apply(net.minecraft.client.player.ClientInput input); }

    interface ClickHook { boolean click(boolean attack, boolean held); }

    interface EntityTiltHook { org.joml.Quaternionfc tilt(net.minecraft.world.entity.Entity entity, float partialTick); }

    interface WorldHook { void render(WorldFrame frame); }

    interface CollisionHook {
        void collect(net.minecraft.world.entity.Entity entity, net.minecraft.world.phys.AABB area, java.util.List<Body> out);
    }

    /** Тело для столкновений: поворот+сдвиг «своя система → мир» и фигуры в своей системе. */
    interface Body {
        /** Своя система → мир (только поворот и сдвиг, без масштаба). */
        org.joml.Matrix4fc toWorld();

        /** Фигуры (своя система), задевающие область localArea (тоже в своей системе). */
        java.util.List<net.minecraft.world.phys.shapes.VoxelShape> shapes(net.minecraft.world.phys.AABB localArea);
    }

    /** Кадр мира для {@link WorldHook}. */
    interface WorldFrame {
        /** Матрицы кадра (начало — камера; мировую точку p рисовать по p − camera()). */
        com.mojang.blaze3d.vertex.PoseStack pose();

        net.minecraft.client.renderer.MultiBufferSource buffers();

        net.minecraft.world.phys.Vec3 camera();

        float partialTick();

        /** Видна ли коробка (мировые координаты) — отсекать невидимое. */
        boolean visible(net.minecraft.world.phys.AABB box);

        /** Очередь отрисовки игры — для блоков-сущностей (сундуки, таблички…) и моделей через рендеры игры. */
        net.minecraft.client.renderer.SubmitNodeCollector submits();

        /** Камера кадра в виде, который ждут рендеры игры (BlockEntityRenderDispatcher.submit и т.п.). */
        net.minecraft.client.renderer.state.CameraRenderState cameraState();
    }
}
