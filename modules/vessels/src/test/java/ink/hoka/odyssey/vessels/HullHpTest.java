package ink.hoka.odyssey.vessels;

import net.minestom.server.MinecraftServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** vessels-4, п.1: прочность, зоны, урон, ремонт, сохранение, предметы ремкомплекта и кристалла. */
class HullHpTest {

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    static VesselKind corabll() throws Exception {
        return new VesselKind("corabll", Map.of("speed", 30, "boost-speed", 50, "turn", 180, "hull", Map.of("hp", 200),
                "zones", Map.of("side", 0.35, "nose", 0.25, "tail", 0.22)), VesselsTest.corabll());
    }

    @Test
    void zonesSplitTheSilhouette() throws Exception {
        VesselKind k = corabll();
        assertEquals(200, k.hp);
        double sum = 0;
        for (int z = 0; z < Zones.COUNT; z++) {
            assertTrue(k.zoneShare[z] > 0.02, "в зоне " + Zones.NAMES[z] + " есть клетки корпуса: " + k.zoneShare[z]);
            sum += k.zoneShare[z];
        }
        assertEquals(1, sum, 1e-9);
        // по геометрии: нос — +Z, двигатели — −Z, левый борт — +X, правый — −X
        // у corabll вперёд торчат две балки (борта), кабина-нос — перед средней части (z ≈ 3)
        assertTrue(k.noseFrom > -2 && k.noseFrom < 1, "нос с z " + k.noseFrom);
        assertEquals(Zones.NOSE, Zones.zoneOf(0, 2.5, k.half, k.zones, k.noseFrom));
        assertEquals(Zones.ENGINE, Zones.zoneOf(0, -k.half[2] * 0.9, k.half, k.zones, k.noseFrom));
        assertEquals(Zones.ENGINE, Zones.zoneOf(k.half[0] * 0.9, -k.half[2] * 0.95, k.half, k.zones, k.noseFrom), "корма — во всю ширину");
        assertEquals(Zones.LEFT, Zones.zoneOf(k.half[0] * 0.8, 0, k.half, k.zones, k.noseFrom));
        assertEquals(Zones.LEFT, Zones.zoneOf(2, k.half[2] * 0.9, k.half, k.zones, k.noseFrom), "балка спереди — борт");
        assertEquals(Zones.RIGHT, Zones.zoneOf(-k.half[0] * 0.8, 0, k.half, k.zones, k.noseFrom));
        assertEquals(Zones.BODY, Zones.zoneOf(0, -2.5, k.half, k.zones, k.noseFrom));
        // без средней части — от передней стенки коробки
        assertEquals(k.half[2] - 0.25 * 2 * k.half[2], Zones.noseFrom(new double[]{3, 1}, k.half, k.zones), 1e-9);
        // попадание — зона ближайшей клетки: точка перед кабиной — нос, у кормы — двигатели
        assertEquals(Zones.NOSE, k.zoneNear(0, 0, 3));
        assertEquals(Zones.ENGINE, k.zoneNear(0, 0, -k.half[2] - 3));
        assertEquals(Zones.LEFT, k.zoneNear(k.half[0] + 3, 0, 0));
        assertEquals(Zones.RIGHT, k.zoneNear(-k.half[0] - 3, 0, 0));
        // удар о блоки «вперёд» — передний край (нос или борт), «назад» — двигатели
        assertEquals(Zones.ENGINE, k.zoneToward(0, 0, -1));
        int fwd = k.zoneToward(0, 0, 1);
        assertTrue(fwd == Zones.NOSE || fwd == Zones.LEFT || fwd == Zones.RIGHT, "вперёд — " + fwd);
    }

    @Test
    void damageRepairAndZones() throws Exception {
        VesselKind k = corabll();
        Vessel v = new Vessel(1, "corabll", "cosmos", 0, 0, 0, Quat.IDENTITY);
        assertEquals(200, v.hp(k.hp));
        assertEquals(194, v.damage(6, Zones.NOSE, k.hp), 1e-9);
        assertEquals(6, v.zoneDmg[Zones.NOSE], 1e-9);
        assertTrue(k.zoneHealth(Zones.NOSE, v.zoneDmg[Zones.NOSE]) < 1);
        assertEquals(1, k.zoneHealth(Zones.BODY, v.zoneDmg[Zones.BODY]), 1e-9);
        // нос бьют — краснеет быстрее, чем падает общая прочность
        for (int i = 0; i < 8; i++) v.damage(6, Zones.NOSE, k.hp);
        assertEquals(146, v.hp(k.hp), 1e-9);
        assertEquals(2, Zones.level(k.zoneHealth(Zones.NOSE, v.zoneDmg[Zones.NOSE])), "нос — красный");
        assertEquals(0, Zones.level(k.zoneHealth(Zones.BODY, 0)), "корпус — белый");
        // ремонт: +50, урон зон — в той же доле
        double add = v.repair(50, k.hp);
        assertEquals(50, add, 1e-9);
        assertEquals(196, v.hp(k.hp), 1e-9);
        assertEquals(54 * (1 - 50.0 / 54), v.zoneDmg[Zones.NOSE], 1e-9);
        assertEquals(4, v.repair(50, k.hp), 1e-9, "не выше наибольшей");
        assertEquals(200, v.hp(k.hp));
        assertEquals(-1, v.hp, "полная — не хранится");
        assertEquals(0, v.zoneDmg[Zones.NOSE], 1e-9);
        assertEquals(0, v.repair(10, k.hp), "цел — ремонт не нужен");
        // урон больше остатка — 0, не ниже
        assertEquals(0, v.damage(500, Zones.BODY, k.hp));
        assertEquals(200, v.zoneDmg[Zones.BODY], 1e-9, "в зону — сколько реально снято");
        // задать прочность
        v.setHp(150, k.hp);
        assertEquals(150, v.hp(k.hp), 1e-9);
        double sum = 0;
        for (double d : v.zoneDmg) sum += d;
        assertEquals(50, sum, 1e-9);
        v.setHp(999, k.hp);
        assertEquals(200, v.hp(k.hp));
        for (double d : v.zoneDmg) assertEquals(0, d);
        // мусор
        assertEquals(200, v.damage(Double.NaN, 0, k.hp));
        assertEquals(200, v.damage(-5, 0, k.hp));
    }

    @Test
    void zoneHealthLevels() {
        assertEquals(1, Zones.health(0, 200, 0.2));
        assertEquals(0.5, Zones.health(20, 200, 0.2), 1e-9);
        assertEquals(0, Zones.health(1000, 200, 0.2));
        assertEquals(0.5, Zones.health(10, 200, 0.01), 1e-9, "маленькая зона — не меньше 10% прочности");
        assertEquals(0, Zones.level(0.9));
        assertEquals(1, Zones.level(0.5));
        assertEquals(2, Zones.level(0.2));
        assertEquals(2, Zones.level(0));
    }

    @Test
    void hpIsSaved(@TempDir Path dir) throws Exception {
        Vessel v = new Vessel(3, "corabll", "cosmos", 1, 2, 3, Quat.IDENTITY);
        v.damage(30, Zones.LEFT, 200);
        Vessel full = new Vessel(4, "corabll", "cosmos", 1, 2, 3, Quat.IDENTITY);
        VesselStore st = new VesselStore(dir.resolve("vessels.json"));
        st.write(VesselStore.json(List.of(v, full)));
        String json = java.nio.file.Files.readString(dir.resolve("vessels.json"));
        assertTrue(json.contains("\"hp\": 170"), json);
        List<Vessel> back = st.load();
        assertFalse(st.broken());
        assertEquals(170, back.get(0).hp(200), 1e-9);
        assertEquals(30, back.get(0).zoneDmg[Zones.LEFT], 1e-9);
        assertEquals(200, back.get(1).hp(200), "без hp — полная");
    }

    @Test
    void repairKitAndCrystalItems() {
        VesselItems items = new VesselItems(() -> null);
        assertTrue(VesselItems.isRepair(items.repair(2)));
        assertEquals(VesselItems.CRYSTAL, VesselItems.kindOf(items.crystal(5)));
        assertNull(VesselItems.upgradeOf(items.crystal(1)), "кристалл — не улучшение");
        assertNull(VesselItems.upgradeOf(items.repair(1)));
        assertFalse(VesselItems.isFuel(items.crystal(1)));
        assertEquals(Upgrades.Type.GUN, VesselItems.upgradeOf(items.of("gun", 1)));
        assertNull(items.of("nope", 1));
        // предмет hoitems с меткой vessel_repair — тоже ремкомплект
        var ho = net.minestom.server.item.ItemStack.of(net.minestom.server.item.Material.PAPER).withTag(VesselItems.HOITEM, "vessel_repair");
        assertTrue(VesselItems.isRepair(ho));
    }
}
