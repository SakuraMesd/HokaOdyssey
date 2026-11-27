package ink.hoka.odyssey.client.api;

/**
 * Модель Blockbench (.bbmodel) в мире, которую рисует ядро. Файл модели — с сервера (ctx.file-путь): поменяли
 * модель на сервере — у игрока она сменится сразу, без перезагрузки ресурсов. Создать: {@code ctx.model(path)};
 * снимается сама при выгрузке модуля. Все вызовы — из клиентского потока.
 */
public interface ModelHandle {

    /** Поставить в точку мира (координаты блоков; низ модели — в этой точке). */
    ModelHandle at(double x, double y, double z);

    /** Повернуть: курс (как у Minecraft: 0 — лицом на юг), наклон (+ — вниз), крен. */
    ModelHandle rotate(float yaw, float pitch, float roll);

    /**
     * 0.78: полный поворот кватернионом (система модели → мир, без поворота «лицом на юг»: +Z модели — +Z мира).
     * Важнее {@link #rotate}; null — снова по углам.
     */
    default ModelHandle rotation(org.joml.Quaternionfc q) { return this; }

    /**
     * 0.78: поза на каждый кадр (плавно между тиками): ядро спрашивает прямо перед рисованием. Модуль кладёт точку
     * мира, где начало модели (0,0,0 Blockbench), и полный поворот (как {@link #rotation}); вернуть false — в этом
     * кадре не рисовать. Важнее {@link #at}/{@link #follow}/{@link #rotate}. null — снять.
     */
    default ModelHandle pose(Placer placer) { return this; }

    /** Поза модели на кадр (см. {@link #pose}). */
    interface Placer {
        boolean place(float partialTick, org.joml.Vector3d pos, org.joml.Quaternionf rot);
    }

    /** Масштаб (1 — 16 пикселей Blockbench = блок). */
    ModelHandle scale(float s);

    /** Ехать за сущностью клиента (по её id): плавно, курс — её; {@link #at} — сдвиг от неё. -1 — отцепить. */
    ModelHandle follow(int entityId);

    /** Играть анимацию модели (режим — как в Blockbench: loop/once/hold); speed 1 — как есть. */
    ModelHandle play(String animation, float speed);

    /** Позу по умолчанию. */
    ModelHandle stop();

    /** Показать / спрятать кость (и всё в ней). */
    ModelHandle bone(String name, boolean visible);

    /** Убрать модель. */
    void remove();
}
