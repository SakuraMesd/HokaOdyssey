package ink.hoka.odyssey.client.vessels.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * {@link Gfx} в картинку PNG — превью HUD и окна корабля без игры. Холсты — тем же кодом, что в игре (один к одному в
 * пикселях экрана); текст — растровым шрифтом игры (ascii, accented, nonlatin_european из jar Minecraft на classpath
 * тестов) с тенью, как у игры: ширины и вид совпадают.
 */
public final class PngGfx implements Gfx {

    final BufferedImage img;
    final Graphics2D g2;
    private final int gw, gh;
    private final float gs;

    public PngGfx(int guiW, int guiH, float gs) {
        this.gw = guiW;
        this.gh = guiH;
        this.gs = gs;
        img = new BufferedImage(Math.round(guiW * gs), Math.round(guiH * gs), BufferedImage.TYPE_INT_ARGB);
        g2 = img.createGraphics();
    }

    /** Фон «космос»: тёмное небо с градиентом, звёзды, край планеты внизу справа. */
    public PngGfx space(long seed) {
        int w = img.getWidth(), h = img.getHeight();
        for (int y = 0; y < h; y++) {
            float t = y / (float) h;
            int r = (int) (6 + 14 * t), gg = (int) (9 + 18 * t), b = (int) (20 + 34 * t);
            g2.setColor(new Color(r, gg, b));
            g2.fillRect(0, y, w, 1);
        }
        Random rnd = new Random(seed);
        for (int i = 0; i < w * h / 900; i++) {
            int x = rnd.nextInt(w), y = rnd.nextInt(h);
            int a = 80 + rnd.nextInt(175), s = rnd.nextInt(10) == 0 ? 2 : 1;
            g2.setColor(new Color(220, 228, 255, a));
            g2.fillRect(x, y, s, s);
        }
        float pr = h * 0.9f, pcx = w * 0.82f, pcy = h * 1.55f;                  // планета
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float dx = x - pcx, dy = y - pcy, d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > pr + 30) continue;
            if (d <= pr) {
                float lit = Math.clamp((-dx * 0.6f - dy) / pr, 0, 1);
                int c = blend(0xFF0E1A2C, 0xFF35577A, lit * 0.8f + 0.1f * (float) Math.sin(x * 0.01 + y * 0.03));
                img.setRGB(x, y, c);
            } else {
                float k = 1 - (d - pr) / 30;
                img.setRGB(x, y, blend(img.getRGB(x, y), 0xFF6FA8DC, 0.35f * k * k));
            }
        }
        return this;
    }

    private static int blend(int a, int b, float t) {
        t = Math.clamp(t, 0, 1);
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bb = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bb;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
    }

    @Override public int width() { return gw; }

    @Override public int height() { return gh; }

    @Override public float scale() { return gs; }

    @Override
    public void fill(float x0, float y0, float x1, float y1, int argb) {
        int a = Math.round(x0 * gs), b = Math.round(y0 * gs), c = Math.round(x1 * gs), d = Math.round(y1 * gs);
        if (c <= a || d <= b) return;
        g2.setComposite(AlphaComposite.SrcOver);
        g2.setColor(new Color(argb, true));
        g2.fillRect(a, b, c - a, d - b);
    }

    @Override
    public void image(String key, Canvas c, float x, float y, float alpha) {
        BufferedImage im = new BufferedImage(c.w, c.h, BufferedImage.TYPE_INT_ARGB);
        for (int yy = 0; yy < c.h; yy++) for (int xx = 0; xx < c.w; xx++) im.setRGB(xx, yy, c.argb(xx, yy));
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.clamp(alpha, 0, 1)));
        g2.drawImage(im, Math.round(x * gs), Math.round(y * gs), null);
        g2.setComposite(AlphaComposite.SrcOver);
    }

    /** Картинка (иконка предмета 16×16) в прямоугольник x, y, size (пиксели интерфейса), без сглаживания. */
    public void icon(BufferedImage icon, float x, float y, float size) {
        g2.setComposite(AlphaComposite.SrcOver);
        g2.drawImage(icon, Math.round(x * gs), Math.round(y * gs), Math.round(size * gs), Math.round(size * gs), null);
    }

    @Override
    public void text(String s, float x, float y, float size, int argb, int align, boolean shadow) {
        if (s == null || s.isEmpty()) return;
        float tw = textWidth(s) * size;
        float ox = align < 0 ? 0 : align == 0 ? -tw / 2 : -tw;
        int px = Math.round((x + ox) * gs), py = Math.round(y * gs);
        int sp = Math.max(1, Math.round(size * gs));                            // пикселей экрана на пиксель шрифта
        if (shadow) {
            int a = argb >>> 24, sc = (a << 24) | ((argb & 0xFCFCFC) >> 2);
            run(s, px + sp, py + sp, sp, sc);
        }
        run(s, px, py, sp, argb);
    }

    private void run(String s, int px, int py, int sp, int argb) {
        g2.setComposite(AlphaComposite.SrcOver);
        g2.setColor(new Color(argb, true));
        int x = px;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            Glyph gl = FONT.glyph(cp);
            if (gl != null) {
                for (int yy = 0; yy < gl.h; yy++) for (int xx = 0; xx < gl.w; xx++)
                    if (gl.on[yy * gl.w + xx]) g2.fillRect(x + xx * sp, py + (yy + 7 - gl.ascent) * sp, sp, sp);
            }
            x += FONT.advance(cp) * sp;
        }
    }

    @Override
    public int textWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            w += FONT.advance(cp);
        }
        return w;
    }

    // ───────────────────────── шрифт игры ─────────────────────────

    record Glyph(int w, int h, int ascent, boolean[] on, int advance) {}

    static final McFont FONT = new McFont();

    /** Растровый шрифт игры (assets/minecraft/font/include/default.json): ширина знака — по самому правому столбцу. */
    static final class McFont {
        final Map<Integer, Glyph> glyphs = new HashMap<>();

        McFont() {
            try (InputStream in = res("assets/minecraft/font/include/default.json")) {
                JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                for (JsonElement e : o.getAsJsonArray("providers")) {
                    JsonObject p = e.getAsJsonObject();
                    if (!"bitmap".equals(p.get("type").getAsString())) continue;
                    String file = p.get("file").getAsString().replace("minecraft:", "");
                    int height = p.has("height") ? p.get("height").getAsInt() : 8, ascent = p.get("ascent").getAsInt();
                    BufferedImage im;
                    try (InputStream pi = res("assets/minecraft/textures/" + file)) { im = ImageIO.read(pi); }
                    JsonArray rows = p.getAsJsonArray("chars");
                    int cols = rows.get(0).getAsString().codePointCount(0, rows.get(0).getAsString().length());
                    int cw = im.getWidth() / cols, ch = im.getHeight() / rows.size();
                    float scale = height / (float) ch;
                    for (int r = 0; r < rows.size(); r++) {
                        String row = rows.get(r).getAsString();
                        int col = 0;
                        for (int i = 0; i < row.length(); col++) {
                            int cp = row.codePointAt(i);
                            i += Character.charCount(cp);
                            if (cp == 0 || glyphs.containsKey(cp)) continue;
                            int right = -1;
                            boolean[] on = new boolean[cw * ch];
                            for (int yy = 0; yy < ch; yy++) for (int xx = 0; xx < cw; xx++) {
                                int a = im.getRGB(col * cw + xx, r * ch + yy) >>> 24;
                                if (a > 0) { on[yy * cw + xx] = true; right = Math.max(right, xx); }
                            }
                            int w = right + 1;
                            glyphs.put(cp, new Glyph(cw, ch, ascent, on, (int) (0.5 + w * scale) + 1));
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("шрифт игры не найден на classpath тестов", e);
            }
        }

        private static InputStream res(String path) throws IOException {
            InputStream in = PngGfx.class.getClassLoader().getResourceAsStream(path);
            if (in == null) throw new IOException("нет " + path);
            return in;
        }

        Glyph glyph(int cp) { return cp == ' ' ? null : glyphs.get(cp); }

        int advance(int cp) {
            if (cp == ' ') return 4;
            Glyph g = glyphs.get(cp);
            return g != null ? g.advance : 6;
        }
    }
}
