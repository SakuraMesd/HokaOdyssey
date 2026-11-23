package ink.hoka.odyssey.client.mixin;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Вектор хода ввода (поле protected) — для ClientContext.setMove. */
@Mixin(ClientInput.class)
public interface ClientInputAccessor {
    @Accessor("moveVector")
    void hoka$setMoveVector(Vec2 v);
}
