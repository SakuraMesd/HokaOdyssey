package ink.hoka.odyssey.holo;

import net.minestom.server.entity.Player;

import java.util.Collection;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Голограммы для других модулей: {@code services().get(HoloApi.class)} (в module.yml — {@code softdepends: [holo]}).
 * <pre>
 * HoloApi holo = services().get(HoloApi.class).orElseThrow();
 * holo.placeholder("ships", "fuel", 10, p -> String.format("%.2f", fuel(p)));        // {fuel} — раз в 0.5 с
 * Hologram h = HoloApi.parse("""
 *     {"id": "dock-1", "map": "cosmos", "pos": [10, 70, 5], "pages": [{"lines": [
 *       [{"type": "text", "text": "&lt;gradient:#8FD8FF:#FFC46B&gt;Док 1"}],
 *       [{"type": "bar", "value": "{fuel}", "label": "Топливо"}],
 *       [{"type": "button", "label": "Заправить", "left": [{"type": "cmd", "value": "refuel"}]}]]}]}""");
 * holo.put(h, false);                                   // временная: не пишется в файл, пропадёт с перезапуском
 * holo.onClick((p, hh, page, line, elem, right) -> …);
 * holo.remove("dock-1");
 * </pre>
 * Всё — из тика сервера (или любого потока: внутри — потокобезопасные коллекции, рассылка мгновенная).
 */
public interface HoloApi {

    /** Нажатие (уже проверенное сервером: расстояние, видимость, задержка, права), после выполнения действий. */
    interface ClickListener {
        /** elem = −1 — клик по панели мимо элементов. */
        void click(Player p, Hologram h, int page, int line, int elem, boolean right);
    }

    /** Разобрать голограмму из JSON (формат — {@link HoloCodec}); ошибка — IllegalArgumentException. */
    static Hologram parse(String json) { return HoloCodec.parse(json); }

    /**
     * Создать или заменить голограмму (по id). persistent — записать в holograms.json; иначе временная (только в
     * памяти). Игрокам рядом — сразу. Консольные действия у голограммы из кода работают, только если
     * {@code h.creatorOp = true} (код доверенный — решает модуль).
     */
    void put(Hologram h, boolean persistent);

    Optional<Hologram> get(String id);

    Collection<Hologram> all();

    /** Поменяли поля полученной голограммы — разослать (и записать, если она не временная). */
    void update(Hologram h);

    boolean remove(String id);

    /** Плейсхолдер {name}: значение для игрока, считается раз в periodTicks; моду уходит только изменившееся. */
    void placeholder(String owner, String name, int periodTicks, Function<Player, String> fn);

    default void placeholder(String owner, String name, Function<Player, String> fn) { placeholder(owner, name, 20, fn); }

    /** Снять все плейсхолдеры владельца (в onDisable своего модуля). */
    void removePlaceholders(String owner);

    void onClick(ClickListener l);

    void offClick(ClickListener l);

    /**
     * Проверка прав ({@code holo.view.<id>}, условие {@code perm:узел}). В ядре прав нет — по умолчанию только OP;
     * модуль прав может подставить свою проверку.
     */
    void permissions(BiPredicate<Player, String> check);

    /** Страница голограммы у игрока (0…). */
    int page(Player p, String id);

    void setPage(Player p, String id, int page);
}
