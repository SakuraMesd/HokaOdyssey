package ink.hoka.odyssey.client.holo;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Превью без игры: голограммы (тот же {@link Layout} и {@link Paint}, что в игре) — программным рисованием Java2D
 * в косой проекции (видно объём кубиков), и окно редактора ({@link EditorUi} через Java2D-{@link Gui}).
 * Папка — свойство holo.preview (по умолчанию build/preview; для отчёта:
 * {@code gradle :modules:holo:test -PholoPreview=../tasks/holo-1-preview}). Текст — системным шрифтом (в игре —
 * шрифт игры), предмет — заглушка-кристалл, картинка — заглушка-градиент.
 */
class PreviewTest {

    static Path out() { return Path.of(System.getProperty("holo.preview", "build/preview")); }

    static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 9), BOLD = FONT.deriveFont(Font.BOLD);
    static final FontMetrics FM, FMB;

    static {
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tmp.createGraphics();
        FM = g.getFontMetrics(FONT);
        FMB = g.getFontMetrics(BOLD);
        g.dispose();
    }

    static final Layout.Metrics METRICS = (cp, bold) -> (bold ? FMB : FM).charWidth(cp) + 0.2f;

    static final String WELCOME = """
            {"id": "welcome", "map": "cosmos", "pos": [12.5, 70, -4], "theme": "space", "width": 4.4, "facing": "billboard_y",
             "pages": [{"lines": [
               [{"type": "text", "text": "<b><gradient:#8FD8FF:#D9E2EE>СТАНЦИЯ «ОРИОН»</gradient></b>", "size": 1.5}],
               [{"type": "separator"}],
               [{"type": "item", "item": "hoka:ruby", "hint": "Рубин"},
                {"type": "text", "text": "Привет, <#FFC46B>{player}</#FFC46B>!<br><gray>Онлайн:</gray> {online} <gray>·</gray> {time}", "align": "left"}],
               [{"type": "bar", "value": "{fuel}", "max": 100, "label": "Топливо {fuel}%", "w": 3.8}],
               [{"type": "button", "label": "На спавн", "left": [{"type": "cmd", "value": "spawn"}]},
                {"type": "button", "label": "Магазин", "color": "#FFC46B", "left": [{"type": "ui", "value": "shop"}]},
                {"type": "button", "label": "Стр. ›", "left": [{"type": "page", "value": "next"}]}]]},
              {"lines": [[{"type": "text", "text": "Вторая страница"}]]}]}
            """;

    static final Map<String, String> VALUES = Map.of("player", "Космонавт", "online", "12", "time", "21:40", "fuel", "72",
            "x", "12", "y", "70", "z", "-4");

    // ═════════════════════════════ голограммы ═════════════════════════════

    /** Голограмма в Java2D: косая проекция (z к зрителю → вниз-влево), смесь «свечение» — сложением цветов. */
    static final class WorldJ2 implements Paint.Painter {
        final Graphics2D g;
        final float ox, oy, s;
        int quads;

        WorldJ2(Graphics2D g, float ox, float oy, float s) {
            this.g = g;
            this.ox = ox;
            this.oy = oy;
            this.s = s;
        }

        float px(float x, float z) { return ox + (x - z * 0.35f) * s; }

        float py(float y, float z) { return oy + (y + z * 0.3f) * s; }

        void poly(int argb, float... xyz) {
            Path2D.Float p = new Path2D.Float();
            for (int i = 0; i < xyz.length; i += 3) {
                float X = px(xyz[i], xyz[i + 2]), Y = py(xyz[i + 1], xyz[i + 2]);
                if (i == 0) p.moveTo(X, Y); else p.lineTo(X, Y);
            }
            p.closePath();
            g.setColor(new Color(argb, true));
            g.fill(p);
            quads++;
        }

        public void rect(float x0, float y0, float x1, float y1, float z, int argb) {
            g.setComposite(AlphaComposite.SrcOver);
            poly(argb, x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z);
        }

        public void glow(float x0, float y0, float x1, float y1, float z, int argb) {
            g.setComposite(Additive.INSTANCE);
            poly(argb, x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z);
            g.setComposite(AlphaComposite.SrcOver);
        }

        public void cube(float cx, float cy, float cz, float sz, int argb) {
            float h = sz / 2, x0 = cx - h, x1 = cx + h, y0 = cy - h, y1 = cy + h, z0 = cz - h, z1 = cz + h;
            int a = argb >>> 24, rgb = argb & 0xFFFFFF;
            g.setComposite(Additive.INSTANCE);
            float halo = sz * 0.9f;                                                // мягкое свечение вокруг
            poly(((a / 7) << 24) | rgb, cx - halo, cy - halo, cz, cx + halo, cy - halo, cz, cx + halo, cy + halo, cz, cx - halo, cy + halo, cz);
            poly((a << 24) | Paint.mul(rgb, 0.85f), x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);   // верх
            poly((a << 24) | Paint.mul(rgb, 0.6f), x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0);    // бок
            poly(argb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);                                // лицо
            g.setComposite(AlphaComposite.SrcOver);
        }

        public void scan(float x0, float y0, float x1, float y1, float z, int argb, float phase) {
            g.setComposite(Additive.INSTANCE);
            for (float y = y0 + phase * 4; y < y1; y += 4) poly(argb, x0, y, z, x1, y, z, x1, Math.min(y1, y + 0.9f), z, x0, Math.min(y1, y + 0.9f), z);
            g.setComposite(AlphaComposite.SrcOver);
            quads -= Math.max(0, (int) ((y1 - y0) / 4) - 1);                    // в игре — один квад с текстурой
        }

        public void text(Layout.Box b, Layout.TLine line, float x, float y, float z, float scale, float alpha) {
            AffineTransform t = g.getTransform();
            g.translate(px(x, z), py(y, z));
            g.scale(s * scale, s * scale);
            drawRuns(g, line.runs(), alpha);
            g.setTransform(t);
        }

        public void item(Layout.Box b, float cx, float cy, float z, float size, float spinDeg, float alpha) {
            AffineTransform t = g.getTransform();
            g.translate(px(cx, z), py(cy, z));
            g.scale(s * size / 16 * Math.max(0.25, Math.abs(Math.cos(Math.toRadians(spinDeg)))), s * size / 16);
            gem(g);
            g.setTransform(t);
        }

        public void image(String name, float x0, float y0, float x1, float y1, float z, float alpha) {
            g.setPaint(new GradientPaint(px(x0, z), py(y0, z), new Color(0x335577), px(x1, z), py(y1, z), new Color(0xFFC46B)));
            g.fill(new Rectangle2D.Float(px(x0, z), py(y0, z), (x1 - x0) * s, (y1 - y0) * s));
        }
    }

    /** Кристалл-заглушка предмета (16×16 вокруг центра). */
    static void gem(Graphics2D g) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(0, -7); p.lineTo(6, -2); p.lineTo(0, 7); p.lineTo(-6, -2); p.closePath();
        g.setPaint(new GradientPaint(-6, -7, new Color(0xFF6B8A), 6, 7, new Color(0x9A1030)));
        g.fill(p);
        g.setColor(new Color(255, 220, 230, 170));
        Path2D.Float f = new Path2D.Float();
        f.moveTo(0, -7); f.lineTo(3, -2); f.lineTo(0, 1); f.lineTo(-3, -2); f.closePath();
        g.fill(f);
    }

    static void drawRuns(Graphics2D g, List<Mini.Run> runs, float alpha) {
        float x = 0;
        for (Mini.Run r : runs) {
            boolean bold = (r.flags() & Mini.BOLD) != 0;
            g.setFont(bold ? BOLD : FONT);
            g.setColor(new Color((Math.round(alpha * 255) << 24) | r.rgb(), true));
            for (int i = 0; i < r.text().length(); ) {
                int cp = r.text().codePointAt(i);
                g.drawString(new String(Character.toChars(cp)), x, 7.5f);
                x += METRICS.width(cp, bold);
                i += Character.charCount(cp);
            }
        }
    }

    /** Сложение цветов (как смесь «свечение» в игре). */
    static final class Additive implements java.awt.Composite {
        static final Additive INSTANCE = new Additive();

        public java.awt.CompositeContext createContext(java.awt.image.ColorModel s, java.awt.image.ColorModel d, RenderingHints h) {
            return new java.awt.CompositeContext() {
                public void dispose() { }

                public void compose(java.awt.image.Raster src, java.awt.image.Raster dstIn, java.awt.image.WritableRaster dstOut) {
                    int w = Math.min(src.getWidth(), dstIn.getWidth()), hh = Math.min(src.getHeight(), dstIn.getHeight());
                    int[] a = new int[4], b = new int[4];
                    for (int y = 0; y < hh; y++) for (int x = 0; x < w; x++) {
                        src.getPixel(x, y, a);
                        dstIn.getPixel(x, y, b);
                        float k = a[3] / 255f;
                        for (int c = 0; c < 3; c++) b[c] = Math.min(255, Math.round(b[c] + a[c] * k));
                        b[3] = Math.max(b[3], a[3]);
                        dstOut.setPixel(x, y, b);
                    }
                }
            };
        }
    }

    static BufferedImage space(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x02030A), w, h, new Color(0x0B1530)));
        g.fillRect(0, 0, w, h);
        Random r = new Random(7);
        for (int i = 0; i < w * h / 900; i++) {
            int c = 120 + r.nextInt(135);
            g.setColor(new Color(c, c, Math.min(255, c + 20), 90 + r.nextInt(160)));
            int s = r.nextInt(9) == 0 ? 2 : 1;
            g.fillRect(r.nextInt(w), r.nextInt(h), s, s);
        }
        g.dispose();
        return img;
    }

    static Graphics2D hq(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    /** Нарисовать голограмму: центр (cx, cy) картинки, масштаб s пикселей на точку. Возвращает число квадов. */
    static int drawHolo(Graphics2D g, Holo h, int page, float cx, float cy, float s, Paint.State st, java.util.function.Consumer<Layout.Panel> hover) {
        Layout.Panel p = Layout.layout(h, page, VALUES, METRICS, Holo.Theme.parse(null).get(h.theme).text());
        if (hover != null) hover.accept(p);
        WorldJ2 w = new WorldJ2(g, cx - p.w() * s / 2, cy - p.h() * s / 2, s);
        Paint.draw(h, p, Holo.Theme.parse(null).get(h.theme), st, w);
        return w.quads;
    }

    static void save(BufferedImage img, String name) throws IOException {
        Files.createDirectories(out());
        ImageIO.write(img, "png", out().resolve(name).toFile());
    }

    @Test
    void hologramSpace() throws IOException {
        Holo h = Holo.parse(Session.parse(WELCOME));
        BufferedImage img = space(1000, 620);
        Graphics2D g = hq(img);
        Paint.State st = new Paint.State();
        st.t = 1.7f;
        st.seed = 11;
        int quads = drawHolo(g, h, 0, 500, 300, 4.6f, st, p -> {
            Layout.Box btn = p.boxes().get(p.boxes().size() - 2);               // «Магазин» — наведена
            st.hovered = btn;
            st.panelHovered = true;
            st.cu = btn.x + btn.w * 0.62f;
            st.cv = btn.y + btn.h * 0.55f;
        });
        g.setFont(FONT.deriveFont(13f));
        g.setColor(new Color(0x8A97A8));
        g.drawString("holo · тема space · текст, предмет (заглушка), шкала, кнопки; наведена «Магазин» (ярче, выдвинута), точка-курсор", 20, 600);
        g.dispose();
        save(img, "holo-space.png");
        assertTrue(quads < 2500, "квадов на голограмму: " + quads);            // десятки голограмм — десятки тысяч квадов
        System.out.println("holo: квадов на голограмму «welcome» — " + quads);
    }

    @Test
    void themesAndModes() throws IOException {
        BufferedImage img = space(1200, 520);
        Graphics2D g = hq(img);
        String[] themes = {"space", "amber", "danger", "green"};
        String[] texts = {"<b>НАВИГАЦИЯ</b>", "<b>⚠ ВНИМАНИЕ</b>", "<b>ОПАСНО: ВАКУУМ</b>", "<b>ШЛЮЗ ОТКРЫТ</b>"};
        for (int i = 0; i < 4; i++) {
            Holo h = Holo.parse(Session.parse("""
                    {"id": "t%d", "pos": [0,0,0], "theme": "%s", "pages": [{"lines": [
                      [{"type": "text", "text": "%s", "size": 1.3}], [{"type": "separator"}],
                      [{"type": "text", "text": "Тема <i>%s</i>"}],
                      [{"type": "bar", "value": "0.%d", "w": 2.2}],
                      [{"type": "button", "label": "OK"}, {"type": "button", "label": "Отмена"}]]}]}
                    """.formatted(i, themes[i], texts[i], themes[i], 3 + i * 2)));
            Paint.State st = new Paint.State();
            st.t = i * 0.9f;
            st.seed = i * 31;
            drawHolo(g, h, 0, 150 + i * 300, 250, 2.6f, st, null);
        }
        g.dispose();
        save(img, "holo-themes.png");
    }

    @Test
    void appearAndDissolve() throws IOException {
        Holo h = Holo.parse(Session.parse(WELCOME));
        float[] ph = {0.12f, 0.3f, 0.55f, 0.8f, 1f};
        BufferedImage img = space(1500, 330);
        Graphics2D g = hq(img);
        for (int i = 0; i < ph.length; i++) {
            Paint.State st = new Paint.State();
            st.appear = ph[i];
            st.seed = 11;
            st.t = 0.5f;
            drawHolo(g, h, 0, 150 + i * 300, 150, 1.55f, st, null);
            g.setFont(FONT.deriveFont(12f));
            g.setColor(new Color(0x8A97A8));
            g.drawString(String.format("появление %.0f%%", ph[i] * 100), 110 + i * 300, 315);
        }
        g.dispose();
        save(img, "holo-appear.png");
    }

    // ═════════════════════════════ редактор ═════════════════════════════

    /** Gui поверх Java2D (масштаб GUI ×2, как в игре). */
    static final class J2Gui implements Gui {
        final Graphics2D g;
        final Deque<AffineTransform> stack = new ArrayDeque<>();

        J2Gui(Graphics2D g) { this.g = g; }

        public int width(String s) {
            float w = 0;
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                w += METRICS.width(cp, false);
                i += Character.charCount(cp);
            }
            return Math.round(w);
        }

        public void fill(float x0, float y0, float x1, float y1, int argb) {
            if (x1 <= x0 || y1 <= y0) return;
            g.setColor(new Color(argb, true));
            g.fill(new Rectangle2D.Float(x0, y0, x1 - x0, y1 - y0));
        }

        public void text(String s, float x, float y, int argb) {
            AffineTransform t = g.getTransform();
            g.translate(x, y);
            drawRuns(g, List.of(new Mini.Run(s, argb & 0xFFFFFF, 0)), (argb >>> 24) / 255f);
            g.setTransform(t);
        }

        public void runs(List<Mini.Run> runs, float x, float y, float s, float alpha) {
            AffineTransform t = g.getTransform();
            g.translate(x, y);
            g.scale(s, s);
            drawRuns(g, runs, alpha);
            g.setTransform(t);
        }

        public void item(String key, float x, float y, float size) {
            AffineTransform t = g.getTransform();
            g.translate(x + size / 2, y + size / 2);
            g.scale(size / 16, size / 16);
            gem(g);
            g.setTransform(t);
        }

        public void image(String name, float x, float y, float w, float h, float alpha) {
            g.setPaint(new GradientPaint(x, y, new Color(0x335577), x + w, y + h, new Color(0xFFC46B)));
            g.fill(new Rectangle2D.Float(x, y, w, h));
        }

        public void push(float tx, float ty, float s) {
            stack.push(g.getTransform());
            g.translate(tx, ty);
            g.scale(s, s);
        }

        public void pop() { g.setTransform(stack.pop()); }

        public void clip(int x0, int y0, int x1, int y1) {
            g.setClip(new Rectangle2D.Float(x0, y0, Math.max(0, Math.min(x1, 100000) - x0), Math.max(0, Math.min(y1, 100000) - y0)));
        }

        public void unclip() { g.setClip(null); }
    }

    static final String INFO = """
            {"images": ["logo.png", "station-map.png"], "items": ["hoka:ruby", "hoka:hoe"], "maps": ["world", "cosmos"],
             "placeholders": [{"name": "player", "period": 100}, {"name": "online", "period": 20}, {"name": "time", "period": 20},
                              {"name": "map", "period": 20}, {"name": "x", "period": 4}, {"name": "fuel", "period": 10}],
             "templates": {"информация со шкалой": {"id": "tpl-info", "pos": [0,0,0]}, "меню с кнопками": {"id": "tpl-menu", "pos": [0,0,0]},
                           "табличка станции": {"id": "tpl-station", "pos": [0,0,0]}}}
            """;

    /** Заглушка игры для редактора. */
    static Gui.Host host(Session s) {
        JsonObject info = Session.parse(INFO);
        return new Gui.Host() {
            public Session session() { return s; }
            public JsonObject info() { return info; }
            public Map<String, Holo.Theme> themes() { return Holo.Theme.parse(null); }
            public Layout.Metrics metrics() { return METRICS; }
            public Map<String, String> values() { return VALUES; }
            public String map() { return "cosmos"; }
            public double[] player() { return new double[]{10, 71.6, -9, 20, 5}; }
            public double[] blockTarget() { return new double[]{12, 70, -3, 0, 0, -1}; }
            public String handItem() { return "hoka:ruby"; }
            public List<String> itemSuggest(String prefix) { return List.of("hoka:ruby", "minecraft:diamond"); }
            public void delete(String id) { }
            public void tp(String id) { }
            public void saveTemplate(String name, JsonObject holo) { }
            public void deleteTemplate(String name) { }
            public void move(String id) { }
            public void close() { }
            public String clipboard() { return ""; }
            public void setClipboard(String v) { }
            public String status() { return "Сохранена «welcome»"; }
            public boolean statusOk() { return true; }
        };
    }

    static Session demoSession() {
        Session s = new Session();
        s.server.put("welcome", Session.parse(WELCOME));
        for (String id : new String[]{"dock-1", "reactor", "menu-main", "lobby-rules"}) {
            JsonObject o = Session.parse(WELCOME);
            o.addProperty("id", id);
            o.addProperty("map", id.startsWith("lobby") ? "world" : "cosmos");
            o.getAsJsonArray("pos").set(0, new com.google.gson.JsonPrimitive(30 + id.length() * 9));
            s.server.put(id, o);
        }
        s.select("welcome");
        return s;
    }

    static BufferedImage editor(EditorUi ui, float mx, float my) {
        int W = 640, H = 360;
        BufferedImage img = space(W * 2, H * 2);
        Graphics2D g = hq(img);
        g.scale(2, 2);
        g.setColor(new Color(0, 0, 0, 0x50));
        g.fillRect(0, 0, W, H);
        J2Gui gui = new J2Gui(g);
        ui.render(gui, W, H, mx, my);                                          // первый кадр — области кликов
        ui.render(gui, W, H, mx, my);
        g.dispose();
        return img;
    }

    @Test
    void editorLines() throws IOException {
        Session s = demoSession();
        s.change("demo", d -> d.addProperty("scale", 1.2));                      // черновик — «не сохранено»
        EditorUi ui = new EditorUi(host(s));
        ui.tab = 1;
        ui.selLine = 4;
        ui.selElem = 1;                                                           // кнопка «Магазин»: поля и действия
        save(editor(ui, 330, 150), "editor-lines.png");
    }

    @Test
    void editorProperties() throws IOException {
        Session s = demoSession();
        EditorUi ui = new EditorUi(host(s));
        ui.tab = 0;
        save(editor(ui, 70, 60), "editor-properties.png");
    }

    @Test
    void editorTextElement() throws IOException {
        Session s = demoSession();
        EditorUi ui = new EditorUi(host(s));
        ui.tab = 1;
        ui.selLine = 2;
        ui.selElem = 1;                                                           // текст с плейсхолдерами — подсветка
        BufferedImage img = editor(ui, 300, 300);
        save(img, "editor-text.png");
        assertEquals(1280, img.getWidth());
    }

    @Test
    void manyHologramsCost() {
        // «десятки голограмм не должны ронять FPS»: квады 30 голограмм — порядка десятков тысяч (как один чанк блоков)
        Holo h = Holo.parse(Session.parse(WELCOME));
        Layout.Panel p = Layout.layout(h, 0, VALUES, METRICS, 0xFFFFFF);
        int[] q = {0};
        Paint.Painter count = new Paint.Painter() {
            public void rect(float a, float b, float c, float d, float z, int argb) { q[0]++; }
            public void glow(float a, float b, float c, float d, float z, int argb) { q[0]++; }
            public void cube(float a, float b, float c, float s, int argb) { q[0] += 5; }
            public void scan(float a, float b, float c, float d, float z, int argb, float ph) { q[0]++; }
            public void text(Layout.Box bx, Layout.TLine l, float x, float y, float z, float s, float al) { q[0] += l.runs().stream().mapToInt(r -> r.text().length()).sum(); }
            public void item(Layout.Box bx, float cx, float cy, float z, float s, float sp, float al) { q[0] += 50; }
            public void image(String n, float a, float b, float c, float d, float z, float al) { q[0]++; }
        };
        Paint.draw(h, p, Holo.Theme.SPACE, new Paint.State(), count);
        System.out.println("holo: квадов в игре на голограмму «welcome» — " + q[0] + ", 30 голограмм — " + q[0] * 30);
        assertTrue(q[0] * 30 < 60000);
    }
}
