package ink.hoka.odyssey.blocks;

import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Носители своих блоков: обычные блоки в них не встают, генератор их не ставит, старые места не сдвинулись. */
class CarriersTest {

    @Test
    void poolsStable() {
        // states.yml хранит строки состояний — набор и порядок пулов не меняли (миграция не нужна)
        // 28ноя: и залитая водой (140 = 10 видов × 7 distance × 2, с вишней); прежние 63 — по-прежнему в пуле (states.yml те же)
        assertEquals(140, BlocksModule.pool(BlocksModule.Kind.TRANSPARENT).size());
        for (Block b : BlocksModule.pool(BlocksModule.Kind.TRANSPARENT))
            assertEquals("true", b.getProperty("persistent"), b.state());
        assertTrue(BlocksModule.carrier(Block.fromState("minecraft:acacia_leaves[distance=1,persistent=true,waterlogged=false]")));
        Set<Integer> seen = new HashSet<>();
        for (BlocksModule.Kind k : BlocksModule.Kind.values())
            for (Block b : BlocksModule.pool(k)) assertTrue(seen.add(b.stateId()), "носитель в двух пулах: " + b.state());
    }

    @Test
    void ordinaryPlacementLeavesCarriers() {
        // ваниль ставит листву persistent=true (distance любая) — сервер переводит в обычную
        for (Block b : BlocksModule.pool(BlocksModule.Kind.TRANSPARENT)) {
            Block n = BlocksModule.foreign(b);
            assertFalse(BlocksModule.carrier(n), b.state());
            assertEquals(b.id(), n.id(), "та же порода");
            assertEquals("false", n.getProperty("persistent"));
        }
        for (BlocksModule.Kind k : BlocksModule.Kind.values())
            for (Block b : BlocksModule.pool(k)) assertFalse(BlocksModule.carrier(BlocksModule.foreign(b)), b.state());
        // состояние по умолчанию (так ставит Minestom без правила) — не носитель
        for (Block b : new Block[]{Block.OAK_LEAVES, Block.ACACIA_LEAVES, Block.NOTE_BLOCK, Block.TRIPWIRE})
            assertFalse(BlocksModule.carrier(b), b.state());
    }

    @Test
    void naturalLeavesNotCarriers() {
        // генератор vale@3: листва persistent=false (distance 1..7, в воде и без), грибные блоки — не носители
        for (Block leaves : Block.values()) {
            if (!leaves.key().value().endsWith("_leaves")) continue;
            for (Block b : leaves.possibleStates()) {
                if ("false".equals(b.getProperty("persistent"))) assertFalse(BlocksModule.carrier(b), b.state());
                if (!BlocksModule.carrier(b)) assertEquals(b.stateId(), BlocksModule.foreign(b).stateId(), "не носитель — не трогаем");
            }
        }
        for (Block m : new Block[]{Block.MUSHROOM_STEM, Block.RED_MUSHROOM_BLOCK, Block.BROWN_MUSHROOM_BLOCK})
            for (Block b : m.possibleStates()) assertFalse(BlocksModule.carrier(b), b.state());
    }
}
