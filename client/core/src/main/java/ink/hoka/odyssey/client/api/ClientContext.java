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

    /** Сразу после движения своего игрока (пара к onLocalTravel): вернуть то, что поменяли перед ним. */
    default void onLocalTravelEnd(TravelHook hook) {}

    /**
     * Весь тик своего игрока (Player.tick): enter — в самом начале, exit — в самом конце, до отправки позиции серверу.
     * Модуль может на это время перевести игрока в другую систему координат (верфь корабля): тогда ВСЁ в тике — ходьба,
     * анимация ног, поворот тела, частицы бега, присед у края, вода — считает игра по блокам той системы. Частицы и
     * звуки из системы в мир — {@link #setSoundFrame}.
     */
    default void onLocalTick(TravelHook enter, TravelHook exit) {}

    /**
     * Звуки, которые игра проигрывает у своего игрока, — из этой системы в мир (yardToWorld), null — как есть. Нужно,
     * когда движение игрока считается в другой системе (верфь корабля): шаги звучат там, где игрок на самом деле.
     */
    default void setSoundFrame(org.joml.Matrix4d yardToWorld) {}

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
     * Поза модели сущности целиком (важнее onEntityTilt): поворот модели (мир, вокруг ног) и углы тела/головы/наклона
     * головы УЖЕ в его системе. Нужно, когда углы живут в другой системе (свой игрок на корабле: курс относительно
     * палубы) — без пересчёта через мировой курс, который на наклонённом корабле прыгает.
     */
    default void onEntityPose(EntityPoseHook hook) {}

    /**
     * Где на самом деле точка из далёкой полосы (верфь корабля, X ≥ 399 000): матрица верфь → мир на кадр pt или null.
     * Нужна, чтобы рисовать там, где корабль, то, что живёт в верфи: частицы бега, пыль, крошки.
     */
    default void setYardResolver(YardResolver resolver) {}

    /**
     * Взгляд своего игрока в системе модуля {курс, наклон} (null — как у игры) — для покачивания руки от первого лица.
     * Тогда и «след» руки (xBob/yBob) модуль держит в той же системе.
     */
    default void setLocalView(LocalViewHook hook) {}

    /**
     * Клик игрока (ПКМ — use, ЛКМ — attack; attack с held=true — ЛКМ зажата, каждый тик). Вернуть true — клик забран
     * модулем: игра его не обрабатывает (не ставит/не ломает блок мира, не бьёт). Так делаются клики по своим
     * объектам (блоки корабля и т.п.).
     */
    void onClick(ClickHook hook);

    /** Колёсико мыши («выбрать блок»): вернуть true — забрал модуль (блок своего корабля и т.п.), игре не выбирать. */
    default void onPick(PickHook hook) {}

    /**
     * Своя рамка выделения (как у игры: тем же проходом, тем же видом линий). pose — в точке камеры, camera — её
     * положение в мире. Вернуть true — рамка нарисована модулем, игра свою (блока мира) в этом кадре не рисует.
     */
    void onOutline(OutlineHook hook);

    /** Вектор хода ввода: x — вбок (+ влево), y — вперёд (+ вперёд), как у игры (−1..1). */
    void setMove(net.minecraft.client.player.ClientInput input, float x, float y);

    /** Не рисовать руку и предмет в ней от первого лица (кабина, свой вид). Снимается при выгрузке модуля. */
    void hideHand(boolean hide);

    /**
     * 0.79 Спрятать HUD игрока: хотбар (и HUD выживания ядра на его месте), сердца, еду, броню, воздух, опыт, прицел,
     * подпись предмета в руке — модуль рисует свой (кабина корабля). Чат, титры, табло — как были. Снимается вызовом
     * с false и при выгрузке модуля.
     */
    default void hideHud(boolean hide) {}

    /**
     * Спрятать экран загрузки мира (вместо него — чёрный экран): для своих переходов с затемнением (вход в планету).
     * Снимается вызовом с false; на всякий случай само снимается через 15 с.
     */
    default void hideLoadingScreen(boolean hide) {}

    /**
     * Свои тела для столкновений (корабли, платформы — под любым углом): игра спрашивает, что мешает сущности
     * в области area (мир); модуль кладёт в out тела. У тела своя система координат, в ней фигуры стоят ровно по
     * сетке — ядро считает столкновение в ней (палуба гладкая при любом повороте), со ступеньками.
     */
    void onCollision(CollisionHook hook);

    /**
     * Своя рамка (AABB) сущности вместо {@code EntityDimensions.makeBoundingBox(pos)}: ядро спрашивает в
     * {@code Entity.makeBoundingBox(Vec3)} — то есть при каждом setPos, движении, refreshDimensions. Только сущности
     * клиентского мира. Вернуть null — как у игры. Позиция сущности остаётся её позицией (модуль сам решает, где она
     * относительно рамки), столкновения с блоками игра считает по этой рамке как есть (рамка ровная по осям мира —
     * например, игрок «стоит» на стене: 1.8 вдоль нормали стены, 0.6 поперёк). Пока модуль даёт рамку игроку,
     * игра не выбирает ему позу по «влезает ли рамка стоя» (Player.updatePlayerPose) — иначе у стены он бы
     * «присел» или «поплыл». Поменялось решение модуля — обновить рамку можно через {@code entity.setPos(entity.position())}.
     */
    default void onEntityBox(EntityBoxHook hook) {}

    /**
     * Глаза сущности: сдвиг от позиции сущности до глаз (мир, блоки) — для getEyePosition()/getEyePosition(pt)/
     * getEyeY() (прицел, ломание и установка блоков, луч взгляда, свет модели). Только сущности клиентского мира.
     * null — как у игры (0, eyeHeight, 0). Камеру ядро не двигает — её положение модуль ставит в onCamera.
     */
    default void onEntityEye(EntityEyeHook hook) {}

    /**
     * 0.80 Своё оформление окна-контейнера сервера (сундук и т.п.): модуль узнаёт окно (по номеру окна — сервер
     * сообщает его своим пакетом заранее) и отдаёт {@link ContainerSkin}: размер окна, места слотов, фон, подписи.
     * Слоты остаются слотами игры (клики, Shift, перетаскивание, обмен с сервером — как всегда), меняется только вид и
     * где стоят слоты. null — окно как у игры. Снимается при выгрузке модуля (открытое окно модуля закрывается).
     */
    default void onContainer(ContainerHook hook) {}

    /**
     * Части модели гуманоида (голова, тело, руки, ноги) после того, как игра их расставила
     * ({@code HumanoidModel.setupAnim}): модуль может поменять их поворот ({@code xRot/yRot/zRot}) и положение
     * ({@code x/y/z}) — например, игрок держит винтовку двумя руками. Зовётся для модели сущности и для моделей её
     * брони (они расставляются тем же состоянием), предмет в руке идёт за рукой сам. Каждый кадр, на каждую модель:
     * считать дёшево. Без крючков — как у игры.
     */
    default void onModelParts(ModelPartsHook hook) {}

    interface HudLayer { void render(GuiGraphics g, DeltaTracker delta); }

    interface ContainerHook {
        /** Окно игры (ещё до раскладки) и номер окна от сервера → оформление модуля или null. */
        ContainerSkin skin(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen, int containerId);
    }

    /** 0.80 Оформление окна-контейнера (см. {@link #onContainer}). Всё — в пикселях GUI. */
    interface ContainerSkin {
        /** Размер окна {ширина, высота} при экране screenW × screenH. Зовётся при каждой раскладке (и при смене размера). */
        int[] size(int screenW, int screenH);

        /**
         * Левый верх предмета слота (номер в menu.slots) в окне, сразу после {@link #size}; null — где у игры. Слот,
         * которого не должно быть видно и под мышью, — далеко за окном (например, {−10000, −10000}).
         */
        int[] slot(int index);

        /** Фон окна вместо текстуры игры; left/top — левый верх окна на экране. */
        void background(GuiGraphics g, int left, int top, int mouseX, int mouseY, float partialTick);

        /**
         * Вместо надписей игры («Сундук», «Инвентарь»): поверх фона, под предметами; начало координат — левый верх
         * окна, мышь — в координатах экрана. hovered — номер слота под мышью (−1 — нет).
         */
        default void labels(GuiGraphics g, int mouseX, int mouseY, int hovered) {}

        /** Рамка слота под мышью — как у игры (false — модуль рисует свою в {@link #labels}). */
        default boolean vanillaHighlight() { return true; }

        /** Окно закрыто. */
        default void removed() {}
    }

    interface MouseHook { boolean onMouse(double yawDeg, double pitchDeg); }

    interface CameraHook { void apply(CameraState cam, float partialTick); }

    interface TravelHook { void before(net.minecraft.client.player.LocalPlayer player); }

    interface InputHook { void apply(net.minecraft.client.player.ClientInput input); }

    interface ClickHook { boolean click(boolean attack, boolean held); }

    /** ctrl — с содержимым (Ctrl + колёсико в творческом). */
    interface PickHook { boolean pick(boolean ctrl); }

    interface OutlineHook {
        boolean render(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
                       net.minecraft.world.phys.Vec3 camera, float partialTick);
    }

    interface EntityBoxHook { net.minecraft.world.phys.AABB box(net.minecraft.world.entity.Entity entity, net.minecraft.world.phys.Vec3 pos); }

    /** entity — сущность модели (null — уже не в мире), model и state — как в HumanoidModel.setupAnim. */
    interface ModelPartsHook {
        void apply(net.minecraft.world.entity.Entity entity, net.minecraft.client.model.HumanoidModel<?> model,
                   net.minecraft.client.renderer.entity.state.HumanoidRenderState state);
    }

    interface EntityEyeHook { net.minecraft.world.phys.Vec3 eye(net.minecraft.world.entity.Entity entity); }

    interface EntityTiltHook { org.joml.Quaternionfc tilt(net.minecraft.world.entity.Entity entity, float partialTick); }

    /**
     * Поза модели: поворот (мир) и углы в его системе (градусы, как у игры: тело, курс головы, наклон головы).
     * offset — сдвиг модели в мировых осях (блоки) от позиции сущности до точки, вокруг которой она поворачивается
     * на tilt; null — поворот вокруг ног, как раньше (свободный полёт — вокруг середины тела, стоя на стене — ноги
     * на стене, а рамка сущности остаётся ровной).
     */
    record EntityPose(org.joml.Quaternionfc tilt, float bodyYaw, float headYaw, float headPitch,
                      org.joml.Vector3fc offset) {
        /** Без сдвига — как раньше: уже собранные модули работают без изменений. */
        public EntityPose(org.joml.Quaternionfc tilt, float bodyYaw, float headYaw, float headPitch) {
            this(tilt, bodyYaw, headYaw, headPitch, null);
        }
    }

    interface EntityPoseHook { EntityPose pose(net.minecraft.world.entity.Entity entity, float partialTick); }

    interface LocalViewHook { float[] view(float partialTick); }

    interface YardResolver { org.joml.Matrix4d toWorld(double x, double y, double z, float partialTick); }

    interface WorldHook { void render(WorldFrame frame); }

    interface CollisionHook {
        void collect(net.minecraft.world.entity.Entity entity, net.minecraft.world.phys.AABB area, java.util.List<Body> out);
    }

    /** Тело для столкновений: поворот+сдвиг «своя система → мир» и фигуры в своей системе. */
    interface Body {
        /** Своя система → мир (только поворот и сдвиг, без масштаба). */
        org.joml.Matrix4fc toWorld();

        /** То же в double — для далёких координат (верфь кораблей: ~400 000, у float шаг 0.03). */
        default org.joml.Matrix4d toWorldD() { return new org.joml.Matrix4d(toWorld()); }

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
