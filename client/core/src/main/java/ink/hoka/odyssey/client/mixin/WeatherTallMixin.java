package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import ink.hoka.odyssey.client.TallHeight;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.WeatherRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 0.56: осадки в высоком мире — страховка над окном (TallHeight.rainTop): колонка под укрытием, которого клиент не
 * знает (остров выше окна кубов), — без дождя/снега и без капель. Карту высот по кубам ведёт TallHeight.cube.
 * Обычный мир — ваниль как есть. require 0: другой мод перепишет осадки — останется ваниль.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherTallMixin {

    @WrapOperation(method = "extractRenderState", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
    private int hoka$columnHeight(Level level, Heightmap.Types t, int x, int z, Operation<Integer> op,
                                  @Local(argsOnly = true) Vec3 cam, @Local(argsOnly = true) WeatherRenderState st) {
        int h = op.call(level, t, x, z);
        return TallHeight.rainTop(level, x, z, h, (int) Math.floor(cam.y), st.radius);
    }

    @WrapOperation(method = "tickRainParticles", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getHeightmapPos(Lnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"))
    private BlockPos hoka$dropPos(ClientLevel level, Heightmap.Types t, BlockPos at, Operation<BlockPos> op,
                                  @Local(argsOnly = true) Camera camera) {
        BlockPos p = op.call(level, t, at);
        int camY = (int) Math.floor(camera.position().y);
        int y = TallHeight.rainTop(level, p.getX(), p.getZ(), p.getY(), camY, 10);   // капли — в радиусе 10 по высоте
        return y == p.getY() ? p : new BlockPos(p.getX(), y, p.getZ());
    }
}
