package ink.hoka.odyssey.server.registry;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Блоки мода в реестре Minestom: те же номера состояний, что у мода (PoolBlocks), сразу после ванильных. */
class PoolRegistryTest {
    @BeforeAll static void init() { MinecraftServer.init(); }

    @Test void poolBlocksHaveModNumbers() {
        Block s0 = Block.fromKey("hoka_odyssey:solid_0000");
        assertNotNull(s0, "solid_0000 нет в реестре — свой block.json не подхватился");
        assertEquals(29671, s0.stateId());
        assertEquals(29671 + 1024, Block.fromKey("hoka_odyssey:clear_0000").stateId());
        assertEquals(29671 + 2048 + 511, Block.fromKey("hoka_odyssey:pass_0511").stateId());
        assertSame(s0, Block.fromStateId(29671));
        assertTrue(s0.registry().occludes());
        assertTrue(s0.isSolid());
        assertFalse(Block.fromKey("hoka_odyssey:clear_0005").registry().occludes());
        assertFalse(Block.fromKey("hoka_odyssey:pass_0005").isSolid());
        assertEquals(1, Block.STONE.stateId());
    }
}
