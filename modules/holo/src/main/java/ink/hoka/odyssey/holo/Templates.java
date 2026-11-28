package ink.hoka.odyssey.holo;

import java.util.LinkedHashMap;
import java.util.Map;

/** Готовые шаблоны (кладутся в templates.json, если их там нет): табличка станции, меню с кнопками, информация со шкалой. */
final class Templates {

    private Templates() {}

    static final String STATION = """
            {"id": "tpl-station", "pos": [0, 0, 0], "facing": "fixed", "theme": "space", "width": 3.2,
             "pages": [{"lines": [
               [{"type": "text", "text": "<gradient:#8FD8FF:#D9E2EE><b>СТАНЦИЯ «АЛЬФА»</b></gradient>", "size": 1.6}],
               [{"type": "separator"}],
               [{"type": "text", "text": "<gray>Сектор</gray> <white>7-B</white>   <gray>Онлайн:</gray> <#FFC46B>{online}</#FFC46B>"}],
               [{"type": "text", "text": "<gray>Время станции:</gray> <white>{time}</white>", "size": 0.8}]]}]}
            """;

    static final String MENU = """
            {"id": "tpl-menu", "pos": [0, 0, 0], "facing": "billboard_y", "theme": "space",
             "pages": [
              {"lines": [
               [{"type": "text", "text": "<b><#FFC46B>МЕНЮ</#FFC46B></b> <gray>· 1/2</gray>", "size": 1.4}],
               [{"type": "separator"}],
               [{"type": "button", "label": "На спавн", "left": [{"type": "cmd", "value": "spawn"}]},
                {"type": "button", "label": "Правила", "left": [{"type": "msg", "value": "<#8FD8FF>Правила: не ломать станции."}]}],
               [{"type": "button", "label": "Дальше ›", "color": "#FFC46B", "left": [{"type": "page", "value": "next"}]}]]},
              {"lines": [
               [{"type": "text", "text": "<b><#FFC46B>МЕНЮ</#FFC46B></b> <gray>· 2/2</gray>", "size": 1.4}],
               [{"type": "separator"}],
               [{"type": "item", "item": "minecraft:compass", "hint": "Навигация"},
                {"type": "text", "text": "Привет, <aqua>{player}</aqua>!"}],
               [{"type": "button", "label": "‹ Назад", "left": [{"type": "page", "value": "prev"}]}]]}]}
            """;

    static final String INFO = """
            {"id": "tpl-info", "pos": [0, 0, 0], "facing": "billboard_y", "theme": "amber",
             "pages": [{"lines": [
               [{"type": "text", "text": "<b>РЕАКТОР</b>", "size": 1.3}],
               [{"type": "bar", "value": "0.72", "label": "Мощность 72%", "w": 2.4}],
               [{"type": "text", "text": "<gray>Координаты:</gray> {x} {y} {z}", "size": 0.8}]]}]}
            """;

    static Map<String, Hologram> builtIn() {
        Map<String, Hologram> m = new LinkedHashMap<>();
        m.put("табличка станции", HoloCodec.parse(STATION));
        m.put("меню с кнопками", HoloCodec.parse(MENU));
        m.put("информация со шкалой", HoloCodec.parse(INFO));
        return m;
    }
}
