package ink.hoka.odyssey.blocks;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Коллизия по частям: разбор файла телепорта, поворот, «игрок проходит внутрь, но не сквозь стекло». */
class CollisionTest {

    static final int[] SIZE = {2, 4, 3};
    static final double[] INSIDE = {-11, 2, -19, 11, 40, 3};

    static double[][][] teleporter() throws Exception {
        return Collision.parse(Files.readString(Path.of("../../demo-objects/teleporter/teleporter_collision.json"), StandardCharsets.UTF_8), SIZE);
    }

    @Test
    void parsesTeleporter() throws Exception {
        double[][][] c = teleporter();
        assertEquals(24, c.length);
        int n = 0;
        for (double[][] p : c) n += p.length;
        assertTrue(n > 100, "коробок " + n);
    }

    @Test
    void brokenFileRejected() {
        assertThrows(IllegalArgumentException.class, () -> Collision.parse("{\"cells\":{\"0,0,0\":[[0,0,0,17,4,16]]}}", SIZE));
        assertThrows(IllegalArgumentException.class, () -> Collision.parse("{\"cells\":{\"2,0,0\":[[0,0,0,16,4,16]]}}", SIZE));
        assertThrows(IllegalArgumentException.class, () -> Collision.parse("{\"cells\":{\"0,0,0\":[[5,0,0,4,4,16]]}}", SIZE));
        assertThrows(RuntimeException.class, () -> Collision.parse("{\"cells\":{}", SIZE));   // битый JSON
        assertThrows(IllegalArgumentException.class, () -> Collision.parse("{}", SIZE));
        double[][][] c = assertDoesNotThrow(() -> Collision.parse("{\"cells\":{\"0,0,0\":[[0,0,0,16,4,16]]}}", SIZE));
        assertEquals(0, c[1].length, "часть без записи — без коллизии");
    }

    @Test
    void teleporterGetsModBlocks(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        var def = new BlocksModule.Def("teleporter", "Телепорт", BlocksModule.Kind.TRANSPARENT, true, java.util.Map.of(),
                "teleporter.bbmodel", "cutout", null, true, null, SIZE, null, "idle", null, teleporter(), INSIDE);
        var a = BlocksModule.allocate(new ink.hoka.odyssey.api.ModuleConfig(dir.resolve("states.yml")), List.of(def));
        assertTrue(a.problems().isEmpty(), a.problems().toString());
        assertEquals(96, a.fresh(), "24 части × 4 поворота");
        assertEquals(0, a.used().get(BlocksModule.Kind.TRANSPARENT), "носители (листва) не нужны");
        assertEquals(96, a.direct().get(BlocksModule.Kind.TRANSPARENT) + a.direct().get(BlocksModule.Kind.PASSABLE));
    }

    static double volume(List<double[]> boxes) {
        double v = 0;
        for (double[] b : boxes) v += (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
        return v;
    }

    @Test
    void rotationKeepsVolumeAndCells() throws Exception {
        double[][][] c = teleporter();
        double v0 = volume(Collision.world(c, SIZE, 0));
        for (int rot : new int[]{0, 90, 180, 270}) {
            assertEquals(v0, volume(Collision.world(c, SIZE, rot)), 1e-6);
            for (int p = 0; p < c.length; p++) {
                int[] o = Multi.offset(SIZE, p, rot);
                for (double[] b : c[p]) {
                    double[] r = Collision.rotateBox(b, rot);
                    assertTrue(r[0] >= 0 && r[3] <= 16 && r[2] >= 0 && r[5] <= 16, "коробка вышла из клетки");
                }
                assertNotNull(o);
            }
        }
    }

    static final double W = 9.6, H = 28.8, STEP = 9.6;   // игрок 0,6 × 1,8, шаг 0,6 блока (px)

    /** Идёт хитбоксом от from к to (px от угла главной клетки) по прямой: дошёл ли (с подъёмом на шаг). */
    static boolean walk(List<double[]> boxes, double[] from, double[] to) {
        double y = 0;
        int n = (int) Math.ceil(Math.hypot(to[0] - from[0], to[1] - from[1]));
        for (int i = 0; i <= n; i++) {
            double t = n == 0 ? 1 : (double) i / n;
            double x = from[0] + (to[0] - from[0]) * t, z = from[1] + (to[1] - from[1]) * t;
            double x1 = x - W / 2, x2 = x + W / 2, z1 = z - W / 2, z2 = z + W / 2;
            double ground = 0;
            for (double[] b : boxes)
                if (b[0] < x2 && b[3] > x1 && b[2] < z2 && b[5] > z1 && b[4] <= y + STEP) ground = Math.max(ground, b[4]);
            y = ground;
            for (double[] b : boxes)
                if (b[0] < x2 && b[3] > x1 && b[2] < z2 && b[5] > z1 && b[1] < y + H && b[4] > y) return false;
        }
        return true;
    }

    /** Точка модели (px, центр габарита) → px от угла главной клетки при повороте. */
    static double[] at(double mx, double mz, int rot) {
        double[] r = Collision.rotate(mx + SIZE[0] * 8 - 8, mz + SIZE[2] * 8 - 8, rot);
        return new double[]{r[0] + 8, r[1] + 8};
    }

    @Test
    void playerWalksInOnlyThroughDoor() throws Exception {
        double[][][] c = teleporter();
        for (int rot : new int[]{0, 90, 180, 270}) {
            List<double[]> boxes = Collision.world(c, SIZE, rot);
            double[] center = at(0, -8, rot);
            assertTrue(walk(boxes, at(0, -40, rot), center), "поворот " + rot + ": проход спереди закрыт");
            assertFalse(walk(boxes, at(40, -8, rot), center), "поворот " + rot + ": сбоку сквозь стекло");
            assertFalse(walk(boxes, at(-40, -8, rot), center), "поворот " + rot + ": сбоку сквозь стекло");
            assertFalse(walk(boxes, at(0, 40, rot), center), "поворот " + rot + ": сзади сквозь машинный блок");
            // стоя в центре: ноги внутри коробки inside
            double[] in = Collision.insideBox(INSIDE, SIZE, rot);
            assertTrue(Collision.contains(in, center[0], 4, center[1]), "поворот " + rot + ": центр колбы не «внутри»");
            assertFalse(Collision.contains(in, at(0, -40, rot)[0], 0, at(0, -40, rot)[1]), "перед проходом — не внутри");
        }
    }
}
