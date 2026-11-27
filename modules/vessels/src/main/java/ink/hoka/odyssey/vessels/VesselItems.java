package ink.hoka.odyssey.vessels;

import ink.hoka.odyssey.hoitems.HoItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;

import java.util.List;

/**
 * Предметы кораблей: топливо и улучшения. Сами предметы (имя, иконка) — модуля hoitems: id vessel_fuel,
 * vessel_engine, vessel_tank, vessel_gun, vessel_warp (описание и иконки — demo-objects/vessels/hoitems/). Нет
 * hoitems или в нём нет такого предмета — запасной ванильный предмет с тем же смыслом.
 * Узнаём предмет по своей метке hoka_vessel или по метке hoitems (hoka_item = vessel_…) — так годятся и предметы,
 * выданные /hoitem give.
 */
final class VesselItems {

    /** Своя метка: «fuel» или вид улучшения. */
    static final Tag<String> TAG = Tag.String("hoka_vessel");
    /** Метка модуля hoitems (тот же ключ, что HoItemsModule.TAG). */
    static final Tag<String> HOITEM = Tag.String("hoka_item");
    static final String FUEL = "fuel";

    /** Модуль hoitems сейчас (его могут перезагрузить; нет — null). */
    private final java.util.function.Supplier<HoItems> hoitems;

    VesselItems(java.util.function.Supplier<HoItems> hoitems) { this.hoitems = hoitems; }

    /** id предмета в hoitems: vessel_fuel, vessel_engine… */
    static String hoId(String what) { return "vessel_" + what; }

    /** Что это за предмет кораблей: «fuel», вид улучшения или null — не наш. */
    static String kindOf(ItemStack it) {
        if (it == null || it.isAir()) return null;
        String own = it.getTag(TAG);
        if (own != null) return own;
        String ho = it.getTag(HOITEM);
        if (ho != null && ho.startsWith("vessel_")) {
            String k = ho.substring(7);
            if (k.equals(FUEL) || Upgrades.Type.of(k) != null) return k;
        }
        return null;
    }

    static boolean isFuel(ItemStack it) { return FUEL.equals(kindOf(it)); }

    /** Вид улучшения или null. */
    static Upgrades.Type upgradeOf(ItemStack it) {
        String k = kindOf(it);
        return k == null || k.equals(FUEL) ? null : Upgrades.Type.of(k);
    }

    ItemStack fuel(int count) {
        return make(FUEL, "Топливо", NamedTextColor.GOLD, Material.GLOWSTONE_DUST,
                List.of("Кристаллы для двигателей корабля.", "Кладите в слоты топлива (меню корабля — G)."), count);
    }

    /** Размер стопки топлива (ёмкость бака = 4 × это). */
    int fuelStack() { return Math.max(1, fuel(1).maxStackSize()); }

    ItemStack upgrade(Upgrades.Type t) {
        String what = switch (t) {
            case ENGINE -> "скорость и форсаж +10%";
            case TANK -> "расход топлива на варп −10%";
            case GUN -> "перезарядка орудия −10%, урон +10%";
            case WARP -> "перезарядка варпа −15%";
        };
        Material m = switch (t) {
            case ENGINE -> Material.BLAZE_ROD;
            case TANK -> Material.BUCKET;
            case GUN -> Material.LIGHTNING_ROD;
            case WARP -> Material.ENDER_EYE;
        };
        return make(t.id, t.title, NamedTextColor.AQUA, m,
                List.of("Улучшение корабля: " + what + ".", "Кладите в слоты улучшений (меню корабля — G)."), 1)
                .withMaxStackSize(1);                                           // одно улучшение — один слот
    }

    private ItemStack make(String what, String name, NamedTextColor color, Material fallback, List<String> lore, int count) {
        HoItems h = hoitems != null ? hoitems.get() : null;
        ItemStack it = h != null ? h.item(hoId(what), Math.max(1, count)) : null;
        if (it != null && !it.isAir()) return it;                              // предмет hoitems — узнаём по его метке (стопки с /hoitem give)
        List<Component> l = lore.stream().map(s -> (Component) Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList();
        return ItemStack.builder(fallback).amount(Math.max(1, count))
                .customName(Component.text(name, color).decoration(TextDecoration.ITALIC, false))
                .lore(l)
                .build()
                .withTag(TAG, what);
    }
}
