package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.TallWorld;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Упаковка координаты блока: X/Z по 24 бита, Y 16 бит (у ванили 26/26/12). Пересчитываем поля в конце инициализации
 * класса — всё остальное (лимиты высоты измерения, маски, сдвиги) игра выводит из них сама.
 */
@Mixin(BlockPos.class)
public abstract class BlockPosPackingMixin {

    @Shadow @Final @Mutable public static int PACKED_HORIZONTAL_LENGTH;
    @Shadow @Final @Mutable public static int PACKED_Y_LENGTH;
    @Shadow @Final @Mutable private static long PACKED_X_MASK;
    @Shadow @Final @Mutable private static long PACKED_Y_MASK;
    @Shadow @Final @Mutable private static long PACKED_Z_MASK;
    @Shadow @Final @Mutable private static int Z_OFFSET;
    @Shadow @Final @Mutable private static int X_OFFSET;
    @Shadow @Final @Mutable public static int MAX_HORIZONTAL_COORDINATE;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void hokaOdyssey$tall(CallbackInfo ci) {
        PACKED_HORIZONTAL_LENGTH = TallWorld.HORIZONTAL_BITS;
        PACKED_Y_LENGTH = 64 - 2 * PACKED_HORIZONTAL_LENGTH;
        PACKED_X_MASK = (1L << PACKED_HORIZONTAL_LENGTH) - 1L;
        PACKED_Y_MASK = (1L << PACKED_Y_LENGTH) - 1L;
        PACKED_Z_MASK = (1L << PACKED_HORIZONTAL_LENGTH) - 1L;
        Z_OFFSET = PACKED_Y_LENGTH;
        X_OFFSET = PACKED_Y_LENGTH + PACKED_HORIZONTAL_LENGTH;
        MAX_HORIZONTAL_COORDINATE = (1 << PACKED_HORIZONTAL_LENGTH) / 2 - 1;
    }
}
