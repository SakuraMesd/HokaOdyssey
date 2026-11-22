package ink.hoka.odyssey.houi;

import com.google.gson.JsonObject;
import net.minestom.server.entity.Player;

import java.util.Collection;

/**
 * HUD мода Hoka Odyssey для других модулей: {@code services().get(HoHud.class)} (softdepends: [houi]).
 * Слой — раскладка {@code module/houi/hud/<layer>.json} (тот же формат, что у окон, плюс anchor/offset/scale/z);
 * данные подставляются в раскладку, {@link #update} меняет их на лету (полосы со {@code smooth} доезжают плавно).
 * <pre>
 * hud.show(p, "oxygen", data);            // показать слой
 * hud.update(p, "oxygen", {"o2": 42});     // обновить
 * hud.hideVanilla(p, List.of("food", "air"));
 * </pre>
 */
public interface HoHud {

    /** Показать слой (заново — с анимацией появления). Без мода — false. */
    boolean show(Player p, String layer, JsonObject data);

    /** Обновить данные слоя (поля поверх прежних). */
    void update(Player p, String layer, JsonObject data);

    /** Убрать слой (null — все наши). */
    void hide(Player p, String layer);

    /**
     * Спрятать ванильные части экрана (остальные — показать): health, armor, food, air, xp, xp_level, hotbar,
     * crosshair, effects, bossbar, scoreboard, mount, actionbar, title, held_item. Пустой список — всё как в ванили.
     */
    void hideVanilla(Player p, Collection<String> parts);

    /** Есть ли раскладка слоя. */
    boolean exists(String layer);
}
