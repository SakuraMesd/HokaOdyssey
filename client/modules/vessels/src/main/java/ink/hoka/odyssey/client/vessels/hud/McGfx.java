package ink.hoka.odyssey.client.vessels.hud;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link Gfx} в игре: GuiGraphics кадра. Холсты — картинки (DynamicTexture) один к одному в пикселях экрана: заливаются
 * в видеокарту, только когда холст перерисован (версия выросла). Прямоугольники и текст — по пикселям экрана (чётко).
 */
public final class McGfx implements Gfx {

    private final String prefix;
    private final Map<String, Tex> textures = new HashMap<>();
    private GuiGraphics g;
    private float gs = 1;
    private Font font;

    private static final class Tex {
        Identifier id;
        DynamicTexture tex;
        int w, h, version = -1;
        /** Какой холст залит (новый холст того же размера начинает с той же версии — сверять и его). */
        Canvas from;
    }

    /** prefix — своя папка имён текстур (hud, menu). */
    public McGfx(String prefix) { this.prefix = prefix; }

    /** Начать кадр. */
    public McGfx frame(GuiGraphics g) {
        this.g = g;
        Minecraft mc = Minecraft.getInstance();
        this.gs = (float) mc.getWindow().getGuiScale();
        this.font = mc.font;
        return this;
    }

    public GuiGraphics graphics() { return g; }

    @Override public int width() { return g.guiWidth(); }

    @Override public int height() { return g.guiHeight(); }

    @Override public float scale() { return gs; }

    @Override
    public void fill(float x0, float y0, float x1, float y1, int argb) {
        int a = Math.round(x0 * gs), b = Math.round(y0 * gs), c = Math.round(x1 * gs), d = Math.round(y1 * gs);
        if (c <= a || d <= b || (argb >>> 24) == 0) return;
        var pose = g.pose();
        pose.pushMatrix();
        pose.scale(1 / gs, 1 / gs);
        g.fill(a, b, c, d, argb);
        pose.popMatrix();
    }

    @Override
    public void image(String key, Canvas c, float x, float y, float alpha) {
        if (alpha <= 0.003f) return;
        Tex t = textures.computeIfAbsent(key, k -> new Tex());
        var tm = Minecraft.getInstance().getTextureManager();
        if (t.tex == null || t.w != c.w || t.h != c.h) {
            if (t.tex != null) tm.release(t.id);
            t.id = Identifier.fromNamespaceAndPath("hoka_odyssey", "vessels/" + prefix + "/" + key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_"));
            t.tex = new DynamicTexture(t.id::toString, c.w, c.h, false);
            t.w = c.w;
            t.h = c.h;
            t.version = -1;
            tm.register(t.id, t.tex);
        }
        if (t.version != c.version || t.from != c) {
            upload(c, t.tex);
            t.version = c.version;
            t.from = c;
        }
        var pose = g.pose();
        pose.pushMatrix();
        pose.scale(1 / gs, 1 / gs);
        pose.translate(Math.round(x * gs), Math.round(y * gs));
        int col = (Math.round(Math.clamp(alpha, 0, 1) * 255) << 24) | 0xFFFFFF;
        g.blit(RenderPipelines.GUI_TEXTURED, t.id, 0, 0, 0, 0, c.w, c.h, c.w, c.h, col);
        pose.popMatrix();
    }

    /** Холст → память картинки (RGBA) напрямую, без setPixel с проверками. */
    private static void upload(Canvas c, DynamicTexture t) {
        NativeImage img = t.getPixels();
        if (img == null) return;
        if (img.format() == NativeImage.Format.RGBA && img.getWidth() == c.w && img.getHeight() == c.h) {
            long ptr = img.getPointer();
            for (int i = 0, n = c.w * c.h; i < n; i++) org.lwjgl.system.MemoryUtil.memPutInt(ptr + i * 4L, c.abgr(i));
        } else {
            for (int y = 0; y < c.h; y++) for (int x = 0; x < c.w; x++) img.setPixel(x, y, c.argb(x, y));
        }
        t.upload();
    }

    @Override
    public void text(String s, float x, float y, float size, int argb, int align, boolean shadow) {
        if (s == null || s.isEmpty() || (argb >>> 24) < 4) return;
        int tw = font.width(s);
        var pose = g.pose();
        pose.pushMatrix();
        float ox = align < 0 ? 0 : align == 0 ? -tw * size / 2 : -tw * size;
        pose.translate(Math.round((x + ox) * gs) / gs, Math.round(y * gs) / gs);   // по пикселям экрана
        pose.scale(size, size);
        g.drawString(font, s, 0, 0, argb, shadow);
        pose.popMatrix();
    }

    @Override public int textWidth(String s) { return font.width(s); }

    /** Выгрузить картинки (модуль выгружается, пилот вышел). */
    public void release() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (Tex t : textures.values()) if (t.tex != null) tm.release(t.id);
        textures.clear();
    }
}
