package ink.hoka.odyssey.respack;

import net.minestom.server.entity.Player;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Общий ресурспак сервера. Модули кладут в него свои файлы, модуль respack собирает zip, раздаёт по HTTP и
 * отправляет игрокам. Получить: {@code services().require(ResourcePackApi.class)} (в module.yml: depends: [respack]).
 *
 * <pre>
 * ResourcePackApi pack = services().require(ResourcePackApi.class);
 * pack.put(id(), "assets/myns/models/item/thing.json", bytes);
 * pack.rebuild();                       // пересобрать и разослать онлайн-игрокам
 * </pre>
 * Файлы модуля держатся, пока модуль включён: при его выключении — {@link #clear(String)} (respack сделает это сам,
 * если модуль выгружен вместе с ним, но лучше вызывать в onDisable).
 */
public interface ResourcePackApi {

    /** Положить файл в пак (путь как в паке: assets/…). owner — id модуля. Перезаписывает. */
    void put(String owner, String path, byte[] data);

    /** Убрать файл модуля из пака. */
    void remove(String owner, String path);

    /** Убрать все файлы модуля. */
    void clear(String owner);

    /**
     * Пересобрать пак (с небольшой задержкой — несколько вызовов подряд дают одну сборку) и отправить игрокам онлайн.
     * Завершается, когда пак собран.
     */
    CompletableFuture<Void> rebuild();

    /** Загружен ли у игрока текущий пак (по ответу клиента). */
    boolean hasPack(Player player);

    /** Адрес текущего пака (пусто — ещё не собран). */
    Optional<String> url();

    /**
     * Файл из ванильного клиента нужной версии (например "assets/minecraft/blockstates/oak_stairs.json").
     * Клиент скачивается с серверов Mojang один раз и проверяется по SHA-1. Пусто — такого файла нет.
     * Вызывать только после {@link #vanillaReady()} (иначе блокирует до загрузки) и не в тике сервера.
     */
    Optional<byte[]> vanilla(String path);

    /** Завершается, когда ванильный клиент скачан и готов к чтению. */
    CompletableFuture<Void> vanillaReady();

    /** Все файлы последнего собранного пака (путь → байты, вместе с pack.mcmeta). Пусто — ещё не собран. */
    java.util.Map<String, byte[]> snapshot();

    /** Вызвать после каждой сборки пака (поток сборки). */
    void onBuilt(Runnable r);

    /**
     * Кому НЕ слать пак (например, игрокам с модом Hoka Odyssey — им ресурсы приходят через мод).
     * Проверяется при отправке: при входе — через pack.join-delay-ticks после спавна, и при пересборке.
     */
    void setSendFilter(java.util.function.Predicate<Player> skip);
}
