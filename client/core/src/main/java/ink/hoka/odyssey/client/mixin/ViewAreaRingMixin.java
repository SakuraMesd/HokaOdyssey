package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.VanillaView;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Этап 6, V1–V3 (CUBIC-CHUNKS.md §3.6): ViewArea высокого мира без Sodium — кольцо по Y, как ваниль делает по X/Z.
 * Вместо секций на всю высоту (у космоса 4094) — 2rY+1 слоёв вокруг камеры, прижатых к границам мира
 * (ViewRing.base). Секция Y лежит в слое floorMod(Y, sizeY); repositionCamera переставляет слои при смене секции
 * камеры по любой оси (LevelRenderer зовёт его и при смене Y). rY = 0 — ваниль без изменений (обычный мир, Sodium).
 * Вместо подкласса HokaViewArea из плана — @Inject в сам ViewArea: тот же результат без @Redirect NEW.
 */
@Mixin(ViewArea.class)
public abstract class ViewAreaRingMixin {

    @Shadow @Final protected LevelRenderer levelRenderer;
    @Shadow @Final protected Level level;
    @Shadow protected int sectionGridSizeY;
    @Shadow protected int sectionGridSizeX;
    @Shadow protected int sectionGridSizeZ;
    @Shadow private int viewDistance;
    @Shadow private SectionPos cameraSectionPos;
    @Shadow public SectionRenderDispatcher.RenderSection[] sections;

    /** Радиус кольца по Y; 0 — ваниль. */
    @Unique private int hoka$ry;

    /** V1: размер по Y = 2rY+1 вместо высоты мира. Зовётся из конструктора до createSections. */
    @Inject(method = "setViewDistance", at = @At("HEAD"), cancellable = true)
    private void hoka$size(int vd, CallbackInfo ci) {
        int r = VanillaView.radiusY(level, vd);
        hoka$ry = r;
        VanillaView.current = r;
        VanillaView.currentView = vd;
        if (r == 0) return;
        int i = vd * 2 + 1;
        sectionGridSizeX = i;
        sectionGridSizeY = 2 * r + 1;
        sectionGridSizeZ = i;
        viewDistance = vd;
        ci.cancel();
    }

    @Unique
    private int hoka$base(int camY) {
        return ink.hoka.odyssey.client.ViewRing.base(camY, hoka$ry, level.getMinSectionY(), level.getMaxSectionY());
    }

    /** V2: кольцо по Y. Слою j достаётся секция base + floorMod(j − base, sizeY) (≡ j по модулю sizeY). */
    @Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true)
    private void hoka$reposition(SectionPos cam, CallbackInfo ci) {
        if (hoka$ry == 0) return;
        int sy = sectionGridSizeY, base = hoka$base(cam.y());
        for (int i = 0; i < sectionGridSizeX; i++) {
            int x0 = cam.x() - viewDistance, x = x0 + Math.floorMod(i - x0, sectionGridSizeX);
            for (int k = 0; k < sectionGridSizeZ; k++) {
                int z0 = cam.z() - viewDistance, z = z0 + Math.floorMod(k - z0, sectionGridSizeZ);
                for (int j = 0; j < sy; j++) {
                    int y = ink.hoka.odyssey.client.ViewRing.layerY(j, base, sy);
                    SectionRenderDispatcher.RenderSection s = sections[(k * sy + j) * sectionGridSizeX + i];
                    long node = SectionPos.asLong(x, y, z);
                    if (s.getSectionNode() != node) s.setSectionNode(node);
                }
            }
        }
        cameraSectionPos = cam;
        levelRenderer.getSectionOcclusionGraph().invalidate();
        ci.cancel();
    }

    /** V3: секция в окне — по X/Z как ваниль, по Y — внутри прижатого кольца. */
    @Inject(method = "containsSection", at = @At("HEAD"), cancellable = true)
    private void hoka$contains(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (hoka$ry == 0) return;
        cir.setReturnValue(hoka$in(x, y, z));
    }

    @Unique
    private boolean hoka$in(int x, int y, int z) {
        if (y < level.getMinSectionY() || y > level.getMaxSectionY()) return false;
        int cx = cameraSectionPos.x(), cz = cameraSectionPos.z();
        if (x < cx - viewDistance || x > cx + viewDistance || z < cz - viewDistance || z > cz + viewDistance) return false;
        int base = hoka$base(cameraSectionPos.y());
        return y >= base && y < base + sectionGridSizeY;
    }

    /** V3: слой секции — floorMod(Y, sizeY), как ваниль по X/Z. */
    @Inject(method = "getRenderSection(III)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;",
            at = @At("HEAD"), cancellable = true)
    private void hoka$get(int x, int y, int z, CallbackInfoReturnable<SectionRenderDispatcher.RenderSection> cir) {
        if (hoka$ry == 0) return;
        if (!hoka$in(x, y, z)) { cir.setReturnValue(null); return; }
        int i = Math.floorMod(x, sectionGridSizeX), j = ink.hoka.odyssey.client.ViewRing.slot(y, sectionGridSizeY), k = Math.floorMod(z, sectionGridSizeZ);
        cir.setReturnValue(sections[(k * sectionGridSizeY + j) * sectionGridSizeX + i]);
    }
}
