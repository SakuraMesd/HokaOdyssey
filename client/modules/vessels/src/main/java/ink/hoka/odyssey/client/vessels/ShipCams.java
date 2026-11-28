package ink.hoka.odyssey.client.vessels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Камеры корабля (vessels-5, S2C CAMERAS / CAMEDIT): первое лицо и виды F5 в системе корабля (нос +Z, верх +Y, левый
 * борт +X; курс + — вправо, наклон + — вниз). Здесь же — перевод мир ↔ система корабля и перебор F5 (без классов игры:
 * тесты).
 */
final class ShipCams {

    /** Камера: точка, углы, FOV (0 — как у игрока), look — «смотреть на» (null — по углам), smooth (NaN — по умолчанию). */
    record Cam(double x, double y, double z, double yaw, double pitch, double fov, double[] look, double smooth) {

        Cam withPos(double nx, double ny, double nz) { return new Cam(nx, ny, nz, yaw, pitch, fov, look, smooth); }

        Cam withAngles(double ny, double np) { return new Cam(x, y, z, ny, np, fov, look, smooth); }

        JsonObject json() {
            JsonObject o = new JsonObject();
            JsonArray p = new JsonArray();
            p.add(r3(x)); p.add(r3(y)); p.add(r3(z));
            o.add("pos", p);
            o.addProperty("yaw", r3(yaw));
            o.addProperty("pitch", r3(pitch));
            if (fov > 0) o.addProperty("fov", r3(fov));
            if (look != null) {
                JsonArray l = new JsonArray();
                for (double d : look) l.add(r3(d));
                o.add("look", l);
            }
            if (!Double.isNaN(smooth)) o.addProperty("smooth", r3(smooth));
            return o;
        }

        static Cam read(JsonElement e) {
            if (!(e instanceof JsonObject o) || !(o.get("pos") instanceof JsonArray p) || p.size() != 3) return null;
            try {
                double[] look = null;
                if (o.get("look") instanceof JsonArray l && l.size() == 3) look = new double[]{l.get(0).getAsDouble(), l.get(1).getAsDouble(), l.get(2).getAsDouble()};
                return new Cam(p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble(), num(o, "yaw", 0), num(o, "pitch", 0),
                        num(o, "fov", 0), look, num(o, "smooth", Double.NaN));
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }

    /** Набор камер: первое лицо (null — место пилота) и виды F5 (пусто — «сзади», как раньше). */
    record Set(Cam first, List<Cam> f5) {
        static final Set EMPTY = new Set(null, List.of());

        Set { f5 = List.copyOf(f5); }

        static Set read(JsonElement e) {
            if (!(e instanceof JsonObject o)) return EMPTY;
            Cam first = Cam.read(o.get("first"));
            List<Cam> l = new ArrayList<>();
            if (o.get("f5") instanceof JsonArray a) for (JsonElement x : a) { Cam c = Cam.read(x); if (c != null) l.add(c); }
            return new Set(first, l);
        }
    }

    private ShipCams() {}

    private static double num(JsonObject o, String k, double def) { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def; }

    private static double r3(double v) { return Math.round(v * 1000) / 1000.0; }

    // ───────────────────────── перебор F5 ─────────────────────────

    /** F5: первое лицо (0) → вид 1 → … → вид n → первое лицо. Своих видов нет — один вид «сзади» (как раньше). */
    static int nextView(int view, int customViews) {
        int n = Math.max(1, customViews);
        return view >= n ? 0 : view + 1;
    }

    // ───────────────────────── мир ↔ система корабля ─────────────────────────

    /** Точка корабля → мир: середина корабля pos, поворот «корабль → мир» rot. */
    static Vector3d toWorld(double lx, double ly, double lz, Vector3d pos, Quaternionf rot) {
        Vector3f v = rot.transform(new Vector3f((float) lx, (float) ly, (float) lz));
        return new Vector3d(pos.x + v.x, pos.y + v.y, pos.z + v.z);
    }

    /** Точка мира → система корабля. */
    static Vector3d toLocal(double wx, double wy, double wz, Vector3d pos, Quaternionf rot) {
        Vector3f v = new Quaternionf(rot).conjugate().transform(new Vector3f((float) (wx - pos.x), (float) (wy - pos.y), (float) (wz - pos.z)));
        return new Vector3d(v.x, v.y, v.z);
    }

    /** Поворот взгляда в системе корабля: курс (+ вправо), наклон (+ вниз) → кватернион (нос взгляда +Z). */
    static Quaternionf orient(double yaw, double pitch) {
        return new Quaternionf().rotationY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch));
    }

    /** Взгляд камеры в мире: поворот корабля × поворот камеры в корабле. */
    static Quaternionf viewWorld(Quaternionf shipRot, double yaw, double pitch) { return new Quaternionf(shipRot).mul(orient(yaw, pitch)); }

    /** Направление взгляда (мир) → {курс, наклон} в системе корабля. */
    static double[] anglesLocal(Vector3f dirWorld, Quaternionf shipRot) {
        Vector3f d = new Quaternionf(shipRot).conjugate().transform(new Vector3f(dirWorld)).normalize();
        double pitch = Math.toDegrees(Math.asin(Math.clamp(-d.y, -1f, 1f)));
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        return new double[]{yaw, pitch};
    }

    /** Углы {курс, наклон}, чтобы из точки from (система корабля) смотреть на точку to. */
    static double[] lookAt(double fx, double fy, double fz, double[] to) {
        double dx = to[0] - fx, dy = to[1] - fy, dz = to[2] - fz, h = Math.sqrt(dx * dx + dz * dz);
        if (h < 1e-9 && Math.abs(dy) < 1e-9) return new double[]{0, 0};
        return new double[]{Math.toDegrees(Math.atan2(-dx, dz)), Math.toDegrees(Math.atan2(-dy, h))};
    }

    /** Углы камеры c: по look (если задано) или свои. */
    static double[] angles(Cam c) { return c.look() != null ? lookAt(c.x(), c.y(), c.z(), c.look()) : new double[]{c.yaw(), c.pitch()}; }

    /** Камера по умолчанию: первое лицо — место пилота (+ eye-height), F5 — «сзади» как раньше (chase-per-size, chase-height). */
    static Cam defaultFirst(double[] seat, double eyeHeight) { return new Cam(seat[0], seat[1] + eyeHeight, seat[2], 0, 0, 0, null, Double.NaN); }

    static Cam defaultChase(double radius, double perSize, double height) {
        double back = radius * perSize, up = radius * height;
        return new Cam(0, up, -back, 0, Math.toDegrees(Math.atan2(up, back) * 0.5), 0, null, Double.NaN);
    }

    /**
     * Камера F5 не в блоках: от середины корабля к точке камеры — если между ними блок (t — доля пути до него, &lt; 1),
     * камера придвигается к кораблю (за 0.3 блока до препятствия). Возвращает долю пути, где стоять.
     */
    static double pullIn(double hitFraction, double length) {
        if (!(hitFraction < 1) || length <= 1e-6) return 1;
        return Math.max(0, hitFraction - 0.3 / length);
    }
}
