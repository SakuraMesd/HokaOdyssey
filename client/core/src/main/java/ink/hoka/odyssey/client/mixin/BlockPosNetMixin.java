package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.TallWorld;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Координата блока в пакетах (все пакеты игры идут через эти два метода): на нашем сервере с высоким миром — наш
 * формат, на любом другом — ванильный (внутри мод хранит иначе, поэтому переводим явно).
 */
@Mixin(FriendlyByteBuf.class)
public abstract class BlockPosNetMixin {

    @Inject(method = "readBlockPos(Lio/netty/buffer/ByteBuf;)Lnet/minecraft/core/BlockPos;", at = @At("HEAD"), cancellable = true)
    private static void hokaOdyssey$read(ByteBuf buf, CallbackInfoReturnable<BlockPos> cir) {
        long l = buf.readLong();
        cir.setReturnValue(TallWorld.net ? new BlockPos(TallWorld.tallX(l), TallWorld.tallY(l), TallWorld.tallZ(l))
                : new BlockPos(TallWorld.vanillaX(l), TallWorld.vanillaY(l), TallWorld.vanillaZ(l)));
    }

    @Inject(method = "writeBlockPos(Lio/netty/buffer/ByteBuf;Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private static void hokaOdyssey$write(ByteBuf buf, BlockPos pos, CallbackInfo ci) {
        buf.writeLong(TallWorld.net ? TallWorld.tallPack(pos.getX(), pos.getY(), pos.getZ())
                : TallWorld.vanillaPack(pos.getX(), pos.getY(), pos.getZ()));
        ci.cancel();
    }
}
