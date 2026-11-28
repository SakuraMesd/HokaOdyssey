package ink.hoka.odyssey.client.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Анимация Blockbench: ключи поворота / сдвига / масштаба по костям. Режимы — как в Blockbench и ModelEngine:
 * {@code loop} — по кругу, {@code once} — один раз и назад в позу по умолчанию, {@code hold} — замереть в конце.
 * Значения-формулы (molang) прототип не считает: берётся число, иначе 0.
 */
public final class BbAnimation {

    public enum Loop { LOOP, ONCE, HOLD }

    record Key(float time, float x, float y, float z, int interp) {}   // interp: 0 linear, 1 catmullrom, 2 step

    /** Каналы одной кости: null = канала нет. */
    static final class Track {
        Key[] rot, pos, scale;
    }

    public final String name;
    public final Loop loop;
    public final float length;
    final Track[] tracks;   // по индексу кости модели

    private BbAnimation(String name, Loop loop, float length, int bones) {
        this.name = name;
        this.loop = loop;
        this.length = length;
        this.tracks = new Track[bones];
    }

    /** {@code legacy} — файл до формата 5.0: ключи поворота x/y и сдвига x записаны с обратным знаком. */
    static BbAnimation parse(JsonObject o, BbModel model, boolean legacy) {
        String loopS = o.has("loop") ? o.get("loop").getAsString() : "once";
        Loop loop = switch (loopS) {
            case "loop" -> Loop.LOOP;
            case "hold" -> Loop.HOLD;
            default -> Loop.ONCE;
        };
        BbAnimation a = new BbAnimation(o.get("name").getAsString(), loop,
                o.has("length") ? o.get("length").getAsFloat() : 0f, model.bones.size());
        if (!o.has("animators")) return a;
        for (var e : o.getAsJsonObject("animators").entrySet()) {
            JsonObject an = e.getValue().getAsJsonObject();
            BbModel.Bone bone = null;
            for (BbModel.Bone b : model.bones) {
                if (b.uuid.equals(e.getKey())) { bone = b; break; }
            }
            if (bone == null && an.has("name")) bone = model.bone(an.get("name").getAsString());
            if (bone == null || !an.has("keyframes")) continue;
            List<Key> rot = new ArrayList<>(), pos = new ArrayList<>(), scl = new ArrayList<>();
            for (JsonElement kel : an.getAsJsonArray("keyframes")) {
                JsonObject k = kel.getAsJsonObject();
                JsonObject dp = k.getAsJsonArray("data_points").get(0).getAsJsonObject();
                String ip = k.has("interpolation") ? k.get("interpolation").getAsString() : "linear";
                String ch = k.get("channel").getAsString();
                float sx = legacy && (ch.equals("rotation") || ch.equals("position")) ? -1f : 1f;
                float sy = legacy && ch.equals("rotation") ? -1f : 1f;
                Key key = new Key(k.get("time").getAsFloat(), sx * num(dp, "x"), sy * num(dp, "y"), num(dp, "z"),
                        switch (ip) { case "catmullrom" -> 1; case "step" -> 2; default -> 0; });
                switch (ch) {
                    case "rotation" -> rot.add(key);
                    case "position" -> pos.add(key);
                    case "scale" -> scl.add(key);
                    default -> { }
                }
            }
            Track t = new Track();
            t.rot = sorted(rot);
            t.pos = sorted(pos);
            t.scale = sorted(scl);
            a.tracks[bone.index] = t;
        }
        return a;
    }

    private static Key[] sorted(List<Key> l) {
        if (l.isEmpty()) return null;
        l.sort(Comparator.comparingDouble(Key::time));
        return l.toArray(Key[]::new);
    }

    private static float num(JsonObject o, String k) {
        if (!o.has(k)) return 0f;
        try {
            return Float.parseFloat(o.get(k).getAsString().trim());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    /**
     * 0.84 Какие кости двигает анимация: у кости есть ключи, и все её потомки. Для модели блока: подвижное рисуется
     * каждый кадр, остальное — в меше чанка.
     */
    public boolean[] moving(BbModel m) {
        boolean[] out = new boolean[m.bones.size()];
        for (BbModel.Bone r : m.roots) mark(r, false, out);
        return out;
    }

    private void mark(BbModel.Bone b, boolean parent, boolean[] out) {
        Track t = b.index < tracks.length ? tracks[b.index] : null;
        boolean mv = parent || (t != null && (t.rot != null || t.pos != null || t.scale != null));
        out[b.index] = mv;
        for (BbModel.Bone c : b.children) mark(c, mv, out);
    }

    /** Время внутри анимации с учётом режима; {@code < 0} = анимация кончилась (once) — поза по умолчанию. */
    public float localTime(float t) {
        if (length <= 0f) return 0f;
        return switch (loop) {
            case LOOP -> t % length;
            case HOLD -> Math.min(t, length);
            case ONCE -> Math.min(t, length);   // конец — держим последний кадр: поза по умолчанию у моделей LittleRoom смещена
        };
    }

    /** Сэмпл канала в момент t. false — канала у кости нет. */
    static boolean sample(Key[] keys, float t, Vector3f out) {
        if (keys == null) return false;
        if (t <= keys[0].time) return set(out, keys[0]);
        Key last = keys[keys.length - 1];
        if (t >= last.time) return set(out, last);
        int i = 0;
        while (keys[i + 1].time < t) i++;
        Key a = keys[i], b = keys[i + 1];
        if (b.interp == 2 || a.interp == 2) return set(out, a);
        float f = (t - a.time) / Math.max(1e-6f, b.time - a.time);
        if (a.interp == 1 || b.interp == 1) {
            Key p = keys[Math.max(0, i - 1)], n = keys[Math.min(keys.length - 1, i + 2)];
            out.set(cr(p.x, a.x, b.x, n.x, f), cr(p.y, a.y, b.y, n.y, f), cr(p.z, a.z, b.z, n.z, f));
            return true;
        }
        out.set(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f);
        return true;
    }

    private static boolean set(Vector3f out, Key k) {
        out.set(k.x, k.y, k.z);
        return true;
    }

    private static float cr(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return 0.5f * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }
}
