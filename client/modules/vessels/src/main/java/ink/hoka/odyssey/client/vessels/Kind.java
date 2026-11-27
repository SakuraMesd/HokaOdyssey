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
    /** Треугольники в системе корабля (по 9 чисел); null — модель ещё не пришла. */
    volatile float[] tris;
    /** Середины клеток корпуса (система корабля, по 3 числа) — столкновения с миром у пилота. */
    volatile float[] hull = new float[0];
    /** Габарит клеток корпуса (система корабля) — грубая проверка «вокруг пусто». */
    final float[] hullLo = new float[3], hullHi = new float[3];

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
    }

    /** Система модели (пиксели) → система корабля: поворот turn (без масштаба — его даёт ModelHandle). */
    Quaternionf modelTurn() { return new Quaternionf().rotationY((float) Math.toRadians(turn)); }

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
        tris = t;
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
