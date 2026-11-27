package ink.hoka.odyssey.client.lod;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import ink.hoka.odyssey.gen.PlanetGenerator;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 0.74 Дальняя прорисовка тел (космос): астероиды — не рельеф, плитки высот тут не годятся. Генератор отдаёт рамки тел
 * ({@link PlanetGenerator#lodBodies}) и блок в точке ({@link PlanetGenerator#lodSample}); по ним в фоне строится грубая
 * объёмная сетка тела (ячейка 1…N блоков — тело ~28 ячеек поперёк), грани с текстурами блоков. Рисуется в проходе LOD
 * ({@link LodRenderer}) без «ближнего круга»: ванильные блоки ложатся поверх, а где куски тела ещё не загружены
 * (дальше дальности по высоте или прорисовки), видна сетка — без дыр. Тело целиком в загруженной области — не рисуем
 * (иначе сквозь прокопанное светилась бы сетка).
 */
final class SpaceLod {

    private SpaceLod() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey");

    static final class Body {
        final double x, y, z, ext;
        int x0, y0, z0, size;
        final java.util.concurrent.atomic.AtomicReference<ByteBuffer> pending = new java.util.concurrent.atomic.AtomicReference<>();
        volatile boolean built, building;
        /** Детализация, с которой построена/строится сетка: 0 — полная, 1 — ½, 2 — ¼ ячеек. */
        volatile int level = -1;
        GpuBuffer vb;
        int quads;
        long seen;

        Body(double x, double y, double z, double ext) { this.x = x; this.y = y; this.z = z; this.ext = ext; }
    }

    static final Map<Long, Body> BODIES = new ConcurrentHashMap<>();
    private static ExecutorService pool;
    private static volatile boolean querying;
    private static PlanetGenerator gen;
    private static long genKey, lastQuery;
    private static int settingsVer = -1;
    static volatile boolean supported;

    private static ExecutorService pool() {
        if (pool == null) pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Hoka LOD bodies");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        return pool;
    }

    /** Раз в секунду: тела в радиусе dist вокруг камеры; новые — строить. На потоке отрисовки. */
    static void update(PlanetGenerator g, long key, double cx, double cy, double cz, int dist, long now) {
        if (g != gen || key != genKey || settingsVer != ink.hoka.odyssey.client.LodSetting.version()) {
            clear(); gen = g; genKey = key; supported = true; settingsVer = ink.hoka.odyssey.client.LodSetting.version();
        }
        if (!supported || querying || now - lastQuery < 1000) return;
        lastQuery = now;
        querying = true;
        int x0 = (int) cx - dist, y0 = (int) cy - dist, z0 = (int) cz - dist, x1 = (int) cx + dist, y1 = (int) cy + dist, z1 = (int) cz + dist;
        pool().execute(() -> {
            try {
                double[] b = g.lodBodies(x0, y0, z0, x1, y1, z1);
                if (b == null) { supported = false; return; }
                for (int i = 0; i + 3 < b.length; i += 4) {
                    long k = key(b[i], b[i + 1], b[i + 2]);
                    Body body = BODIES.get(k);
                    if (body == null) {
                        body = new Body(b[i], b[i + 1], b[i + 2], b[i + 3]);
                        BODIES.put(k, body);
                    }
                    body.seen = now;
                    // детализация по расстоянию: ближе 1500 — полная, до 3500 — ½, дальше — ¼; сменилась — перестроить
                    double dx = body.x - cx, dy = body.y - cy, dz = body.z - cz, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    int want = d < 1500 ? 0 : d < 3500 ? 1 : 2;
                    if (want != body.level && !body.building) {
                        body.building = true;
                        body.level = want;
                        Body fb = body;
                        pool().execute(() -> build(g, fb, want));
                    }
                }
            } catch (Throwable e) {
                LOG.warn("LOD тел: {}", e.toString());
            } finally {
                querying = false;
            }
        });
    }

    private static long key(double x, double y, double z) {
        return (long) Math.floor(x) * 73856093L ^ (long) Math.floor(y) * 19349663L ^ (long) Math.floor(z) * 83492791L;
    }

    /** Сетка тела: ячейки шага s, грань — где сосед пуст. */
    private static void build(PlanetGenerator g, Body b, int level) {
        try {
            int cells = Math.max(6, ink.hoka.odyssey.client.LodSetting.bodyCells() >> level);
            int s = Math.max(1, (int) Math.ceil(b.ext * 2 / cells));
            int x0 = (int) Math.floor(b.x - b.ext), y0 = (int) Math.floor(b.y - b.ext), z0 = (int) Math.floor(b.z - b.ext);
            int n = (int) Math.ceil(b.ext * 2 / s) + 1;
            int[] st = new int[n * n * n];
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) for (int k = 0; k < n; k++)
                st[(j * n + k) * n + i] = Math.max(0, g.lodSample(x0 + i * s + s / 2, y0 + j * s + s / 2, z0 + k * s + s / 2));
            LodQuads q = new LodQuads(256);
            int[][] dir = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
            for (int j = 0; j < n; j++) for (int k = 0; k < n; k++) for (int i = 0; i < n; i++) {
                int id = st[(j * n + k) * n + i];
                if (id == 0) continue;
                LodBlocks.Info info = LodBlocks.info(id);
                if (info.kind != LodBlocks.SOLID || info.faces == null) continue;
                float cx = i * s, cy = j * s, cz = k * s;
                for (int f = 0; f < 6; f++) {
                    int ni = i + dir[f][0], nj = j + dir[f][1], nk = k + dir[f][2];
                    boolean open = ni < 0 || nj < 0 || nk < 0 || ni >= n || nj >= n || nk >= n || st[(nj * n + nk) * n + ni] == 0;
                    if (!open) continue;
                    LodBlocks.Face fc = info.faces[f];
                    if (fc == null) continue;
                    float sh = LodQuads.SHADE[f] * 0.85f;                         // космос: свет «не ниже 65%» — чуть темнее неба 15
                    float r = (fc.tint() == LodBlocks.TINT_NONE ? 1 : 0.8f) * sh;
                    face(q, f, cx, cy, cz, s, fc, r);
                }
            }
            b.x0 = x0; b.y0 = y0; b.z0 = z0; b.size = n * s;
            ByteBuffer nb = q.finish();
            ByteBuffer old = b.pending.getAndSet(nb);
            if (old != null) MemoryUtil.memFree(old);
            b.built = true;
        } catch (Throwable e) {
            LOG.warn("LOD тела: {}", e.toString());
            b.built = true;
        } finally {
            b.building = false;
        }
    }

    private static void face(LodQuads q, int f, float x, float y, float z, float s, LodBlocks.Face fc, float k) {
        float X = x + s, Y = y + s, Z = z + s;
        switch (f) {
            case 0 -> q.quad(x, y, z, X, y, z, X, y, Z, x, y, Z, fc, f, k, k, k, 1);
            case 1 -> q.quad(x, Y, z, x, Y, Z, X, Y, Z, X, Y, z, fc, f, k, k, k, 1);
            case 2 -> q.quad(x, y, z, x, Y, z, X, Y, z, X, y, z, fc, f, k, k, k, 1);
            case 3 -> q.quad(x, y, Z, X, y, Z, X, Y, Z, x, Y, Z, fc, f, k, k, k, 1);
            case 4 -> q.quad(x, y, z, x, y, Z, x, Y, Z, x, Y, z, fc, f, k, k, k, 1);
            default -> q.quad(X, y, z, X, Y, z, X, Y, Z, X, y, Z, fc, f, k, k, k, 1);
        }
    }

    /** Заливка готовых сеток (поток отрисовки), не больше 6 за кадр; давно невидимые — освободить. */
    static void uploads(long now, double cx, double cy, double cz, int dist) {
        int up = 0;
        double far = dist * 1.3 + 256;
        for (var it = BODIES.values().iterator(); it.hasNext(); ) {
            Body b = it.next();
            ByteBuffer p = up < 6 ? b.pending.getAndSet(null) : null;
            if (p != null) {
                if (b.vb != null) b.vb.close();                                  // перестроили с другой детализацией
                b.quads = p.remaining() / LodQuads.QUAD;
                b.vb = RenderSystem.getDevice().createBuffer(() -> "Hoka LOD body", GpuBuffer.USAGE_VERTEX, p);
                MemoryUtil.memFree(p);
                up++;
            }
            double dx = b.x - cx, dy = b.y - cy, dz = b.z - cz;
            if (dx * dx + dy * dy + dz * dz > far * far && now - b.seen > 5000) {
                if (b.vb != null) b.vb.close();
                ByteBuffer q = b.pending.getAndSet(null);
                if (q != null) MemoryUtil.memFree(q);
                it.remove();
            }
        }
    }

    /**
     * Тела для отрисовки: построенные, в дальности, НЕ целиком в загруженной области (горизонталь — прорисовка rdBlocks,
     * вертикаль — дальность по высоте vBlocks).
     */
    static List<Body> visible(double cx, double cy, double cz, int dist, int rdBlocks, int vBlocks) {
        List<Body> l = new ArrayList<>();
        for (Body b : BODIES.values()) {
            if (b.vb == null || b.quads == 0) continue;
            double dx = b.x - cx, dy = b.y - cy, dz = b.z - cz;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > (double) dist * dist) continue;
            if (b.ext * b.ext < d2 * 0.004 * 0.004) continue;                     // меньше ~пикселя-двух — не рисуем
            double hor = Math.max(Math.abs(dx), Math.abs(dz)) + b.ext, ver = Math.abs(dy) + b.ext;
            if (hor < rdBlocks - 24 && ver < vBlocks - 24) continue;              // целиком загружено — настоящие блоки
            l.add(b);
        }
        return l;
    }

    static void clear() {
        for (Body b : BODIES.values()) {
            if (b.vb != null) b.vb.close();
            ByteBuffer q = b.pending.getAndSet(null);
            if (q != null) MemoryUtil.memFree(q);
        }
        BODIES.clear();
        supported = true;
    }

    static int count() { return BODIES.size(); }

    private static PlanetGenerator probed;
    private static boolean probedOk;

    /** Генератор умеет тела (космос): тогда они рисуются и при выключенной дальней прорисовке. */
    static boolean supports(PlanetGenerator g) {
        if (g == null) return false;
        if (g != probed) {
            probed = g;
            try { probedOk = g.lodBodies(0, 0, 0, 0, 0, 0) != null; } catch (Throwable e) { probedOk = false; }
        }
        return probedOk;
    }
}
