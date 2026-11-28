package ink.hoka.odyssey.survival;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** PvP-зоны: попадание точки в коробку, границы, несколько зон, значение карты, файл, режимы боя игроков. */
class ZoneBookTest {

    @Test
    void boxBounds() {
        ZoneBook.Zone z = ZoneBook.Zone.of("a", "m", 10, 0, 10, -10, 20, -10);   // углы в любом порядке
        assertEquals(-10, z.x0());
        assertEquals(10, z.x1());
        assertTrue(z.contains(0, 5, 0));
        assertTrue(z.contains(-10, 0, -10));                                  // нижний угол — внутри
        assertTrue(z.contains(10.999, 20.999, 10.999));                       // блок x1 — целиком внутри
        assertFalse(z.contains(11, 5, 0));                                    // за блоком x1 — снаружи
        assertFalse(z.contains(-10.001, 5, 0));
        assertFalse(z.contains(0, 21, 0));
        assertFalse(z.contains(0, -0.5, 0));
        assertEquals(21L * 21 * 21, z.volume());
    }

    @Test
    void severalZonesAndMaps() {
        ZoneBook b = ZoneBook.EMPTY
                .with(ZoneBook.Zone.of("arena", "cosmos", 0, 0, 0, 9, 9, 9))
                .with(ZoneBook.Zone.of("belt", "cosmos", 100, -50, 100, 200, 50, 200))
                .with(ZoneBook.Zone.of("pit", "vale", 0, 0, 0, 9, 9, 9));
        assertEquals("arena", b.zoneAt("cosmos", 5, 5, 5).name());
        assertEquals("belt", b.zoneAt("cosmos", 150, 0, 150).name());
        assertNull(b.zoneAt("cosmos", 50, 0, 50));
        assertTrue(b.isPvp("cosmos", 5, 5, 5));
        assertFalse(b.isPvp("cosmos", 50, 0, 50));                            // вне зон, у карты ничего — не PvP
        assertTrue(b.isPvp("vale", 1, 1, 1));
        assertFalse(b.isPvp("other", 5, 5, 5));                               // та же коробка, другая карта
        assertFalse(b.isPvp(null, 5, 5, 5));
        // значение карты вне зон
        ZoneBook d = b.withDefault("vale", true);
        assertTrue(d.isPvp("vale", 500, 0, 500));
        assertFalse(d.isPvp("cosmos", 500, 0, 500));
        assertFalse(d.withDefault("vale", null).isPvp("vale", 500, 0, 500));
        // замена зоны с тем же именем (без учёта регистра), удаление
        ZoneBook r = b.with(ZoneBook.Zone.of("ARENA", "cosmos", 20, 0, 20, 30, 5, 30));
        assertEquals(3, r.zones().size());
        assertNull(r.zoneAt("cosmos", 5, 5, 5));
        assertNotNull(r.zoneAt("cosmos", 25, 1, 25));
        ZoneBook w = r.without("arena");
        assertEquals(2, w.zones().size());
        assertSame(w, w.without("нет-такой"));
        assertEquals(java.util.List.of("belt", "pit"), w.names());
    }

    @Test
    void jsonRoundTrip(@TempDir Path dir) throws Exception {
        ZoneBook b = ZoneBook.EMPTY.with(ZoneBook.Zone.of("arena", "cosmos", -5, 0, -5, 5, 10, 5)).withDefault("vale", true).withDefault("cosmos", false);
        ZoneBook.Store st = new ZoneBook.Store(dir.resolve("pvp-zones.json"));
        assertEquals(0, st.load().zones().size());                            // нет файла — пусто
        assertFalse(st.broken());
        assertTrue(st.write(b.toJson()));
        ZoneBook l = new ZoneBook.Store(dir.resolve("pvp-zones.json")).load();
        assertEquals(b.zones(), l.zones());
        assertEquals(b.defaults(), l.defaults());
        assertFalse(Files.exists(dir.resolve("pvp-zones.json.tmp")));
    }

    @Test
    void brokenFileIsKept(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("pvp-zones.json");
        Files.writeString(f, "{\"zones\":[{\"name\":\"a\"");
        ZoneBook.Store st = new ZoneBook.Store(f);
        assertTrue(st.load().zones().isEmpty());
        assertTrue(st.broken());
        assertTrue(Files.exists(dir.resolve("pvp-zones.json.broken")));
        assertFalse(st.write(ZoneBook.EMPTY.toJson()));                      // битый не перезаписываем
        assertEquals("{\"zones\":[{\"name\":\"a\"", Files.readString(f));
        Files.writeString(f, "{\"zones\":[{\"name\":\"a\",\"map\":\"m\",\"min\":[0,0,0],\"max\":[1,1,1]},"
                + "{\"name\":\"A\",\"map\":\"m\",\"min\":[0,0,0],\"max\":[1,1,1]}]}");
        new ZoneBook.Store(f).load();
        ZoneBook.Store st2 = new ZoneBook.Store(f);
        st2.load();
        assertTrue(st2.broken());                                             // два одинаковых имени — тоже битый
    }

    @Test
    void modes() {
        assertEquals("all", ZoneBook.mode(null, true));
        assertEquals("off", ZoneBook.mode(null, false));
        assertEquals("zones", ZoneBook.mode("Zones", true));
        assertEquals("off", ZoneBook.mode("false", true));                     // YAML: off без кавычек читается как false
        assertEquals("all", ZoneBook.mode("чепуха", false));
        assertTrue(ZoneBook.playerPvp("all", false, false));
        assertFalse(ZoneBook.playerPvp("off", true, true));
        assertTrue(ZoneBook.playerPvp("zones", true, true));
        assertFalse(ZoneBook.playerPvp("zones", true, false));
        assertFalse(ZoneBook.playerPvp("zones", false, true));
        assertTrue(ZoneBook.validName("arena_1-b"));
        assertTrue(ZoneBook.validName("арена"));
        assertFalse(ZoneBook.validName("a b"));
        assertFalse(ZoneBook.validName(""));
    }

    @Test
    void safeZoneInsidePvpMap() {
        ZoneBook b = ZoneBook.EMPTY.withDefault("cosmos", true)
                .with(ZoneBook.Zone.of("base", "cosmos", 0, 0, 0, 99, 99, 99, false))
                .with(ZoneBook.Zone.of("ring", "cosmos", 40, 40, 40, 59, 59, 59));            // арена внутри базы
        assertTrue(b.isPvp("cosmos", 500, 50, 500), "вне зон на cosmos — PvP");
        assertFalse(b.isPvp("cosmos", 10, 10, 10), "в безопасной зоне — без PvP");
        assertTrue(b.isPvp("cosmos", 50, 50, 50), "меньшая вложенная PvP-зона главнее");
        assertFalse(b.isPvp("world", 10, 10, 10));
        ZoneBook back = ZoneBook.fromJson(b.toJson());                                         // флаг переживает файл
        assertFalse(back.zone("base").pvp());
        assertTrue(back.zone("ring").pvp());
        assertFalse(back.isPvp("cosmos", 10, 10, 10));
    }
}
