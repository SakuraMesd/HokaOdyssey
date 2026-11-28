package ink.hoka.odyssey.client.stations;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Границы станций без игры: геометрия {@link BorderMesh} (тот же код, что в игре) растеризуется в PNG простым
 * программным рисованием (перспектива, сложение цветов — как у прохода LIGHTNING), плюс надпись {@link Banner}.
 * Папка — свойство stations.preview (по умолчанию build/preview; для отчёта:
 * {@code gradle :modules:stations:test -PstationsPreview=../tasks/stations-1-preview}).
 * Текст надписи в превью — системным шрифтом (в игре — шрифт игры), размеры близкие.
 */
class BorderPreviewTest {

    static Path out() { return Path.of(System.getProperty("stations.preview", "build/preview")); }

    static final int W = 960, H = 540;
    static final int BLUE = 0x66CCFF, AMBER = 0xFFB040, YELLOW = 0xFFE040;

    /** Кадр: камера (мир, double), курс и наклон (градусы, как у игры: yaw 0 — на +Z, pitch > 0 — вниз). */
    static final class Frame implements BorderMesh.Sink {
        final float[] acc = new float[W * H * 3];
        final double fx, fy, fz, rx, ry, rz, ux, uy, uz, fpx;
        int quads;

        Frame(float yaw, float pitch, double fovDeg) {
            double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
            fx = -Math.sin(y) * Math.cos(p); fy = -Math.sin(p); fz = Math.cos(y) * Math.cos(p);
            double rlx = -Math.cos(y), rlz = -Math.sin(y);                          // вправо от взгляда
            rx = rlx; ry = 0; rz = rlz;
            ux = ry * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - ry * fx;  // вверх = вправо × вперёд
            fpx = (H / 2.0) / Math.tan(Math.toRadians(fovDeg / 2));
        }

        double[] proj(float x, float y, float z) {
            double d = x * fx + y * fy + z * fz;
            return new double[]{W / 2.0 + (x * rx + y * ry + z * rz) / d * fpx, H / 2.0 - (x * ux + y * uy + z * uz) / d * fpx, d};
        }

        @Override
        public void quad(float x0, float y0, float z0, int c0, float x1, float y1, float z1, int c1,
                         float x2, float y2, float z2, int c2, float x3, float y3, float z3, int c3) {
            quads++;
            double[] a = proj(x0, y0, z0), b = proj(x1, y1, z1), c = proj(x2, y2, z2), d = proj(x3, y3, z3);
            if (a[2] < 0.05 || b[2] < 0.05 || c[2] < 0.05 || d[2] < 0.05) return;
            tri(a, c0, b, c1, c, c2);
            tri(a, c0, c, c2, d, c3);
        }

        private void tri(double[] a, int ca, double[] b, int cb, double[] c, int cc) {
            int x0 = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0])))), x1 = (int) Math.min(W - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
            int y0 = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1])))), y1 = (int) Math.min(H - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
            double area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
            if (Math.abs(area) < 1e-9 || x0 > x1 || y0 > y1) return;
            float[] pa = pre(ca), pb = pre(cb), pc = pre(cc);
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                double px = x + 0.5, py = y + 0.5;
                double w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area;
                double w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                int i = (y * W + x) * 3;
                for (int k = 0; k < 3; k++) acc[i + k] += (float) (pa[k] * w0 + pb[k] * w1 + pc[k] * w2);
            }
        }

        /** Цвет × прозрачность (сложение SRC_ALPHA, ONE). */
        private static float[] pre(int argb) {
            float a = (argb >>> 24) / 255f;
            return new float[]{((argb >> 16) & 255) / 255f * a, ((argb >> 8) & 255) / 255f * a, (argb & 255) / 255f * a};
        }

        /** Фон «космос» + накопленное свечение → картинка. */
        BufferedImage image(long seed, boolean hull) {
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Random r = new Random(seed);
            float[] bg = new float[W * H * 3];
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                float t = y / (float) H;
                int i = (y * W + x) * 3;
                bg[i] = 0.02f + 0.03f * t; bg[i + 1] = 0.03f + 0.04f * t; bg[i + 2] = 0.07f + 0.07f * t;
            }
            for (int s = 0; s < 500; s++) {
                int x = r.nextInt(W), y = r.nextInt(H), i = (y * W + x) * 3;
                float v = 0.3f + r.nextFloat() * 0.6f;
                bg[i] += v; bg[i + 1] += v; bg[i + 2] += v * 1.05f;
            }
            if (hull) {                                                       // тёмный «корпус» снизу кадра (стена станции)
                for (int y = (int) (H * 0.80); y < H; y++) for (int x = 0; x < W; x++) {
                    int i = (y * W + x) * 3;
                    float n = ((x / 24 + y / 24) & 1) == 0 ? 0.11f : 0.13f;
                    bg[i] = n; bg[i + 1] = n * 1.02f; bg[i + 2] = n * 1.08f;
                }
            }
            for (int i = 0, p = 0; p < W * H; p++, i += 3) {
                int rr = tone(bg[i] + acc[i]), gg = tone(bg[i + 1] + acc[i + 1]), bb = tone(bg[i + 2] + acc[i + 2]);
                img.setRGB(p % W, p / W, (rr << 16) | (gg << 8) | bb);
            }
            return img;
        }

        private static int tone(float v) { return Math.round(Math.clamp(v, 0, 1) * 255); }
    }

    /** Коробка из блоков (min..max включительно) в кадр. */
    static void box(Frame f, int[] b, int rgb, float bright, boolean film, boolean far, double[] cam, double[] eye, double t) {
        BorderMesh.box(b[0], b[1], b[2], b[3] + 1.0, b[4] + 1.0, b[5] + 1.0, rgb, bright, film, far,
                cam[0], cam[1], cam[2], eye[0], eye[1], eye[2], t, new BorderMesh.Style(), f);
    }

    static void save(BufferedImage img, String name) throws IOException {
        Files.createDirectories(out());
        ImageIO.write(img, "png", out().resolve(name).toFile());
    }

    // станция в космосе на больших координатах (кубовая карта cosmos)
    static final int SX = 12000, SY = 8000, SZ = -9000;
    static final int[] STATION = {SX, SY, SZ, SX + 39, SY + 19, SZ + 29};
    static final int[] DOCK = {SX + 40, SY + 4, SZ + 8, SX + 55, SY + 11, SZ + 19};   // вакуумный шлюз сбоку

    @Test
    void previewFar() throws IOException {
        double[] cam = {SX - 30.3, SY + 32.1, SZ - 38.7};
        Frame f = new Frame(-35, 26, 70);
        box(f, STATION, BLUE, 1, true, false, cam, cam, 3.0);
        box(f, DOCK, AMBER, 1, true, false, cam, cam, 3.0);
        save(f.image(1, false), "1-edges-far.png");
        assertTrue(f.quads > 0);
    }

    @Test
    void previewNearFilm() throws IOException {
        double[] cam = {SX + 14.4, SY + 6.62, SZ - 3.5};                     // 3.5 блока перед гранью z = SZ, снаружи
        Frame f = new Frame(-12, -6, 70);
        box(f, STATION, BLUE, 1, true, false, cam, cam, 5.0);
        save(f.image(2, false), "2-film-near.png");
    }

    @Test
    void previewInside() throws IOException {
        double[] cam = {SX + 36.2, SY + 1.62, SZ + 12.5};                    // внутри, у стены x = SX+40
        Frame f = new Frame(-70, -4, 70);
        box(f, STATION, BLUE, 1, true, false, cam, cam, 7.0);
        box(f, DOCK, AMBER, 1, true, false, cam, cam, 7.0);
        save(f.image(3, true), "3-inside-wall.png");
    }

    @Test
    void previewSelection() throws IOException {
        double[] cam = {SX - 12.5, SY + 9.6, SZ - 14.5};
        Frame f = new Frame(-40, 18, 70);
        int[] sel = {SX - 2, SY, SZ - 3, SX + 9, SY + 6, SZ + 5};
        box(f, STATION, BLUE, 1.35f, false, true, cam, cam, 2.0);           // /hostation show — ярче, без плёнки издали
        box(f, sel, YELLOW, 1.4f, false, true, cam, cam, 2.0);
        box(f, new int[]{sel[0], sel[1], sel[2], sel[0], sel[1], sel[2]}, BorderMesh.lighten(YELLOW, 0.3f), 1.6f, false, true, cam, cam, 2.0);
        box(f, new int[]{sel[3], sel[4], sel[5], sel[3], sel[4], sel[5]}, BorderMesh.lighten(YELLOW, 0.3f), 1.6f, false, true, cam, cam, 2.0);
        save(f.image(4, false), "4-selection.png");
    }

    @Test
    void previewBanner() throws IOException {
        float gs = 2;
        int gw = 480, gh = 150;
        BufferedImage img = new BufferedImage((int) (gw * gs), (int) (gh * gs * 2), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(new Color(10, 14, 26));
        g2.fillRect(0, 0, img.getWidth(), img.getHeight());
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        Banner b = new Banner();
        b.show("Станция Альфа", "гравитация 1g", BLUE, 0);
        b.draw(new AwtCanvas(g2, gw, gs, 0), Banner.IN + 500);
        Banner o = new Banner();
        o.show("Открытый космос", "", 0xC8D4E6, 0);
        o.draw(new AwtCanvas(g2, gw, gs, gh * gs), Banner.IN + 500);
        save(img, "5-banner.png");
        assertTrue(b.active(Banner.IN + 500));
        assertFalse(b.active(Banner.IN + Banner.HOLD + Banner.OUT + 1));
        assertEquals(0, b.alpha(Banner.IN + Banner.HOLD + Banner.OUT), 1e-6);
        assertEquals(1, b.alpha(Banner.IN + 10), 1e-6);
    }

    /** Холст надписи на AWT (пиксели GUI × gs), сдвиг по y — для второй надписи на той же картинке. */
    record AwtCanvas(Graphics2D g, int width, float gs, float dy) implements Banner.Canvas {
        @Override public void fill(float x0, float y0, float x1, float y1, int argb) {
            g.setColor(new Color(argb, true));
            g.fillRect(Math.round(x0 * gs), Math.round(y0 * gs + dy), Math.max(1, Math.round((x1 - x0) * gs)), Math.max(1, Math.round((y1 - y0) * gs)));
        }

        @Override public void text(String s, float x, float y, float scale, int argb) {
            g.setColor(new Color(argb, true));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(8 * scale * gs)));
            g.drawString(s, x * gs, (y + 7.5f * scale) * gs + dy);
        }

        @Override public int textWidth(String s) { return s.length() * 6; }        // ширина как у шрифта игры (≈6 px)
    }

    // ───────────────────────────── не только картинки ─────────────────────────────

    /** Далеко от начала мира (граница cosmos 100 000) вершины — относительно камеры, маленькие числа: без дрожания. */
    @Test
    void cameraRelativeAtHugeCoords() {
        double[] cam = {99_000.37, 30_000.5, -98_500.81};
        float[] max = {0};
        BorderMesh.box(98_990, 29_990, -98_510, 99_010, 30_010, -98_490, BLUE, 1, true, false,
                cam[0], cam[1], cam[2], cam[0], cam[1], cam[2], 1, new BorderMesh.Style(),
                (x0, y0, z0, c0, x1, y1, z1, c1, x2, y2, z2, c2, x3, y3, z3, c3) -> {
                    for (float v : new float[]{x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3}) max[0] = Math.max(max[0], Math.abs(v));
                });
        assertTrue(max[0] > 1 && max[0] < 40, "вершины относительно камеры: " + max[0]);
    }

    /** Плёнка — только вблизи грани; издалека — одни рёбра; совсем далеко — ничего. */
    @Test
    void filmOnlyNear() {
        int[] near = count(4), mid = count(30), far = count(500);
        assertTrue(near[1] > 50, "плёнка вблизи: " + near[1]);
        assertEquals(0, mid[1], "плёнки издалека нет");
        assertTrue(mid[0] > 0, "рёбра издалека видны");
        assertEquals(0, far[0] + far[1], "совсем далеко — ничего");
    }

    /** {рёбра (полупрозрачные ленты), плёнка} — по прямоугольности: квадраты плёнки лежат в плоскости грани z = 0. */
    private static int[] count(double dist) {
        int[] n = new int[2];
        BorderMesh.box(-20, 0, 0, 20, 20, 30, BLUE, 1, true, false, 0, 10, -dist, 0, 10, -dist, 0, new BorderMesh.Style(),
                (x0, y0, z0, c0, x1, y1, z1, c1, x2, y2, z2, c2, x3, y3, z3, c3) -> {
                    boolean flat = Math.abs(z0 - z1) < 1e-3 && Math.abs(z1 - z2) < 1e-3 && Math.abs(z2 - z3) < 1e-3
                            && Math.abs(z0 - (dist - 0.012)) < 1e-3;                 // грань z=0, сдвинута к камере
                    n[flat ? 1 : 0]++;
                });
        return n;
    }
}
