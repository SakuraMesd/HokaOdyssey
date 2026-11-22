package ink.hoka.odyssey.ships;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Модель корабля из настоящих ванильных ассетов. Нужен клиент 1.21.11: переменная окружения HOKA_CLIENT_JAR
 * (путь к jar), иначе тест пропускается. Чертёж — HOKA_TEMPLATE (json) или встроенный пример.
 */
class ShipModelTest {

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    @Test
    void buildsValidModel() throws Exception {
        String jar = System.getenv("HOKA_CLIENT_JAR");
        Assumptions.assumeTrue(jar != null && Files.isRegularFile(Path.of(jar)), "нет HOKA_CLIENT_JAR — пропуск");
        String tpl = System.getenv("HOKA_TEMPLATE");
        ShipTemplate t = tpl != null ? ShipTemplate.load(Path.of(tpl)) : ShipsMathTest.sample();
        try (ZipFile z = new ZipFile(jar)) {
            ShipModel m = ShipModel.build(t, 0.5, p -> {
                try {
                    ZipEntry e = z.getEntry(p);
                    if (e == null) return null;
                    try (InputStream in = z.getInputStream(e)) { return in.readAllBytes(); }
                } catch (Exception ex) { return null; }
            });
            System.out.println("элементов: " + m.elements + ", масштаб: " + m.scale + ", JSON: " + m.modelJson.length() / 1024 + " КБ");
            assertTrue(m.elements > 0);
            JsonObject model = JsonParser.parseString(m.modelJson).getAsJsonObject();
            JsonObject textures = model.getAsJsonObject("textures");
            for (JsonElement el : model.getAsJsonArray("elements")) {
                JsonObject e = el.getAsJsonObject();
                for (String k : new String[]{"from", "to"})
                    for (JsonElement v : e.getAsJsonArray(k)) {
                        double d = v.getAsDouble();
                        assertTrue(d >= -16 && d <= 32, k + " вне −16..32: " + d);
                    }
                for (var f : e.getAsJsonObject("faces").entrySet()) {
                    String ref = f.getValue().getAsJsonObject().get("texture").getAsString();
                    assertTrue(ref.startsWith("#") && textures.has(ref.substring(1)), "нет текстуры " + ref);
                    String tex = textures.get(ref.substring(1)).getAsString();
                    assertNotNull(z.getEntry("assets/minecraft/textures/" + tex.substring(tex.indexOf(':') + 1) + ".png"), "текстура не существует: " + tex);
                }
                if (e.has("rotation")) {
                    double a = e.getAsJsonObject("rotation").get("angle").getAsDouble();
                    assertTrue(Math.abs(a) <= 45, "угол " + a);
                }
            }
            JsonParser.parseString(m.itemJson);
        }
    }

    @Test
    void stairsRotationLikeVanilla() {
        // ступенька facing=south: вариант y=90 поворачивает модель «смотрит на восток» на юг
        int[] e = {1, 0, 0};
        double[] r = ShipModel.Resolver.turn(new double[]{e[0], e[1], e[2]}, 0, 90);
        assertEquals(0, r[0], 1e-9); assertEquals(1, r[2], 1e-9);
        assertEquals("south", ShipModel.Resolver.turnDir("east", 0, 90));
        assertEquals("down", ShipModel.Resolver.turnDir("north", 90, 0));          // как в игре: наблюдатель facing=down — x=90
        assertEquals("up", ShipModel.Resolver.turnDir("north", 270, 0));           // facing=up — x=270
        assertEquals("east", ShipModel.Resolver.turnDir("north", 0, 90));          // facing=east — y=90
        assertNotNull(Block.OAK_STAIRS);
    }
}
