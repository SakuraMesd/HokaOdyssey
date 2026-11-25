package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.CubeQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 0.49: кубы применяются по бюджету кадра (CubeQueue). Ванильная правка блока / блок-сущности сначала дожимает
 * очередь — куб, пришедший раньше правки, не ляжет поверх неё (порядок как у пакетов). На сетевом потоке
 * (до ensureRunningOnSameThread) ничего не делаем.
 */
@Mixin(ClientPacketListener.class)
public abstract class BlockUpdateOrderMixin {

    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void hoka$order(ClientboundBlockUpdatePacket p, CallbackInfo ci) { hoka$drain(p.getPos()); }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"))
    private void hoka$order(ClientboundSectionBlocksUpdatePacket p, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        net.minecraft.core.BlockPos[] first = {null};
        p.runUpdates((pos, st) -> { if (first[0] == null) first[0] = pos.immutable(); });   // все блоки пакета — в одной секции
        hoka$drain(first[0]);
    }

    @Inject(method = "handleBlockEntityData", at = @At("HEAD"))
    private void hoka$order(ClientboundBlockEntityDataPacket p, CallbackInfo ci) { hoka$drain(p.getPos()); }

    /** 0.49.1: дожать очередь только до последнего куба этой секции (не всю — без фриза при ломании во время прогрузки). */
    @org.spongepowered.asm.mixin.Unique
    private static void hoka$drain(net.minecraft.core.BlockPos pos) {
        if (pos == null || !Minecraft.getInstance().isSameThread()) return;
        CubeQueue.drainThrough(net.minecraft.core.SectionPos.asLong(pos));
    }
}
