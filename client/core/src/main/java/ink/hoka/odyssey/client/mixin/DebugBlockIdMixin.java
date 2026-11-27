package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ink.hoka.odyssey.client.block.PoolBlocks;
import net.minecraft.client.gui.components.debug.DebugEntryLookingAtBlock;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 0.75 F3: блок мода показывается своим id (hoka_odyssey:nickel_ore), а не номером запаса (solid_003). */
@Mixin(DebugEntryLookingAtBlock.class)
public abstract class DebugBlockIdMixin {

    @WrapOperation(method = "display", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/DefaultedRegistry;getKey(Ljava/lang/Object;)Lnet/minecraft/resources/Identifier;"))
    private Identifier hoka$id(DefaultedRegistry<?> reg, Object block, Operation<Identifier> op) {
        if (block instanceof PoolBlocks.PoolBlock pb && pb.customId != null) return Identifier.fromNamespaceAndPath("hoka_odyssey", pb.customId);
        return op.call(reg, block);
    }
}
