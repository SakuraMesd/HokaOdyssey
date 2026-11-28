package ink.hoka.odyssey.blocks;

import ink.hoka.odyssey.api.ModuleConfig;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Многоклеточный блок (верстак 2×1×2): клетки и поворот, проверка места, снятие всех частей, выдача носителей. */
class MultiTest {

    static final int[] SIZE = {2, 1, 2};

    /** Мир-таблица: всё, чего нет, — воздух; под y = 0 — камень (опора). */
    static final class MapWorld implements Multi.World {
        final Map<String, Block> m = new HashMap<>();
        static String k(int x, int y, int z) { return x + "," + y + "," + z; }
        @Override public Block get(int x, int y, int z) { return m.getOrDefault(k(x, y, z), y < 0 ? Block.STONE : Block.AIR); }
        @Override public void set(int x, int y, int z, Block b) { if (b.isAir()) m.remove(k(x, y, z)); else m.put(k(x, y, z), b); }
        @Override public boolean loaded(int x, int y, int z) { return true; }
    }

    static Set<String> cells(int x, int y, int z, int rot) {
        Set<String> s = new HashSet<>();
        for (int p = 0; p < Multi.parts(SIZE); p++) {
            int[] o = Multi.offset(SIZE, p, rot);
            s.add(MapWorld.k(x + o[0], y + o[1], z + o[2]));
        }
        return s;
    }

    @Test
    void cellsAndRotation() {
        assertEquals(4, Multi.parts(SIZE));
        // rot 0: в +X и +Z; дальше — поворот как у мода (rotateY(−rot)): (dx, dz) → 90: (−dz, dx), 180: (−dx, −dz), 270: (dz, −dx)
        assertEquals(Set.of("0,0,0", "1,0,0", "0,0,1", "1,0,1"), cells(0, 0, 0, 0));
        assertEquals(Set.of("0,0,0", "-1,0,0", "0,0,1", "-1,0,1"), cells(0, 0, 0, 90));
        assertEquals(Set.of("0,0,0", "-1,0,0", "0,0,-1", "-1,0,-1"), cells(0, 0, 0, 180));
        assertEquals(Set.of("0,0,0", "1,0,0", "0,0,-1", "1,0,-1"), cells(0, 0, 0, 270));
        for (int rot : new int[]{0, 90, 180, 270}) {
            assertArrayEquals(new int[]{0, 0, 0}, Multi.offset(SIZE, 0, rot), "главная клетка на месте");
            for (int p = 0; p < 4; p++) {
                int[] o = Multi.offset(SIZE, p, rot);
                assertArrayEquals(new int[]{0, 0, 0}, Multi.main(SIZE, o[0], o[1], o[2], p, rot), "главная по части " + p + "@" + rot);
                int[] l = Multi.local(SIZE, p);
                assertEquals(p, Multi.part(SIZE, l[0], l[1], l[2]));
            }
        }
        // лицо модели (−Z) смотрит на игрока: игрок смотрит на юг (yaw 0) → 0, на запад (90) → 90, …
        assertEquals(0, Multi.rotOf(0));
        assertEquals(90, Multi.rotOf(90));
        assertEquals(180, Multi.rotOf(-180));
        assertEquals(270, Multi.rotOf(-90));
        assertEquals(0, Multi.rotOf(44));
    }

    @Test
    void placeCheckAndRemove() {
        Block[] parts = BlocksModule.pool(BlocksModule.Kind.TRANSPARENT).subList(0, 4).toArray(Block[]::new);
        for (int rot : new int[]{0, 90, 180, 270}) {
            MapWorld w = new MapWorld();
            assertNull(Multi.check(w, SIZE, 5, 0, 5, rot));
            // занятая клетка → отказ
            int[] o = Multi.offset(SIZE, 3, rot);
            w.set(5 + o[0], 0, 5 + o[2], Block.STONE);
            assertNotNull(Multi.check(w, SIZE, 5, 0, 5, rot));
            w.set(5 + o[0], 0, 5 + o[2], Block.AIR);
            // нет опоры → отказ
            w.m.put(MapWorld.k(5 + o[0], -1, 5 + o[2]), Block.AIR);
            assertNotNull(Multi.check(w, SIZE, 5, 0, 5, rot));
            w.m.put(MapWorld.k(5 + o[0], -1, 5 + o[2]), Block.STONE);
            // трава заменяемая
            w.set(5 + o[0], 0, 5 + o[2], Block.SHORT_GRASS);
            assertNull(Multi.check(w, SIZE, 5, 0, 5, rot));

            Multi.place(w, parts, SIZE, 5, 0, 5, rot, false);
            assertEquals(cells(5, 0, 5, rot), w.m.keySet().stream().filter(k -> !k.contains(",-1,")).collect(java.util.stream.Collectors.toSet()));
            // сосед — не наш, не трогаем
            w.set(100, 0, 100, Block.STONE);
            // ломаем любую часть (её уже нет — как после ломания) → убраны остальные три
            for (int broken = 0; broken < 4; broken++) {
                MapWorld w2 = new MapWorld();
                Multi.place(w2, parts, SIZE, 5, 0, 5, rot, false);
                int[] b = Multi.offset(SIZE, broken, rot);
                w2.set(5 + b[0], 0, 5 + b[2], Block.AIR);
                assertEquals(3, Multi.remove(w2, parts, SIZE, 5 + b[0], 0, 5 + b[2], broken, rot));
                assertTrue(w2.m.isEmpty(), "осталась половинка: " + w2.m);
            }
        }
    }

    static BlocksModule.Def workbench() {
        return new BlocksModule.Def("space_workbench", "Космический верстак", BlocksModule.Kind.TRANSPARENT, true, Map.of(),
                "space_workbench.bbmodel", "cutout", null, true, null, SIZE, new int[]{0, 0, 0, 16, 24, 16}, "idle", "workbench");
    }

    @Test
    void carriersAllocatedOnce(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("states.yml");
        // уже выданное место другого блока — не трогается
        Files.writeString(f, "states:\n  mortar@0: \"" + BlocksModule.pool(BlocksModule.Kind.TRANSPARENT).get(0).state() + "\"\n");
        BlocksModule.Def mortar = new BlocksModule.Def("mortar", "Ступка", BlocksModule.Kind.TRANSPARENT, true, Map.of(),
                "mortar.bbmodel", "cutout", null, true, null);
        var a = BlocksModule.allocate(new ModuleConfig(f), List.of(mortar, workbench()));
        assertTrue(a.problems().isEmpty(), a.problems().toString());
        assertEquals(3 + 16, a.fresh(), "ступке 3 новых поворота, верстаку 16 мест");
        Map<Integer, Block[]> wb = a.blockOf().get("space_workbench");
        assertEquals(4, wb.size());
        Set<Integer> ids = new HashSet<>();
        for (Block[] parts : wb.values()) { assertEquals(4, parts.length); for (Block b : parts) assertTrue(ids.add(b.stateId())); }
        assertEquals(16, ids.size());
        assertFalse(ids.contains(BlocksModule.pool(BlocksModule.Kind.TRANSPARENT).get(0).stateId()), "чужое место занято");
        assertEquals(BlocksModule.pool(BlocksModule.Kind.TRANSPARENT).get(0).stateId(), a.blockOf().get("mortar").get(0)[0].stateId());
        var placed = a.byState().get(wb.get(90)[3].stateId());
        assertEquals(90, placed.rot());
        assertEquals(3, placed.part());
        assertEquals(20, a.used().get(BlocksModule.Kind.TRANSPARENT));
        String saved = Files.readString(f);
        assertTrue(saved.contains("space_workbench@90#3"), saved);

        // повторный запуск: ничего нового, всё на тех же местах
        var b = BlocksModule.allocate(new ModuleConfig(f), List.of(mortar, workbench()));
        assertEquals(0, b.fresh());
        for (int rot : new int[]{0, 90, 180, 270})
            for (int p = 0; p < 4; p++) assertEquals(wb.get(rot)[p].stateId(), b.blockOf().get("space_workbench").get(rot)[p].stateId());
        assertEquals(saved, Files.readString(f));
    }

    @Test
    void parseInts() {
        assertArrayEquals(new int[]{2, 1, 2}, BlocksModule.ints(List.of(2, 1, 2), 3, 1, 4));
        assertArrayEquals(new int[]{0, 0, 0, 16, 24, 16}, BlocksModule.ints(List.of(0, 0, 0, 16, 24, 16), 6, 0, 32));
        assertNull(BlocksModule.ints(List.of(2, 2), 3, 1, 4));
        assertNull(BlocksModule.ints("2x2", 3, 1, 4));
        assertEquals("space_workbench@90#3", BlocksModule.stateKey(workbench(), 90, 3));
        assertEquals("space_workbench@0", BlocksModule.stateKey(workbench(), 0, 0));
    }
}
