package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.CubeQueue;
import ink.hoka.odyssey.client.SparseSections;
import ink.hoka.odyssey.client.TallWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 0.57 (задача 1, вместо BlockUpdateOrderMixin): тяжёлые ванильные пакеты столбца высокого мира (скелет 1876
 * секций) — в очередь CubeQueue: применяются по бюджету кадра, в порядке с кубами. Ответ на Ping больше не ждёт
 * за тысячами скелетов. Порядок с остальным:
 * <ul>
 *   <li>скелет — всегда в очередь (поколение столбца растёт сейчас: ждущие кубы старого столбца устарели);</li>
 *   <li>Forget, правки блоков / блок-сущностей, биомы, свет столбца — в очередь, если по столбцу (секции) что-то ждёт;</li>
 *   <li>BlockChangedAck — если в очереди есть отложенный ванильный пакет (иначе предсказание откатится раньше правки);</li>
 *   <li>ChunkBatchFinished — если очередь не пуста: время пачки включает применение, игра сама попросит меньше
 *       столбцов за тик (обратная связь, очередь не растёт без предела).</li>
 * </ul>
 * Сущности, позиции, чат — сразу. Все инъекции — после ensureRunningOnSameThread (только главный поток).
 */
@Mixin(ClientPacketListener.class)
public abstract class PacketDeferMixin {

    @Shadow private ClientLevel level;

    @Unique private static final String ENSURE = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Unique
    private boolean hoka$tall() { return TallWorld.net && !CubeQueue.REPLAY && level != null && level.getSectionsCount() > 64; }

    @Unique
    private void hoka$defer(Runnable r, long[] cols, long sec) { CubeQueue.defer(level, r, cols, sec); }

    @Inject(method = "handleLevelChunkWithLight", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void hoka$column(ClientboundLevelChunkWithLightPacket p, CallbackInfo ci) {
        if (!hoka$tall()) return;
        CubeQueue.columnChanged(p.getX(), p.getZ());                   // ждущие кубы старого столбца — устарели
        ClientPacketListener self = (ClientPacketListener) (Object) this;
        hoka$defer(() -> p.handle(self), new long[]{ChunkPos.asLong(p.getX(), p.getZ())}, Long.MIN_VALUE);
        ci.cancel();
    }

    @Inject(method = "handleForgetLevelChunk", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void hoka$forget(ClientboundForgetLevelChunkPacket p, CallbackInfo ci) {
        if (!hoka$tall() || !CubeQueue.waitsColumn(p.pos().x, p.pos().z)) return;
        CubeQueue.columnChanged(p.pos().x, p.pos().z);                 // при повторе — не растить (ServerLightForgetMixin)
        ClientPacketListener self = (ClientPacketListener) (Object) this;
        hoka$defer(() -> p.handle(self), new long[]{p.pos().toLong()}, Long.MIN_VALUE);
        ci.cancel();
    }

    @Inject(method = "handleBlockUpdate", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void hoka$block(ClientboundBlockUpdatePacket p, CallbackInfo ci) {
        if (hoka$sec(p.getPos(), () -> p.handle((ClientPacketListener) (Object) this))) ci.cancel();
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void hoka$blocks(ClientboundSectionBlocksUpdatePacket p, CallbackInfo ci) {
        if (!hoka$tall()) return;
        BlockPos[] first = {null};
        p.runUpdates((pos, st) -> { if (first[0] == null) first[0] = pos.immutable(); });   // все блоки пакета — в одной секции
        if (hoka$sec(first[0], () -> p.handle((ClientPacketListener) (Object) this))) ci.cancel();
    }

    @Inject(method = "handleBlockEntityData", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void hoka$blockEntity(ClientboundBlockEntityDataPacket p, CallbackInfo ci) {
        if (hoka$sec(p.getPos(), () -> p.handle((ClientPacketListener) (Object) this))) ci.cancel();
    }

    /** Правка в секции pos: ждёт столбец или куб этой секции — в очередь (держит секцию и столбец). */
    @Unique
    private boolean hoka$sec(BlockPos pos, Runnable r) {
        if (pos == null || !hoka$tall()) return false;
        long sec = SectionPos.asLong(pos);
        if (!CubeQueue.waits(sec)) return false;
        hoka$defer(r, new long[]{ChunkPos.asLong(SectionPos.x(sec), SectionPos.z(sec))}, sec);
        return true;
    }

    @Inject(method = "handleChunksBiomes", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void hoka$biomes(ClientboundChunksBiomesPacket p, CallbackInfo ci) {
        if (!hoka$tall()) return;
        var data = p.chunkBiomeData();
        boolean wait = false;
        long[] cols = new long[data.size()];
        for (int i = 0; i < cols.length; i++) {
            cols[i] = data.get(i).pos().toLong();
            if (CubeQueue.waitsColumn(data.get(i).pos().x, data.get(i).pos().z)) wait = true;
        }
        if (!wait) return;
        ClientPacketListener self = (ClientPacketListener) (Object) this;
        hoka$defer(() -> p.handle(self), cols, Long.MIN_VALUE);
        ci.cancel();
    }

    @Inject(method = "handleLightUpdatePacket", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void hoka$light(ClientboundLightUpdatePacket p, CallbackInfo ci) {
        if (!hoka$tall() || !CubeQueue.waitsColumn(p.getX(), p.getZ())) return;
        ClientPacketListener self = (ClientPacketListener) (Object) this;
        hoka$defer(() -> p.handle(self), new long[]{ChunkPos.asLong(p.getX(), p.getZ())}, Long.MIN_VALUE);
        ci.cancel();
    }

    @Inject(method = "handleBlockChangedAck", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void hoka$ack(ClientboundBlockChangedAckPacket p, CallbackInfo ci) {
        if (!hoka$tall() || !CubeQueue.hasVanilla()) return;
        ClientPacketListener self = (ClientPacketListener) (Object) this;
        hoka$defer(() -> p.handle(self), null, Long.MIN_VALUE);
        ci.cancel();
    }

    /**
     * У ваниль-обработчика нет ensureRunningOnSameThread (сетевой поток): на главный — через очередь пакетов (тот же
     * порядок, что у скелетов; mc.execute шёл бы отдельной очередью). Там: очередь кубов пуста — как в ванили, иначе
     * в неё с always (ответ о пачке нужен серверу и после смены мира — иначе Minestom перестанет слать столбцы).
     */
    @Inject(method = "handleChunkBatchFinished", at = @At("HEAD"), cancellable = true)
    private void hoka$batch(ClientboundChunkBatchFinishedPacket p, CallbackInfo ci) {
        if (!TallWorld.net || CubeQueue.REPLAY) return;
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener self = (ClientPacketListener) (Object) this;
        if (!mc.isSameThread()) {
            try { mc.packetProcessor().scheduleIfPossible(self, p); } catch (java.util.concurrent.RejectedExecutionException ex) { return; }
            ci.cancel();
            return;
        }
        if (CubeQueue.isEmpty() || level == null) return;
        CubeQueue.defer(level, () -> p.handle(self), null, Long.MIN_VALUE, true);
        ci.cancel();
    }

    /** 0.57 (1а): скелет, разобранный в сетевом потоке (ChunkPacketPreparseMixin), — на время чтения столбца. */
    @Inject(method = "updateLevelChunk", at = @At("HEAD"))
    private void hoka$pre(int x, int z, ClientboundLevelChunkPacketData data, CallbackInfo ci) {
        SparseSections.pending = data instanceof ink.hoka.odyssey.client.HokaPreparsed h ? h : null;
    }

    @Inject(method = "updateLevelChunk", at = @At("RETURN"))
    private void hoka$preDone(int x, int z, ClientboundLevelChunkPacketData data, CallbackInfo ci) {
        SparseSections.pending = null;
    }
}
