package ink.hoka.odyssey.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * plasma-pipes-1: 30 моделей труб (6 деталей × 5 цветов) — разбираются, анимации flow/flow_reverse по 0,5 с с бесшовной
 * петлёй, модель в своей клетке; и стоимость рисования подвижных костей 300 труб за кадр (BlockAnims.draw).
 */
class PlasmaPipesModelTest {

    static final String[] PARTS = {"straight", "straight_vertical", "elbow", "elbow_up", "elbow_down", "tee"};
    static final String[] COLORS = {"cyan", "pink", "green", "orange", "violet"};

    static BbModel load(String id) throws Exception {
        Path p = Path.of("../../demo-objects/plasma_pipes/" + id + ".bbmodel");
        JsonObject root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("free", root.getAsJsonObject("meta").get("model_format").getAsString(), id);
        return BbModel.parse("blocks/" + id + ".bbmodel", root);
    }

    /** Центры сгустков (элементы подвижных костей: по 6 граней на кубик) в позе. */
    static List<float[]> blobs(BbModel m, boolean[] moving, Matrix4f[] pose) {
        List<float[]> out = new ArrayList<>();
        Vector3f v = new Vector3f();
        for (BbModel.Bone b : m.bones) {
            if (!moving[b.index] || b.faces.isEmpty()) continue;
            for (int k = 0; k + 6 <= b.faces.size(); k += 6) {
                float x = 0, y = 0, z = 0;
                for (int fi = k; fi < k + 6; fi++) {
                    BbModel.Face f = b.faces.get(fi);
                    for (int i = 0; i < 4; i++) {
                        pose[b.index].transformPosition(f.xyz()[i * 3], f.xyz()[i * 3 + 1], f.xyz()[i * 3 + 2], v);
                        x += v.x; y += v.y; z += v.z;
                    }
                }
                out.add(new float[]{x / 24, y / 24, z / 24});
            }
        }
        return out;
    }

    static String dump(List<float[]> l) {
        StringBuilder sb = new StringBuilder();
        for (float[] p : l) sb.append(String.format(java.util.Locale.ROOT, " (%.1f %.1f %.1f)", p[0], p[1], p[2]));
        return sb.toString();
    }

    static boolean near(float[] p, float[] q) { return Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]) + Math.abs(p[2] - q[2]) < 0.05f; }

    /** Насколько центр вышел из клетки (0 — внутри), px. */
    static float out(float[] p) {
        return Math.max(0, Math.max(Math.max(-8 - p[0], p[0] - 8), Math.max(Math.max(-8 - p[2], p[2] - 8), Math.max(-p[1], p[1] - 16))));
    }

    /** Насколько центр близко к грани клетки изнутри (px до ближайшей грани). */
    static float toFace(float[] p) {
        return Math.min(Math.min(8 - Math.abs(p[0]), 8 - Math.abs(p[2])), Math.min(p[1], 16 - p[1]));
    }

    /**
     * Петля без рывка внутри трубы: к концу периода каждый сгусток встаёт на место соседнего, кроме «крайних» — ушедший
     * у стыка-выхода (не дальше шага от грани, внутри или уже в соседней трубе) и появившийся у стыка-входа. На стыке
     * сгусток подменяется сгустком соседней трубы (у прямой — точно на том же месте, у колена — со сдвигом, см. отчёт).
     * Возвращает наибольший сдвиг подмены на стыке, px (0 — шва не видно).
     */
    static float seamless(List<float[]> a0, List<float[]> a1, String what) {
        assertEquals(a0.size(), a1.size(), what);
        List<Float> gone = new ArrayList<>(), born = new ArrayList<>();
        for (float[] p : a1) if (a0.stream().noneMatch(q -> near(p, q))) {
            // тройник, обратное течение: ветка вливается в ствол — её сгусток пропадает в середине развилки
            if (Math.abs(p[0]) + Math.abs(p[1] - 8) + Math.abs(p[2]) <= 2f) continue;
            float d = out(p) > 0 ? -out(p) : toFace(p);                         // от грани: + внутри, − снаружи
            assertTrue(d <= 4f && d >= -4f, what + ": в конце петли сгусток не на месте другого и не у стыка:" + dump(a1) + " / начало" + dump(a0));
            gone.add(d);
        }
        for (float[] q : a0) if (a1.stream().noneMatch(p -> near(p, q))) {
            float d = toFace(q);
            assertTrue(d >= 0 && d <= 4f, what + ": новый сгусток не у стыка-входа:" + dump(a0));
            born.add(d);
        }
        assertFalse(gone.isEmpty(), what + ": сгустки никуда не уходят");
        assertTrue(born.size() >= gone.size() || born.size() > 0, what + ": ушло " + gone + ", появилось " + born);
        float jump = 0;                                                       // ушедший на d снаружи ↔ у соседа новый на d внутри
        for (float g : gone) {
            float best = Float.MAX_VALUE;
            for (float bn : born) best = Math.min(best, Math.abs(-g - bn));
            jump = Math.max(jump, best);
        }
        return jump;
    }

    /** Габарит всех граней модели в позе. */
    static float[] bounds(BbModel m, Matrix4f[] pose) {
        float[] b = {1e9f, 1e9f, 1e9f, -1e9f, -1e9f, -1e9f};
        Vector3f v = new Vector3f();
        for (BbModel.Bone bone : m.bones) for (BbModel.Face f : bone.faces) for (int i = 0; i < 4; i++) {
            pose[bone.index].transformPosition(f.xyz()[i * 3], f.xyz()[i * 3 + 1], f.xyz()[i * 3 + 2], v);
            b[0] = Math.min(b[0], v.x); b[1] = Math.min(b[1], v.y); b[2] = Math.min(b[2], v.z);
            b[3] = Math.max(b[3], v.x); b[4] = Math.max(b[4], v.y); b[5] = Math.max(b[5], v.z);
        }
        return b;
    }

    static void inCell(float[] b, String what) {
        float e = 1e-3f;
        assertTrue(b[0] >= -8 - e && b[3] <= 8 + e && b[2] >= -8 - e && b[5] <= 8 + e && b[1] >= -e && b[4] <= 16 + e,
                what + ": габарит " + java.util.Arrays.toString(b) + " вне клетки");
    }

    @Test
    void allModels() throws Exception {
        for (String c : COLORS) for (String part : PARTS) {
            String id = "plasma_pipe_" + part + "_" + c;
            BbModel m = load(id);
            Matrix4f[] rest = new Matrix4f[m.bones.size()], pose = new Matrix4f[m.bones.size()];
            Pose.compute(m, null, -1, rest);
            inCell(bounds(m, rest), id);
            for (String an : new String[]{"flow", "flow_reverse"}) {
                BbAnimation a = m.animations.get(an);
                assertNotNull(a, id + ": нет анимации " + an);
                assertEquals(0.5f, a.length, 1e-6, id + " " + an);
                assertEquals(BbAnimation.Loop.LOOP, a.loop, id + " " + an);
                boolean[] mv = a.moving(m);
                Matrix4f[] p0 = new Matrix4f[m.bones.size()], p1 = new Matrix4f[m.bones.size()];
                Pose.compute(m, a, 0f, p0);
                Pose.compute(m, a, 0.5f, p1);
                List<float[]> b0 = blobs(m, mv, p0);
                assertFalse(b0.isEmpty(), id + " " + an + ": нет подвижных сгустков");
                float jump = seamless(b0, blobs(m, mv, p1), id + " " + an);
                if (c.equals("cyan")) System.out.printf(java.util.Locale.ROOT, "%s %s: подмена на стыке %.1f px%n", part, an, jump);
                if (part.startsWith("straight")) assertEquals(0f, jump, 0.05f, id + " " + an + ": у прямой шов на стыке");
                for (int i = 0; i <= 10; i++) {                                  // по ходу петли — заходят в соседа не дальше шага (4 px)
                    Pose.compute(m, a, i * 0.05f, pose);
                    float[] b = bounds(m, pose);
                    float over = Math.max(Math.max(-8 - b[0], b[3] - 8), Math.max(Math.max(-8 - b[2], b[5] - 8), Math.max(-b[1], b[4] - 16)));
                    assertTrue(over <= 4.01f, id + " " + an + " t=" + i * 0.05f + ": вышел за клетку на " + over + " px");
                }
            }
        }
    }

    /** Приёмник вершин без рисования: считает вершины. */
    static final class Count implements VertexConsumer {
        long n;
        @Override public VertexConsumer addVertex(float x, float y, float z) { n++; return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int argb) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float w) { return this; }
    }

    /**
     * Бенч: 300 труб (6 деталей × 2 анимации) — поза на группу и вершины подвижных костей за кадр. Печатает время кадра
     * (CPU-часть BlockAnims.render без видеокарты); порог — с большим запасом, ловит только провалы.
     */
    @Test
    void bench300() throws Exception {
        List<BbModel> models = new ArrayList<>();
        for (String part : PARTS) models.add(load("plasma_pipe_" + part + "_cyan"));
        int groups = models.size() * 2;
        Matrix4f[][] poses = new Matrix4f[groups][];
        boolean[][] moving = new boolean[groups][];
        BbAnimation[] anims = new BbAnimation[groups];
        for (int g = 0; g < groups; g++) {
            BbModel m = models.get(g / 2);
            anims[g] = m.animations.get(g % 2 == 0 ? "flow" : "flow_reverse");
            moving[g] = anims[g].moving(m);
            poses[g] = new Matrix4f[m.bones.size()];
        }
        Pose.Scratch s = new Pose.Scratch();
        Count vc = new Count();
        Matrix4f base = new Matrix4f();
        int frames = 600;
        long t0 = 0;
        for (int f = -200; f < frames; f++) {                                    // 200 кадров прогрева
            if (f == 0) { t0 = System.nanoTime(); vc.n = 0; }
            float sec = f / 60f;
            for (int g = 0; g < groups; g++) {
                BbModel m = models.get(g / 2);
                Pose.compute(m, anims[g], anims[g].localTime(sec), poses[g], s);
                for (int b = 0; b < 300 / groups; b++) {
                    base.identity().translate(b, 0, g).scale(1 / 16f);
                    BlockAnims.draw(vc, m, moving[g], poses[g], base, 0, 0xF000F0);
                }
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6 / frames;
        System.out.printf("трубы: 300 блоков, %d вершин за кадр, %.3f мс на кадр (CPU)%n", vc.n / frames, ms);
        assertTrue(ms < 4, "300 труб: " + ms + " мс на кадр — дорого");
    }
}
