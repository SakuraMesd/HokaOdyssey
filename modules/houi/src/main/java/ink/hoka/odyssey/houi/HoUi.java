package ink.hoka.odyssey.houi;

import com.google.gson.JsonObject;
import net.minestom.server.entity.Player;

import java.util.function.Consumer;

/**
 * Окна мода Hoka Odyssey для других модулей: {@code services().get(HoUi.class)} (в module.yml —
 * {@code softdepends: [houi]}). Раскладка окна — файл {@code module/houi/menus/<menu>.json} (рисует мод, правится
 * файлом + {@code /houi reload}); данные — JSON, подставляются в раскладку ({@code {coins}}, списки для repeat).
 * <pre>
 * ui.onAction("shop", (p, action) -> { if (action.startsWith("buy:")) … ui.update(p, "shop", data); });
 * ui.open(player, "shop", data);
 * </pre>
 */
public interface HoUi {

    /** Нажатие кнопки окна: игрок и строка action узла (после подстановок). */
    interface ActionHandler { void onAction(Player p, String action); }

    /** Открыть окно (если у игрока нет мода — false). */
    boolean open(Player p, String menu, JsonObject data);

    /** Обновить данные открытого окна (поля поверх прежних) — без переоткрытия. */
    void update(Player p, String menu, JsonObject data);

    /** Закрыть окно у игрока (menu = null — любое наше). */
    void close(Player p, String menu);

    /** Какое наше окно открыто у игрока (null — никакое). */
    String openMenu(Player p);

    /** Кнопки окна menu (один обработчик на окно). */
    void onAction(String menu, ActionHandler h);

    /** Игрок закрыл окно menu (Esc, «close», выход). */
    void onClose(String menu, Consumer<Player> h);

    /** Снять обработчики окна. */
    void off(String menu);

    /** Есть ли раскладка. */
    boolean exists(String menu);
}
