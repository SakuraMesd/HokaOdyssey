package ink.hoka.odyssey.vessels;

import ink.hoka.odyssey.api.ModuleConfig;
import net.minestom.server.MinecraftServer;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** vessels-4, п.2: ключи предметов, рецепты мастерской из config.yml, крафт из груза, выпадение кристаллов. */
class WorkshopTest {

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    static final VesselItems ITEMS = new VesselItems(() -> null);
    static final ModuleConfig CFG = new ModuleConfig(Path.of("src/main/resources/config.yml"));

    /** Как VesselsModule.make для vessel:… и minecraft:… */
    static ItemStack make(String key, int n) {
        if (key.startsWith("vessel:")) return ITEMS.of(key.substring(7), n);
        Material m = Material.fromKey(key);
        return m == null ? null : ItemStack.of(m, n);
    }

    static ItemStack make1(String key) { return make(key, 1); }

    static ItemStack ore(String id, int n) { return ItemStack.of(Material.NOTE_BLOCK, n).withTag(Workshop.BLOCK_TAG, id); }

    static List<Workshop.Recipe> recipes() {
        List<String> bad = new ArrayList<>();
        List<Workshop.Recipe> rs = Workshop.parse(CFG.get("recipes"), bad);
        assertTrue(bad.isEmpty(), bad.toString());
        return rs;
    }

    static Workshop.Recipe recipe(String id) { return recipes().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(); }

    @Test
    void keys() {
        assertEquals("vessel:crystal", Workshop.keyOf(ITEMS.crystal(3)));
        assertEquals("vessel:fuel", Workshop.keyOf(ITEMS.fuel(3)));
        assertEquals("vessel:engine", Workshop.keyOf(ITEMS.upgrade(Upgrades.Type.ENGINE)));
        assertEquals("hoka:iron_ore", Workshop.keyOf(ore("iron_ore", 1)));
        assertEquals("minecraft:iron_ingot", Workshop.keyOf(ItemStack.of(Material.IRON_INGOT)));
        assertEquals("item:ruby", Workshop.keyOf(ItemStack.of(Material.PAPER).withTag(VesselItems.HOITEM, "ruby")));
        assertNull(Workshop.keyOf(ItemStack.AIR));
        assertEquals("Медная руда", Workshop.name("hoka:copper_ore", Map.of()));
        assertEquals("Медь", Workshop.name("hoka:copper_ore", Map.of("hoka:copper_ore", "Медь")));
        assertEquals("iron ingot", Workshop.name("minecraft:iron_ingot", null));
    }

    @Test
    void defaultRecipesFromConfig() {
        List<Workshop.Recipe> rs = recipes();
        assertEquals(List.of("fuel", "repair", "engine", "tank", "gun", "warp"), rs.stream().map(Workshop.Recipe::id).toList());
        for (Workshop.Recipe r : rs) {
            ItemStack res = make(r.result(), 1);
            assertNotNull(res, "результат делается: " + r.result());
            for (String k : r.needs().keySet())
                assertTrue(k.startsWith("vessel:") || k.startsWith("hoka:") || k.startsWith("minecraft:"), k);
        }
        Workshop.Recipe fuel = recipe("fuel");
        assertEquals(16, fuel.count());
        assertEquals(Map.of("vessel:crystal", 4), fuel.needs());
        // все руды рецептов добываются на астероидах (руды генератора space)
        for (Workshop.Recipe r : rs) for (String k : r.needs().keySet())
            if (k.startsWith("hoka:")) assertTrue(Workshop.NAMES.containsKey(k), "руда космоса: " + k);
    }

    @Test
    void badRecipesSkipped() {
        List<String> bad = new ArrayList<>();
        Map<String, Object> cfg = new java.util.LinkedHashMap<>();
        cfg.put("a", Map.of("result", "vessel:fuel"));                                   // нет needs
        cfg.put("b", Map.of("needs", Map.of("vessel:crystal", 1)));                       // нет result
        cfg.put("c", "мусор");
        cfg.put("d", Map.of("result", "vessel:fuel", "needs", Map.of("vessel:crystal", 2)));
        List<Workshop.Recipe> rs = Workshop.parse(cfg, bad);
        assertEquals(1, rs.size());
        assertEquals(3, bad.size());
        Map<String, Object> many = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 10; i++) many.put("r" + i, Map.of("result", "vessel:fuel", "needs", Map.of("vessel:crystal", 1)));
        assertEquals(Workshop.MAX, Workshop.parse(many, bad).size(), "не больше 8");
    }

    @Test
    void craftFuelGoesToTankFirst() {
        Cargo c = new Cargo();
        c.storage[0] = ITEMS.crystal(10);
        Workshop.Recipe fuel = recipe("fuel");
        assertEquals(2, Workshop.maxTimes(c.storage, fuel, 8));
        assertEquals(Workshop.Result.OK, Workshop.craft(c, fuel, 2, WorkshopTest::make1));
        assertEquals(2, c.storage[0].amount(), "списано 8 кристаллов");
        assertEquals(32, c.fuelUnits(), "топливо — в бак");
        for (int i = 1; i < Cargo.STORAGE; i++) assertTrue(c.storage[i].isAir());
        assertEquals(Workshop.Result.MISSING, Workshop.craft(c, fuel, 1, WorkshopTest::make1));
        assertEquals(2, c.storage[0].amount(), "не хватило — ничего не тронуто");
        assertEquals(32, c.fuelUnits());
    }

    @Test
    void craftFromOresAndNoRoom() {
        Cargo c = new Cargo();
        c.storage[3] = ore("iron_ore", 3);
        c.storage[7] = ore("iron_ore", 2);
        c.storage[8] = ore("nickel_ore", 2);
        c.storage[9] = ITEMS.crystal(1);
        Workshop.Recipe rep = recipe("repair");
        assertTrue(Workshop.enough(c.storage, rep, 1));
        assertEquals(Workshop.Result.OK, Workshop.craft(c, rep, 1, WorkshopTest::make1));
        assertEquals(1, Workshop.have(c.storage, "hoka:iron_ore"));
        assertEquals(0, Workshop.have(c.storage, "hoka:nickel_ore"));
        assertEquals(0, Workshop.have(c.storage, "vessel:crystal"));
        assertEquals(1, Workshop.have(c.storage, "vessel:repair"), "ремкомплект — в грузе");

        // груз забит: результат не влезает — ничего не тратится
        Cargo full = new Cargo();
        for (int i = 0; i < Cargo.STORAGE; i++) full.storage[i] = ItemStack.of(Material.STONE, 64);
        full.storage[0] = ore("silver_ore", 4);
        full.storage[1] = ore("iron_ore", 8);
        full.storage[2] = ITEMS.crystal(7);                                      // 1 останется — слот не освободится
        Workshop.Recipe gun = recipe("gun");
        assertEquals(Workshop.Result.OK, Workshop.craft(full, gun, 1, WorkshopTest::make1), "ингредиенты освободили слоты — влезло");
        Cargo jam = new Cargo();
        for (int i = 0; i < Cargo.STORAGE; i++) jam.storage[i] = ItemStack.of(Material.STONE, 64);
        jam.storage[0] = ore("silver_ore", 5);
        jam.storage[1] = ore("iron_ore", 9);
        jam.storage[2] = ITEMS.crystal(7);
        assertEquals(Workshop.Result.NO_ROOM, Workshop.craft(jam, gun, 1, WorkshopTest::make1));
        assertEquals(5, jam.storage[0].amount(), "нет места — ничего не списано");
        assertEquals(7, jam.storage[2].amount());
        assertEquals(Workshop.Result.BAD, Workshop.craft(jam, new Workshop.Recipe("x", "hoka:iron_ore", 1, Map.of("minecraft:stone", 1)), 1, k -> null));
    }

    @Test
    void fuelDropsConfig() {
        assertTrue(CFG.get("fuel-drops") instanceof Map<?, ?>);
        Map<?, ?> d = (Map<?, ?>) CFG.get("fuel-drops");
        assertTrue(d.containsKey("hoka:uranium_ore"));
        for (var e : d.entrySet()) {
            Map<?, ?> m = (Map<?, ?>) e.getValue();
            double ch = VesselKind.num(m, "chance", -1);
            assertTrue(ch > 0 && ch <= 1, e.getKey() + " шанс");
            assertTrue(VesselKind.num(m, "min", 0) >= 1 && VesselKind.num(m, "max", 0) >= VesselKind.num(m, "min", 0), e.getKey() + " сколько");
            assertTrue(Workshop.NAMES.containsKey(e.getKey().toString()), "блок астероида: " + e.getKey());
        }
    }
}
