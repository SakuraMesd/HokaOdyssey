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

    /** Каждый тик клиента (20 раз в секунду). */
    /** Модель .bbmodel из файлов сервера в мире (рисует ядро, обновляется вместе с файлом). */
    ModelHandle model(String path);

    void onTick(Runnable r);

    /** Рисовать поверх HUD каждый кадр. */
    void onHud(HudLayer layer);

    /**
     * Движение мыши (в градусах поворота, уже с чувствительностью игрока). Вернуть true — движение забрано модулем:
     * игрок не повернётся (так делается свой прицел мыши без задержки сервера).
     */
    void onMouse(MouseHook hook);

    /** Камера: после расчёта игрой — поменять курс, наклон, крен, положение. */
    void onCamera(CameraHook hook);

    interface HudLayer { void render(GuiGraphics g, DeltaTracker delta); }

    interface MouseHook { boolean onMouse(double yawDeg, double pitchDeg); }

    interface CameraHook { void apply(CameraState cam, float partialTick); }
}
