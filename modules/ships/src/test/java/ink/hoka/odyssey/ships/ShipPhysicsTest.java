package ink.hoka.odyssey.ships;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Маски материалов и формул тела (без Minestom-блоков — только чистая математика и glob). */
class ShipPhysicsTest {

    @Test
    void glob() {
        assertTrue(ShipPhysics.Config.glob("*_planks", "oak_planks"));
        assertFalse(ShipPhysics.Config.glob("*_planks", "oak_planks_x"));
        assertTrue(ShipPhysics.Config.glob("*glass*", "white_stained_glass_pane"));
        assertTrue(ShipPhysics.Config.glob("stone", "stone"));
        assertFalse(ShipPhysics.Config.glob("stone", "stone_button"));
        assertTrue(ShipPhysics.Config.glob("*_fence*", "oak_fence_gate"));
        assertTrue(ShipPhysics.Config.glob("*torch*", "wall_torch"));
        assertFalse(ShipPhysics.Config.glob("*_slab", "slab"));
    }
}
