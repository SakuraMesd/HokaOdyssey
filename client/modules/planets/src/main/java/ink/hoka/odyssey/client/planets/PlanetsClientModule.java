package ink.hoka.odyssey.client.planets;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Планеты издали. Планета — скруглённый куб (сторона size, скругление round) в виде «как Земля»: океаны, материки
 * (свои — от seed), пустыни, горы, льды на полюсах, облака отдельной оболочкой и тонкая атмосфера. Всё процедурно,
 * цвет запечён в вершины, освещение — с одной стороны. Построек не видно (это модель).
 * <p>Видна из любой точки мира: игра обрезает всё дальше дальней плоскости (дальность прорисовки × 4), поэтому планета
 * всегда вписывается целиком: рисуется ближе и меньше в той же пропорции — тот же угловой размер.
 * <pre>
 * S2C: 1 LIST  int n; n × (double x, y, z, float size, float round, int rgb, int atmoArgb, int seed)
 *      2 FADE  int in, int hold, int out — тики: темнеет, держится чёрным (экран загрузки мира спрятан), светлеет
 * </pre>
 */
public final class PlanetsClientModule implements ClientModule {

    private static final int GRID = 56;                                  // квадратов на ребро грани

    /** Квад сетки: 4 вершины × (x,y,z) + 4 цвета ARGB + центр квада (для отсечения атмосферы). */
    /** Квад сетки: 4 вершины × (x,y,z), их нормали × 3, 4 цвета ARGB, центр квада. */
    private record Quad(float[] p, float[] n, int[] c, float cx, float cy, float cz) {}

    /** atmoRgb/atmoA — цвет и яркость свечения; glowK — «край планеты» в долях угла для оболочки атмосферы. */
    private record Planet(double x, double y, double z, float size, List<List<Quad>> lods, List<Quad> atmo,
                          int atmoRgb, float atmoA, float glowK) {}

    private volatile List<Planet> planets = List.of();
    /**
     * 0.2.9: сетки планет (шум fbm по десяткам тысяч вершин — до сотни мс) строятся здесь, в фоне, а не на главном потоке
     * в onMessage (был фриз кадра); главный поток только заливает готовое в видеопамять (render → bake/upload).
     */
    private java.util.concurrent.ExecutorService gen;
    /** Номер последнего списка: достроенный старый (пришёл новый / модуль выгружен) не ставим. */
    private final java.util.concurrent.atomic.AtomicInteger listVer = new java.util.concurrent.atomic.AtomicInteger();

    /** Планета из пакета LIST — до построения сеток. */
    private record Spec(double x, double y, double z, float size, float round, int rgb, int atmo, int seed, String type) {}
    private ClientContext ctx;
    private int fadeIn, fadeHold, fadeOut, fadeT = -1;

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        gen = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Hoka planets");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        ctx.onMessage(this::onMessage);
        ctx.onWorld(this::render);
        ctx.onTick(() -> {
            if (fadeT < 0) return;
            fadeT++;
            if (fadeT == fadeIn + fadeHold) ctx.hideLoadingScreen(false);
            if (fadeT > fadeIn + fadeHold + fadeOut) fadeT = -1;
        });
        ctx.onHud((g, d) -> {
            float a = fadeAlpha(d.getGameTimeDeltaPartialTick(false));
            if (a > 0) g.fill(0, 0, g.guiWidth(), g.guiHeight(), ((int) (a * 255) << 24));
        });
    }

    @Override
    public void disable() {
        if (ctx != null) ctx.hideLoadingScreen(false);
        listVer.incrementAndGet();                                          // недостроенный список — не нужен
        if (gen != null) { gen.shutdownNow(); gen = null; }
        releaseAll();                                                       // видеопамять — назад
    }

    /** Освободить буферы всех планет (смена списка / выгрузка модуля). */
    private void releaseAll() {
        for (Baked b : baked.values()) b.release();
        baked.clear();
    }

    private float fadeAlpha(float pt) {
        if (fadeT < 0) return 0;
        float t = fadeT + pt;
        if (t < fadeIn) { float k = t / Math.max(1, fadeIn); return k * k * (3 - 2 * k); }
        if (t < fadeIn + fadeHold) return 1;
        float k = Math.min(1, (t - fadeIn - fadeHold) / Math.max(1, fadeOut));
        return 1 - k * k * (3 - 2 * k);
    }

    private void onMessage(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int op = in.readByte();
            if (op == 1) {
                int n = in.readInt();
                List<Spec> specs = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    float size = in.readFloat(), round = in.readFloat();
                    int rgb = in.readInt(), atmo = in.readInt();
                    int seed = in.available() >= 4 ? in.readInt() : 1;
                    specs.add(new Spec(x, y, z, size, round, rgb, atmo, seed, "terra"));
                }
                // 0.3: виды планет — после списка (старый сервер их не шлёт)
                for (int i = 0; i < n && in.available() > 0; i++) {
                    Spec o = specs.get(i);
                    specs.set(i, new Spec(o.x(), o.y(), o.z(), o.size(), o.round(), o.rgb(), o.atmo(), o.seed(), in.readUTF()));
                }
                int ver = listVer.incrementAndGet();
                var ex = gen;
                if (ex == null) return;
                try {
                    ex.execute(() -> {
                        if (listVer.get() != ver) return;                   // пока ждали, пришёл новый список
                        List<Planet> l = build(specs);
                        if (listVer.get() == ver) planets = l;              // render заметит новый список сам
                    });
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                }
            } else if (op == 2) {
                fadeIn = in.readInt(); fadeHold = in.readInt(); fadeOut = in.readInt(); fadeT = 0;
                ctx.hideLoadingScreen(true);                               // экран загрузки мира — чёрный
            }
        } catch (Exception ignored) {
        }
    }

    /** Сетки планет из пакета (фоновый поток: шум fbm тяжёлый). */
    private static List<Planet> build(List<Spec> specs) {
        List<Planet> l = new ArrayList<>();
        Spec star = null;
        for (Spec sp : specs) if ("star".equals(sp.type())) { star = sp; break; }
        for (Spec sp : specs) {
            int seed = sp.seed();
            float h = sp.size() / 2, r = Math.min(sp.round(), h);
            boolean isStar = "star".equals(sp.type());
            // свет — от звезды карты (у самой звезды теней нет), без звезды — как раньше
            Vector3f light = star != null && !isStar
                    ? new Vector3f((float) (star.x() - sp.x()), (float) (star.y() - sp.y()), (float) (star.z() - sp.z())).normalize() : LIGHT;
            float at = isStar ? 0.32f : "gas".equals(sp.type()) ? 0.05f : ATMO_T;
            l.add(new Planet(sp.x(), sp.y(), sp.z(), sp.size(),
                    List.of(24, 48, 72).stream().map(g -> shell(h, r, 0, g, isStar ? null : light, (dir, lit) -> Kinds.paint(sp.type(), sp.rgb(), dir, seed, lit))).toList(),
                    shell(h, r, h * at, 32, light, (dir, lit) -> 0xFF000000),
                    sp.atmo() & 0xFFFFFF, (sp.atmo() >>> 24) / 255f,
                    (float) Math.sqrt(1 - Math.pow(r / (r + h * at), 2))));
        }
        return List.copyOf(l);
    }

    // ───────────────────────── сетка ─────────────────────────

    private interface Paint { int color(Vector3f dir, float lit); }

    private static final Vector3f LIGHT = new Vector3f(0.55f, 0.65f, 0.5f).normalize();

    /** Оболочка скруглённого куба (масштаб k от центра): точка грани → внутренний куб + нормаль × скругление. */
    /** Толщина оболочки атмосферы, доля половины стороны. */
    private static final float ATMO_T = 0.06f;

    /** Оболочка скруглённого куба с отступом t блоков по нормали (одинаковая толщина на гранях и углах). */
    private static List<Quad> shell(float h, float r, float t, int GRID, Vector3f light, Paint paint) {
        List<Quad> out = new ArrayList<>();
        float inner = h - r;
        for (int face = 0; face < 6; face++) {
            int axis = face / 2;
            float sign = face % 2 == 0 ? 1 : -1;
            for (int i = 0; i < GRID; i++) for (int j = 0; j < GRID; j++) {
                float[] p = new float[12], nn = new float[12];
                int[] col = new int[4];
                int[][] c = sign > 0 ? new int[][]{{i, j}, {i + 1, j}, {i + 1, j + 1}, {i, j + 1}}
                        : new int[][]{{i, j}, {i, j + 1}, {i + 1, j + 1}, {i + 1, j}};
                boolean any = false;
                for (int q = 0; q < 4; q++) {
                    float u = -h + 2 * h * c[q][0] / GRID, v = -h + 2 * h * c[q][1] / GRID;
                    float[] f = new float[3];
                    f[axis] = sign * h; f[(axis + 1) % 3] = u; f[(axis + 2) % 3] = v;
                    Vector3f in = new Vector3f(clamp(f[0], inner), clamp(f[1], inner), clamp(f[2], inner));
                    Vector3f n = new Vector3f(f[0], f[1], f[2]).sub(in);
                    if (n.lengthSquared() < 1e-6f) { n.set(0); n.setComponent(axis, sign); } else n.normalize();
                    Vector3f pos = new Vector3f(n).mul(r + t).add(in);
                    p[q * 3] = pos.x; p[q * 3 + 1] = pos.y; p[q * 3 + 2] = pos.z;
                    nn[q * 3] = n.x; nn[q * 3 + 1] = n.y; nn[q * 3 + 2] = n.z;
                    float lit = light == null ? 1f : 0.12f + 0.88f * Math.max(0, n.dot(light));          // null — светится сама (звезда)
                    col[q] = paint.color(new Vector3f(pos).normalize(), lit);
                    if ((col[q] >>> 24) != 0) any = true;
                }
                if (any) out.add(new Quad(p, nn, col, (p[0] + p[3] + p[6] + p[9]) / 4, (p[1] + p[4] + p[7] + p[10]) / 4, (p[2] + p[5] + p[8] + p[11]) / 4));
            }
        }
        return out;
    }

    private static float clamp(float v, float m) { return Math.max(-m, Math.min(m, v)); }

    // ───────────────────────── «как Земля» ─────────────────────────

    static int surface(Vector3f d, int seed, float lit) {
        float e = fbm(d.x * 2.1f, d.y * 2.1f, d.z * 2.1f, seed, 5);           // высота: океан / суша
        float m = fbm(d.x * 3.3f + 40, d.y * 3.3f, d.z * 3.3f, seed + 7, 3);   // влажность: пустыни / леса
        float lat = Math.abs(d.y);
        int c;
        if (lat > 0.86f || (lat > 0.74f && e > 0.0f)) c = 0xE8F0F5;            // полярные льды
        else if (e < -0.02f) c = lerp(0x123E78, 0x2A6FB0, smooth(-0.35f, -0.02f, e));   // океан: глубина → мелководье
        else if (e < 0.02f) c = 0xD8C890;                                       // берег
        else if (e > 0.33f) c = lerp(0x6E6250, 0xBFB8AE, smooth(0.33f, 0.5f, e));         // горы, снег на вершинах
        else if (m > 0.12f && lat < 0.45f) c = lerp(0xC9A65A, 0xB08A48, smooth(0.12f, 0.4f, m));   // пустыни
        else c = lerp(0x3E8A3A, 0x2C5E2A, smooth(0.02f, 0.3f, e));             // леса и равнины
        return 0xFF000000 | shade(c, lit);
    }

    /** Облака впечены в цвет поверхности (отдельная оболочка мерцала с поверхностью — не хватает точности глубины). */
    static int withClouds(int surface, Vector3f d, int seed, float lit) {
        float n = fbm(d.x * 3.2f + 100, d.y * 4.2f, d.z * 3.2f, seed + 31, 4);
        float a = smooth(0.05f, 0.35f, n) * 0.85f;
        if (a <= 0.01f) return surface;
        return 0xFF000000 | lerp(surface & 0xFFFFFF, shade(0xFFFFFF, 0.3f + 0.7f * lit), a);
    }

    static int shade(int c, float k) {
        int r = Math.min(255, (int) ((c >> 16 & 255) * k)), g = Math.min(255, (int) ((c >> 8 & 255) * k)), b = Math.min(255, (int) ((c & 255) * k));
        return r << 16 | g << 8 | b;
    }

    static int lerp(int a, int b, float t) {
        int r = (int) ((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = (int) ((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    static float smooth(float a, float b, float x) {
        float t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /** Фрактальный шум (сумма октав value-noise), примерно −0.7…0.7. */
    static float fbm(float x, float y, float z, int seed, int oct) {
        float s = 0, a = 0.5f;
        for (int o = 0; o < oct; o++) { s += a * noise(x, y, z, seed + o * 1013); x *= 2.03f; y *= 2.03f; z *= 2.03f; a *= 0.5f; }
        return s;
    }

    private static float noise(float x, float y, float z, int seed) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        float xf = x - xi, yf = y - yi, zf = z - zi;
        float u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf), w = zf * zf * (3 - 2 * zf);
        float r = 0;
        for (int k = 0; k < 8; k++) {
            int dx = k & 1, dy = k >> 1 & 1, dz = k >> 2;
            float wt = (dx == 1 ? u : 1 - u) * (dy == 1 ? v : 1 - v) * (dz == 1 ? w : 1 - w);
            r += wt * hash(xi + dx, yi + dy, zi + dz, seed);
        }
        return r;
    }

    private static float hash(int x, int y, int z, int seed) {
        int h = x * 374761393 + y * 668265263 + z * 1274126177 + seed * 974634421;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFF) / 32767.5f - 1f;
    }

    // ───────────────────────── отрисовка ─────────────────────────

    private void render(ClientContext.WorldFrame f) {
        List<Planet> l = planets;
        if (l != bakedFor) { releaseAll(); bakedFor = l; }                 // список планет обновился — старые буферы прочь
        if (l.isEmpty()) return;
        Vec3 cam = f.camera();
        PoseStack ps = f.pose();
        float far = Minecraft.getInstance().gameRenderer.getDepthFar();
        frameBk.clear();
        frameM.clear();
        for (Planet p : l) {
            double dx = p.x - cam.x, dy = p.y - cam.y, dz = p.z - cam.z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double reach = d + p.size * 0.5 * 1.05 * 1.75;               // самая дальняя точка планеты
            float s = (float) Math.min(1, far * 0.8 / reach);               // вписать в дальнюю плоскость целиком
            ps.pushPose();
            ps.translate(dx * s, dy * s, dz * s);
            ps.scale(s, s, s);
            Matrix4f m = ps.last().pose();
            double ang = p.size * 0.5 / Math.max(1, d - p.size * 0.5);      // примерный угловой размер → детализация
            int lod = ang > 0.8 ? 2 : ang > 0.25 ? 1 : 0;
            float lx = (float) -dx, ly = (float) -dy, lz = (float) -dz;   // камера в координатах планеты
            Baked bk = baked.get(p), old = bk;
            // вершины (видимая сторона + дымка у края + атмосфера) зависят только от того, откуда смотрим: пересобираем,
            // когда камера сдвинулась заметно (0.2% расстояния ≈ 0.1° по направлению) или сменилась детализация
            // 0.2.10: не чаще ~30 раз в секунду (на скорости корабля сдвиг 0.2% был почти каждый кадр), и запечка
            // пишет в прежние массивы того же Baked (растут только при нехватке) — без нового мусора на каждую пересборку
            float tol = (float) (d * 0.002);
            long now = System.nanoTime();
            if (bk == null || bk.lod != lod || ((Math.abs(bk.lx - lx) > tol || Math.abs(bk.ly - ly) > tol || Math.abs(bk.lz - lz) > tol)
                    && now - bk.at >= 33_000_000L)) {
                bk = bake(p, p.lods.get(lod), lod, lx, ly, lz, old);           // old — те же массивы и буферы видеопамяти
                bk.at = now;
                bk.upload();                                                   // в видеопамять — только при пересборке
                baked.put(p, bk);
            }
            frameBk.add(bk);
            if (frameM.size() >= matPool.size()) matPool.add(new Matrix4f());
            frameM.add(matPool.get(frameM.size()).set(m));
            ps.popPose();
        }
        // сначала поверхности всех планет, потом атмосферы (как было у пакетов): атмосфера не пишет глубину, и
        // поверхность дальней планеты, нарисованная после, затёрла бы дымку ближней
        try {
            for (int i = 0; i < frameBk.size(); i++) {
                Baked bk = frameBk.get(i);
                Matrix4f m = frameM.get(i);
                if (bk.gpu && drawGpu(bk, m, false)) continue;               // на кадр — только поза
                VertexConsumer vc = f.buffers().getBuffer(SURFACE);          // запасной путь — вершинами, как раньше
                float[] sv = bk.surf;
                int[] sc = bk.surfC;
                for (int k = 0, n = bk.surfN; k < n; k++) vc.addVertex(m, sv[k * 3], sv[k * 3 + 1], sv[k * 3 + 2]).setColor(sc[k]);
            }
            for (int i = 0; i < frameBk.size(); i++) {
                Baked bk = frameBk.get(i);
                Matrix4f m = frameM.get(i);
                if (bk.gpu && drawGpu(bk, m, true)) continue;
                VertexConsumer va = f.buffers().getBuffer(GLOW);
                float[] av = bk.atmo;
                int[] ac = bk.atmoC;
                for (int k = 0, n = bk.atmoN; k < n; k++) va.addVertex(m, av[k * 3], av[k * 3 + 1], av[k * 3 + 2]).setColor(ac[k]);
            }
        } finally {
            frameBk.clear();
            frameM.clear();
        }
    }

    /** Планеты этого кадра и их позы (между проходами поверхностей и атмосфер). */
    private final List<Baked> frameBk = new ArrayList<>();
    private final List<Matrix4f> frameM = new ArrayList<>();
    /** Запас матриц под позы кадра (без new Matrix4f на каждую планету каждый кадр). */
    private final List<Matrix4f> matPool = new ArrayList<>();

    /** Поверхность (atmo = false) или атмосфера планеты — из видеопамяти. false — не вышло. */
    private static boolean drawGpu(Baked bk, Matrix4f pose, boolean atmo) {
        if ((atmo ? bk.atmoN : bk.surfN) < 4) return true;                  // нечего рисовать
        try {
            Matrix4f mv = new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix()).mul(pose);
            var transform = com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms()
                    .writeTransform(mv, new org.joml.Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
            var target = Minecraft.getInstance().getMainRenderTarget();
            var color = com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride != null
                    ? com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride : target.getColorTextureView();
            var depth = com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride != null
                    ? com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
            try (var pass = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "Hoka planet", color, java.util.OptionalInt.empty(), depth, java.util.OptionalDouble.empty())) {
                com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transform);
                var idx = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
                int ic = (atmo ? bk.atmoN : bk.surfN) / 4 * 6;
                pass.setPipeline(atmo ? GLOW_PIPE : SURFACE_PIPE);
                pass.setVertexBuffer(0, atmo ? bk.vbAtmo : bk.vbSurf);
                pass.setIndexBuffer(idx.getBuffer(ic), idx.type());
                pass.drawIndexed(0, 0, ic, 1);
            }
            return true;
        } catch (Exception e) {
            bk.gpu = false;                                                    // дальше эта запечка — вершинами
            return false;
        }
    }

    /** Готовые вершины планеты для этой точки обзора (планета → кэш; пересборка — только при заметном сдвиге камеры). */
    private static final class Baked {
        int lod, surfN, atmoN;
        float lx, ly, lz;
        long at;                                                           // когда запечено (System.nanoTime)
        float[] surf, atmo;
        int[] surfC, atmoC;
        com.mojang.blaze3d.buffers.GpuBuffer vbSurf, vbAtmo;               // видеопамять (переживает пересборку)
        boolean gpu;                                                       // залито — рисуем из видеопамяти

        /** Залить вершины в видеопамять; буфер растёт только при нехватке места. */
        void upload() {
            try {
                vbSurf = put(vbSurf, surf, surfC, surfN, "Hoka planet");
                vbAtmo = put(vbAtmo, atmo, atmoC, atmoN, "Hoka planet glow");
                gpu = true;
            } catch (Exception e) {
                release();                                                     // не вышло — рисуем вершинами
            }
        }

        void release() {
            if (vbSurf != null) vbSurf.close();
            if (vbAtmo != null) vbAtmo.close();
            vbSurf = vbAtmo = null;
            gpu = false;
        }

        /** POSITION_COLOR: 3 float + 4 байта RGBA = 16 байт на вершину. */
        private static com.mojang.blaze3d.buffers.GpuBuffer put(com.mojang.blaze3d.buffers.GpuBuffer vb, float[] v, int[] c, int n, String name) {
            long bytes = Math.max(16, (long) n * 16);
            java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc((int) bytes);
            try {
                for (int k = 0; k < n; k++) {
                    int argb = c[k];
                    buf.putFloat(v[k * 3]).putFloat(v[k * 3 + 1]).putFloat(v[k * 3 + 2])
                            .put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
                }
                if (n == 0) buf.putLong(0).putLong(0);
                buf.flip();
                var dev = com.mojang.blaze3d.systems.RenderSystem.getDevice();
                if (vb == null || vb.isClosed() || vb.size() < bytes) {
                    if (vb != null) vb.close();
                    // запас 25%, чтобы при повороте камеры не пересоздавать буфер
                    vb = dev.createBuffer(() -> name, com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST, bytes + bytes / 4);
                }
                try {
                    dev.createCommandEncoder().writeToBuffer(vb.slice(0, buf.remaining()), buf);
                } catch (RuntimeException e) {
                    vb.close();                                                // не терять буфер при ошибке заливки
                    throw e;
                }
                return vb;
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(buf);
            }
        }
    }

    private final java.util.Map<Planet, Baked> baked = new java.util.IdentityHashMap<>();
    private List<Planet> bakedFor;

    private static Baked bake(Planet p, List<Quad> surface, int lod, float lx, float ly, float lz, Baked reuse) {
        Baked b = reuse != null ? reuse : new Baked();
        b.lod = lod; b.lx = lx; b.ly = ly; b.lz = lz;
        if (b.surf == null || b.surf.length < surface.size() * 12) b.surf = new float[surface.size() * 12];
        if (b.surfC == null || b.surfC.length < surface.size() * 4) b.surfC = new int[surface.size() * 4];
        int n = 0;
        // поверхность: у края (взгляд по касательной) подмешиваем цвет атмосферы — дымка у горизонта планеты
        for (Quad q : surface) {
            float qx = q.cx - lx, qy = q.cy - ly, qz = q.cz - lz;
            float qinv = 1f / (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
            // настоящая нормаль квада — среднее нормалей вершин (направление от центра у краёв граней врёт)
            float nx = q.n[0] + q.n[3] + q.n[6] + q.n[9], ny = q.n[1] + q.n[4] + q.n[7] + q.n[10], nz = q.n[2] + q.n[5] + q.n[8] + q.n[11];
            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            float qf = -(nx * qx + ny * qy + nz * qz) * qinv / Math.max(1e-4f, nl);
            if (qf < -0.08f) continue;                                        // точно обратная сторона — не строим
            for (int k = 0; k < 4; k++) {
                float px = q.p[k * 3], py = q.p[k * 3 + 1], pz = q.p[k * 3 + 2];
                int c = q.c[k];
                if (qf <= 0.6f) {                                             // не прямо на нас — дымка у края
                    float vx = px - lx, vy = py - ly, vz = pz - lz;
                    float inv = 1f / (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                    float facing = -(q.n[k * 3] * vx + q.n[k * 3 + 1] * vy + q.n[k * 3 + 2] * vz) * inv;   // 1 — прямо на нас
                    float edge = 1 - Math.max(0, Math.min(1, facing));
                    float mix = p.atmoA * 1.4f * edge * edge * edge;
                    if (mix > 0.01f) c = 0xFF000000 | lerp(c & 0xFFFFFF, p.atmoRgb, Math.min(0.8f, mix));
                }
                b.surf[n * 3] = px; b.surf[n * 3 + 1] = py; b.surf[n * 3 + 2] = pz;
                b.surfC[n++] = c;
            }
        }
        b.surfN = n;
        // атмосфера: задняя половина оболочки; у края планеты ярко, к внешнему краю оболочки тает до нуля
        if (b.atmo == null || b.atmo.length < p.atmo.size() * 12) b.atmo = new float[p.atmo.size() * 12];
        if (b.atmoC == null || b.atmoC.length < p.atmo.size() * 4) b.atmoC = new int[p.atmo.size() * 4];
        n = 0;
        for (Quad q : p.atmo) {
            if (q.cx * (q.cx - lx) + q.cy * (q.cy - ly) + q.cz * (q.cz - lz) <= 0) continue;   // лицом к камере — пропуск
            for (int k = 0; k < 4; k++) {
                float px = q.p[k * 3], py = q.p[k * 3 + 1], pz = q.p[k * 3 + 2];
                float vx = px - lx, vy = py - ly, vz = pz - lz;
                float inv = 1f / (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                float back = (q.n[k * 3] * vx + q.n[k * 3 + 1] * vy + q.n[k * 3 + 2] * vz) * inv;   // 0 — внешний край
                float a = smooth(0, p.glowK, back);
                int alpha = (int) (Math.min(1, p.atmoA * 1.6f) * a * a * 255);
                b.atmo[n * 3] = px; b.atmo[n * 3 + 1] = py; b.atmo[n * 3 + 2] = pz;
                b.atmoC[n++] = alpha << 24 | p.atmoRgb;
            }
        }
        b.atmoN = n;
        return b;
    }

    // Свои типы отрисовки: у ванильного debugQuads нет записи глубины и есть сортировка всех квадов каждый кадр (дорого).
    private static final com.mojang.blaze3d.pipeline.RenderPipeline SURFACE_PIPE = com.mojang.blaze3d.pipeline.RenderPipeline
            .builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath("hoka_odyssey", "pipeline/planet"))
            .withVertexShader("core/position_color").withFragmentShader("core/position_color")
            .withoutBlend().withDepthWrite(true).withCull(true)
            .withVertexFormat(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)
            .build();
    private static final com.mojang.blaze3d.pipeline.RenderPipeline GLOW_PIPE = com.mojang.blaze3d.pipeline.RenderPipeline
            .builder(net.minecraft.client.renderer.RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath("hoka_odyssey", "pipeline/planet_glow"))
            .withVertexShader("core/position_color").withFragmentShader("core/position_color")
            .withBlend(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT).withDepthWrite(false).withCull(false)
            .withVertexFormat(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)
            .build();
    private static final net.minecraft.client.renderer.rendertype.RenderType SURFACE = net.minecraft.client.renderer.rendertype.RenderType.create(
            "hoka_planet", net.minecraft.client.renderer.rendertype.RenderSetup.builder(SURFACE_PIPE).createRenderSetup());
    private static final net.minecraft.client.renderer.rendertype.RenderType GLOW = net.minecraft.client.renderer.rendertype.RenderType.create(
            "hoka_planet_glow", net.minecraft.client.renderer.rendertype.RenderSetup.builder(GLOW_PIPE).createRenderSetup());

    private static void draw(VertexConsumer vc, Matrix4f m, List<Quad> quads) {
        for (Quad q : quads)
            for (int k = 0; k < 4; k++) vc.addVertex(m, q.p[k * 3], q.p[k * 3 + 1], q.p[k * 3 + 2]).setColor(q.c[k]);
    }
}
