package ink.hoka.odyssey.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 0.80 Левый верх окна-контейнера на экране (оформление модуля рисует фон по нему). */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("leftPos") int hoka$leftPos();

    @Accessor("topPos") int hoka$topPos();
}
