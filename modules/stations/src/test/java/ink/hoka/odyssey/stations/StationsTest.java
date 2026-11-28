package ink.hoka.odyssey.stations;

import ink.hoka.odyssey.gravity.VacuumService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Регионы станций: попадание точки, пересечения, файл, выделение и expand, ответ гравитации (modeAt). */
class StationsTest {

    private static Region region(String id, long created, Grav g, Box... boxes) {
        return new Region(id, "cosmos", id, null, g, List.of(boxes), created, Map.of());
    }

    @Test
    void pointInBoxByBlockFaces() {
        Box b = Box.of(10, 64, 10, 0, 60, 0);                                   // углы в любом порядке
        assertEquals(new Box(0, 60, 0, 10, 64, 10), b);
        assertTrue(b.contains(0, 60, 0));
        assertTrue(b.contains(10.999, 64.999, 10.999));                         // последний блок целиком
        assertFalse(b.contains(11, 62, 5));
        assertFalse(b.contains(-0.001, 62, 5));
        assertFalse(b.contains(5, 65, 5));
        assertEquals(11L * 5 * 11, b.volume());
        assertTrue(Box.of(-5, -5, -5, -1, -1, -1).contains(-0.5, -1.5, -5));    // отрицательные координаты
    }

    @Test
    void multiBoxRegion() {
        Region r = region("l", 1, Grav.NORMAL, Box.of(0, 0, 0, 9, 9, 9), Box.of(10, 0, 0, 19, 4, 4));   // буква «Г»
        assertTrue(r.contains(5, 5, 5));
        assertTrue(r.contains(15, 2, 2));
        assertFalse(r.contains(15, 7, 7));
        assertEquals(1000 + 250, r.volume());
        RegionIndex idx = new RegionIndex(List.of(r));
        assertSame(r, idx.at("cosmos", 15.5, 1, 1));
        assertNull(idx.at("cosmos", 15.5, 6, 1));
        assertNull(idx.at("world", 5, 5, 5));                                   // другая карта
    }

    @Test
    void smallerWinsOnOverlapAndNewerOnTie() {
        Region big = region("big", 1, Grav.NORMAL, Box.of(-100, -100, -100, 100, 100, 100));
        Region small = region("small", 2, Grav.VACUUM, Box.of(0, 0, 0, 9, 9, 9));
        Region twinOld = region("twin-old", 3, Grav.NORMAL, Box.of(50, 50, 50, 60, 60, 60));
        Region twinNew = region("twin-new", 4, Grav.MAP, Box.of(55, 55, 55, 65, 65, 65));
        RegionIndex idx = new RegionIndex(List.of(big, twinNew, small, twinOld));
        assertSame(small, idx.at("cosmos", 5, 5, 5));
        assertSame(big, idx.at("cosmos", -5, 5, 5));
        assertSame(twinNew, idx.at("cosmos", 57, 57, 57));                      // равный объём — созданный позже
        assertSame(twinOld, idx.at("cosmos", 51, 51, 51));
        assertEquals(List.of(small, twinNew, twinOld, big), idx.map("cosmos").regions());
    }

    @Test
    void editsThroughRegions() {
        Regions rs = new Regions();
        Region a = rs.create("alpha", "cosmos", "Станция Альфа", Box.of(0, 0, 0, 9, 9, 9), null);
        assertEquals(Grav.NORMAL, a.gravity());
        assertThrows(Regions.Refused.class, () -> rs.create("alpha", "cosmos", null, Box.of(0, 0, 0, 1, 1, 1), null));
        assertThrows(Regions.Refused.class, () -> rs.create("Bad Id", "cosmos", null, Box.of(0, 0, 0, 1, 1, 1), null));
        assertThrows(Regions.Refused.class, () -> rs.create("nosel", "cosmos", null, null, null));
        assertThrows(Regions.Refused.class, () -> rs.addBox("alpha", "world", Box.of(0, 0, 0, 1, 1, 1)));   // другая карта
        int v = rs.version();
        rs.addBox("alpha", "cosmos", Box.of(20, 0, 0, 29, 9, 9));
        assertTrue(rs.version() > v);
        assertEquals("alpha", rs.index().at("cosmos", 25, 5, 5).id());
        rs.rename("alpha", "  Альфа-2 ");
        assertEquals("Альфа-2", rs.get("alpha").title());
        rs.gravity("alpha", Grav.VACUUM);
        assertEquals(Grav.VACUUM, rs.index().byId("alpha").gravity());
        rs.expand("alpha", 2, Box.Dir.UP, -64, 319);
        assertEquals(9, rs.get("alpha").boxes().get(0).maxY());                 // первая — как была
        assertEquals(11, rs.get("alpha").boxes().get(1).maxY());                // расширяется только последняя
        rs.remove("alpha");
        assertNull(rs.index().at("cosmos", 5, 5, 5));
        assertThrows(Regions.Refused.class, () -> rs.remove("alpha"));
    }

    @Test
    void saveAndLoad(@TempDir Path dir) throws Exception {
        Regions rs = new Regions();
        rs.create("alpha", "cosmos", "Станция «Альфа»", Box.of(0, 0, 0, 9, 9, 9), null);
        rs.create("beta", "cosmos", "Бета", Box.of(-30000, -32000, 5, 30000, 32000, 6), "8d2f0c3e-0000-0000-0000-000000000001");
        rs.addBox("alpha", "cosmos", Box.of(10, 0, 0, 12, 3, 3));
        rs.gravity("beta", Grav.MAP);
        RegionStore st = new RegionStore(dir.resolve("regions.json"));
        st.write(RegionStore.serialize(rs.all(), rs.seq()));
        assertFalse(Files.exists(dir.resolve("regions.json.tmp")));

        RegionStore.Data d = st.load();
        assertEquals(rs.seq(), d.seq());
        assertEquals(rs.all(), d.regions());
        Regions back = new Regions();
        back.replaceAll(d.regions(), d.seq());
        Region c = back.create("gamma", "cosmos", null, Box.of(0, 0, 0, 1, 1, 1), null);
        assertEquals(3, c.created());                                           // счётчик продолжается
        assertEquals("gamma", c.title());
    }

    @Test
    void brokenFileIsNotTouched(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("regions.json");
        String junk = "{ \"regions\": [ { \"id\": \"a\", \"map\": \"cosmos\", \"boxes\": [[1,2,3]] } ] }";
        Files.writeString(f, junk);
        RegionStore st = new RegionStore(f);
        assertThrows(RegionStore.Broken.class, st::load);
        Files.writeString(f, "{ not json");
        assertThrows(RegionStore.Broken.class, st::load);
        assertEquals("{ not json", Files.readString(f));
        assertEquals(0, new RegionStore(dir.resolve("none.json")).load().regions().size());   // нет файла — пусто
    }

    @Test
    void selectionPointsAndExpand() {
        Selection s = Selection.NONE.with(1, "cosmos", 5, 70, -3);
        assertFalse(s.complete());
        s = s.with(2, "cosmos", -5, 60, 3);
        assertTrue(s.complete());
        assertEquals(new Box(-5, 60, -3, 5, 70, 3), s.box());
        Selection other = s.with(1, "world", 0, 0, 0);                          // точка на другой карте — вторая сброшена
        assertEquals("world", other.map());
        assertNull(other.p2());
        assertFalse(other.complete());

        Box b = s.box();
        assertEquals(new Box(-5, 60, -3, 5, 80, 3), b.expand(10, Box.Dir.UP, -64, 319));
        assertEquals(new Box(-5, 50, -3, 5, 70, 3), b.expand(10, Box.Dir.DOWN, -64, 319));
        assertEquals(new Box(-7, 58, -5, 7, 72, 5), b.expand(2, Box.Dir.ALL, -64, 319));
        assertEquals(new Box(-7, 60, -5, 7, 70, 5), b.expand(2, StationCommand.dir("sides"), -64, 319));
        assertEquals(319, b.expand(1000, Box.Dir.UP, -64, 319).maxY());          // по высоте карты
        Box shrunk = b.expand(-100, Box.Dir.ALL, -64, 319);                     // сжать до одного слоя, не сломаться
        assertEquals(1, shrunk.sizeX());
        assertEquals(1, shrunk.sizeY());
        assertEquals(new Box(-5, 60, -3, 5, 80, 3), Selection.of("cosmos", b.expand(10, Box.Dir.UP, -64, 319)).box());
    }

    @Test
    void gravityModeAt() {
        Regions rs = new Regions();
        rs.create("alpha", "cosmos", "Альфа", Box.of(0, 0, 0, 9, 9, 9), null);
        rs.create("bay", "cosmos", "Шлюз", Box.of(4, 4, 4, 5, 5, 5), null);
        rs.gravity("bay", Grav.VACUUM);
        GravityBridge g = new GravityBridge(null, rs, i -> "cosmos");
        VacuumService.ZoneLookup z = g.provider().zones(null);
        // без Instance (в тестах) — карта неизвестна → MAP; с картой — через тот же поиск
        assertEquals(VacuumService.Mode.MAP, z.modeAt(5, 5, 5));
        VacuumService.ZoneLookup zc = g.zonesForMap("cosmos");
        assertEquals(VacuumService.Mode.NORMAL, zc.modeAt(1.5, 1, 1.5));
        assertEquals(VacuumService.Mode.VACUUM, zc.modeAt(4.5, 4.5, 4.5));         // меньший регион внутри — главнее
        assertEquals(VacuumService.Mode.MAP, zc.modeAt(10.5, 1, 1));               // снаружи — как у карты
        assertEquals(VacuumService.Mode.MAP, zc.modeAt(-0.01, 1, 1));
        rs.gravity("alpha", Grav.MAP);                                            // правка видна сразу
        assertEquals(VacuumService.Mode.MAP, zc.modeAt(1.5, 1, 1.5));
        rs.remove("bay");
        assertEquals(VacuumService.Mode.MAP, zc.modeAt(4.5, 4.5, 4.5));
        assertEquals(VacuumService.Mode.MAP, g.zonesForMap("world").modeAt(1, 1, 1));
    }
}
