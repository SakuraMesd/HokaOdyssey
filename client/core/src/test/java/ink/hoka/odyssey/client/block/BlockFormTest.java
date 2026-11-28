package ink.hoka.odyssey.client.block;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.84 Разбор новых полей своего блока (part, size, shape, animation) и форма клетки по shape с поворотом. */
class BlockFormTest {

    static JsonObject json(String s) { return JsonParser.parseString(s).getAsJsonObject(); }

    @Test
    void parseFields() {
        BlockForm f = BlockForm.parse(json("{\"id\":\"space_workbench\",\"rot\":0,\"part\":2,\"size\":[2,1,2],"
                + "\"shape\":[0,0,0,16,24,16],\"animation\":\"idle\"}"));
        assertEquals(2, f.part());
        assertFalse(f.main());
        assertArrayEquals(new int[]{2, 1, 2}, f.size());
        assertArrayEquals(new double[]{0, 0, 0, 16, 24, 16}, f.box());
        assertEquals("idle", f.animation());

        // старое описание (без новых полей) — как раньше: одна клетка, полный куб, без анимации
        BlockForm old = BlockForm.parse(json("{\"id\":\"mortar\",\"rot\":90,\"model\":\"blocks/mortar.bbmodel\"}"));
        assertTrue(old.main());
        assertArrayEquals(new int[]{1, 1, 1}, old.size());
        assertNull(old.box());
        assertNull(old.animation());
        assertArrayEquals(new float[]{8, 8}, old.modelOffset(false), "1×1, не Java Block — центр модели в середине клетки (как было)");
        assertArrayEquals(new float[]{0, 0}, old.modelOffset(true));
        assertArrayEquals(new float[]{16, 16}, f.modelOffset(false), "2×2 — центр модели на стыке четырёх клеток");
    }

    @Test
    void shapeRotates() {
        int[] s = {2, 0, 0, 16, 24, 6};                                          // полоса у северной (−Z) стороны
        assertArrayEquals(new double[]{2, 0, 0, 16, 24, 6}, BlockForm.rotateBox(s, 0));
        // 90: (x, z) от середины → (−z, x): северная полоса уходит на восток (+X)
        assertArrayEquals(new double[]{10, 0, 2, 16, 24, 16}, BlockForm.rotateBox(s, 90));
        assertArrayEquals(new double[]{0, 0, 10, 14, 24, 16}, BlockForm.rotateBox(s, 180));
        assertArrayEquals(new double[]{0, 0, 0, 6, 24, 14}, BlockForm.rotateBox(s, 270));
        // верстак: полная клетка по X/Z при любом повороте, высота 1,5
        for (int rot : new int[]{0, 90, 180, 270})
            assertArrayEquals(new double[]{0, 0, 0, 16, 24, 16}, BlockForm.rotateBox(new int[]{0, 0, 0, 16, 24, 16}, rot));
    }

    @Test
    void voxelShape() {
        var sh = PoolBlocks.shapeOf(new double[]{0, 0, 0, 16, 24, 16});
        var box = sh.bounds();
        assertEquals(0, box.minX, 1e-9);
        assertEquals(1.5, box.maxY, 1e-9);
        assertEquals(1, box.maxZ, 1e-9);
        assertNull(PoolBlocks.shapeOf((double[]) null), "нет shape — полный куб (как у носителя)");
    }
}
