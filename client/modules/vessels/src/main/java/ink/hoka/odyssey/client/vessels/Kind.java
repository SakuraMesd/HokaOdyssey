package ink.hoka.odyssey.client.vessels;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Вид корабля (от сервера, пакет KINDS) и его форма из модели. Система корабля — как на сервере (VesselKind):
 * начало — середина габарита модели (center, пиксели), оси модели повёрнуты на turn вокруг вертикали (нос +Z,
 * верх +Y, левый борт +X), единица — блок.
 */
final class Kind {

    final String id;
    final String model;
    final float scale, turn;
    final float[] center;   // пиксели
    final double[] half;    // блоки, система корабля
    final double[] seat;    // глаза пилота, система корабля
    final double speed, boostSpeed, accel, turnSpeed, rollSpeed;
    /** Прочность корпуса (hull.hp вида) и зоны повреждений (как у сервера: Zones, начало носа — от сервера). */
    final double hp;
    final Zones.Params zones;
    /** Начало носа: от сервера (nose-from), иначе — как сервер, по клеткам корпуса, когда придёт модель. */
    private final boolean noseGiven;
    volatile double noseFrom;
    /** Зона каждого треугольника (по середине, проекция XZ) — силуэт в HUD красится по зонам; null — модели нет. */
    volatile int[] triZone;
    /** Треугольники в системе корабля (по 9 чисел); null — модель ещё не пришла. */
    volatile float[] tris;
    /** Середины клеток корпуса (система корабля, по 3 числа) — столкновения с миром у пилота. */
    volatile float[] hull = new float[0];
    /** Габарит клеток корпуса (система корабля) — грубая проверка «вокруг пусто». */
    final float[] hullLo = new float[3], hullHi = new float[3];
    /**
     * Мелкие клетки корпуса — по полблока (ключ {@link #fineKey} индексов floor(x·2)…): твёрдый корпус для игроков
     * (стоять, упираться — ступенька 0.5 меньше шага игрока 0.6, по корпусу можно ходить). Те же, что на сервере
     * (VesselKind.fine).
     */
    volatile java.util.Set<Long> fine = java.util.Set.of();

    Kind(String id, JsonObject o) {
        this.id = id;
        this.model = o.get("model").getAsString();
        this.scale = o.has("scale") ? o.get("scale").getAsFloat() : 1;
        this.turn = o.has("turn") ? o.get("turn").getAsFloat() : 0;
        this.center = floats(o.getAsJsonArray("center"));
        this.half = doubles(o.getAsJsonArray("half"));
        this.seat = doubles(o.getAsJsonArray("seat"));
        this.speed = num(o, "speed", 30);
        this.boostSpeed = num(o, "boost-speed", 50);
        this.accel = num(o, "accel", 15);
        this.turnSpeed = num(o, "turn-speed", 90);
        this.rollSpeed = num(o, "roll-speed", 120);
        this.hp = num(o, "hp", 200);
        JsonObject z = o.has("zones") && o.get("zones").isJsonObject() ? o.getAsJsonObject("zones") : new JsonObject();
        this.zones = new Zones.Params(num(z, "side", Zones.Params.DEFAULT.side()), num(z, "nose", Zones.Params.DEFAULT.nose()),
                num(z, "tail", Zones.Params.DEFAULT.tail()));
        this.noseGiven = z.has("nose-from");
        this.noseFrom = num(z, "nose-from", half[2] - zones.nose() * 2 * half[2]);
    }

    /** Зоны треугольников (система корабля, по 9 чисел): по середине треугольника. */
    int[] zonesOf(float[] t) {
        int[] out = new int[t.length / 9];
        for (int i = 0; i < out.length; i++) {
            int b = i * 9;
            double x = (t[b] + t[b + 3] + t[b + 6]) / 3, zz = (t[b + 2] + t[b + 5] + t[b + 8]) / 3;
            out[i] = Zones.zoneOf(x, zz, half, zones, noseFrom);
        }
        return out;
    }

    /** Система модели (пиксели) → система корабля: поворот turn (без масштаба — его даёт ModelHandle). */
    Quaternionf modelTurn() { return new Quaternionf().rotationY((float) Math.toRadians(turn)); }

    /**
     * Поза корабля (середина модели в мире, поворот «система корабля → мир») → поза модели для ModelHandle: начало
     * Blockbench (0,0,0) и поворот модели (с turn). Та же формула, что у {@link #shape}, — отрисовка, луч и корпус
     * считают нос одинаково.
     */
    void modelPose(org.joml.Vector3d pos, Quaternionf rot) {
        rot.mul(modelTurn());
        float s = scale / 16f;
        Vector3f c = rot.transform(new Vector3f(-center[0] * s, -center[1] * s, -center[2] * s));
        pos.add(c.x, c.y, c.z);
    }

    /** Радиус корабля (до угла коробки), блоки. */
    double radius() { return Math.sqrt(half[0] * half[0] + half[1] * half[1] + half[2] * half[2]); }

    /** Модель пришла (или поменялась): треугольники (пиксели, поза по умолчанию) → система корабля и клетки корпуса. */
    void shape(float[] modelTris) {
        float[] t = new float[modelTris.length];
        Quaternionf r = modelTurn();
        Vector3f v = new Vector3f();
        float k = scale / 16f;
        for (int i = 0; i + 2 < modelTris.length; i += 3) {
            r.transform(v.set((modelTris[i] - center[0]) * k, (modelTris[i + 1] - center[1]) * k, (modelTris[i + 2] - center[2]) * k));
            t[i] = v.x;
            t[i + 1] = v.y;
            t[i + 2] = v.z;
        }
        hull = hullOf(t, hullLo, hullHi);
        fine = fineOf(t);
        if (!noseGiven) {                                                       // старый сервер — посчитать как он (середины клеток)
            float[] h = hull;
            double[] xz = new double[h.length / 3 * 2];
            for (int i = 0, j = 0; i + 2 < h.length; i += 3, j += 2) { xz[j] = h[i]; xz[j + 1] = h[i + 2]; }
            noseFrom = Zones.noseFrom(xz, half, zones);
        }
        triZone = zonesOf(t);
        tris = t;
    }

    static long fineKey(int ix, int iy, int iz) {
        return ((long) (ix & 0x1FFFFF) << 42) | ((long) (iy & 0x1FFFFF) << 21) | (iz & 0x1FFFFF);
    }

    /** Мелкая клетка с точкой (система корабля) — корпус? */
    boolean fineAt(double x, double y, double z) {
        return fine.contains(fineKey((int) Math.floor(x * 2), (int) Math.floor(y * 2), (int) Math.floor(z * 2)));
    }

    /** Ноги (система корабля) на корпусе: под ступнями (0.6 × 0.6) в пределах below вниз есть мелкая клетка. */
    boolean standsOn(double x, double y, double z, double below) {
        for (double dx : new double[]{-0.29, 0, 0.29}) for (double dz : new double[]{-0.29, 0, 0.29}) {
            for (double d = 0.05; d <= below + 1e-9; d += 0.25) if (fineAt(x + dx, y - d, z + dz)) return true;
        }
        return false;
    }

    /** Есть ли мелкие клетки в коробке (система корабля). */
    boolean fineIn(double x0, double y0, double z0, double x1, double y1, double z1) {
        java.util.Set<Long> f = fine;
        if (f.isEmpty()) return false;
        int ax = (int) Math.floor(x0 * 2), ay = (int) Math.floor(y0 * 2), az = (int) Math.floor(z0 * 2);
        int bx = (int) Math.floor(x1 * 2), by = (int) Math.floor(y1 * 2), bz = (int) Math.floor(z1 * 2);
        if ((long) (bx - ax + 1) * (by - ay + 1) * (bz - az + 1) > 4096) return false;
        for (int x = ax; x <= bx; x++) for (int y = ay; y <= by; y++) for (int z = az; z <= bz; z++)
            if (f.contains(fineKey(x, y, z))) return true;
        return false;
    }

    /** Клетки по полблока, которых касаются треугольники (точки по поверхности через ~1/6 блока). */
    static java.util.Set<Long> fineOf(float[] t) {
        java.util.Set<Long> out = new HashSet<>();
        for (int i = 0; i + 8 < t.length; i += 9) {
            double len = Math.max(dist(t, i, i + 3), Math.max(dist(t, i + 3, i + 6), dist(t, i, i + 6)));
            int n = Math.max(1, (int) Math.ceil(len * 6));
            for (int u = 0; u <= n; u++) for (int w = 0; u + w <= n; w++) {
                double fu = (double) u / n, fv = (double) w / n, fw = 1 - fu - fv;
                out.add(fineKey((int) Math.floor((t[i] * fw + t[i + 3] * fu + t[i + 6] * fv) * 2),
                        (int) Math.floor((t[i + 1] * fw + t[i + 4] * fu + t[i + 7] * fv) * 2),
                        (int) Math.floor((t[i + 2] * fw + t[i + 5] * fu + t[i + 8] * fv) * 2)));
            }
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    /** Клетки, которых касаются треугольники (точки по поверхности через ~1/3 блока) → их середины. */
    static float[] hullOf(float[] t, float[] lo, float[] hi) {
        Set<Long> seen = new HashSet<>();
        List<int[]> cells = new ArrayList<>();
        for (int i = 0; i + 8 < t.length; i += 9) {
            double len = Math.max(dist(t, i, i + 3), Math.max(dist(t, i + 3, i + 6), dist(t, i, i + 6)));
            int n = Math.max(1, (int) Math.ceil(len * 3));
            for (int u = 0; u <= n; u++) for (int w = 0; u + w <= n; w++) {
                double fu = (double) u / n, fv = (double) w / n, fw = 1 - fu - fv;
                int x = (int) Math.floor(t[i] * fw + t[i + 3] * fu + t[i + 6] * fv);
                int y = (int) Math.floor(t[i + 1] * fw + t[i + 4] * fu + t[i + 7] * fv);
                int z = (int) Math.floor(t[i + 2] * fw + t[i + 5] * fu + t[i + 8] * fv);
                if (seen.add(((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF))) cells.add(new int[]{x, y, z});
            }
        }
        float[] out = new float[cells.size() * 3];
        lo[0] = lo[1] = lo[2] = Float.MAX_VALUE;
        hi[0] = hi[1] = hi[2] = -Float.MAX_VALUE;
        for (int i = 0; i < cells.size(); i++) for (int a = 0; a < 3; a++) {
            float c = cells.get(i)[a] + 0.5f;
            out[i * 3 + a] = c;
            lo[a] = Math.min(lo[a], c);
            hi[a] = Math.max(hi[a], c);
        }
        if (cells.isEmpty()) { lo[0] = lo[1] = lo[2] = hi[0] = hi[1] = hi[2] = 0; }
        return out;
    }

    private static double dist(float[] t, int a, int b) {
        double dx = t[a] - t[b], dy = t[a + 1] - t[b + 1], dz = t[a + 2] - t[b + 2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double num(JsonObject o, String k, double def) {
        try { return o.has(k) ? o.get(k).getAsDouble() : def; } catch (Exception e) { return def; }
    }

    private static float[] floats(JsonArray a) {
        return a == null || a.size() < 3 ? new float[3] : new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    private static double[] doubles(JsonArray a) {
        return a == null || a.size() < 3 ? new double[]{1, 1, 1} : new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }
}
