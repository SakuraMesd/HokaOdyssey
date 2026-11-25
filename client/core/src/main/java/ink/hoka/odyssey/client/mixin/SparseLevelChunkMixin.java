package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ink.hoka.odyssey.client.SparseSections;
import ink.hoka.odyssey.client.TallWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Высокий мир, разреженные секции (CUBIC-CHUNKS.md §3.1, этап 2):
 * <ul>
 *   <li>C3t — пакет столбца: пустые секции с одним биомом → общая секция мира, остальные — свои;</li>
 *   <li>C2 — запись блока в общую секцию: сначала своя копия (биомы сохраняются);</li>
 *   <li>C4 — пакет биомов: общие секции не меняем, а заменяем.</li>
 * </ul>
 */
@Mixin(LevelChunk.class)
public abstract class SparseLevelChunkMixin {

    @Unique private boolean hoka$readSparse;

    @Inject(method = "replaceWithPacketData", at = @At("HEAD"))
    private void hoka$readSections(FriendlyByteBuf buf, Map<Heightmap.Types, long[]> heights,
                                   Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> be, CallbackInfo ci) {
        hoka$readSparse = TallWorld.net;
        if (!hoka$readSparse) return;
        LevelChunk self = (LevelChunk) (Object) this;
        ink.hoka.odyssey.client.CubeQueue.columnChanged(self.getPos().x, self.getPos().z);   // ждущие кубы старого столбца — устарели
        var f = self.getLevel().palettedContainerFactory();
        LevelChunkSection[] secs = self.getSections();
        // 0.50.1: столбец прислан повторно (подстраховка сервера / переотправка) — скелет затирает кубы с блоками. У
        // соседей грани к ним ОТКРЫВАЮТСЯ (дыра, пока кубы не придут снова): сам куб и соседи — обязательной пометкой.
        // Раньше это покрывала пометка 3×3 в TallLight.enable, с 0.50 там только свой столбец.
        boolean tall = self.getLevel().getSectionsCount() > 64 && net.minecraft.client.Minecraft.getInstance().isSameThread();
        for (int i = 0; i < secs.length; i++) {
            LevelChunkSection old = secs[i];
            // 0.51: повторный скелет живого столбца — его кубы с блоками в кэш (сервер пришлёт их ссылкой)
            if (tall && old != null && !(old instanceof SparseSections.Shared) && !old.hasOnlyAir())
                ink.hoka.odyssey.client.CubeCache.captureSection(self, i, old);
            secs[i] = SparseSections.read(f, buf);
            if (tall && old != null && !(old instanceof SparseSections.Shared) && !old.hasOnlyAir())
                ink.hoka.odyssey.client.SectionDirty.mark(self.getPos().x, self.getSectionYFromSectionIndex(i), self.getPos().z,
                        ink.hoka.odyssey.client.SectionDirty.blockFaces(old, secs[i]));
        }
        ink.hoka.odyssey.client.ContentRange.column(self.getLevel(), self.getPos().x, self.getPos().z, secs);   // слои с геометрией
        if (tall) ink.hoka.odyssey.client.CubeCache.label(self);         // 0.51: столбец — под текущим ключом карты
    }

    /** Ванильный цикл чтения секций: уже прочитано выше — пропускаем. */
    @WrapOperation(method = "replaceWithPacketData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;read(Lnet/minecraft/network/FriendlyByteBuf;)V"))
    private void hoka$skipRead(LevelChunkSection s, FriendlyByteBuf buf, Operation<Void> op) {
        if (!hoka$readSparse) op.call(s, buf);
    }

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void hoka$copyOnWrite(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        if (state.isAir()) return;
        LevelChunk self = (LevelChunk) (Object) this;
        int idx = self.getSectionIndex(pos.getY());
        LevelChunkSection[] secs = self.getSections();
        if (idx < 0 || idx >= secs.length) return;
        if (secs[idx].hasOnlyAir())                                     // первый блок в пустом кубе — слой с геометрией
            ink.hoka.odyssey.client.ContentRange.add(self.getLevel(), self.getPos().x, self.getSectionYFromSectionIndex(idx), self.getPos().z);
        if (secs[idx] instanceof SparseSections.Shared sh)
            secs[idx] = SparseSections.copyForWrite(self.getLevel().palettedContainerFactory(), sh);
    }

    @Inject(method = "replaceBiomes", at = @At("HEAD"), cancellable = true)
    private void hoka$biomes(FriendlyByteBuf buf, CallbackInfo ci) {
        LevelChunk self = (LevelChunk) (Object) this;
        LevelChunkSection[] secs = self.getSections();
        boolean any = false;
        for (LevelChunkSection s : secs) if (s instanceof SparseSections.Shared) { any = true; break; }
        if (!any) return;
        var f = self.getLevel().palettedContainerFactory();
        for (int i = 0; i < secs.length; i++) {
            if (secs[i] instanceof SparseSections.Shared) secs[i] = SparseSections.readBiomes(f, buf);
            else secs[i].readBiomes(buf);
        }
        ci.cancel();
    }
}
