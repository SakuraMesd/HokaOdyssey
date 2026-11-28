package ink.hoka.odyssey.vessels;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** vessels-2: топливо, улучшения, варп, луч выстрела, хранилище, доступ к меню, корпус «стоит на корабле». */
class GameplayTest {

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    private static final VesselItems ITEMS = new VesselItems(() -> null);   // без hoitems — запасные предметы

    // ───────────────────────── топливо ─────────────────────────

    @Test
    void fuelUnitsAndConsume() {
        Cargo c = new Cargo();
        assertEquals(0, c.fuelUnits());
        c.fuel[0] = ITEMS.fuel(64);
        c.fuel[1] = ITEMS.fuel(10);
        c.fuel[2] = ItemStack.of(Material.STONE, 30);                          // не топливо — не считается
        c.fuel[3] = ITEMS.fuel(5);
        assertEquals(79, c.fuelUnits());
        assertEquals(64, ITEMS.fuelStack());
        assertEquals(256, Cargo.capacity(ITEMS.fuelStack()));                 // 4 слота × стопка

        assertTrue(c.consumeFuel(7));                                           // с последнего слота: 5 из [3], 2 из [1]
        assertTrue(c.fuel[3].isAir());
        assertEquals(8, c.fuel[1].amount());
        assertEquals(64, c.fuel[0].amount());
        assertEquals(30, c.fuel[2].amount(), "камень не тронут");
        assertEquals(72, c.fuelUnits());

        assertFalse(c.consumeFuel(73), "не хватает — ничего не списано");
        assertEquals(72, c.fuelUnits());
        assertTrue(c.consumeFuel(72));
        assertEquals(0, c.fuelUnits());
        assertTrue(c.fuel[0].isAir() && c.fuel[1].isAir());
        assertTrue(c.consumeFuel(0));
    }

    @Test
    void itemsAreRecognised() {
        assertTrue(VesselItems.isFuel(ITEMS.fuel(3)));
        assertNull(VesselItems.upgradeOf(ITEMS.fuel(3)));
        for (Upgrades.Type t : Upgrades.Type.values()) {
            ItemStack u = ITEMS.upgrade(t);
            assertEquals(t, VesselItems.upgradeOf(u));
            assertEquals(1, u.maxStackSize(), "улучшение — по одному");
        }
        // предмет hoitems (метка hoka_item = vessel_…) — тоже наш
        ItemStack ho = ItemStack.of(Material.PAPER).withTag(Tag.String("hoka_item"), "vessel_fuel");
        assertTrue(VesselItems.isFuel(ho));
        assertEquals(Upgrades.Type.GUN, VesselItems.upgradeOf(ItemStack.of(Material.PAPER).withTag(Tag.String("hoka_item"), "vessel_gun")));
        assertNull(VesselItems.kindOf(ItemStack.of(Material.PAPER).withTag(Tag.String("hoka_item"), "vessel_nothing")));
        assertNull(VesselItems.kindOf(ItemStack.of(Material.GLOWSTONE_DUST)), "обычная светопыль — не топливо");
    }

    // ───────────────────────── улучшения и варп ─────────────────────────

    @Test
    void upgradesStack() {
        Upgrades.Config c = Upgrades.Config.DEFAULT;
        Upgrades.Stats none = Upgrades.stats(new int[4], c);
        assertEquals(Upgrades.Stats.NONE, none);
        Upgrades.Stats s = Upgrades.stats(new int[]{2, 3, 1, 2}, c);           // 2 двигателя, 3 бака, 1 орудие, 2 катушки
        assertEquals(1.2, s.speed(), 1e-9);
        assertEquals(0.7, s.warpCost(), 1e-9);
        assertEquals(0.9, s.gunReload(), 1e-9);
        assertEquals(1.1, s.gunDamage(), 1e-9);
        assertEquals(0.7, s.warpReload(), 1e-9);
        Upgrades.Stats many = Upgrades.stats(new int[]{0, 8, 0, 8}, c);         // скидки не ниже min-factor
        assertEquals(0.2, many.warpCost(), 1e-9);
        assertEquals(0.1, many.warpReload(), 1e-9);
        // из слотов груза: одно улучшение — один слот, мусор не считается
        Cargo g = new Cargo();
        g.upgrades[0] = ITEMS.upgrade(Upgrades.Type.ENGINE);
        g.upgrades[1] = ITEMS.upgrade(Upgrades.Type.ENGINE);
        g.upgrades[2] = ITEMS.upgrade(Upgrades.Type.TANK);
        g.upgrades[3] = ItemStack.of(Material.DIRT, 5);
        assertArrayEquals(new int[]{2, 1, 0, 0}, g.upgradeCounts());
        // из конфига
        Upgrades.Config cfg = Upgrades.Config.of(Map.of("engine", Map.of("speed", 0.25), "min-factor", 0.5, "tank", Map.of("warp-cost", 0.2)));
        Upgrades.Stats st = Upgrades.stats(new int[]{2, 4, 0, 0}, cfg);
        assertEquals(1.5, st.speed(), 1e-9);
        assertEquals(0.5, st.warpCost(), 1e-9);
    }

    @Test
    void warpCostWithUpgrades() {
        int cap = Cargo.capacity(64);
        assertEquals(64, Cargo.warpCost(cap, 0.25, 1));                         // четверть бака
        assertEquals(52, Cargo.warpCost(cap, 0.25, Upgrades.stats(new int[]{0, 2, 0, 0}, Upgrades.Config.DEFAULT).warpCost()));  // 51.2 → 52
        assertEquals(58, Cargo.warpCost(cap, 0.25, 0.9));                       // 57.6 → 58
        assertEquals(7, Cargo.warpCost(cap, 0.25, 0.1));                        // 6.4 → 7
        assertEquals(0, Cargo.warpCost(cap, 0, 1), "доля 0 — бесплатно");
        assertEquals(1, Cargo.warpCost(4, 0.01, 1), "хоть одна штука");
    }

    @Test
    void warpTargetBoundsAndShift() {
        WarpMath.Bounds b = WarpMath.Bounds.of(100_000, 0, 0, -32752, 32752, 10);   // граница 100k, радиус корабля 8 + 2
        WarpMath.Free all = (x, y, z) -> true;
        double[] east = {1, 0, 0};
        // просто вперёд на 10 000
        WarpMath.Result r = WarpMath.target(new double[]{0, 100, 0}, east, 10_000, b, 64, 32, 1, all);
        assertArrayEquals(new double[]{10_000, 100, 0}, r.at(), 1e-9);
        assertFalse(r.shortened());
        // у границы: 45 000 + 10 000 > 50 000 − 10 → урезан до границы
        r = WarpMath.target(new double[]{45_000, 100, 0}, east, 10_000, b, 64, 32, 1, all);
        assertEquals(49_990, r.at()[0], 1e-6);
        assertTrue(r.shortened());
        // впритык к границе — некуда
        r = WarpMath.target(new double[]{49_950, 100, 0}, east, 10_000, b, 64, 32, 1, all);
        assertNull(r.at());
        assertEquals("впереди граница карты", r.why());
        // вверх — упирается в высоту мира (32 752 − 10)
        r = WarpMath.target(new double[]{0, 30_000, 0}, new double[]{0, 1, 0}, 10_000, b, 64, 32, 1, all);
        assertEquals(32_742, r.at()[1], 1e-6);
        // наискосок вниз — тоже по высоте, и точка на той же прямой
        double s = Math.sqrt(0.5);
        r = WarpMath.target(new double[]{0, -30_000, 0}, new double[]{s, -s, 0}, 10_000, b, 64, 32, 1, all);
        assertEquals(-32_742, r.at()[1], 1e-6);
        assertEquals(r.at()[0], -30_000 - r.at()[1], 1e-6);
        // в точке — скала до x 10 003 и до y 104: назад по курсу на 4 блока — свободно (раньше, чем вверх на 5)
        WarpMath.Free rock = (x, y, z) -> !(x >= 9_997 && y < 105);
        r = WarpMath.target(new double[]{0, 100, 0}, east, 10_000, b, 64, 32, 1, rock);
        assertTrue(r.shifted());
        assertArrayEquals(new double[]{9_996, 100, 0}, r.at(), 1e-9, "назад на 4 — раньше, чем вверх на 5");
    }

    @Test
    void warpShiftOrder() {
        WarpMath.Bounds b = WarpMath.Bounds.of(100_000, 0, 0, -1000, 1000, 10);
        double[] east = {1, 0, 0};
        // занято только по курсу в полосе x 9 990…10 010 (на любой высоте до 200): вверх не помогает, назад на 11 — да
        WarpMath.Free wall = (x, y, z) -> x < 9_990 || x > 10_010 || y > 1000;
        WarpMath.Result r = WarpMath.target(new double[]{0, 100, 0}, east, 10_000, b, 64, 32, 1, wall);
        assertArrayEquals(new double[]{9_989, 100, 0}, r.at(), 1e-9);
        // занят пол (y < 103) на большом участке: назад не помогает, вверх на 3 — да
        WarpMath.Free floor = (x, y, z) -> y >= 103;
        r = WarpMath.target(new double[]{0, 100, 0}, east, 10_000, b, 64, 32, 1, floor);
        assertArrayEquals(new double[]{10_000, 103, 0}, r.at(), 1e-9);
        // всё занято — прыжка нет
        r = WarpMath.target(new double[]{0, 100, 0}, east, 10_000, b, 64, 32, 1, (x, y, z) -> false);
        assertNull(r.at());
        assertEquals("точка прыжка занята", r.why());
        // сдвиг вверх не выводит за высоту мира
        WarpMath.Bounds low = WarpMath.Bounds.of(100_000, 0, 0, -1000, 112, 10);    // середина корабля не выше 102
        r = WarpMath.target(new double[]{0, 100, 0}, east, 10_000, low, 64, 32, 1, floor);
        assertTrue(r.at() == null || r.at()[1] <= 102);
    }

    // ───────────────────────── выстрел ─────────────────────────

    /** Мир: твёрдые клетки из набора, остальное воздух; незагружено — x ≥ unloadedFrom. */
    private static ShotRay.Blocks world(Set<List<Integer>> solid, int unloadedFrom) {
        return (x, y, z) -> x >= unloadedFrom ? ShotRay.Blocks.UNLOADED : solid.contains(List.of(x, y, z)) ? ShotRay.Blocks.SOLID : ShotRay.Blocks.AIR;
    }

    private static ShotRay.Target mob(Object ref, double x, double y, double z) {
        return new ShotRay.Target(ref, x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    @Test
    void shotRay300() {
        double[] o = {0.5, 100.5, 0.5}, east = {1, 0, 0};
        ShotRay.Blocks empty = world(Set.of(), Integer.MAX_VALUE);
        // сущность на 120 блоках
        ShotRay.Hit h = ShotRay.cast(o, east, 300, empty, List.of(mob("zombie", 120.5, 100, 0.5)), List.of());
        assertEquals(ShotRay.Kind.ENTITY, h.kind());
        assertEquals("zombie", h.ref());
        assertEquals(119.7, h.t(), 1e-6);
        // на 299 — достаёт, на 302 — нет (дальность 300)
        assertEquals(ShotRay.Kind.ENTITY, ShotRay.cast(o, east, 300, empty, List.of(mob("far", 299.5, 100, 0.5)), List.of()).kind());
        h = ShotRay.cast(o, east, 300, empty, List.of(mob("too-far", 302, 100, 0.5)), List.of());
        assertEquals(ShotRay.Kind.NONE, h.kind());
        assertEquals(300, h.t(), 1e-9);
        // блок раньше сущности — попадание в блок
        h = ShotRay.cast(o, east, 300, world(Set.of(List.of(50, 100, 0)), Integer.MAX_VALUE), List.of(mob("behind", 120.5, 100, 0.5)), List.of());
        assertEquals(ShotRay.Kind.BLOCK, h.kind());
        assertEquals(50, h.bx());
        assertEquals(49.5, h.t(), 1e-9);
        // мимо сущности (сбоку на 2 блока) — промах
        assertEquals(ShotRay.Kind.NONE, ShotRay.cast(o, east, 300, empty, List.of(mob("side", 100, 100, 2.5)), List.of()).kind());
        // незагруженный куб на пути — промах, сущность за ним не задета
        h = ShotRay.cast(o, east, 300, world(Set.of(), 80), List.of(mob("hidden", 120.5, 100, 0.5)), List.of());
        assertEquals(ShotRay.Kind.UNLOADED, h.kind());
        assertEquals(79.5, h.t(), 1e-9);
        // по диагонали в блок
        double s = Math.sqrt(1 / 3.0);
        h = ShotRay.cast(o, new double[]{s, s, s}, 300, world(Set.of(List.of(10, 110, 10)), Integer.MAX_VALUE), List.of(), List.of());
        assertEquals(ShotRay.Kind.BLOCK, h.kind());
        assertArrayEquals(new int[]{10, 110, 10}, new int[]{h.bx(), h.by(), h.bz()});
    }

    @Test
    void shotIgnoresOwnShipHitsOthers() throws Exception {
        VesselKind k = VesselsTest.kind(VesselsTest.corabll());
        double[] o = {0, 100, 0}, north = {0, 0, -1};
        // чужой корабль на 100 блоков впереди, боком к лучу
        ShotRay.Hull other = new ShotRay.Hull("other", new double[]{0, 100, -100}, Quat.yaw(90), k.half, k.hull);
        ShotRay.Hit h = ShotRay.cast(o, north, 300, world(Set.of(), Integer.MAX_VALUE), List.of(), List.of(other));
        assertEquals(ShotRay.Kind.VESSEL, h.kind());
        assertEquals("other", h.ref());
        assertTrue(h.t() > 100 - k.half[2] - 1 && h.t() < 100, "попадание в борт, t=" + h.t());
        // свой корабль (луч изнутри) в список не передают: стреляя из своего корабля, луч уходит дальше
        ShotRay.Hit through = ShotRay.cast(o, north, 300, world(Set.of(), Integer.MAX_VALUE), List.of(mob("z", 0, 99.5, -200)), List.of());
        assertEquals(ShotRay.Kind.ENTITY, through.kind());
        // пилот и стоящие на корабле — тоже нет
        List<ShotRay.Target> all = List.of(mob("pilot", 0, 99, -1), mob("z", 0, 99.5, -200));
        h = ShotRay.cast(o, north, 300, world(Set.of(), Integer.MAX_VALUE), ShotRay.without(all, Set.of("pilot")), List.of());
        assertEquals("z", h.ref());
        // луч над чужим кораблём (выше коробки) — мимо
        h = ShotRay.cast(new double[]{0, 100 + k.half[1] + 1.5, 0}, north, 300, world(Set.of(), Integer.MAX_VALUE), List.of(), List.of(other));
        assertEquals(ShotRay.Kind.NONE, h.kind());
    }

    // ───────────────────────── хранилище ─────────────────────────

    @Test
    void storageRoundTripWithComponents(@TempDir Path dir) throws Exception {
        Vessel v = new Vessel(3, "corabll", "cosmos", 1, 2, 3, Quat.IDENTITY);
        v.owner = "00000000-0000-0000-0000-000000000042";
        v.ownerName = "Pilot42";
        ItemStack named = ItemStack.builder(Material.DIAMOND_SWORD)
                .customName(Component.text("Меч героя", NamedTextColor.GOLD))
                .lore(List.of(Component.text("строка 1"), Component.text("строка 2")))
                .set(DataComponents.DAMAGE, 17)
                .build()
                .withTag(Tag.String("custom"), "значение");
        v.cargo.storage[0] = named;
        v.cargo.storage[26] = ItemStack.of(Material.COBBLESTONE, 64);
        v.cargo.upgrades[7] = ITEMS.upgrade(Upgrades.Type.WARP);
        v.cargo.fuel[2] = ITEMS.fuel(33);
        VesselStore st = new VesselStore(dir.resolve("vessels.json"));
        assertTrue(st.write(VesselStore.json(List.of(v))));
        List<Vessel> back = new VesselStore(dir.resolve("vessels.json")).load();
        assertEquals(1, back.size());
        Vessel b = back.get(0);
        assertEquals(v.owner, b.owner);
        assertEquals("Pilot42", b.ownerName);
        assertEquals(named, b.cargo.storage[0], "предмет целиком: имя, описание, прочность, метка");
        assertEquals(17, b.cargo.storage[0].get(DataComponents.DAMAGE));
        assertEquals("значение", b.cargo.storage[0].getTag(Tag.String("custom")));
        assertEquals(ItemStack.of(Material.COBBLESTONE, 64), b.cargo.storage[26]);
        assertTrue(b.cargo.storage[1].isAir());
        assertEquals(Upgrades.Type.WARP, VesselItems.upgradeOf(b.cargo.upgrades[7]));
        assertEquals(33, b.cargo.fuelUnits());
        // старый файл (vessels-1, без груза и владельца) читается
        java.nio.file.Files.writeString(dir.resolve("old.json"),
                "{\"version\":1,\"vessels\":[{\"id\":1,\"kind\":\"corabll\",\"map\":\"w\",\"x\":0,\"y\":0,\"z\":0,\"rot\":[0,0,0,1]}]}");
        VesselStore old = new VesselStore(dir.resolve("old.json"));
        List<Vessel> ov = old.load();
        assertFalse(old.broken());
        assertNull(ov.get(0).owner);
        assertTrue(ov.get(0).cargo.isEmpty());
    }

    @Test
    void brokenItemKeepsFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("vessels.json");
        String bad = "{\"version\":1,\"vessels\":[{\"id\":1,\"kind\":\"c\",\"map\":\"w\",\"x\":0,\"y\":0,\"z\":0,\"rot\":[0,0,0,1],"
                + "\"cargo\":{\"storage\":[{\"id\":\"minecraft:no_such_item_xyz\",\"count\":1}]}}]}";
        java.nio.file.Files.writeString(f, bad);
        VesselStore st = new VesselStore(f);
        st.load();
        assertTrue(st.broken(), "предмет не разобрался — файл не перезаписываем (груз не теряется)");
        assertFalse(st.write(VesselStore.json(List.of())));
        assertEquals(bad, java.nio.file.Files.readString(f));
    }

    // ───────────────────────── меню ─────────────────────────

    @Test
    void menuAccess() {
        Vessel v = new Vessel(1, "corabll", "w", 0, 0, 0, Quat.IDENTITY);
        String me = "11111111-1111-1111-1111-111111111111", stranger = "22222222-2222-2222-2222-222222222222";
        assertFalse(VesselsModule.canOpen(v, me, false), "ничей — только OP");
        assertTrue(VesselsModule.canOpen(v, me, true));
        v.owner = me;
        assertTrue(VesselsModule.canOpen(v, me, false), "владелец");
        assertFalse(VesselsModule.canOpen(v, stranger, false), "чужой");
        assertTrue(VesselsModule.canOpen(v, stranger, true), "OP — любой");
    }

    @Test
    void menuSlots() {
        assertEquals(VesselMenu.Role.STORAGE, VesselMenu.role(0));
        assertEquals(VesselMenu.Role.STORAGE, VesselMenu.role(26));
        assertEquals(VesselMenu.Role.DECOR, VesselMenu.role(VesselMenu.UP_LABEL));
        for (int i = 0; i < Cargo.UPGRADES; i++) assertEquals(VesselMenu.Role.UPGRADE, VesselMenu.role(VesselMenu.UP0 + i));
        assertEquals(VesselMenu.Role.DECOR, VesselMenu.role(VesselMenu.FUEL_LABEL));
        for (int i = 0; i < Cargo.FUEL; i++) assertEquals(VesselMenu.Role.FUEL, VesselMenu.role(VesselMenu.FUEL0 + i));
        for (int s : new int[]{VesselMenu.INFO_WARP, VesselMenu.INFO_GUN, 45, -1})
            assertEquals(VesselMenu.Role.DECOR, VesselMenu.role(s));
        assertEquals(VesselMenu.Role.BUTTON, VesselMenu.role(VesselMenu.WORKSHOP));    // vessels-4: мастерская и ремонт
        assertEquals(VesselMenu.Role.BUTTON, VesselMenu.role(VesselMenu.REPAIR));
        assertFalse(VesselMenu.accepts(VesselMenu.Role.BUTTON, ItemStack.of(Material.DIRT)));
        ItemStack fuel = ITEMS.fuel(10), up = ITEMS.upgrade(Upgrades.Type.GUN), dirt = ItemStack.of(Material.DIRT);
        assertTrue(VesselMenu.accepts(VesselMenu.Role.FUEL, fuel));
        assertFalse(VesselMenu.accepts(VesselMenu.Role.FUEL, up));
        assertFalse(VesselMenu.accepts(VesselMenu.Role.FUEL, dirt));
        assertTrue(VesselMenu.accepts(VesselMenu.Role.UPGRADE, up));
        assertFalse(VesselMenu.accepts(VesselMenu.Role.UPGRADE, fuel));
        assertTrue(VesselMenu.accepts(VesselMenu.Role.STORAGE, dirt));
        assertTrue(VesselMenu.accepts(VesselMenu.Role.UPGRADE, ItemStack.AIR), "забрать можно всегда");
        assertFalse(VesselMenu.accepts(VesselMenu.Role.DECOR, dirt));
        assertEquals(1, VesselMenu.maxIn(VesselMenu.Role.UPGRADE, up));
        assertEquals(64, VesselMenu.maxIn(VesselMenu.Role.FUEL, fuel));
    }

    // ───────────────────────── корпус ─────────────────────────

    @Test
    void standOnHull() throws Exception {
        VesselKind k = new VesselKind("c", Map.of("turn", 180), VesselsTest.corabll());
        assertFalse(k.fine.isEmpty());
        // для каждой колонки (x, z) с корпусом: над верхней мелкой клеткой — «стоит», на 3 блока выше — нет
        int checked = 0;
        List<long[]> tops = new ArrayList<>();
        for (int ix = -8; ix < 8; ix++) for (int iz = -14; iz < 14; iz++) {
            int top = Integer.MIN_VALUE;
            for (int iy = -6; iy < 6; iy++) if (k.fine.contains(VesselKind.fineKey(ix, iy, iz))) top = iy;
            if (top != Integer.MIN_VALUE) tops.add(new long[]{ix, top, iz});
        }
        for (long[] t : tops) {
            double x = (t[0] + 0.5) / 2, y = (t[1] + 1) / 2.0, z = (t[2] + 0.5) / 2;
            assertTrue(k.standsOn(x, y, z, 0.6), "на корпусе: " + x + " " + y + " " + z);
            assertFalse(k.standsOn(x, y + 3, z, 0.6), "в воздухе над корпусом");
            checked++;
        }
        assertTrue(checked > 50, "колонок с корпусом: " + checked);
        // без провалов: соседние верхние клетки по палубе отличаются не больше чем на полблока + полблока (шаг игрока 0.6)
        assertFalse(k.standsOn(0, k.half[1] + 3, 0, 0.6));
        // стволы — из конфига, иначе из носа
        assertEquals(1, k.guns.size());
        assertEquals(k.half[2] + 0.5, k.guns.get(0)[2], 1e-9);
        VesselKind g = new VesselKind("c", Map.of("guns", List.of(List.of(2, -0.5, 6.9), List.of(-2, -0.5, 6.9))), VesselsTest.corabll());
        assertEquals(2, g.guns.size());
        assertArrayEquals(new double[]{-2, -0.5, 6.9}, g.guns.get(1), 1e-9);
    }
}
