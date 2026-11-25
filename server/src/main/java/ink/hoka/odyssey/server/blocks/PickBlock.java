package ink.hoka.odyssey.server.blocks;

import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerPickBlockEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

/**
 * Колёсико мыши — «выбрать блок» (как у игры с 1.21.4: клиент только просит, предмет в руку кладёт сервер; Minestom
 * этого не делает). Предмет блока уже есть на панели — выбрать его; есть в инвентаре — поменять местами с ячейкой
 * панели; нет — в творческом положить новый (на свободную ячейку панели или вместо выбранного).
 */
public final class PickBlock {

    private PickBlock() {}

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerPickBlockEvent.class, e -> pick(e.getPlayer(), e.getBlock(), e.isIncludeData()));
    }

    static Material itemOf(Block b) {
        Material m = b.registry() != null ? b.registry().material() : null;
        if (m == null) m = Material.fromKey(b.key());
        return m != null && m != Material.AIR ? m : null;
    }

    private static void pick(Player p, Block b, boolean data) {
        if (b == null || b.isAir()) return;
        Material m = itemOf(b);
        if (m == null) return;
        PlayerInventory inv = p.getInventory();
        int held = p.getHeldSlot();
        for (int i = 0; i < 9; i++)                                             // уже на панели — просто выбрать
            if (inv.getItemStack(i).material() == m) { p.setHeldItemSlot((byte) i); return; }
        int slot = hotbarSlot(inv, held);
        for (int i = 9; i < 36; i++) {                                          // в инвентаре — на панель (обмен)
            if (inv.getItemStack(i).material() != m) continue;
            ItemStack from = inv.getItemStack(i), to = inv.getItemStack(slot);
            inv.setItemStack(slot, from);
            inv.setItemStack(i, to);
            p.setHeldItemSlot((byte) slot);
            return;
        }
        if (p.getGameMode() != GameMode.CREATIVE) return;                       // взять из ниоткуда — только в творческом
        ItemStack it = ItemStack.of(m);
        if (data && b.nbt() != null && !b.nbt().isEmpty() && b.registry() != null && b.registry().blockEntityType() != null)
            it = it.with(net.minestom.server.component.DataComponents.BLOCK_ENTITY_DATA,   // Ctrl: с содержимым (сундук с вещами)
                    new net.minestom.server.item.component.TypedCustomData<>(b.registry().blockEntityType(), b.nbt()));
        inv.setItemStack(slot, it);
        p.setHeldItemSlot((byte) slot);
    }

    /** Как у игры: выбранная ячейка, если пуста; иначе первая пустая на панели; иначе выбранная. */
    private static int hotbarSlot(PlayerInventory inv, int held) {
        if (inv.getItemStack(held).isAir()) return held;
        for (int k = 0; k < 9; k++) {
            int i = (held + k) % 9;
            if (inv.getItemStack(i).isAir()) return i;
        }
        return held;
    }
}
