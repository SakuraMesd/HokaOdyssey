package ink.hoka.odyssey.hoitems;

import net.minestom.server.item.ItemStack;

/**
 * Свои предметы для других модулей: {@code services().get(HoItems.class)} (в module.yml — {@code softdepends: [hoitems]}).
 * <pre>
 * ItemStack ruby = items.item("ruby", 3);   // null — такого нет
 * if ("ruby".equals(items.idOf(stack))) …
 * </pre>
 */
public interface HoItems {

    /** Предмет по id из config.yml модуля hoitems (null — нет такого). */
    ItemStack item(String id, int count);

    /** id своего предмета или null. */
    String idOf(ItemStack stack);
}
