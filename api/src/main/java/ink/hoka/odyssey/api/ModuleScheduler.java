package ink.hoka.odyssey.api;

import net.minestom.server.timer.Task;

/**
 * Задачи модуля. Все отменяются автоматически при выключении модуля. 1 тик = 50 мс (20 в секунду).
 * Код в run/later/repeat выполняется в тике сервера — можно трогать мир и игроков.
 * async — отдельный поток (виртуальный): только для тяжёлой работы без доступа к миру (файлы, сеть, расчёты).
 */
public interface ModuleScheduler {

    /** В начале следующего тика. */
    Task run(Runnable r);

    /** Через ticks тиков. */
    Task later(Runnable r, int ticks);

    /** Через delayTicks, затем каждые periodTicks. */
    Task repeat(Runnable r, int delayTicks, int periodTicks);

    /** В отдельном потоке. Прерывается при выключении модуля. */
    void async(Runnable r);
}
