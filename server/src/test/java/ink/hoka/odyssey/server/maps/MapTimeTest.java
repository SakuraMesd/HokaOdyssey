package ink.hoka.odyssey.server.maps;

import ink.hoka.odyssey.api.ModuleConfig;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Время суток карты: раздел time: в map.yml, правка файла без потери комментариев, ход с дробной скоростью. */
class MapTimeTest {

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    @TempDir Path tmp;

    @Test
    void readSection() throws Exception {
        Path f = tmp.resolve("a.yml");
        Files.writeString(f, "tall: true\ntime: {cycle: false, speed: 2.5, start: 25000}\n");
        var c = MapTime.read(new ModuleConfig(f));
        assertFalse(c.cycle());
        assertEquals(2.5, c.speed());
        assertEquals(1000L, c.start());                                    // по модулю суток
        Files.writeString(f, "tall: true\n");
        assertEquals(MapTime.Cfg.DEFAULT, MapTime.read(new ModuleConfig(f)));
        Files.writeString(f, "time: {speed: 999}\n");
        assertEquals(MapTime.MAX_SPEED, MapTime.read(new ModuleConfig(f)).speed());
    }

    @Test
    void patchKeepsComments() throws Exception {
        String src = "# карта\ntall: true   # высокий\ntime:\n  cycle: true\n  # внутри\n  speed: 1.0\n# после\nspawn:\n  x: 1\n";
        String out = MapTime.patchText(src, new MapTime.Cfg(false, 2.0, 6000L));
        assertEquals("# карта\ntall: true   # высокий\ntime: {cycle: false, speed: 2.0, start: 6000}\n# после\nspawn:\n  x: 1\n", out);
        String added = MapTime.patchText("tall: true\n\n", new MapTime.Cfg(true, 0.5, null));
        assertEquals("tall: true\ntime: {cycle: true, speed: 0.5}\n\n", added);

        Path f = tmp.resolve("map.yml");
        Files.writeString(f, src, StandardCharsets.UTF_8);
        var meta = new ModuleConfig(f);
        MapTime.save(meta, new MapTime.Cfg(true, 3.0, 1000L));
        assertEquals(3.0, meta.getDouble("time.speed", 0));                // meta перечитан
        String text = Files.readString(f);
        assertTrue(text.contains("# карта") && text.contains("# после"), "комментарии вне раздела time: целы");
        assertEquals(1, meta.getInt("spawn.x", 0));
    }

    @Test
    void parseAndLabel() {
        assertEquals(1000L, MapTime.parse("day"));
        assertEquals(18000L, MapTime.parse("MIDNIGHT"));
        assertEquals(123L, MapTime.parse("123"));
        assertNull(MapTime.parse("вечер?"));
        assertEquals("06:00 (0)", MapTime.label(0));
        assertEquals("12:00 (6000)", MapTime.label(6000));
        assertEquals("00:00 (18000)", MapTime.label(18000 + 24000 * 5));
    }

    @Test
    void fractionalSpeedTicks() {
        InstanceContainer inst = MinecraftServer.getInstanceManager().createInstanceContainer();
        try {
            inst.setTime(0);
            MapTime.apply(inst, new MapTime.Cfg(true, 0.5, 100L), true);
            assertEquals(100, inst.getTime());
            assertEquals(0, inst.getTimeRate(), "speed ≠ 1 — свой ход, клиенту «время стоит» + пакеты");
            for (int i = 0; i < 10; i++) MinecraftServer.getSchedulerManager().processTick();
            assertEquals(105, inst.getTime());
            MapTime.apply(inst, new MapTime.Cfg(true, 1.0, null), false);
            assertEquals(1, inst.getTimeRate(), "speed 1 — штатный ход Minestom");
            MapTime.apply(inst, new MapTime.Cfg(false, 1.0, null), false);
            long t = inst.getTime();
            for (int i = 0; i < 10; i++) MinecraftServer.getSchedulerManager().processTick();
            assertEquals(t, inst.getTime());
            assertEquals(0, inst.getTimeRate());
        } finally {
            MinecraftServer.getInstanceManager().unregisterInstance(inst);
        }
    }

    @Test
    void overworldHasTimelines() throws Exception {
        DimensionType o = ink.hoka.odyssey.server.tall.TallWorldAccess.overworld();
        assertNotNull(o, "ванильный overworld с timelines есть в реестре");
        assertTrue(o.timelines().size() > 0);
        assertFalse(o.attributes().entries().isEmpty(), "цвет неба/тумана заданы");
        DimensionType t = ink.hoka.odyssey.server.tall.TallWorldAccess.tall(o, -15008, 30016, true, 0);
        assertEquals(o.timelines(), t.timelines());
        assertEquals(o.attributes(), t.attributes());
        assertEquals(-15008, t.minY());
        assertFalse(t.hasFixedTime());
    }
}
