package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.block.Remap;
import net.minecraft.core.IdMapper;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 0.73 Свои блоки — настоящие блоки мода: номер носителя от сервера → блок мода (и обратно), только для таблицы
 * состояний блоков ({@link Remap}).
 */
@Mixin(IdMapper.class)
public abstract class IdMapperRemapMixin {

    @Inject(method = "byId", at = @At("HEAD"), cancellable = true)
    private void hoka$byId(int id, CallbackInfoReturnable<Object> cir) {
        if ((Object) this != Block.BLOCK_STATE_REGISTRY) return;
        Object r = Remap.byId(id);
        if (r != null) cir.setReturnValue(r);
    }

    @Inject(method = "getId", at = @At("HEAD"), cancellable = true)
    private void hoka$getId(Object o, CallbackInfoReturnable<Integer> cir) {
        if ((Object) this != Block.BLOCK_STATE_REGISTRY) return;
        int id = Remap.toId(o);
        if (id >= 0) cir.setReturnValue(id);
    }
}
