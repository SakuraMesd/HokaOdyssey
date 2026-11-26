package ink.hoka.odyssey.server.tall;

import ink.hoka.odyssey.api.worldgen.Bounds;
import ink.hoka.odyssey.api.worldgen.GenSettings;
import ink.hoka.odyssey.gen.GenObjects;
import ink.hoka.odyssey.server.objects.ObjectLibrary;
import ink.hoka.odyssey.server.objects.ObjectPacks;
import ink.hoka.odyssey.server.tall.gen.ModuleCubeGen;
import ink.hoka.odyssey.server.tall.gen.PortableGens;
import ink.hoka.odyssey.server.tall.gen.PortableProvider;
import net.minestom.server.MinecraftServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Расхождение 26ноя (hoka23, «хэш op 21» в кубах -4 19 -4, -3 19 -4, 2 20 -4, 7 20 5): vale пишет в куб над рельефом
 * только биомы (блоков нет), сервер клал такой куб общей пустой секцией (биом 0) — хэш в памяти ≠ генерации клиента.
 * Путь загрузки ленивого куба (CubeRegionLoader.generate → секция) обязан давать ту же видимую часть, что и visHash.
 */
class CubeGenBiomeAirTest {

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    @Test
    void lazyLoadKeepsBiomesOfAirCube() throws Exception {
        byte[] jar = Files.readAllBytes(Path.of(System.getProperty("hoka.test.vale-jar")));
        var l = PortableGens.load(jar);
        PortableProvider pp = new PortableProvider(l.provider(), l.sha256(), jar, l.manifest());
        GenObjects objs = ObjectPacks.build(new ObjectLibrary(Path.of(System.getProperty("hoka.test.demo-objects-dir"))),
                List.of("oak", "birch", "spruce", "bushes", "rocks", "glycine", "azure", "palm", "glowfingers", "bellflowers", "rafflesia"));
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("sea_level", 300);
        opts.put("tree_density", 1.0);
        var g = new ModuleCubeGen(pp, new GenSettings("hoka23", 20261126L, new Bounds(-500, 2000, 25000), opts), 5L, objs);
        int bad = 0, airBiome = 0;
        StringBuilder sb = new StringBuilder();
        for (int cx = -6; cx <= 9; cx++) for (int cy = 18; cy <= 21; cy++) for (int cz = -6; cz <= 6; cz++) {
            if (g.uniform(cx, cy, cz) != null) continue;                    // однородные — меткой без хэша
            CubeRegionLoader.Loaded d = CubeRegionLoader.generate(g, cx, cy - CubeRegionLoader.MIN_SECTION, cz);
            var live = d.section() == null ? TallSections.EMPTY : d.section();
            if (d.section() != null && d.section().blockPalette().count() == 0) airBiome++;
            if (CubeHash.of(live) != g.visHash(cx, cy, cz)) { bad++; if (bad < 10) sb.append(cx).append(' ').append(cy).append(' ').append(cz).append("; "); }
        }
        System.out.println("[biome-air] кубов воздух+биомы " + airBiome + ", расхождений " + bad);
        assertEquals(0, bad, sb.toString());
    }
}
