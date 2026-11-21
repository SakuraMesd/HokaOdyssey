package ink.hoka.odyssey.server.blocks;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Правила установки на подставном мире (без клиента): ступеньки, сундуки, плиты, бочка, бревно. */
class BlockRulesTest {

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    /** Мир-карта: всё, чего нет, — воздух. */
    static final class World implements Block.Getter {
        final Map<Vec, Block> m = new HashMap<>();
        @Override public Block getBlock(int x, int y, int z, Condition c) { return m.getOrDefault(new Vec(x, y, z), Block.AIR); }
        void set(Point p, Block b) { m.put(new Vec(p.blockX(), p.blockY(), p.blockZ()), b); }
        Block get(Point p) { return getBlock(p); }

        /** Поставить как игрок + обновить соседей (как Minestom). */
        Block place(Block item, Point pos, BlockFace face, Point cursor, Pos player, boolean sneak) {
            BlockPlacementRule r = BlockRules.ruleFor(item);
            Block b = r.blockPlace(new BlockPlacementRule.PlacementState(this, item, face, pos, cursor, player, ItemStack.AIR, sneak));
            set(pos, b);
            for (Dir d : Dir.values()) {
                Point n = d.rel(pos);
                Block nb = get(n);
                BlockPlacementRule nr = nb.isAir() ? null : BlockRules.ruleFor(nb);
                if (nr != null) set(n, nr.blockUpdate(new BlockPlacementRule.UpdateState(this, n, nb, BlockFace.TOP)));
            }
            return get(pos);
        }
    }

    static Pos looking(float yaw, float pitch) { return new Pos(0, 0, 0, yaw, pitch); }

    static final float NORTH = 180, SOUTH = 0, EAST = -90, WEST = 90;

    @Test
    void stairsFacingAndHalf() {
        World w = new World();
        Block s = w.place(Block.OAK_STAIRS, new Vec(0, 0, 0), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(NORTH, 0), false);
        assertEquals("north", s.getProperty("facing"));
        assertEquals("bottom", s.getProperty("half"));
        Block t = w.place(Block.OAK_STAIRS, new Vec(5, 0, 0), BlockFace.SOUTH, new Vec(0.5, 0.8, 0), looking(NORTH, 0), false);
        assertEquals("top", t.getProperty("half"), "клик по верхней половине боковой грани — перевёрнутая");
    }

    @Test
    void stairsCorners() {
        World w = new World();
        // A смотрит на север; спереди (z−1) — ступенька, смотрящая на восток → внешний угол
        w.place(Block.OAK_STAIRS, new Vec(0, 0, -1), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(EAST, 0), false);
        Block a = w.place(Block.OAK_STAIRS, new Vec(0, 0, 0), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(NORTH, 0), false);
        assertEquals("outer_right", a.getProperty("shape"));
        // B смотрит на север; сзади (z+1) — ступенька на запад, поставленная ПОСЛЕ → B пересчитывается во внутренний угол
        World w2 = new World();
        w2.place(Block.OAK_STAIRS, new Vec(0, 0, 0), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(NORTH, 0), false);
        w2.place(Block.OAK_STAIRS, new Vec(0, 0, 1), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(WEST, 0), false);
        assertEquals("inner_left", w2.get(new Vec(0, 0, 0)).getProperty("shape"));
    }

    @Test
    void chestsConnect() {
        World w = new World();
        Block c1 = w.place(Block.CHEST, new Vec(0, 0, 0), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(NORTH, 0), false);
        assertEquals("south", c1.getProperty("facing"), "сундук лицом к игроку");
        assertEquals("single", c1.getProperty("type"));
        Block c2 = w.place(Block.CHEST, new Vec(1, 0, 0), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(NORTH, 0), false);
        assertEquals("left", c2.getProperty("type"));
        assertEquals("right", w.get(new Vec(0, 0, 0)).getProperty("type"), "первый сундук пристроился");
        // Shift — не соединять
        Block c3 = w.place(Block.CHEST, new Vec(0, 0, 5), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(NORTH, 0), false);
        Block c4 = w.place(Block.CHEST, new Vec(1, 0, 5), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(NORTH, 0), true);
        assertEquals("single", c4.getProperty("type"));
        assertEquals("single", w.get(new Vec(0, 0, 5)).getProperty("type"));
        // сломали половину — вторая снова одиночная
        w.set(new Vec(1, 0, 0), Block.AIR);
        Block left = w.get(new Vec(0, 0, 0));
        w.set(new Vec(0, 0, 0), BlockRules.ruleFor(left).blockUpdate(new BlockPlacementRule.UpdateState(w, new Vec(0, 0, 0), left, BlockFace.EAST)));
        assertEquals("single", w.get(new Vec(0, 0, 0)).getProperty("type"));
        // сундук-ловушка не соединяется с обычным
        Block tr = w.place(Block.TRAPPED_CHEST, new Vec(-1, 0, 0), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(NORTH, 0), false);
        assertEquals("single", tr.getProperty("type"));
    }

    @Test
    void slabsDouble() {
        World w = new World();
        Block s = w.place(Block.STONE_SLAB, new Vec(0, 0, 0), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(NORTH, 0), false);
        assertEquals("bottom", s.getProperty("type"));
        BlockPlacementRule r = BlockRules.ruleFor(Block.STONE_SLAB);
        assertTrue(r.isSelfReplaceable(new BlockPlacementRule.Replacement(s, BlockFace.TOP, new Vec(0.5, 0.5, 0.5), false, Material.STONE_SLAB)));
        assertFalse(r.isSelfReplaceable(new BlockPlacementRule.Replacement(s, BlockFace.TOP, new Vec(0.5, 0.5, 0.5), false, Material.OAK_SLAB)));
        Block d = w.place(Block.STONE_SLAB, new Vec(0, 0, 0), BlockFace.TOP, new Vec(0.5, 0.5, 0.5), looking(NORTH, 0), false);
        assertEquals("double", d.getProperty("type"));
    }

    @Test
    void barrelLogFurnace() {
        World w = new World();
        assertEquals("up", w.place(Block.BARREL, new Vec(0, 0, 0), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(NORTH, 80), false).getProperty("facing"),
                "бочка, если смотреть вниз — крышкой вверх");
        assertEquals("south", w.place(Block.BARREL, new Vec(2, 0, 0), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(NORTH, 0), false).getProperty("facing"));
        assertEquals("x", w.place(Block.OAK_LOG, new Vec(4, 0, 0), BlockFace.EAST, new Vec(1, 0.5, 0.5), looking(WEST, 0), false).getProperty("axis"));
        assertEquals("south", w.place(Block.FURNACE, new Vec(6, 0, 0), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(NORTH, 0), false).getProperty("facing"));
        assertEquals("north", w.place(Block.OBSERVER, new Vec(8, 0, 0), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(NORTH, 0), false).getProperty("facing"));
        assertEquals(Block.WALL_TORCH.id(), w.place(Block.TORCH, new Vec(10, 0, 0), BlockFace.EAST, new Vec(1, 0.5, 0.5), looking(WEST, 0), false).id());
    }

    @Test
    void fencesConnect() {
        World w = new World();
        w.place(Block.OAK_FENCE, new Vec(0, 0, 0), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(NORTH, 0), false);
        w.place(Block.OAK_FENCE, new Vec(1, 0, 0), BlockFace.TOP, new Vec(0.5, 1, 0.5), looking(NORTH, 0), false);
        assertEquals("true", w.get(new Vec(0, 0, 0)).getProperty("east"));
        assertEquals("true", w.get(new Vec(1, 0, 0)).getProperty("west"));
        assertEquals("false", w.get(new Vec(1, 0, 0)).getProperty("east"));
    }

    @Test
    void everyRuleHandlesAllFaces() {
        // каждое правило должно отдать блок для любой грани и взгляда — без исключений
        World w = new World();
        int checked = 0;
        for (Block b : Block.values()) {
            BlockPlacementRule r = BlockRules.ruleFor(b);
            if (r == null) continue;
            for (BlockFace f : BlockFace.values()) for (float yaw : new float[]{NORTH, SOUTH, EAST, WEST}) {
                Block res = r.blockPlace(new BlockPlacementRule.PlacementState(w, b, f, new Vec(0, 64, 0), new Vec(0.3, 0.7, 0.6), looking(yaw, 30), ItemStack.AIR, false));
                assertNotNull(res, b.name() + " " + f);
            }
            checked++;
        }
        assertTrue(checked > 300, "правил мало: " + checked);
    }
}
