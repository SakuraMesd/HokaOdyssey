package ink.hoka.odyssey.blocks;

import ink.hoka.odyssey.api.ModuleConfig;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** plasma-pipes-1: коллизии деталей (стыки по сторонам на 4 поворотах) и варианты анимации (animations). */
class PlasmaPipesTest {

    @BeforeAll static void init() { MinecraftServer.init(); }

    enum Side { NORTH, EAST, SOUTH, WEST, DOWN, UP }

    static final int[] ONE = {1, 1, 1};

    /** Стыки деталей при повороте 0 (таблица задачи). */
    static final Map<String, Set<Side>> JOINTS = Map.of(
            "straight", EnumSet.of(Side.NORTH, Side.SOUTH),
            "straight_vertical", EnumSet.of(Side.DOWN, Side.UP),
            "elbow", EnumSet.of(Side.NORTH, Side.EAST),
            "elbow_up", EnumSet.of(Side.NORTH, Side.UP),
            "elbow_down", EnumSet.of(Side.NORTH, Side.DOWN),
            "tee", EnumSet.of(Side.NORTH, Side.SOUTH, Side.EAST));

    static double[][][] collision(String part) throws Exception {
        return Collision.parse(Files.readString(Path.of("../../demo-objects/plasma_pipes/collision/plasma_pipe_" + part + ".json"),
                StandardCharsets.UTF_8), ONE);
    }

    /** Стороны клетки, которых касаются коробки (труба выходит в эту грань). */
    static Set<Side> open(List<double[]> boxes) {
        Set<Side> s = EnumSet.noneOf(Side.class);
        for (double[] b : boxes) {
            if (b[2] <= 0) s.add(Side.NORTH);
            if (b[5] >= 16) s.add(Side.SOUTH);
            if (b[0] <= 0) s.add(Side.WEST);
            if (b[3] >= 16) s.add(Side.EAST);
            if (b[1] <= 0) s.add(Side.DOWN);
            if (b[4] >= 16) s.add(Side.UP);
        }
        return s;
    }

    /** Сторона после поворота на rot (как Multi/модель: 90 — север уходит на восток). */
    static Side turn(Side s, int rot) {
        if (s == Side.UP || s == Side.DOWN) return s;
        Side[] ring = {Side.NORTH, Side.EAST, Side.SOUTH, Side.WEST};
        int i = List.of(ring).indexOf(s);
        return ring[(i + rot / 90) % 4];
    }

    @Test
    void jointsFollowRotation() throws Exception {
        for (var e : JOINTS.entrySet()) {
            double[][][] c = collision(e.getKey());
            assertEquals(1, c.length);
            for (double[] b : c[0]) assertTrue(b[0] >= 0 && b[3] <= 16 && b[1] >= 0 && b[4] <= 16 && b[2] >= 0 && b[5] <= 16, e.getKey());
            for (int rot : new int[]{0, 90, 180, 270}) {
                Set<Side> want = EnumSet.noneOf(Side.class);
                for (Side s : e.getValue()) want.add(turn(s, rot));
                assertEquals(want, open(Collision.world(c, ONE, rot)), e.getKey() + " поворот " + rot);
            }
            // сечение трубы 8×8 по оси: середина клетки занята, углы — нет (по верху можно ходить, сквозь — нет)
            List<double[]> w = Collision.world(c, ONE, 0);
            assertTrue(w.stream().anyMatch(b -> Collision.contains(b, 8, 8, 8)), e.getKey() + ": середина пустая");
            assertTrue(w.stream().noneMatch(b -> Collision.contains(b, 1, 1, 1) || Collision.contains(b, 15, 15, 15)), e.getKey() + ": углы заняты");
        }
    }

    static BlocksModule.Def pipe(String id, boolean rotatable, double[][][] col) {
        return new BlocksModule.Def(id, "Плазменная труба", BlocksModule.Kind.TRANSPARENT, rotatable, Map.of(),
                id + ".bbmodel", "cutout", null, true, null, ONE, null, "flow", null, col, null, List.of("flow", "flow_reverse"));
    }

    @Test
    void variantsGetOwnPlacesAndSurviveReload(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("states.yml");
        var elbow = pipe("plasma_pipe_elbow_cyan", true, collision("elbow"));
        var vertical = pipe("plasma_pipe_straight_vertical_cyan", false, collision("straight_vertical"));
        assertEquals(2, elbow.variants());
        assertEquals("flow_reverse", elbow.animOf(1));
        var a = BlocksModule.allocate(new ModuleConfig(f), List.of(elbow, vertical));
        assertTrue(a.problems().isEmpty(), a.problems().toString());
        assertEquals(4 * 2 + 2, a.fresh(), "колено: 4 поворота × 2 варианта, вертикаль: 1 × 2");
        var m = a.blockOf().get("plasma_pipe_elbow_cyan");
        assertEquals(8, m.size());
        for (int rot : new int[]{0, 90, 180, 270}) {
            Block fwd = m.get(BlocksModule.key(rot, 0))[0], rev = m.get(BlocksModule.key(rot, 1))[0];
            assertNotEquals(fwd.stateId(), rev.stateId());
            var p = a.byState().get(rev.stateId());
            assertEquals(rot, p.rot());
            assertEquals(1, p.variant());
            assertEquals(rev.stateId(), m.get(p.key())[0].stateId(), "по месту — тот же блок варианта");
            assertTrue(fwd.key().asString().startsWith("hoka_odyssey:clear_"), "блок мода, не листва");
        }
        String saved = Files.readString(f);
        assertTrue(saved.contains("plasma_pipe_elbow_cyan@90~1"), saved);
        assertTrue(saved.contains("plasma_pipe_straight_vertical_cyan~1"), saved);

        // перезапуск (и чанк перезагружен — в мире лежит тот же блок): места те же, вариант узнаётся по блоку
        var b = BlocksModule.allocate(new ModuleConfig(f), List.of(elbow, vertical));
        assertEquals(0, b.fresh());
        Block rev90 = m.get(BlocksModule.key(90, 1))[0];
        assertEquals(1, b.byState().get(rev90.stateId()).variant());
        assertEquals(90, b.byState().get(rev90.stateId()).rot());
        assertEquals(saved, Files.readString(f));
    }

    @Test
    void variantNames() {
        assertEquals("течение вперёд", BlocksModule.variantName("flow"));
        assertEquals("течение обратно", BlocksModule.variantName("flow_reverse"));
    }
}
