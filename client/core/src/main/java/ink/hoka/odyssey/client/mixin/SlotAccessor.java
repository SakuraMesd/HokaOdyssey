package ink.hoka.odyssey.client.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 0.67: место слота на экране (инвентарь Hoka Odyssey переставляет слоты; на закрытии возвращает). */
@Mixin(Slot.class)
public interface SlotAccessor {
    @Mutable @Accessor("x") void hoka$setX(int x);

    @Mutable @Accessor("y") void hoka$setY(int y);
}
