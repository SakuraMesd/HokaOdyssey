package ink.hoka.odyssey.jetpack;

import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.Equippable;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.sound.SoundEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Ранец: equippable без вида брони кодируется в сеть и обратно, метки узнаются. */
class JetpackItemTest {

    @BeforeAll static void init() { MinecraftServer.init(); }

    @Test void equippableRoundTrip() {
        var eq = new Equippable(EquipmentSlot.CHESTPLATE, SoundEvent.ITEM_ARMOR_EQUIP_IRON, null, null, null,
                true, true, false, true, false, Equippable.DEFAULT_SHEARING_SOUND);
        ItemStack it = ItemStack.of(Material.IRON_INGOT).with(DataComponents.EQUIPPABLE, eq)
                .withTag(JetpackModule.HOITEM, "jetpack").withTag(JetpackModule.FUEL, 300);
        byte[] b = NetworkBuffer.makeArray(ItemStack.NETWORK_TYPE, it);
        ItemStack back = NetworkBuffer.wrap(b, 0, b.length).read(ItemStack.NETWORK_TYPE);
        assertEquals(EquipmentSlot.CHESTPLATE, back.get(DataComponents.EQUIPPABLE).slot());
        assertNull(back.get(DataComponents.EQUIPPABLE).assetId());
        assertTrue(JetpackModule.isPack(back));
        assertEquals(300, back.getTag(JetpackModule.FUEL));
        assertTrue(JetpackModule.isCanister(ItemStack.of(Material.BLAZE_POWDER).withTag(JetpackModule.HOITEM, "jetpack_fuel")));
    }
}
