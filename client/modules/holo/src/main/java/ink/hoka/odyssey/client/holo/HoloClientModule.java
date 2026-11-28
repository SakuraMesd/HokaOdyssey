package ink.hoka.odyssey.client.holo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Голограммы у игрока: панели в кубоидном космическом стиле ({@link Paint}) по раскладке ({@link Layout}), наведение
 * лучом от глаз ({@link Orient}), нажатия ЛКМ/ПКМ — серверу, подсказки, редактор ({@link EditorScreen}, только OP),
 * режим «двигать» (следовать за прицелом, колёсико — расстояние, стрелки-гизмо по осям). Протокол — серверный
 * {@code modules/holo/.../Net.java}.
 */
public final class HoloClientModule implements ClientModule {

    static final int HELLO = 1, SET = 2, REMOVE = 3, CLEAR = 4, VALUES = 5, PAGE = 6, INFO = 7, EDIT = 8, RESULT = 9;
    static final int C_CLICK = 1, C_SAVE = 2, C_DELETE = 3, C_TP = 4, C_TPL_SAVE = 5, C_TPL_DELETE = 6, C_INFO_REQ = 7;
    static final long APPEAR_MS = 420, DISSOLVE_MS = 420;
    static final double CLICK_REACH = 16;

    /** Голограмма у мода: серверная версия, страница, раскладка (кэш), предметы. */
    static final class Live {
        Holo holo;
        int page;
        long born, dying = -1;
        Layout.Panel panel;
        int panelVersion = -1;
        Holo drawn;
        final Map<String, ItemStack> items = new HashMap<>();
        int seed;
    }

    /** Что нарисовано в кадре (для наведения). */
    record Placed(Live live, Holo holo, Layout.Panel panel, Orient o, double cx, double cy, double cz, double k) {}

    private ClientContext ctx;
    final Map<String, Live> live = new LinkedHashMap<>();
    final Map<String, String> values = new HashMap<>();
    Map<String, Holo.Theme> themes = Holo.Theme.parse(null);
    boolean editorAllowed;
    String map = "";
    private int valuesVersion;
    /** Редактор: сессия (черновики) и последние данные INFO. */
    final Session session = new Session();
    JsonObject info = new JsonObject();
    String lastResult = "";
    boolean lastOk = true;
    long lastResultAt;
    final Mover mover = new Mover(this);
    private final Map<String, Holo> draftCache = new HashMap<>();
    private int draftVersion = -1;

    // наведение (считается в кадре, читается кликом и HUD)
    Placed hoverPlaced;
    Layout.Box hoverBox;
    private boolean jWas;
    private final long t0 = System.nanoTime();
    final Metrics metrics = new Metrics();

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        session.saver = o -> send(C_SAVE, out -> str(out, Session.json(o)));
        ctx.onMessage(this::onMessage);
        ctx.onWorld(this::render);
        ctx.onHud((g, d) -> Hud.draw(this, g));
        ctx.onTick(this::tick);
        ctx.onClick(this::click);
        ctx.logger().info("holo: загружен");
    }

    ClientContext ctx() { return ctx; }

    // ───────────────────────────── сеть ─────────────────────────────

    private void onMessage(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            switch (in.readUnsignedByte()) {
                case HELLO -> {
                    editorAllowed = (in.readUnsignedByte() & 1) != 0;
                    map = in.readUTF();
                    themes = Holo.Theme.parse(Session.parse(str(in)));
                    for (Live l : live.values()) l.panelVersion = -1;
                }
                case SET -> {
                    JsonObject o = Session.parse(str(in));
                    int page = in.readInt();
                    Holo h = Holo.parse(o);
                    Live l = live.get(h.id);
                    if (l == null || l.dying >= 0) {
                        l = new Live();
                        l.born = System.currentTimeMillis();
                        l.seed = h.id.hashCode();
                        live.put(h.id, l);
                    }
                    l.holo = h;
                    l.page = page;
                    l.panelVersion = -1;
                    l.items.clear();
                    int n = in.readInt();
                    for (int i = 0; i < n; i++) {
                        String key = in.readUTF();
                        byte[] b = in.readNBytes(in.readInt());
                        ItemStack it = decode(b);
                        if (it != null) l.items.put(key, it);
                    }
                    if (!session.dirty.contains(h.id)) session.server.put(h.id, o);
                    session.serverChanged(h.id);
                }
                case REMOVE -> {
                    String id = in.readUTF();
                    boolean anim = in.readUnsignedByte() == 1;
                    if (anim) { session.server.remove(id); session.serverChanged(id); }
                    Live l = live.get(id);
                    if (l != null) {
                        if (anim) l.dying = System.currentTimeMillis();
                        else live.remove(id);
                    }
                }
                case CLEAR -> live.clear();
                case VALUES -> {
                    int n = in.readInt();
                    for (int i = 0; i < n; i++) values.put(in.readUTF(), in.readUTF());
                    valuesVersion++;
                }
                case PAGE -> {
                    Live l = live.get(in.readUTF());
                    int p = in.readInt();
                    if (l != null && l.page != p) { l.page = p; l.panelVersion = -1; }
                }
                case INFO -> {
                    info = Session.parse(str(in));
                    if (info.has("holograms")) {
                        session.server.clear();                                 // удалённые на сервере — из списка
                        for (var e : info.getAsJsonArray("holograms")) {
                            JsonObject o = e.getAsJsonObject();
                            session.server.put(o.get("id").getAsString(), o);
                        }
                    }
                    session.version++;
                }
                case EDIT -> openEditor(in.readUTF());
                case RESULT -> {
                    lastOk = in.readUnsignedByte() == 1;
                    lastResult = in.readUTF();
                    lastResultAt = System.currentTimeMillis();
                    session.result(in.readUTF(), lastOk);
                }
                default -> { }
            }
        } catch (IOException | RuntimeException e) {
            ctx.logger().warn("holo: битый пакет: {}", e.toString());
        }
    }

    private static ItemStack decode(byte[] b) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null) return null;
        try { return ItemStack.OPTIONAL_STREAM_CODEC.decode(new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(b), conn.registryAccess())); }
        catch (RuntimeException e) { return null; }
    }

    interface Body { void write(DataOutputStream out) throws IOException; }

    void send(int op, Body body) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) {
            out.writeByte(op);
            body.write(out);
        } catch (IOException e) {
            return;
        }
        ctx.send(b.toByteArray());
    }

    static void str(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    static String str(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > (8 << 20)) throw new IOException("строка " + n);
        return new String(in.readNBytes(n), StandardCharsets.UTF_8);
    }

    // ───────────────────────────── данные для рисования ─────────────────────────────

    /** Что рисовать для голограммы: черновик редактора (живой предпросмотр) или серверная версия. */
    Holo shown(String id, Holo server) {
        if (session.version != draftVersion) { draftCache.clear(); draftVersion = session.version; }
        if (!session.drafts.containsKey(id)) return server;
        return draftCache.computeIfAbsent(id, k -> {
            try { return Holo.parse(session.drafts.get(k)); } catch (RuntimeException e) { return server; }
        });
    }

    Holo.Theme theme(Holo h) { return themes.getOrDefault(h.theme, Holo.Theme.SPACE); }

    Layout.Panel panel(Live l, Holo h) {
        int ver = valuesVersion * 31 + l.page;
        if (l.panel == null || l.panelVersion != ver || l.drawn != h) {
            l.panel = Layout.layout(h, l.page, values, metrics, theme(h).text());
            l.panelVersion = ver;
            l.drawn = h;
        }
        return l.panel;
    }

    /** Черновики новых (ещё не сохранённых) голограмм — тоже рисуются. */
    private List<Live> draftOnly() {
        List<Live> out = new ArrayList<>();
        for (String id : session.drafts.keySet()) {
            if (live.containsKey(id)) continue;
            Live l = drafts.computeIfAbsent(id, k -> {
                Live n = new Live();
                n.born = System.currentTimeMillis();
                n.seed = k.hashCode();
                return n;
            });
            l.holo = shown(id, null);
            if (l.holo != null && map.equals(l.holo.map)) out.add(l);
        }
        drafts.keySet().retainAll(session.drafts.keySet());
        return out;
    }

    private final Map<String, Live> drafts = new HashMap<>();

    // ───────────────────────────── мир ─────────────────────────────

    private static final com.mojang.blaze3d.pipeline.RenderPipeline PIPE_BG = com.mojang.blaze3d.pipeline.RenderPipeline
            .builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("hoka_odyssey", "pipeline/holo_bg"))
            .withVertexShader("core/position_color").withFragmentShader("core/position_color")
            .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT).withDepthWrite(false).withCull(false)
            .withVertexFormat(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)
            .build();
    private static final com.mojang.blaze3d.pipeline.RenderPipeline PIPE_GLOW = com.mojang.blaze3d.pipeline.RenderPipeline
            .builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("hoka_odyssey", "pipeline/holo_glow"))
            .withVertexShader("core/position_color").withFragmentShader("core/position_color")
            .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.LIGHTNING).withDepthWrite(false).withCull(false)
            .withVertexFormat(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)
            .build();
    private static final com.mojang.blaze3d.pipeline.RenderPipeline PIPE_SCAN = com.mojang.blaze3d.pipeline.RenderPipeline
            .builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("hoka_odyssey", "pipeline/holo_scan"))
            .withVertexShader("core/position_tex_color").withFragmentShader("core/position_tex_color").withSampler("Sampler0")
            .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.LIGHTNING).withDepthWrite(false).withCull(false)
            .withVertexFormat(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)
            .build();
    static final net.minecraft.client.renderer.rendertype.RenderType BG = net.minecraft.client.renderer.rendertype.RenderType.create(
            "hoka_holo_bg", net.minecraft.client.renderer.rendertype.RenderSetup.builder(PIPE_BG).createRenderSetup());
    static final net.minecraft.client.renderer.rendertype.RenderType GLOW = net.minecraft.client.renderer.rendertype.RenderType.create(
            "hoka_holo_glow", net.minecraft.client.renderer.rendertype.RenderSetup.builder(PIPE_GLOW).createRenderSetup());
    private static final Identifier SCAN_TEX = Identifier.fromNamespaceAndPath("hoka_odyssey", "holo/scanlines");
    static final net.minecraft.client.renderer.rendertype.RenderType SCAN = net.minecraft.client.renderer.rendertype.RenderType.create(
            "hoka_holo_scan", net.minecraft.client.renderer.rendertype.RenderSetup.builder(PIPE_SCAN)
                    .withTexture("Sampler0", SCAN_TEX, () -> com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getSampler(
                            com.mojang.blaze3d.textures.AddressMode.REPEAT, com.mojang.blaze3d.textures.AddressMode.REPEAT,
                            com.mojang.blaze3d.textures.FilterMode.LINEAR, com.mojang.blaze3d.textures.FilterMode.LINEAR, false))
                    .createRenderSetup());
    private boolean scanReady;

    /** Сканлинии: 1×8 текселей — светлая полоса и мягкий спад; повтор по высоте панели. */
    private void makeScanTexture() {
        var img = new com.mojang.blaze3d.platform.NativeImage(1, 8, false);
        float[] a = {1f, 0.55f, 0.18f, 0.06f, 0.03f, 0.03f, 0.06f, 0.25f};
        for (int y = 0; y < 8; y++) img.setPixel(0, y, (Math.round(a[y] * 255) << 24) | 0xFFFFFF);
        Minecraft.getInstance().getTextureManager().register(SCAN_TEX,
                new net.minecraft.client.renderer.texture.DynamicTexture(SCAN_TEX::toString, img));
        scanReady = true;
    }

    private void render(ClientContext.WorldFrame f) {
        Minecraft mc = Minecraft.getInstance();
        hoverPlaced = null;
        hoverBox = null;
        if (mc.player == null || mc.level == null) return;
        if (live.isEmpty() && session.drafts.isEmpty()) return;
        if (!scanReady) makeScanTexture();
        long now = System.currentTimeMillis();
        float t = (System.nanoTime() - t0) / 1e9f;
        Vec3 cam = f.camera();
        Vec3 eye = mc.player.getEyePosition(f.partialTick());
        Vec3 look = mc.player.getViewVector(f.partialTick());

        List<Placed> list = new ArrayList<>();
        List<Live> all = new ArrayList<>(live.values());
        all.addAll(draftOnly());
        for (Live l : all) {
            Holo h = shown(l.holo.id, l.holo);
            if (h == null || !map.equals(h.map) && !map.isEmpty()) continue;
            if (l.dying >= 0 && now - l.dying > DISSOLVE_MS) continue;
            double dx = h.x - cam.x, dy = h.y - cam.y, dz = h.z - cam.z;
            if (dx * dx + dy * dy + dz * dz > h.range * h.range && !h.id.equals(session.selected)) continue;
            Layout.Panel p = panel(l, h);
            double k = h.scale / Layout.PX_PER_BLOCK;
            double bob = h.hover ? Math.sin(t * 1.3 + l.seed * 0.001) * 0.04 : 0;
            double cy = h.y + bob, r = Math.max(p.w(), p.h()) * k * 0.75 + 0.2;
            if (!f.visible(new AABB(h.x - r, cy - r, h.z - r, h.x + r, cy + r, h.z + r))) continue;
            list.add(new Placed(l, h, p, Orient.of(h, cy, cam.x, cam.y, cam.z), h.x, cy, h.z, k));
        }
        live.values().removeIf(l -> l.dying >= 0 && now - l.dying > DISSOLVE_MS);
        if (list.isEmpty() && !mover.active()) return;
        list.sort((a, b) -> Double.compare(dist2(b, cam), dist2(a, cam)));     // дальние — первыми

        // наведение: ближайшая панель по лучу взгляда, не за блоком
        double best = CLICK_REACH;
        HitResult hr = mc.hitResult;
        if (hr != null && hr.getType() == HitResult.Type.BLOCK) best = Math.min(best, hr.getLocation().distanceTo(eye));
        double[] bestUv = null;
        if (mc.screen == null && !mover.active()) for (Placed pl : list) {
            if (pl.live().dying >= 0) continue;
            double[] uv = pl.o().hit(eye.x, eye.y, eye.z, look.x, look.y, look.z, pl.cx(), pl.cy(), pl.cz(), pl.panel().w(), pl.panel().h(), pl.k());
            if (uv == null || uv[2] >= best || uv[0] < 0 || uv[1] < 0 || uv[0] > pl.panel().w() || uv[1] > pl.panel().h()) continue;
            best = uv[2];
            hoverPlaced = pl;
            bestUv = uv;
        }
        if (hoverPlaced != null) hoverBox = hoverPlaced.panel().at((float) bestUv[0], (float) bestUv[1]);

        WorldPainter wp = new WorldPainter(f, mc);
        for (Placed pl : list) {
            Live l = pl.live();
            Paint.State st = new Paint.State();
            st.t = t;
            st.seed = l.seed;
            st.appear = l.dying >= 0 ? 1 - (now - l.dying) / (float) DISSOLVE_MS : Math.min(1, (now - l.born) / (float) APPEAR_MS);
            if (pl == hoverPlaced) {
                st.hovered = hoverBox;
                st.panelHovered = true;
                boolean clickable = hoverBox != null && hoverBox.el.clickable || pl.holo().panelClick;
                if (clickable) { st.cu = (float) bestUv[0]; st.cv = (float) bestUv[1]; }
            }
            if (pl.holo().id.equals(session.selected) && mc.screen instanceof EditorScreen) st.panelHovered = true;
            PoseStack ps = f.pose();
            ps.pushPose();
            ps.translate((float) (pl.cx() - cam.x), (float) (pl.cy() - cam.y), (float) (pl.cz() - cam.z));
            ps.mulPose(new Quaternionf().rotationYXZ((float) (Math.PI - Math.toRadians(pl.o().yaw)), (float) -Math.toRadians(pl.o().pitch), 0));
            ps.scale((float) pl.k(), (float) -pl.k(), (float) pl.k());
            ps.translate(-pl.panel().w() / 2, -pl.panel().h() / 2, 0);
            wp.begin(ps, l, theme(pl.holo()));
            Paint.draw(pl.holo(), pl.panel(), theme(pl.holo()), st, wp);
            ps.popPose();
        }
        mover.render(f, wp, cam, eye, look);
        wp.end();
    }

    private static double dist2(Placed p, Vec3 c) {
        double dx = p.cx() - c.x, dy = p.cy() - c.y, dz = p.cz() - c.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Рисование {@link Paint} в мир: фон, свечение (кубики), сканлинии — свои проходы; текст — шрифт игры; предметы — рендер игры. */
    final class WorldPainter implements Paint.Painter {
        final ClientContext.WorldFrame f;
        final Minecraft mc;
        final VertexConsumer bg, glow;
        final List<float[]> scans = new ArrayList<>();
        final List<Runnable> late = new ArrayList<>();
        Matrix4f m;
        PoseStack ps;
        Live l;

        WorldPainter(ClientContext.WorldFrame f, Minecraft mc) {
            this.f = f;
            this.mc = mc;
            this.bg = f.buffers().getBuffer(BG);
            this.glow = f.buffers().getBuffer(GLOW);
        }

        void begin(PoseStack ps, Live l, Holo.Theme th) {
            this.ps = ps;
            this.m = new Matrix4f(ps.last().pose());
            this.l = l;
        }

        void end() {
            MultiBufferSource.BufferSource bs = f.buffers() instanceof MultiBufferSource.BufferSource b ? b : null;
            if (bs != null) { bs.endBatch(BG); bs.endBatch(GLOW); }
            if (!scans.isEmpty()) {
                VertexConsumer sc = f.buffers().getBuffer(SCAN);
                for (float[] q : scans) {
                    Matrix4f mm = new Matrix4f(q[8], q[9], q[10], q[11], q[12], q[13], q[14], q[15], q[16], q[17], q[18], q[19], q[20], q[21], q[22], q[23]);
                    int c = Float.floatToRawIntBits(q[5]);
                    float v0 = q[1] / 4 + q[6] * 8, v1 = q[3] / 4 + q[6] * 8;
                    sc.addVertex(mm, q[0], q[1], q[4]).setUv(0, v0).setColor(c);
                    sc.addVertex(mm, q[0], q[3], q[4]).setUv(0, v1).setColor(c);
                    sc.addVertex(mm, q[2], q[3], q[4]).setUv(1, v1).setColor(c);
                    sc.addVertex(mm, q[2], q[1], q[4]).setUv(1, v0).setColor(c);
                }
                if (bs != null) bs.endBatch(SCAN);
            }
            for (Runnable r : late) r.run();
            if (bs != null) bs.endBatch();
        }

        @Override
        public void rect(float x0, float y0, float x1, float y1, float z, int argb) { quad(bg, x0, y0, x1, y1, z, argb); }

        @Override
        public void glow(float x0, float y0, float x1, float y1, float z, int argb) { quad(glow, x0, y0, x1, y1, z, argb); }

        private void quad(VertexConsumer vc, float x0, float y0, float x1, float y1, float z, int argb) {
            if ((argb >>> 24) == 0) return;
            vc.addVertex(m, x0, y0, z).setColor(argb);
            vc.addVertex(m, x0, y1, z).setColor(argb);
            vc.addVertex(m, x1, y1, z).setColor(argb);
            vc.addVertex(m, x1, y0, z).setColor(argb);
        }

        @Override
        public void cube(float cx, float cy, float cz, float s, int argb) {
            if ((argb >>> 24) == 0) return;
            float h = s / 2, x0 = cx - h, x1 = cx + h, y0 = cy - h, y1 = cy + h, z0 = cz - h, z1 = cz + h;
            int a = argb >>> 24, rgb = argb & 0xFFFFFF;
            int top = (a << 24) | Paint.mul(rgb, 0.85f), side = (a << 24) | Paint.mul(rgb, 0.62f), bot = (a << 24) | Paint.mul(rgb, 0.45f);
            quad(glow, x0, y0, x1, y1, z1, argb);                                 // лицо (к зрителю)
            VertexConsumer g = glow;
            g.addVertex(m, x0, y0, z0).setColor(top); g.addVertex(m, x0, y0, z1).setColor(top);   // верх (y вниз — y0)
            g.addVertex(m, x1, y0, z1).setColor(top); g.addVertex(m, x1, y0, z0).setColor(top);
            g.addVertex(m, x0, y1, z0).setColor(bot); g.addVertex(m, x1, y1, z0).setColor(bot);
            g.addVertex(m, x1, y1, z1).setColor(bot); g.addVertex(m, x0, y1, z1).setColor(bot);
            g.addVertex(m, x0, y0, z0).setColor(side); g.addVertex(m, x0, y1, z0).setColor(side);
            g.addVertex(m, x0, y1, z1).setColor(side); g.addVertex(m, x0, y0, z1).setColor(side);
            g.addVertex(m, x1, y0, z0).setColor(side); g.addVertex(m, x1, y0, z1).setColor(side);
            g.addVertex(m, x1, y1, z1).setColor(side); g.addVertex(m, x1, y1, z0).setColor(side);
        }

        @Override
        public void scan(float x0, float y0, float x1, float y1, float z, int argb, float phase) {
            if ((argb >>> 24) == 0) return;
            float[] q = new float[24];
            q[0] = x0; q[1] = y0; q[2] = x1; q[3] = y1; q[4] = z; q[5] = Float.intBitsToFloat(argb); q[6] = phase;
            m.get(q, 8);
            scans.add(q);
        }

        @Override
        public void text(Layout.Box b, Layout.TLine line, float x, float y, float z, float scale, float alpha) {
            int a = Math.round(alpha * 255);
            if (a < 6) return;
            FormattedCharSequence seq = sequence(b, line);
            Matrix4f mm = new Matrix4f(m).translate(x, y, z).scale(scale, scale, 1);
            mc.font.drawInBatch(seq, 0, 0, (a << 24) | 0xFFFFFF, false, mm, f.buffers(), Font.DisplayMode.POLYGON_OFFSET, 0, 0xF000F0);
        }

        @Override
        public void item(Layout.Box b, float cx, float cy, float z, float size, float spinDeg, float alpha) {
            ItemStack it = stack(l, b.el.item);
            if (it == null || it.isEmpty() || alpha < 0.3f) return;
            Matrix4f mm = new Matrix4f(m);
            late.add(() -> {
                ItemStackRenderState st = new ItemStackRenderState();
                mc.getItemModelResolver().updateForTopItem(st, it, ItemDisplayContext.FIXED, mc.level, null, 0);
                PoseStack p = new PoseStack();
                p.last().pose().set(mm);
                p.translate(cx, cy, z);
                p.scale(size, -size, size);
                p.mulPose(new Quaternionf().rotationY((float) Math.toRadians(spinDeg)));
                st.submit(p, f.submits(), 0xF000F0, OverlayTexture.NO_OVERLAY, 0);
            });
        }

        @Override
        public void image(String name, float x0, float y0, float x1, float y1, float z, float alpha) {
            if (name == null || name.isEmpty()) return;
            Identifier tex = ctx.texture("holo/img/" + name);
            VertexConsumer vc = f.buffers().getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.text(tex));
            int c = Paint.argb(alpha, 0xFFFFFF);
            vc.addVertex(m, x0, y0, z).setColor(c).setUv(0, 0).setLight(0xF000F0);
            vc.addVertex(m, x0, y1, z).setColor(c).setUv(0, 1).setLight(0xF000F0);
            vc.addVertex(m, x1, y1, z).setColor(c).setUv(1, 1).setLight(0xF000F0);
            vc.addVertex(m, x1, y0, z).setColor(c).setUv(1, 0).setLight(0xF000F0);
        }
    }

    /** Строка текста → последовательность игры (кэш в боксе раскладки). */
    @SuppressWarnings("unchecked")
    static FormattedCharSequence sequence(Layout.Box b, Layout.TLine line) {
        Map<Layout.TLine, FormattedCharSequence> c;
        if (b.cache instanceof Map<?, ?> mm) c = (Map<Layout.TLine, FormattedCharSequence>) mm;
        else { c = new java.util.IdentityHashMap<>(); b.cache = c; }
        return c.computeIfAbsent(line, l -> component(l).getVisualOrderText());
    }

    static MutableComponent component(Layout.TLine l) {
        MutableComponent root = Component.empty();
        for (Mini.Run r : l.runs()) {
            int fl = r.flags();
            root.append(Component.literal(r.text()).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(r.rgb()))
                    .withBold((fl & Mini.BOLD) != 0).withItalic((fl & Mini.ITALIC) != 0).withUnderlined((fl & Mini.UNDERLINE) != 0)
                    .withStrikethrough((fl & Mini.STRIKE) != 0).withObfuscated((fl & Mini.OBF) != 0)));
        }
        return root;
    }

    /** Предмет: присланный сервером (в т.ч. свои hoitems) или ванильный по id (для черновиков). */
    ItemStack stack(Live l, String key) {
        if (key == null) return null;
        if (l != null) {
            ItemStack it = l.items.get(key);
            if (it != null) return it;
        }
        for (Live o : live.values()) {
            ItemStack it = o.items.get(key);
            if (it != null) return it;
        }
        try {
            Identifier id = Identifier.parse(key.contains(":") ? key : "minecraft:" + key);
            if (id.getNamespace().equals("hoka")) return new ItemStack(net.minecraft.world.item.Items.PAPER);
            return BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new).orElse(new ItemStack(net.minecraft.world.item.Items.BARRIER));
        } catch (RuntimeException e) {
            return new ItemStack(net.minecraft.world.item.Items.BARRIER);
        }
    }

    /** Ширина букв шрифта игры (кэш) и пропорции картинок (по заголовку PNG). */
    final class Metrics implements Layout.Metrics {
        private final Map<Integer, Float> w = new HashMap<>();
        private final Map<String, Float> aspects = new HashMap<>();

        @Override
        public float width(int cp, boolean bold) {
            int key = bold ? -cp - 1 : cp;
            return w.computeIfAbsent(key, k -> {
                String s = new String(Character.toChars(cp));
                return (float) Minecraft.getInstance().font.width(bold ? Component.literal(s).withStyle(Style.EMPTY.withBold(true)) : Component.literal(s));
            });
        }

        @Override
        public float imageAspect(String image) {
            if (image == null) return 1;
            Float a = aspects.get(image);
            if (a != null) return a;
            var b = ctx.file("holo/img/" + image);
            if (b.isEmpty() || b.get().length < 24) return 1;
            byte[] d = b.get();
            int wi = ((d[16] & 255) << 24) | ((d[17] & 255) << 16) | ((d[18] & 255) << 8) | (d[19] & 255);
            int he = ((d[20] & 255) << 24) | ((d[21] & 255) << 16) | ((d[22] & 255) << 8) | (d[23] & 255);
            float r = wi > 0 && he > 0 ? he / (float) wi : 1;
            aspects.put(image, r);
            return r;
        }
    }

    // ───────────────────────────── ввод ─────────────────────────────

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!live.isEmpty()) live.clear();
            return;
        }
        mover.tick(mc);
        boolean j = mc.screen == null && com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_J);
        if (j && !jWas && editorAllowed && !mover.active()) {
            String id = hoverPlaced != null ? hoverPlaced.holo().id : "";
            send(C_INFO_REQ, out -> { });
            openEditor(id);
        }
        jWas = j;
    }

    private boolean click(boolean attack, boolean held) {
        Minecraft mc = Minecraft.getInstance();
        if (mover.active()) return mover.click(attack, held);
        Placed pl = hoverPlaced;
        if (pl == null || mc.screen != null) return false;
        Layout.Box b = hoverBox;
        boolean clickable = b != null && b.el.clickable;
        if (!clickable && !pl.holo().panelClick) return false;
        if (held) return true;                                                    // зажатая ЛКМ — не ломать блок за панелью
        int line = clickable ? b.line : 0, elem = clickable ? b.elem : -1;
        int page = pl.live().page;
        String id = pl.holo().id;
        send(C_CLICK, out -> {
            out.writeUTF(id);
            out.writeInt(page);
            out.writeInt(line);
            out.writeInt(elem);
            out.writeByte(attack ? 0 : 1);
        });
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.4f, 0.35f));
        return true;
    }

    void openEditor(String id) {
        if (!editorAllowed) return;
        if (id != null && !id.isEmpty() && session.get(id) != null) session.select(id);
        else if (id != null && !id.isEmpty()) session.selected = id;            // придёт с INFO
        if (Minecraft.getInstance().screen instanceof EditorScreen) return;     // уже открыт — не терять черновики
        Minecraft.getInstance().setScreen(new EditorScreen(this));
    }

    /** Список голограмм для редактора: id → JSON (серверные + черновики). */
    Map<String, JsonObject> allHolos() {
        Map<String, JsonObject> m = new java.util.TreeMap<>(session.server);
        m.putAll(session.drafts);
        return m;
    }

    JsonArray infoArray(String key) {
        return info.has(key) && info.get(key).isJsonArray() ? info.getAsJsonArray(key) : new JsonArray();
    }

    /** Точка блока под прицелом и нормаль грани (null — не блок). */
    static double[] blockTarget() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.hitResult instanceof BlockHitResult bh) || bh.getType() != HitResult.Type.BLOCK) return null;
        var n = bh.getDirection().getUnitVec3i();
        Vec3 p = bh.getLocation();
        return new double[]{p.x, p.y, p.z, n.getX(), n.getY(), n.getZ()};
    }
}
