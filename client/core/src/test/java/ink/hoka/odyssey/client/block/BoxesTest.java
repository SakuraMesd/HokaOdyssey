package ink.hoka.odyssey.client.block;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.88 Коллизия части из нескольких коробок (boxes): разбор, поворот, объединение в форму. */
class BoxesTest {

    @Test
    void parseAndRotate() {
        var d = JsonParser.parseString("{\"rot\":90,\"part\":1,\"size\":[2,4,3],\"boxes\":[[0,0,0,16,4,16],[2,4,0,16,16,6]]}").getAsJsonObject();
        BlockForm f = BlockForm.parse(d);
        assertTrue(f.hasBoxes());
        assertEquals(2, f.boxes().length);
        assertArrayEquals(new double[]{0, 0, 0, 16, 4, 16}, f.boxes()[0]);
        assertArrayEquals(new double[]{10, 4, 2, 16, 16, 16}, f.boxes()[1], "как shape: северная полоса уходит на восток");

        var empty = BlockForm.parse(JsonParser.parseString("{\"rot\":0,\"boxes\":[]}").getAsJsonObject());
        assertTrue(empty.hasBoxes());
        assertEquals(0, empty.boxes().length, "пустой список — часть без формы");
        assertFalse(BlockForm.parse(JsonParser.parseString("{\"rot\":0}").getAsJsonObject()).hasBoxes());
    }

    @Test
    void unionShape() {
        var s = PoolBlocks.shapeOf(new double[][]{{0, 0, 0, 16, 4, 16}, {0, 4, 0, 8, 16, 8}});
        var boxes = s.toAabbs();
        double vol = 0;
        for (var b : boxes) vol += (b.maxX - b.minX) * (b.maxY - b.minY) * (b.maxZ - b.minZ);
        assertEquals(0.25 + 0.5 * 0.75 * 0.5, vol, 1e-9);
        var bounds = s.bounds();
        assertEquals(0, bounds.minY, 1e-9);
        assertEquals(1, bounds.maxY, 1e-9);
        assertTrue(PoolBlocks.shapeOf(new double[0][]).isEmpty());
    }
}
