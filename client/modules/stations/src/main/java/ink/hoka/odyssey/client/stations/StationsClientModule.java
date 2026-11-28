package ink.hoka.odyssey.client.stations;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Станции у игрока: границы регионов (сервер — модуль stations) — светящиеся рёбра и силовая плёнка вблизи
 * ({@link BorderMesh}), надпись входа/выхода ({@link Banner}), выделение указкой (жёлтым), у админа — подписи регионов
 * и показ всех регионов ({@code /hostation show}). Протокол — {@code modules/stations/.../Net.java} на сервере:
 * <pre>
 * 1 REGIONS    UTF map; byte admin; int cNormal, cVacuum, cMap, cSel; int n; n × (UTF id, UTF title, byte grav, int nb, nb × 6 int)
 * 2 SELECTION  byte has; [3 int]; [3 int]
 * 3 TRANSITION byte kind; UTF title; UTF sub; byte grav
 * 4 SHOW       int seconds; UTF id
 * </pre>
 */
public final class StationsClientModule implements ClientModule {

    /** Регион у клиента: коробки в мировых координатах граней блоков (min, max+1). */
    record Reg(String id, String title, int grav, double[] boxes) {}

    private volatile List<Reg> regions = List.of();
    private volatile boolean admin;
    private volatile int[] colors = {0x66CCFF, 0xFFB040, 0xB8C0D0, 0xFFE040};
    private volatile int[] sel1, sel2;
    private volatile long showUntil;
    private volatile String showId = "";
    private final Banner banner = new Banner();
    private final BorderMesh.Style style = new BorderMesh.Style();
    private final long t0 = System.nanoTime();
    private ClientContext ctx;

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        ctx.onMessage(this::onMessage);
        ctx.onWorld(this::render);
        ctx.onHud((g, delta) -> banner.draw(new GuiCanvas(g), System.currentTimeMillis()));
        ctx.onTick(() -> {
            if (Minecraft.getInstance().level == null && !regions.isEmpty()) { regions = List.of(); sel1 = sel2 = null; }
        });
        ctx.logger().info("stations: загружен");
    }

    // ───────────────────────────── сеть ─────────────────────────────

    private void onMessage(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            switch (in.readUnsignedByte()) {
                case 1 -> {
                    in.readUTF();                                               // карта (на будущее: кэш по картам)
                    boolean adm = in.readByte() != 0;
                    int[] c = new int[4];
                    for (int i = 0; i < 4; i++) c[i] = in.readInt() & 0xFFFFFF;
                    int n = in.readInt();
                    List<Reg> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        String id = in.readUTF(), title = in.readUTF();
                        int grav = in.readUnsignedByte(), nb = in.readInt();
                        double[] b = new double[nb * 6];
                        for (int k = 0; k < nb; k++) {
                            for (int q = 0; q < 3; q++) b[k * 6 + q] = in.readInt();
                            for (int q = 3; q < 6; q++) b[k * 6 + q] = in.readInt() + 1.0;
                        }
                        list.add(new Reg(id, title, grav, b));
                    }
                    admin = adm;
                    colors = c;
                    regions = List.copyOf(list);
                }
                case 2 -> {
                    int has = in.readUnsignedByte();
                    int[] a = (has & 1) != 0 ? new int[]{in.readInt(), in.readInt(), in.readInt()} : null;
                    int[] b = (has & 2) != 0 ? new int[]{in.readInt(), in.readInt(), in.readInt()} : null;
                    sel1 = a;
                    sel2 = b;
                }
                case 3 -> {
                    in.readUnsignedByte();
                    String title = in.readUTF(), sub = in.readUTF();
                    int grav = in.readUnsignedByte();
                    banner.show(title, sub, grav < 3 ? colors[grav] : 0xC8D4E6, System.currentTimeMillis());
                }
                case 4 -> {
                    int sec = in.readInt();
                    showId = in.readUTF();
                    showUntil = sec <= 0 ? 0 : System.currentTimeMillis() + sec * 1000L;
                }
                default -> { }
            }
        } catch (IOException e) {
            ctx.logger().warn("stations: битый пакет: {}", e.toString());
        }
    }

    // ───────────────────────────── мир ─────────────────────────────

    private static final com.mojang.blaze3d.pipeline.RenderPipeline PIPE = com.mojang.blaze3d.pipeline.RenderPipeline
            .builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath("hoka_odyssey", "pipeline/station_border"))
            .withVertexShader("core/position_color").withFragmentShader("core/position_color")
            .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.LIGHTNING).withDepthWrite(false).withCull(false)
            .withVertexFormat(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)
            .build();
    private static final net.minecraft.client.renderer.rendertype.RenderType BORDER = net.minecraft.client.renderer.rendertype.RenderType.create(
            "hoka_station_border", net.minecraft.client.renderer.rendertype.RenderSetup.builder(PIPE).createRenderSetup());

    private void render(ClientContext.WorldFrame f) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        List<Reg> regs = regions;
        int[] s1 = sel1, s2 = sel2;
        long now = System.currentTimeMillis();
        boolean show = now < showUntil;
        if (regs.isEmpty() && s1 == null && s2 == null) return;

        Vec3 cam = f.camera();
        Vec3 eye = mc.player.getEyePosition(f.partialTick());
        double t = (System.nanoTime() - t0) / 1e9;
        Matrix4f m = f.pose().last().pose();
        VertexConsumer vc = f.buffers().getBuffer(BORDER);
        BorderMesh.Sink sink = (x0, y0, z0, c0, x1, y1, z1, c1, x2, y2, z2, c2, x3, y3, z3, c3) -> {
            vc.addVertex(m, x0, y0, z0).setColor(c0);
            vc.addVertex(m, x1, y1, z1).setColor(c1);
            vc.addVertex(m, x2, y2, z2).setColor(c2);
            vc.addVertex(m, x3, y3, z3).setColor(c3);
        };
        int[] col = colors;
        List<Reg> labels = new ArrayList<>();
        for (Reg r : regs) {
            boolean shown = show && (showId.isEmpty() || showId.equals(r.id()));
            double maxD = shown ? 512 : style.edgeFar;
            boolean any = false;
            for (int k = 0; k < r.boxes().length; k += 6) {
                double[] b = r.boxes();
                double d = BorderMesh.boxDist(b[k], b[k + 1], b[k + 2], b[k + 3], b[k + 4], b[k + 5], cam.x, cam.y, cam.z);
                double shell = BorderMesh.shellDist(b[k], b[k + 1], b[k + 2], b[k + 3], b[k + 4], b[k + 5], cam.x, cam.y, cam.z);
                if (d > maxD && shell > maxD) continue;
                if (d > 0 && !f.visible(new AABB(b[k], b[k + 1], b[k + 2], b[k + 3], b[k + 4], b[k + 5]).inflate(0.5))) continue;
                any = true;
                final int self = k;
                BorderMesh.box(b[k], b[k + 1], b[k + 2], b[k + 3], b[k + 4], b[k + 5], col[Math.min(r.grav(), 2)],
                        shown ? 1.35f : 1f, true, shown, cam.x, cam.y, cam.z, eye.x, eye.y, eye.z, t, style, sink,
                        b.length == 6 ? null : (x, y, z) -> {                      // другие коробки этой станции
                            for (int o = 0; o < b.length; o += 6)
                                if (o != self && x > b[o] && x < b[o + 3] && y > b[o + 1] && y < b[o + 4] && z > b[o + 2] && z < b[o + 5]) return true;
                            return false;
                        });
            }
            if (admin && any && (shown || nearest(r, cam) < 48)) labels.add(r);
        }
        // выделение указкой: коробка ярко-жёлтым (видно издалека), обе точки — рамкой блока
        if (s1 != null && s2 != null) {
            BorderMesh.box(Math.min(s1[0], s2[0]), Math.min(s1[1], s2[1]), Math.min(s1[2], s2[2]),
                    Math.max(s1[0], s2[0]) + 1, Math.max(s1[1], s2[1]) + 1, Math.max(s1[2], s2[2]) + 1,
                    col[3], 1.4f, false, true, cam.x, cam.y, cam.z, eye.x, eye.y, eye.z, t, style, sink);
        }
        for (int[] p : new int[][]{s1, s2}) {
            if (p == null) continue;
            BorderMesh.box(p[0] - 0.02, p[1] - 0.02, p[2] - 0.02, p[0] + 1.02, p[1] + 1.02, p[2] + 1.02,
                    BorderMesh.lighten(col[3], 0.3f), 1.6f, false, true, cam.x, cam.y, cam.z, eye.x, eye.y, eye.z, t, style, sink);
        }
        if (f.buffers() instanceof net.minecraft.client.renderer.MultiBufferSource.BufferSource bs) bs.endBatch(BORDER);
        for (Reg r : labels) label(f, mc, r, cam, col[Math.min(r.grav(), 2)]);
    }

    private static double nearest(Reg r, Vec3 c) {
        double best = Double.MAX_VALUE;
        double[] b = r.boxes();
        for (int k = 0; k < b.length; k += 6)
            best = Math.min(best, BorderMesh.boxDist(b[k], b[k + 1], b[k + 2], b[k + 3], b[k + 4], b[k + 5], c.x, c.y, c.z));
        return best;
    }

    /** Подпись над первой коробкой: «alpha · Станция Альфа» (видно сквозь стены — это режим админа). */
    private void label(ClientContext.WorldFrame f, Minecraft mc, Reg r, Vec3 cam, int rgb) {
        double[] b = r.boxes();
        double x = (b[0] + b[3]) / 2, y = b[4] + 0.6, z = (b[2] + b[5]) / 2;
        double dist = Math.sqrt((x - cam.x) * (x - cam.x) + (y - cam.y) * (y - cam.y) + (z - cam.z) * (z - cam.z));
        float sc = (float) (0.025 * Math.max(1, dist / 12));                     // издалека — крупнее, чтобы читалось
        PoseStack ps = f.pose();
        ps.pushPose();
        ps.translate((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z));
        ps.mulPose(mc.gameRenderer.getMainCamera().rotation());
        ps.scale(sc, -sc, sc);
        Font font = mc.font;
        String s = r.id() + " · " + r.title();
        font.drawInBatch(s, -font.width(s) / 2f, 0, 0xFF000000 | BorderMesh.lighten(rgb, 0.6f), false, ps.last().pose(), f.buffers(),
                Font.DisplayMode.SEE_THROUGH, 0x60000000, 0xF000F0);
        ps.popPose();
        if (f.buffers() instanceof net.minecraft.client.renderer.MultiBufferSource.BufferSource bs) bs.endBatch();
    }

    // ───────────────────────────── HUD ─────────────────────────────

    /** {@link Banner.Canvas} поверх GuiGraphics: заливка — с точностью до пикселя экрана, текст — шрифтом игры. */
    private static final class GuiCanvas implements Banner.Canvas {
        private final GuiGraphics g;
        private final float gs;

        GuiCanvas(GuiGraphics g) {
            this.g = g;
            this.gs = (float) Minecraft.getInstance().getWindow().getGuiScale();
        }

        @Override public int width() { return g.guiWidth(); }

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
        public void text(String s, float x, float y, float scale, int argb) {
            if ((argb >>> 24) < 4) return;
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(x, y);
            pose.scale(scale, scale);
            g.drawString(Minecraft.getInstance().font, s, 0, 0, argb, false);
            pose.popMatrix();
        }

        @Override public int textWidth(String s) { return Minecraft.getInstance().font.width(s); }
    }
}
