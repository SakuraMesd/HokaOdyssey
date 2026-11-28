package ink.hoka.odyssey.client.lod;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import ink.hoka.odyssey.client.ClientGen;
import ink.hoka.odyssey.client.LodSetting;
import ink.hoka.odyssey.gen.GenBounds;
import ink.hoka.odyssey.gen.LodTile;
import ink.hoka.odyssey.gen.PlanetGenerator;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 0.64 Дальняя прорисовка (аналог Voxy / Distant Horizons). За обычной «Дальностью прорисовки» мир виден упрощённым:
 * дерево плиток 32×32 ячейки, ячейка уровня L — 2^L блоков (L1 = 2 … L6 = 64). Чем дальше плитка, тем крупнее
 * ячейки (настройка «Качество»). Данные — из генератора планеты на клиенте (как кубы при клиентской генерации:
 * сеть не нужна): верх ячейки, блок, вода, биом ({@link PlanetGenerator#lod}). Сетки строятся в фоне и держатся в
 * видеопамяти, пока не кончится лимит (отвернулся или отошёл — не выгружаются сразу).
 * <p>Рисуется в начале основного прохода, после неба: своя проекция (near 4, far = дальность LOD — точная глубина
 * вдали), затем глубина сбрасывается и ванильная земля рисуется поверх. Внутри ближнего круга LOD отбрасывается в
 * шейдере (там настоящие блоки), полоса у края — запасная: закрывает ещё не загруженные чанки. Туман — общий с игрой,
 * но от дальности LOD ({@link #fogChunks}).</p>
 */
public final class LodRenderer {

    private LodRenderer() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey");

    /** Уровень 0 — плитка 32×32 настоящих блоков (LodVoxel), 1…6 — ячейки 2…64 блока (карта высот генератора). */
    static final int N = 32, MIN_LEVEL = 0, MAX_LEVEL = 6;
    /** Плиток в памяти не больше (старые по давности — вон). */
    private static final int MAX_NODES = 2400;
    /** Разделять плитку уровня L ≥ 2, если до неё ближе ширины × K (по качеству 1…4). */
    private static final float[] SPLIT_K = {0, 1.0f, 1.4f, 1.9f, 2.6f};
    /** Насколько дальше ближнего круга — настоящие блоки (уровень 0), по качеству. */
    private static final int[] VOXEL_RING = {0, 64, 160, 288, 448};
    /** Запас при обратном укрупнении: разделённая плитка собирается обратно лишь на 15% дальше — без мигания. */
    private static final float HYST = 1.15f;
    private static int voxelR;

    // ───────────── кадр: матрицы (LodLevelRendererMixin) ─────────────

    private static final Matrix4f VIEW = new Matrix4f(), PROJ = new Matrix4f();
    private static volatile boolean haveMatrices;

    public static void frameMatrices(Matrix4f view, Matrix4f projection, Vector4f fogColor) {
        VIEW.set(view);
        PROJ.set(projection);
        if (fogColor != null) VANILLA_FOG.set(fogColor);
        haveMatrices = true;
    }

    private static final Vector4f VANILLA_FOG = new Vector4f(0.7f, 0.8f, 1f, 1f);
    private static volatile int skyFog = -1;
    private static volatile long skyFogAt;

    /** Модуль неба: цвет его тумана (RGB) на этот кадр — дальние места тают в цвет горизонта. */
    public static void skyFogColor(int rgb) { skyFog = rgb & 0xFFFFFF; skyFogAt = System.currentTimeMillis(); }

    // ───────────── плитки ─────────────

    static final class Node {
        final long key;
        final int level, tx, tz;
        /** 0 — нет, 1 — в очереди, 2 — собрана (ждёт заливки), 3 — готова, 4 — пусто. */
        volatile int st;
        volatile LodMesh pending;
        GpuBuffer vb, wb;
        int quads, wquads;
        float yMin, yMax;
        /** Разделена в прошлый раз (гистерезис). */
        boolean wasSplit;
        long used;
        long prio;
        int epoch;

        Node(long key, int level, int tx, int tz) { this.key = key; this.level = level; this.tx = tx; this.tz = tz; }

        int x0() { return tx * width(level); }

        int z0() { return tz * width(level); }

        boolean ready() { return st == 3 || st == 4; }
    }

    static int width(int level) { return N << level; }

    static long key(int level, int tx, int tz) { return (long) level << 56 | ((long) (tx & 0xFFFFFFF)) << 28 | (tz & 0xFFFFFFF); }

    private static final ConcurrentHashMap<Long, Node> NODES = new ConcurrentHashMap<>();
    private static final PriorityBlockingQueue<Node> QUEUE = new PriorityBlockingQueue<>(256, (a, b) -> Long.compare(a.prio, b.prio));
    private static final AtomicInteger EPOCH = new AtomicInteger();
    private static volatile PlanetGenerator gen;
    private static volatile Holder<Biome>[] biomes;
    private static volatile GenBounds bounds;
    private static volatile long seed;
    private static long genKey;
    private static Thread[] workers;
    private static final AtomicLong builtTiles = new AtomicLong(), builtNanos = new AtomicLong();
    private static volatile int dirty = 1;

    private static synchronized void startWorkers() {
        if (workers != null) return;
        int n = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 3));
        workers = new Thread[n];
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(LodRenderer::work, "hoka-lod-" + i);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
            workers[i] = t;
        }
    }

    private static void work() {
        while (true) {
            Node n;
            try { n = QUEUE.take(); } catch (InterruptedException e) { return; }
            int ep = EPOCH.get();
            PlanetGenerator g = gen;
            if (n.epoch != ep || g == null || n.st != 1) continue;
            if (frame - n.used > 120 || NODES.get(n.key) != n) { n.st = 0; continue; }   // уже не нужна (ушёл)
            long t0 = System.nanoTime();
            LodMesh m = null;
            try {
                if (n.level == 0) m = LodVoxel.build(g, bounds, seed, n.x0(), n.z0(), biomes);
                else {
                    LodTile t = new LodTile(n.x0(), n.z0(), 1 << n.level, N);
                    if (g.lod(t)) m = LodMesh.heightmap(t, biomes, bounds);
                }
            } catch (Throwable e) {
                LOG.warn("дальняя прорисовка: плитка {}:{},{} — {}", n.level, n.tx, n.tz, e.toString());
            }
            builtTiles.incrementAndGet();
            builtNanos.addAndGet(System.nanoTime() - t0);
            if (n.epoch != EPOCH.get() || n.st != 1 || NODES.get(n.key) != n) { if (m != null) m.free(); continue; }
            if (m == null) { n.st = 4; dirty = 1; continue; }
            n.pending = m;
            n.st = 2;
        }
    }

    private static void request(Node n, double dist) {
        if (n.st != 0) return;
        n.epoch = EPOCH.get();
        n.prio = (long) (MAX_LEVEL - n.level) << 40 | (long) Math.max(0, Math.min(1L << 39, (long) dist));
        n.st = 1;
        QUEUE.add(n);
    }

    /** Всё в мусор (смена карты, выкл): видеопамять — сразу, в потоке рендера. */
    private static void clearAll() {
        SpaceLod.clear();
        EPOCH.incrementAndGet();
        QUEUE.clear();
        for (Node n : NODES.values()) release(n);
        NODES.clear();
        draws.clear();
        dirty = 1;
    }

    private static void release(Node n) {
        if (n.vb != null) { vramBytes -= n.vb.size(); n.vb.close(); n.vb = null; }
        if (n.wb != null) { vramBytes -= n.wb.size(); n.wb.close(); n.wb = null; }
        LodMesh p = n.pending;
        n.pending = null;
        if (p != null) p.free();
        n.st = 0;
    }

    // ───────────── выбор плиток ─────────────

    private static final List<Node> draws = new ArrayList<>();
    private static double selX = Double.NaN, selZ = Double.NaN;
    private static int selVersion = -1, selDistance, selCutoff;
    private static volatile long frame;

    /** Для тумана: дальность LOD в чанках (не меньше обычной). Зовут миксин тумана и модуль неба. */
    public static int fogChunks(int rd) {
        return active ? Math.max(rd, LodSetting.distance()) : rd;
    }

    private static volatile boolean active;

    private static void select(double cx, double cz, int distBlocks, int cutoff, float k) {
        draws.clear();
        int w = width(MAX_LEVEL);
        int tx0 = Math.floorDiv((int) Math.floor(cx) - distBlocks, w), tx1 = Math.floorDiv((int) Math.floor(cx) + distBlocks, w);
        int tz0 = Math.floorDiv((int) Math.floor(cz) - distBlocks, w), tz1 = Math.floorDiv((int) Math.floor(cz) + distBlocks, w);
        for (int tz = tz0; tz <= tz1; tz++) for (int tx = tx0; tx <= tx1; tx++) visit(MAX_LEVEL, tx, tz, cx, cz, distBlocks, cutoff, k, true);
    }

    /** 0 — пропустить (вне круга/карты или целиком внутри ближнего круга), иначе 1. */
    private static boolean skip(int level, int tx, int tz, double cx, double cz, int dist, int cutoff) {
        int w = width(level), x0 = tx * w, z0 = tz * w;
        GenBounds b = bounds;
        if (b != null && b.size() > 0) {
            int h = b.size() / 2;
            if (x0 + w <= -h || x0 >= h || z0 + w <= -h || z0 >= h) return true;
        }
        double minD = rectMin(cx, cz, x0, z0, w), maxD = rectMax(cx, cz, x0, z0, w);
        return minD > dist || maxD < cutoff;
    }

    private static double rectMin(double cx, double cz, int x0, int z0, int w) {
        double dx = Math.max(0, Math.max(x0 - cx, cx - (x0 + w))), dz = Math.max(0, Math.max(z0 - cz, cz - (z0 + w)));
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double rectMax(double cx, double cz, int x0, int z0, int w) {
        double dx = Math.max(Math.abs(x0 - cx), Math.abs(x0 + w - cx)), dz = Math.max(Math.abs(z0 - cz), Math.abs(z0 + w - cz));
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static Node node(int level, int tx, int tz) {
        long key = key(level, tx, tz);
        Node n = NODES.get(key);
        if (n == null) { n = new Node(key, level, tx, tz); NODES.put(key, n); }
        n.used = frame;
        return n;
    }

    /**
     * Разделить плитку на 4 помельче: уровень 1 — внутри кольца настоящих блоков, выше — по качеству (и всё, что
     * ближе кольца). С запасом: уже разделённая собирается обратно лишь заметно дальше (нет мигания на границе).
     */
    private static boolean split(int level, int tx, int tz, double cx, double cz, float k) {
        if (level <= MIN_LEVEL) return false;
        int w = width(level);
        double d = rectMin(cx, cz, tx * w, tz * w, w);
        double t = level == 1 ? voxelR : Math.max(w * k, voxelR);
        Node n = NODES.get(key(level, tx, tz));
        boolean was = n != null && n.wasSplit;
        return d < (was ? t * HYST : t);
    }

    /** Запомнить решение (вызывать только при обходе для отрисовки — не из renderable). */
    private static boolean splitNow(int level, int tx, int tz, double cx, double cz, float k) {
        boolean sp = split(level, tx, tz, cx, cz, k);
        if (level > MIN_LEVEL) node(level, tx, tz).wasSplit = sp;
        return sp;
    }

    /** Всё поддерево можно нарисовать (каждый нужный лист готов). */
    private static boolean renderable(int level, int tx, int tz, double cx, double cz, int dist, int cutoff, float k) {
        if (skip(level, tx, tz, cx, cz, dist, cutoff)) return true;
        if (split(level, tx, tz, cx, cz, k)) {
            for (int c = 0; c < 4; c++)
                if (!renderable(level - 1, tx * 2 + (c & 1), tz * 2 + (c >> 1), cx, cz, dist, cutoff, k)) return false;
            return true;
        }
        Node n = NODES.get(key(level, tx, tz));
        return n != null && n.ready();
    }

    private static void visit(int level, int tx, int tz, double cx, double cz, int dist, int cutoff, float k, boolean emit) {
        if (skip(level, tx, tz, cx, cz, dist, cutoff)) return;
        int w = width(level);
        double d = rectMin(cx, cz, tx * w, tz * w, w);
        if (splitNow(level, tx, tz, cx, cz, k)) {
            boolean all = true;
            for (int c = 0; c < 4 && all; c++)
                all = renderable(level - 1, tx * 2 + (c & 1), tz * 2 + (c >> 1), cx, cz, dist, cutoff, k);
            if (!all) {
                Node me = node(level, tx, tz);
                request(me, d);
                if (me.ready()) {                                             // пока дети не готовы — рисуем себя
                    if (emit && me.st == 3) draws.add(me);
                    for (int c = 0; c < 4; c++) visit(level - 1, tx * 2 + (c & 1), tz * 2 + (c >> 1), cx, cz, dist, cutoff, k, false);
                    return;
                }
            }
            for (int c = 0; c < 4; c++) visit(level - 1, tx * 2 + (c & 1), tz * 2 + (c >> 1), cx, cz, dist, cutoff, k, emit);
            return;
        }
        Node me = node(level, tx, tz);
        request(me, d);
        if (emit && me.st == 3) draws.add(me);
    }

    // ───────────── рендер ─────────────

    private static boolean registered;
    private static final Identifier PHASE = Identifier.fromNamespaceAndPath("hoka_odyssey", "lod");

    public static void register() {
        if (registered) return;
        registered = true;
        // после неба (модуль sky рисует в START_MAIN в фазе по умолчанию)
        WorldRenderEvents.START_MAIN.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, PHASE);
        WorldRenderEvents.START_MAIN.register(PHASE, c -> render());
    }

    private static RenderPipeline pipe(String name, boolean water) {
        var b = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("hoka_odyssey", "pipeline/" + name))
                .withVertexShader(Identifier.fromNamespaceAndPath("hoka_odyssey", "core/lod"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("hoka_odyssey", "core/lod"))
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("HokaLod", UniformType.UNIFORM_BUFFER)
                .withSampler("Sampler0")
                .withSampler("Sampler2")
                .withVertexFormat(LodQuads.FORMAT, VertexFormat.Mode.QUADS)
                .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                .withCull(false);
        if (water) b.withShaderDefine("HOKA_WATER").withDepthWrite(false).withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT);
        else b.withDepthWrite(true).withoutBlend();
        return b.build();
    }

    private static final RenderPipeline PIPE = pipe("lod", false), WATER_PIPE = pipe("lod_water", true);

    private static GpuBuffer projBuf, paramBuf;
    private static final Matrix4f P = new Matrix4f();
    private static final Vector4f MOD = new Vector4f(1, 1, 1, 1);
    private static final Vector3f OFF = new Vector3f();
    private static final Matrix4f IDENT = new Matrix4f();
    private static final FrustumIntersection FRUSTUM = new FrustumIntersection();
    private static final Matrix4f PV = new Matrix4f();
    private static long errors, retryAt, backoff;
    private static int lastDrawn, lastQuads, uploadsLastFrame;
    private static long vramBytes;
    private static boolean underground;
    private static int surfaceCheckIn;

    private static void render() {
        long now = System.currentTimeMillis();
        if (now < retryAt) return;
        try {
            render0();
            backoff = 0;
        } catch (Throwable e) {
            errors++;
            backoff = backoff == 0 ? 2000 : Math.min(60_000, backoff * 2);
            retryAt = now + backoff;
            LOG.warn("дальняя прорисовка: ошибка (всего {}), пауза {} мс", errors, backoff, e);
        }
    }

    private static void render0() {
        frame++;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lvl = mc.level;
        PlanetGenerator g = ClientGen.lodGenerator();
        // 0.76: космос — рельефа нет (плитки не строим: генератор перебирал бы кубы по всей высоте), только тела со
        // своей дальностью из настроек («Дальность астероидов»)
        boolean space = SpaceLod.supports(g);
        int distChunks = space ? LodSetting.bodyDistance() : LodSetting.distance();
        if (space && !NODES.isEmpty()) { for (Node n : NODES.values()) release(n); NODES.clear(); draws.clear(); }
        if (lvl == null || g == null || distChunks <= 0) {
            active = false;
            if (!NODES.isEmpty()) { clearAll(); gen = null; genKey = 0; }
            return;
        }
        long k = ClientGen.lodKey();
        if (k != genKey || gen != g) {
            clearAll();
            LodBlocks.clear();
            genKey = k;
            gen = g;
            biomes = ClientGen.lodBiomes();
            bounds = ClientGen.lodBounds();
            seed = ClientGen.lodSeed();
        }
        active = true;
        startWorkers();
        uploads();
        Camera cam = mc.gameRenderer.getMainCamera();
        var pos = cam.position();
        int rd = mc.options.getEffectiveRenderDistance();
        int dist = distChunks * 16;
        int cutoff = Math.max(48, rd * 16 - 8);                          // ближе — настоящие блоки (иначе кроны LOD торчат над ними)
        SpaceLod.update(g, k, pos.x, pos.y, pos.z, dist, now());             // 0.74: тела (космос)
        SpaceLod.uploads(now(), pos.x, pos.y, pos.z, dist);
        float qk = SPLIT_K[LodSetting.quality()];
        voxelR = Math.min(dist, cutoff + VOXEL_RING[LodSetting.quality()]);
        if (!space && (dirty != 0 || selVersion != LodSetting.version() || selDistance != dist || selCutoff != cutoff
                || Double.isNaN(selX) || Math.abs(pos.x - selX) + Math.abs(pos.z - selZ) > 8 || frame % 20 == 0)) {
            dirty = 0;
            selVersion = LodSetting.version();
            selDistance = dist;
            selCutoff = cutoff;
            selX = pos.x;
            selZ = pos.z;
            select(pos.x, pos.z, dist, cutoff, qk);
            evict();
        }
        // под землёй (глубже поверхности) — LOD не нужен
        if (!space && --surfaceCheckIn <= 0) {
            surfaceCheckIn = 20;
            int bx = (int) Math.floor(pos.x), bz = (int) Math.floor(pos.z);
            // одна ячейка LOD (точка рельефа, без объектов) — дёшево; highest() тянул объекты соседей (40 мс в кадре)
            int sy = Integer.MIN_VALUE;
            try {
                LodTile one = new LodTile(bx, bz, 1, 1);
                if (g.lod(one) && one.state[0] != 0) sy = one.top[0];
            } catch (Throwable ignored) {}
            underground = sy != Integer.MIN_VALUE && pos.y < sy - 24;
        }
        if ((!space && underground) || !haveMatrices || (draws.isEmpty() && SpaceLod.count() == 0)) { lastDrawn = 0; lastQuads = 0; return; }
        if (cam.getFluidInCamera() != FogType.NONE) { lastDrawn = 0; lastQuads = 0; return; }
        draw(mc, cam, lvl, dist, cutoff);
        if (now() - lastLog > 30_000) { lastLog = now(); LOG.info("[LOD] {}", debugLine()); }
    }

    private static long lastLog;

    private static long now() { return System.currentTimeMillis(); }

    /** Заливка готовых сеток в видеопамять: не больше 8 за кадр. */
    private static void uploads() {
        int up = 0;
        var dev = RenderSystem.getDevice();
        for (Node n : NODES.values()) {
            if (n.st != 2) continue;
            LodMesh m = n.pending;
            if (m == null) continue;
            n.pending = null;
            if (n.vb != null) { vramBytes -= n.vb.size(); n.vb.close(); n.vb = null; }
            if (n.wb != null) { vramBytes -= n.wb.size(); n.wb.close(); n.wb = null; }
            if (m.opaque != null) { n.vb = dev.createBuffer(() -> "Hoka LOD", GpuBuffer.USAGE_VERTEX, m.opaque); vramBytes += n.vb.size(); }
            if (m.water != null) { n.wb = dev.createBuffer(() -> "Hoka LOD water", GpuBuffer.USAGE_VERTEX, m.water); vramBytes += n.wb.size(); }
            n.quads = m.opaqueQuads;
            n.wquads = m.waterQuads;
            n.yMin = m.yMin;
            n.yMax = m.yMax;
            m.free();
            n.st = 3;
            dirty = 1;
            if (++up >= 8) break;
        }
        uploadsLastFrame = up;
    }

    /** Лишние плитки (давно не нужны) — вон, старые первыми. */
    private static void evict() {
        long budget = vramBudget();
        int over = NODES.size() - MAX_NODES;
        if (over <= 0 && vramBytes <= budget) return;
        List<Node> l = new ArrayList<>(NODES.values());
        l.removeIf(n -> n.used == frame);
        l.sort((a, b) -> Long.compare(a.used, b.used));
        for (int i = 0; i < l.size() && (i < over || vramBytes > budget); i++) {
            Node n = l.get(i);
            NODES.remove(n.key);
            release(n);
        }
    }

    /** Видеопамять под плитки (не нужные в этом кадре сверх неё — вон): по качеству 96…512 МБ. */
    private static long vramBudget() {
        return (long) new int[]{0, 96, 192, 320, 512}[LodSetting.quality()] << 20;
    }

    private static void draw(Minecraft mc, Camera cam, ClientLevel lvl, int dist, int cutoff) {
        var pos = cam.position();
        // проекция кадра с другими near/far: заменить строку z (покачивание камеры — аффинное, см. описание класса)
        float near = 4f, far = dist * 1.5f + 2048f;
        float a = (far + near) / (near - far), b = 2f * far * near / (near - far);
        P.set(PROJ);
        P.m02(-a * PROJ.m03()); P.m12(-a * PROJ.m13()); P.m22(-a * PROJ.m23()); P.m32(-a * PROJ.m33() + b);
        // отсечение по пирамиде видимости
        PV.set(P).mul(VIEW);
        FRUSTUM.set(PV);
        MOD.set(1, 1, 1, 1);                                                  // свет — световая карта игры (в шейдере)

        var dev = RenderSystem.getDevice();
        if (projBuf == null) projBuf = dev.createBuffer(() -> "Hoka LOD projection", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
        if (paramBuf == null) paramBuf = dev.createBuffer(() -> "Hoka LOD params", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 32);
        if (bodyParamBuf == null) bodyParamBuf = dev.createBuffer(() -> "Hoka LOD body params", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 32);
        var enc = dev.createCommandEncoder();
        try (MemoryStack ms = MemoryStack.stackPush()) {
            ByteBuffer pb = ms.malloc(64);
            P.get(pb);
            enc.writeToBuffer(projBuf.slice(), pb);
            ByteBuffer qb = ms.malloc(32);
            float fr, fg, fb;
            int sf = skyFog;
            if (sf >= 0 && System.currentTimeMillis() - skyFogAt < 2000) { fr = (sf >> 16 & 255) / 255f; fg = (sf >> 8 & 255) / 255f; fb = (sf & 255) / 255f; }
            else { fr = VANILLA_FOG.x; fg = VANILLA_FOG.y; fb = VANILLA_FOG.z; }
            // ближний круг (там настоящие блоки), край (нет), туман LOD: от 60% до края дальности (по сфере)
            qb.putFloat(0, cutoff).putFloat(4, Float.MAX_VALUE).putFloat(8, dist * 0.6f).putFloat(12, dist)
              .putFloat(16, fr).putFloat(20, fg).putFloat(24, fb).putFloat(28, 1);
            enc.writeToBuffer(paramBuf.slice(), qb);
            qb.putFloat(0, 0);                                                  // тела: без «ближнего круга» (ваниль поверх)
            enc.writeToBuffer(bodyParamBuf.slice(), qb);
        }

        // сначала все матрицы (writeTransform пишет в буфер — только вне прохода)
        int cnt = 0, quads = 0;
        double px = pos.x, py = pos.y, pz = pos.z;
        if (slices.length < draws.size()) slices = new GpuBufferSlice[draws.size() * 2];
        if (vis.length < draws.size()) vis = new Node[draws.size() * 2];
        int maxQ = 0;
        for (Node n : draws) {
            if (n.vb == null && n.wb == null) continue;
            float ox = (float) (n.x0() - px), oz = (float) (n.z0() - pz), oy = (float) -py;
            int w = width(n.level);
            if (!FRUSTUM.testAab(ox, n.yMin + oy, oz, ox + w, n.yMax + oy, oz + w)) continue;
            OFF.set(ox, oy, oz);
            slices[cnt] = RenderSystem.getDynamicUniforms().writeTransform(VIEW, MOD, OFF, IDENT);
            vis[cnt++] = n;
            quads += n.quads + n.wquads;
            maxQ = Math.max(maxQ, Math.max(n.quads, n.wquads));
        }
        // 0.74: тела космоса
        List<SpaceLod.Body> bodies = SpaceLod.visible(px, py, pz, dist, mc.options.getEffectiveRenderDistance() * 16,
                ink.hoka.odyssey.client.VerticalView.get() * 16);
        int bn = 0;
        if (bodySlices.length < bodies.size()) { bodySlices = new GpuBufferSlice[bodies.size() * 2]; bodyVis = new SpaceLod.Body[bodies.size() * 2]; }
        for (SpaceLod.Body bd : bodies) {
            if (bd.vb == null || bd.quads == 0) continue;                       // 0.90: список видимых кэшируется — тело могли выгрузить
            float ox = (float) (bd.x0 - px), oy = (float) (bd.y0 - py), oz = (float) (bd.z0 - pz);
            if (!FRUSTUM.testAab(ox, oy, oz, ox + bd.size, oy + bd.size, oz + bd.size)) continue;
            OFF.set(ox, oy, oz);
            bodySlices[bn] = RenderSystem.getDynamicUniforms().writeTransform(VIEW, MOD, OFF, IDENT);
            bodyVis[bn++] = bd;
            quads += bd.quads;
            maxQ = Math.max(maxQ, bd.quads);
        }
        lastDrawn = cnt + bn;
        lastQuads = quads;
        if (cnt == 0 && bn == 0) return;
        var target = mc.getMainRenderTarget();
        var color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
        var depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
        if (depth == null) return;
        var idx = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer ib = idx.getBuffer(maxQ * 6);
        try (RenderPass pass = enc.createRenderPass(() -> "Hoka LOD", color, OptionalInt.empty(), depth, OptionalDouble.of(1.0))) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", projBuf.slice());
            pass.setUniform("HokaLod", paramBuf.slice());
            var atlas = mc.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            var samplers = RenderSystem.getSamplerCache();
            var atlasSampler = samplers.getSampler(com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE, com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
                    com.mojang.blaze3d.textures.FilterMode.NEAREST, com.mojang.blaze3d.textures.FilterMode.NEAREST, true);
            var lightSampler = samplers.getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR);
            pass.setIndexBuffer(ib, idx.type());
            for (int p = 0; p < 2; p++) {                                       // 0 — земля, 1 — вода поверх (полупрозрачная)
                pass.setPipeline(p == 0 ? PIPE : WATER_PIPE);
                pass.bindTexture("Sampler0", atlas.getTextureView(), atlasSampler);
                pass.bindTexture("Sampler2", mc.gameRenderer.lightTexture().getTextureView(), lightSampler);
                for (int i = 0; i < cnt; i++) {
                    Node n = vis[i];
                    GpuBuffer vb = p == 0 ? n.vb : n.wb;
                    int q = p == 0 ? n.quads : n.wquads;
                    if (vb == null || q == 0) continue;
                    pass.setUniform("DynamicTransforms", slices[i]);
                    pass.setVertexBuffer(0, vb);
                    pass.drawIndexed(0, 0, q * 6, 1);
                }
            }
            if (bn > 0) {                                                       // тела: свои параметры (без ближнего круга)
                pass.setPipeline(PIPE);
                pass.setUniform("HokaLod", bodyParamBuf.slice());
                pass.bindTexture("Sampler0", atlas.getTextureView(), atlasSampler);
                pass.bindTexture("Sampler2", mc.gameRenderer.lightTexture().getTextureView(), lightSampler);
                for (int i = 0; i < bn; i++) {
                    pass.setUniform("DynamicTransforms", bodySlices[i]);
                    pass.setVertexBuffer(0, bodyVis[i].vb);
                    pass.drawIndexed(0, 0, bodyVis[i].quads * 6, 1);
                }
            }
            for (int i = 0; i < cnt; i++) { slices[i] = null; vis[i] = null; }
            for (int i = 0; i < bn; i++) { bodySlices[i] = null; bodyVis[i] = null; }
        }
        enc.clearDepthTexture(depth.texture(), 1.0);                          // ванильная земля — поверх
    }

    private static GpuBufferSlice[] slices = new GpuBufferSlice[64];
    private static GpuBufferSlice[] bodySlices = new GpuBufferSlice[64];
    private static SpaceLod.Body[] bodyVis = new SpaceLod.Body[64];
    private static GpuBuffer bodyParamBuf;
    private static Node[] vis = new Node[64];

    // ───────────── F3 ─────────────

    public static String debugLine() {
        if (!active) return null;
        long bt = builtTiles.get();
        int ready = 0, queued = 0;
        int vox = 0;
        for (Node n : NODES.values()) { if (n.st == 3) { ready++; if (n.level == 0) vox++; } else if (n.st == 1) queued++; }
        return String.format("Hoka LOD: %d чанк., блоки до %d, видно %d плиток / %d квадов, готово %d (блоков %d), очередь %d, %.1f мс/плитка, %d МБ%s",
                LodSetting.distance(), voxelR, lastDrawn, lastQuads, ready, vox, queued, bt == 0 ? 0 : builtNanos.get() / 1e6 / bt,
                vramBytes >> 20, underground ? ", под землёй — выкл" : "");
    }
}
