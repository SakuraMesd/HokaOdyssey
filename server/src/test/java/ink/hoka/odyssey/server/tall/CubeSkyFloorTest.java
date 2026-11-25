package ink.hoka.odyssey.server.tall;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * op 16 SKY_FLOOR: каждый куб столбца не ниже CubeLight.floorAt по свету сервера — «небо 15, блоки 0» (запись
 * SKY_FULL|BLK_ZERO), т.е. его можно не слать ядру 0.54. И S3: правки считаются первыми, без бюджета тика.
 */
class CubeSkyFloorTest {

    static final int MIN = CubeLight.MIN;

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    /** Все кубы [floor, floor + up) столбца — «всё 15» по data(explicit) и записи op 11. */
    static void assertFullAbove(CubeLightTest.FakeWorld w, int cx, int cz, int up, String what) {
        int floor = w.eng.floorAt(cx, cz);
        assertNotEquals(Integer.MAX_VALUE, floor, what + ": пол есть");
        CubeLightTest.FakeCol c = w.cols.get(CubeLight.colKey(cx, cz));
        List<Integer> idx = new ArrayList<>();
        for (int s = floor; s < floor + up; s++) idx.add(s - MIN);
        var l = w.eng.data(c.st, idx, true);
        for (LightOp.Rec r : LightOp.records(cx, cz, MIN, l, idx, true))
            assertTrue(LightOp.isSkyFull(r), what + ": куб " + cx + " " + (r.i() + MIN) + " " + cz + " выше пола " + floor + " не «всё 15»");
    }

    @Test
    void flatGround() {
        var w = new CubeLightTest.FakeWorld(true, 40);
        w.load(-2, 2, -2, 2);
        // земля до y 40: skyTop 40 → пол ((40+31)>>4)+1 = 5; верхний непустой куб — секция 2 → 2+2 = 4
        assertEquals(5, w.eng.floorAt(0, 0));
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) assertFullAbove(w, cx, cz, 6, "ровная земля");
        assertEquals(Integer.MAX_VALUE, w.eng.floorAt(10, 10), "столбец не загружен — правила нет");
    }

    @Test
    void towerTorchAndGlass() {
        var w = new CubeLightTest.FakeWorld(true, 40);
        w.load(-2, 2, -2, 2);
        for (int y = 41; y <= 120; y++) w.set(5, y, 5, Block.STONE);   // башня в (0,0)
        w.set(20, 150, 4, Block.TORCH);                                 // факел высоко в (1,0) без опоры
        w.set(-10, 200, -10, Block.GLASS);                              // стекло (свет пропускает) в (−1,−1)
        w.set(3, 300, 30, Block.GLOWSTONE);                             // светящийся полный блок в (0,1)
        w.eng.process();
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) assertFullAbove(w, cx, cz, 8, "башня/факел/стекло");
        assertTrue(w.eng.floorAt(0, 0) >= ((120 + 31) >> 4) + 1, "пол выше башни");
        assertTrue(w.eng.floorAt(1, 1) >= (300 >> 4) + 2, "пол соседа светящегося блока — над его кубом");
        // сломали башню — пол опустился, выше него снова «всё 15»
        for (int y = 120; y >= 41; y--) w.set(5, y, 5, Block.AIR);
        w.eng.process();
        assertFullAbove(w, 0, 0, 8, "после сноса башни");
    }

    /** Пол не ниже любого не «всё 15» куба: перебор всей полосы над землёй. */
    @Test
    void noDarkCubeAboveFloor() {
        var w = new CubeLightTest.FakeWorld(true, 40);
        w.load(-2, 2, -2, 2);
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) w.set(x, 90, z, Block.STONE);   // плита-крыша на стыке 4 столбцов
        w.set(0, 89, 0, Block.TORCH);
        w.eng.process();
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) {
            int floor = w.eng.floorAt(cx, cz);
            CubeLightTest.FakeCol c = w.cols.get(CubeLight.colKey(cx, cz));
            List<Integer> idx = new ArrayList<>();
            for (int s = 0; s < floor + 4; s++) idx.add(s - MIN);
            int highestDark = Integer.MIN_VALUE;
            for (LightOp.Rec r : LightOp.records(cx, cz, MIN, w.eng.data(c.st, idx, true), idx, true))
                if (!LightOp.isSkyFull(r)) highestDark = Math.max(highestDark, r.i() + MIN);
            assertTrue(highestDark < floor, "столбец " + cx + " " + cz + ": тёмный куб " + highestDark + " не ниже пола " + floor);
        }
    }

    /** S3: при выбранном бюджете правки всё равно считаются (до EDIT_FREE), потоковые события ждут. */
    @Test
    void editsFirstWithoutBudget() {
        var w = new CubeLightTest.FakeWorld(true, 40);
        w.load(-2, 2, -2, 2);
        w.eng.budgetNs = 1;                                             // как тик с выбранным бюджетом
        for (int k = 0; k < 50; k++) w.eng.cubeLoadedAt(0, 10 + k, 0);   // хвост потоковых событий
        CubeLightTest.FakeCol c = w.cols.get(CubeLight.colKey(0, 0));
        int t0 = c.h.get(HeightIndex.Kind.SKY, 8, 8);
        c.set(8, 60, 8, Block.GLOWSTONE);
        w.eng.editAt(8, 60, 8, t0, c.h.get(HeightIndex.Kind.SKY, 8, 8));
        w.eng.flush();
        assertEquals(0, w.eng.editsQueued(), "правка посчитана в этом же вызове");
        assertTrue(w.eng.pending(), "потоковые события — по бюджету, ждут");
        int i = (60 >> 4) - MIN;
        var l = w.eng.data(c.st, List.of(i), true);
        assertTrue(l != null && l.blockMask().get(i + 1), "свет блоков у светящегося блока посчитан");
        w.eng.budgetNs = Long.MAX_VALUE;
        w.eng.process();
        CubeLightTest.assertBox(w, -8, 44, -8, 32, 32, 32, "после правки вне бюджета");
    }
}
