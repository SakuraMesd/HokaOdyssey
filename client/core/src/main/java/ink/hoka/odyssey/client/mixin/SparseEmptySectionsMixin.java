package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.TallWorld;
import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Высокий мир + Sodium: набор «загруженных пустых секций» нужен только ванильной отрисовке (SectionOcclusionGraph),
 * Sodium его не читает. В высоком мире это ~1870 long на каждый столбец (миллионы на дальность) — не ведём.
 * Без Sodium — ваниль как есть.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public abstract class SparseEmptySectionsMixin {

    @Unique private static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");

    @Unique private static boolean hoka$skip() { return SODIUM && TallWorld.net; }

    @Inject(method = "addEmptySections", at = @At("HEAD"), cancellable = true)
    private void hoka$add(net.minecraft.world.level.chunk.LevelChunk c, CallbackInfo ci) { if (hoka$skip()) ci.cancel(); }

    @Inject(method = "dropEmptySections", at = @At("HEAD"), cancellable = true)
    private void hoka$drop(net.minecraft.world.level.chunk.LevelChunk c, CallbackInfo ci) { if (hoka$skip()) ci.cancel(); }

    @Inject(method = "refreshEmptySections", at = @At("HEAD"), cancellable = true)
    private void hoka$refresh(net.minecraft.world.level.chunk.LevelChunk c, CallbackInfo ci) { if (hoka$skip()) ci.cancel(); }

    @Inject(method = "onSectionEmptinessChanged", at = @At("HEAD"), cancellable = true)
    private void hoka$changed(int x, int y, int z, boolean empty, CallbackInfo ci) { if (hoka$skip()) ci.cancel(); }
}
