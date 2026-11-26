package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 0.64 дальняя прорисовка: туман игры считается от дальности LOD (иначе ближний мир тонул бы в дымке у края обычной
 * дальности, а за ним — чёткие дальние горы). Окружения тумана (дождь, вода, слепота) работают как прежде.
 */
@Mixin(GameRenderer.class)
public abstract class LodFogMixin {

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lorg/joml/Vector4f;"),
            require = 0)
    private Vector4f hoka$lodFog(FogRenderer fr, Camera cam, int rd, DeltaTracker dt, float darken, ClientLevel lvl, Operation<Vector4f> op) {
        return op.call(fr, cam, ink.hoka.odyssey.client.lod.LodRenderer.fogChunks(rd), dt, darken, lvl);
    }
}
