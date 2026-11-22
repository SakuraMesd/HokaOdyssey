package ink.hoka.odyssey.houi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ink.hoka.odyssey.api.Services;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Тестовый магазин — пример, как модулю работать с окном: открыть с данными, ловить кнопки, обновлять данные на
 * лету. Монеты — в памяти (до перезапуска), у каждого по 100. Раскладка — module/houi/menus/demo_shop.json.
 */
final class DemoShop {

    record Good(String id, String item, String name, String desc, int price) {}

    private static final List<Good> GOODS = List.of(
            new Good("ruby", "hoka:ruby", "&cРубин", "Свой предмет: иконка с сервера", 10),
            new Good("hoe", "hoka:hoe", "&6Тяпка", "Свой предмет: модель .bbmodel", 25),
            new Good("bread", "minecraft:bread", "&fХлеб", "Обычный предмет игры", 3),
            new Good("gapple", "minecraft:golden_apple", "&eЗолотое яблоко", "Дорогое — проверь, что кнопка гаснет", 120));

    private final HoUi ui;
    private final Services services;
    private final Logger log;
    private final Map<UUID, Integer> coins = new ConcurrentHashMap<>();
    /** Кто хочет знать о покупках/монетах (тестовый HUD). */
    java.util.function.BiConsumer<Player, String> listener = (p, msg) -> { };

    int coins(Player p) {
        return coins.computeIfAbsent(p.getUuid(), k -> 100);
    }

    DemoShop(HoUi ui, Services services, Logger log) {
        this.ui = ui;
        this.services = services;
        this.log = log;
        ui.onAction("demo_shop", this::onAction);
    }

    void open(Player p) {
        JsonObject d = new JsonObject();
        d.addProperty("coins", coins.computeIfAbsent(p.getUuid(), k -> 100));
        d.addProperty("player", p.getUsername());
        d.addProperty("msg", "");
        JsonArray goods = new JsonArray();
        for (Good g : GOODS) {
            JsonObject o = new JsonObject();
            o.addProperty("id", g.id);
            o.addProperty("item", g.item);
            o.addProperty("name", g.name);
            o.addProperty("desc", g.desc);
            o.addProperty("price", g.price);
            goods.add(o);
        }
        d.add("goods", goods);
        if (!ui.open(p, "demo_shop", d)) p.sendMessage(net.kyori.adventure.text.Component.text("Нужен мод Hoka Odyssey"));
    }

    private void onAction(Player p, String action) {
        if (action.equals("add")) {                                    // кнопка «+50» — для проверки обновления данных
            int c = coins.merge(p.getUuid(), 50, Integer::sum);
            update(p, c, "&a+50 монет");
            return;
        }
        if (!action.startsWith("buy:")) return;
        Good g = GOODS.stream().filter(x -> x.id.equals(action.substring(4))).findFirst().orElse(null);
        if (g == null) return;
        int c = coins.computeIfAbsent(p.getUuid(), k -> 100);
        if (c < g.price) { update(p, c, "&cНе хватает монет"); return; }
        ItemStack it = stack(g.item);
        if (it == null) { update(p, c, "&cПредмет недоступен"); return; }
        coins.put(p.getUuid(), c - g.price);
        p.getInventory().addItemStack(it);
        update(p, c - g.price, "&aКуплено: " + g.name);
    }

    private void update(Player p, int c, String msg) {
        listener.accept(p, msg);
        JsonObject d = new JsonObject();
        d.addProperty("coins", c);
        d.addProperty("msg", msg);
        ui.update(p, "demo_shop", d);
    }

    private ItemStack stack(String item) {
        if (item.startsWith("hoka:")) {
            try {
                return services.get(ink.hoka.odyssey.hoitems.HoItems.class).map(h -> h.item(item.substring(5), 1)).orElse(null);
            } catch (Throwable e) {                                    // модуля hoitems нет
                log.warn("магазин: нет модуля hoitems для {}", item);
                return null;
            }
        }
        Material m = Material.fromKey(item);
        return m == null ? null : ItemStack.of(m);
    }
}
