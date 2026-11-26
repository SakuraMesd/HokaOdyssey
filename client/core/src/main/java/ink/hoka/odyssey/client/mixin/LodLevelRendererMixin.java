package ink.hoka.odyssey.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 0.64 дальняя прорисовка: матрица проекции кадра (с покачиванием камеры) — LOD строит свою с другими near/far. */
@Mixin(LevelRenderer.class)
public abstract class LodLevelRendererMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void hoka$lodMatrices(GraphicsResourceAllocator alloc, DeltaTracker dt, boolean outline, Camera camera, Matrix4f view,
                                  Matrix4f projection, Matrix4f cullProjection, GpuBufferSlice fog, Vector4f fogColor, boolean sky,
                                  CallbackInfo ci) {
        ink.hoka.odyssey.client.lod.LodRenderer.frameMatrices(view, projection, fogColor);
    }
}
