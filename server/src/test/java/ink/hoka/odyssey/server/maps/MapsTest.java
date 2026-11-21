package ink.hoka.odyssey.server.maps;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Постройки сохраняются: блок → сохранить → карта загружается заново с диска → блок на месте. */
class MapsTest {

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    // Windows держит открытые region-файлы → авто-уборка временной папки падает; на Linux-сервере это не проблема
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tmp;

    @Test
    void blocksSurviveReload() throws Exception {
        Maps a = new Maps(tmp);
        Maps.GameMap m = a.create("build", Maps.Generator.FLAT);
        m.instance.loadChunk(0, 0).get(30, TimeUnit.SECONDS);
        m.instance.setBlock(3, 41, 3, Block.CHEST.withProperty("facing", "east"));
        m.instance.setBlock(4, 41, 3, Block.OAK_STAIRS.withProperty("facing", "west"));
        a.saveAllBlocking();
        assertTrue(Files.list(tmp.resolve("build/region")).findAny().isPresent(), "файлы region/*.mca записаны");
        MinecraftServer.getInstanceManager().unregisterInstance(m.instance);

        Maps b = new Maps(tmp);                                            // как после перезапуска сервера
        Maps.GameMap m2 = b.load("build");
        assertNotSame(m.instance, m2.instance);
        m2.instance.loadChunk(0, 0).get(30, TimeUnit.SECONDS);
        Block chest = m2.instance.getBlock(3, 41, 3);
        assertEquals(Block.CHEST.id(), chest.id());
        assertEquals("east", chest.getProperty("facing"));
        assertEquals("west", m2.instance.getBlock(4, 41, 3).getProperty("facing"));
        assertEquals(Block.GRASS_BLOCK.id(), m2.instance.getBlock(10, 40, 10).id(), "генерация осталась");
    }

    @Test
    void createListDelete() throws Exception {
        Maps a = new Maps(tmp);
        a.create("one", Maps.Generator.VOID);
        assertTrue(a.names().contains("one"));
        assertThrows(IllegalArgumentException.class, () -> a.create("one", Maps.Generator.FLAT));
        assertThrows(IllegalArgumentException.class, () -> a.create("Bad Name", Maps.Generator.FLAT));
        a.delete("one").get(30, TimeUnit.SECONDS);
        assertFalse(a.names().contains("one"));
        assertFalse(Files.exists(tmp.resolve("one")));
    }
}
