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
