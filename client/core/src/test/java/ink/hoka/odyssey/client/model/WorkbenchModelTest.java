package ink.hoka.odyssey.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.84 Модель космического верстака после сдвига (tools/shift_bbmodel.py): габарит 2 × 1,5 × 2 блока на месте,
 * все элементы в костях, анимация idle есть и двигает только подвижные части.
 */
class WorkbenchModelTest {

    static JsonObject load(String name) throws Exception {
        Path p = Path.of("../../demo-objects/workbench/assets/" + name);
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** Габарит в позе по умолчанию: {minX, minY, minZ, maxX, maxY, maxZ}, px. */
    static float[] bounds(BbModel m) {
        Matrix4f[] rest = new Matrix4f[m.bones.size()];
        Pose.compute(m, null, -1, rest);
        float[] b = {1e9f, 1e9f, 1e9f, -1e9f, -1e9f, -1e9f};
        Vector3f v = new Vector3f();
        for (BbModel.Bone bone : m.bones) for (BbModel.Face f : bone.faces) for (int i = 0; i < 4; i++) {
            rest[bone.index].transformPosition(f.xyz()[i * 3], f.xyz()[i * 3 + 1], f.xyz()[i * 3 + 2], v);
            b[0] = Math.min(b[0], v.x); b[1] = Math.min(b[1], v.y); b[2] = Math.min(b[2], v.z);
            b[3] = Math.max(b[3], v.x); b[4] = Math.max(b[4], v.y); b[5] = Math.max(b[5], v.z);
        }
        return b;
    }

    /** Габарит по X/Z и низ — точно; верх — экран наклонён, его угол чуть ниже 24 px (не выше 1,5 блока). */
    static void assertBox(float[] want, float[] got) {
        for (int i : new int[]{0, 1, 2, 3, 5}) assertEquals(want[i], got[i], 1e-4f, "габарит[" + i + "]");
        assertTrue(got[4] <= want[4] + 1e-4f && got[4] > want[4] - 1f, "верх " + got[4]);
    }

    static int faces(BbModel m) {
        int n = 0;
        for (BbModel.Bone b : m.bones) n += b.faces.size();
        return n;
    }

    static int jsonFaces(JsonObject root) {
        int n = 0;
        for (var e : root.getAsJsonArray("elements")) {
            var faces = e.getAsJsonObject().getAsJsonObject("faces");
            for (var f : faces.entrySet()) if (!f.getValue().getAsJsonObject().get("texture").isJsonNull()) n++;
        }
        return n;
    }

    @Test
    void animatedFreeModel() throws Exception {
        JsonObject root = load("space_workbench.bbmodel");
        assertEquals("free", root.getAsJsonObject("meta").get("model_format").getAsString());
        BbModel m = BbModel.parse("blocks/space_workbench.bbmodel", root);
        // формат free: центр модели (0, 0) — в середине габарита блока (у 2×2 — стык четырёх клеток)
        assertBox(new float[]{-16, 0, -16, 16, 24, 16}, bounds(m));
        assertEquals(jsonFaces(root), faces(m), "все грани всех элементов — в костях");
        BbAnimation idle = m.animations.get("idle");
        assertNotNull(idle, "анимация idle");
        assertEquals(4f, idle.length, 1e-6);
        assertEquals(BbAnimation.Loop.LOOP, idle.loop);
        boolean[] mv = idle.moving(m);
        for (String moving : new String[]{"Манипулятор", "Головка", "Искры", "Голограмма"})
            assertTrue(mv[m.bone(moving).index], moving + " двигается");
        for (String still : new String[]{"Корпус", "Экран"})
            assertFalse(mv[m.bone(still).index], still + " — в меше");
        // в любой момент анимации модель не выходит за 1,5 блока по высоте и за 2×2
        Matrix4f[] pose = new Matrix4f[m.bones.size()];
        Vector3f v = new Vector3f();
        for (float t = 0; t < 4; t += 0.1f) {
            Pose.compute(m, idle, idle.localTime(t), pose);
            for (BbModel.Bone b : m.bones) for (BbModel.Face f : b.faces) for (int i = 0; i < 4; i++) {
                pose[b.index].transformPosition(f.xyz()[i * 3], f.xyz()[i * 3 + 1], f.xyz()[i * 3 + 2], v);
                assertTrue(v.y <= 24.01f && v.y >= -0.01f, "высота " + v.y + " в " + t + " с, кость " + b.name);
                assertTrue(Math.abs(v.x) <= 16.01f && Math.abs(v.z) <= 16.01f, "за пределами 2×2: " + v + " кость " + b.name);
            }
        }
    }

    @Test
    void staticJavaBlockModel() throws Exception {
        JsonObject root = load("space_workbench_static.bbmodel");
        BbModel m = BbModel.parse("blocks/space_workbench_static.bbmodel", root);
        // java_block: 0..32 от угла главной клетки (покрывает 2×2 клетки), высота 24 px = 1,5 блока
        assertBox(new float[]{0, 0, 0, 32, 24, 32}, bounds(m));
        assertEquals(jsonFaces(root), faces(m));
    }
}
