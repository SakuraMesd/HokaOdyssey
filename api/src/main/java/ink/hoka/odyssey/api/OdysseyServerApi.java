package ink.hoka.odyssey.api;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;

import java.util.List;

/** Общие вещи ядра, доступные модулям. */
public interface OdysseyServerApi {

    /** Основная карта maps/world (куда игроки попадают при входе, если модуль не выбрал другую). */
    InstanceContainer world();

    Pos spawn();

    /** Включённые модули по порядку включения. */
    List<ModuleDescriptor> modules();

    /** Версия ядра. */
    String version();

    /** Оператор сервера (ops.json, выдаётся /hoop). Для своих админ-команд модуля. */
    boolean isOp(net.minestom.server.entity.Player player);

    /** Карта из maps/ по имени (загружается при необходимости); пусто — такой карты нет. */
    java.util.Optional<InstanceContainer> map(String name);

    /** Имена всех карт в maps/. */
    java.util.SortedSet<String> mapNames();

    /** Имя карты, если мир — карта из maps/ (иначе пусто). */
    java.util.Optional<String> mapName(net.minestom.server.instance.Instance instance);

    /**
     * map.yml карты (свежее чтение с диска; правки модулем не сохраняются, пока не вызван save). Пусто — нет такой
     * карты/файла. Нужен модулям для своих разделов карты (например {@code sky:} — небо).
     */
    default java.util.Optional<ModuleConfig> mapConfig(String name) {
        if (name == null || !name.matches("[a-z0-9_\\-]{1,32}")) return java.util.Optional.empty();
        java.nio.file.Path f = java.nio.file.Path.of("maps", name, "map.yml");
        return java.nio.file.Files.isRegularFile(f) ? java.util.Optional.of(new ModuleConfig(f)) : java.util.Optional.empty();
    }

    /**
     * Версия ядра мода Hoka Odyssey у игрока из рукопожатия на этапе входа (configuration, hoka_odyssey:hi) — известна
     * ещё до появления в мире. Пусто — мода нет, ядро старше 0.29 или ядро сервера старое.
     */
    default java.util.Optional<String> clientCore(net.minestom.server.entity.Player player) { return java.util.Optional.empty(); }

    /**
     * Отметка этапа входа игрока (например «модули», «axiom») для строки «вход ник: авторизация→мир …» в логе ядра.
     * После того как строка напечатана, отметки не учитываются.
     */
    default void joinStage(net.minestom.server.entity.Player player, String stage) {}

    /** id зарегистрированных генераторов планет (встроенные flat, void и модулей). */
    default java.util.SortedSet<String> generatorIds() { return new java.util.TreeSet<>(); }

    /**
     * jar переносимых генераторов планет для раздачи клиентам (путь "gen/&lt;id&gt;@&lt;v&gt;-&lt;sha8&gt;.jar" → байты) — их
     * кладёт в свои файлы модуль clientmods (см. {@link GenFilesSink}).
     */
    default java.util.Map<String, byte[]> generatorFiles() { return java.util.Map.of(); }

    // ───────────── кубы 16³ (высокий мир, CUBIC-CHUNKS.md §4.10) ─────────────
    // Карты с кубами по запросу (store: cubes + cubes: true, ядро 0.4 шаг 10): куб загружен = он в памяти (READY);
    // писать в незагруженный куб нельзя (IllegalStateException), getBlock там — воздух. Закрепление сразу грузит
    // рамку и не даёт выгрузить её столбцы. Остальные карты — как раньше: куб загружен = загружен его столбец.
    // Ниже — запасные реализации поверх столбцов (ядро их переопределяет).
    // Рамки — в координатах блоков, обе границы включительно (min/max можно передать в любом порядке).

    /** Загружен ли куб с блоком (x, y, z). Незагруженный куб для физики считать твёрдым, писать в него нельзя. */
    default boolean isCubeLoaded(net.minestom.server.instance.Instance inst, int x, int y, int z) {
        return inst.isChunkLoaded(x >> 4, z >> 4);
    }

    default boolean isCubeLoaded(net.minestom.server.instance.Instance inst, net.minestom.server.coordinate.Point p) {
        return isCubeLoaded(inst, p.blockX(), p.blockY(), p.blockZ());
    }

    /** Загружены ли все кубы рамки. Не ждёт и ничего не грузит. */
    default boolean areCubesLoaded(net.minestom.server.instance.Instance inst,
                                   net.minestom.server.coordinate.Point min, net.minestom.server.coordinate.Point max) {
        int x0 = Math.min(min.blockX(), max.blockX()) >> 4, x1 = Math.max(min.blockX(), max.blockX()) >> 4;
        int z0 = Math.min(min.blockZ(), max.blockZ()) >> 4, z1 = Math.max(min.blockZ(), max.blockZ()) >> 4;
        for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++)
            if (!inst.isChunkLoaded(cx, cz)) return false;
        return true;
    }

    /**
     * Загрузить все кубы рамки; будущее завершится, когда загружены все. В тике сервера его НЕ ждать (join/get —
     * зависание): продолжать в thenRun через планировщик или брать {@link #ensureCubes}.
     */
    default java.util.concurrent.CompletableFuture<Void> loadCubes(net.minestom.server.instance.Instance inst,
                                   net.minestom.server.coordinate.Point min, net.minestom.server.coordinate.Point max) {
        int x0 = Math.min(min.blockX(), max.blockX()) >> 4, x1 = Math.max(min.blockX(), max.blockX()) >> 4;
        int z0 = Math.min(min.blockZ(), max.blockZ()) >> 4, z1 = Math.max(min.blockZ(), max.blockZ()) >> 4;
        var loads = new java.util.ArrayList<java.util.concurrent.CompletableFuture<?>>();
        for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) loads.add(inst.loadChunk(cx, cz));
        return java.util.concurrent.CompletableFuture.allOf(loads.toArray(java.util.concurrent.CompletableFuture[]::new));
    }

    /** Вариант для тика: true — все кубы рамки уже загружены; иначе запускает загрузку недостающих (не ждёт) и даёт false. */
    default boolean ensureCubes(net.minestom.server.instance.Instance inst,
                                net.minestom.server.coordinate.Point min, net.minestom.server.coordinate.Point max) {
        if (areCubesLoaded(inst, min, max)) return true;
        loadCubes(inst, min, max);
        return false;
    }

    /**
     * Закрепить кубы рамки за владельцем (корабль, плот верфи…): не выгружать, пока не снято {@link #unpin}.
     * Владелец может держать несколько рамок. Рамка сразу начинает грузиться (без ожидания), её столбцы не выгружаются.
     */
    default void pinCubes(net.minestom.server.instance.Instance inst,
                          net.minestom.server.coordinate.Point min, net.minestom.server.coordinate.Point max, Object owner) {}

    /** Снять все закрепления владельца. */
    default void unpin(Object owner) {}
}
