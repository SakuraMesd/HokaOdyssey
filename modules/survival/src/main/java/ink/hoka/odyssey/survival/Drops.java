package ink.hoka.odyssey.survival;

import net.minestom.server.component.DataComponents;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.enchant.Enchantment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Что выпадает из блока в выживании — упрощённые ванильные правила (без таблиц добычи): блок без нужного инструмента
 * (камень без кирки) — ничего; камень — булыжник, руды — сырьё, трава — земля, стекло и лёд — ничего (с «Шёлковым
 * касанием» — сам блок), листва — изредка саженец/яблоко, верхние половины дверей и цветов — ничего; остальное — сам
 * блок. Удача (Fortune) не учитывается.
 */
final class Drops {

    private Drops() {}

    /** Блок → что выпадает (материал, мин, макс). */
    private record D(Material m, int min, int max) {}

    private static final Map<String, D> MAP = Map.ofEntries(
            Map.entry("minecraft:stone", new D(Material.COBBLESTONE, 1, 1)),
            Map.entry("minecraft:deepslate", new D(Material.COBBLED_DEEPSLATE, 1, 1)),
            Map.entry("minecraft:grass_block", new D(Material.DIRT, 1, 1)),
            Map.entry("minecraft:podzol", new D(Material.DIRT, 1, 1)),
            Map.entry("minecraft:mycelium", new D(Material.DIRT, 1, 1)),
            Map.entry("minecraft:dirt_path", new D(Material.DIRT, 1, 1)),
            Map.entry("minecraft:farmland", new D(Material.DIRT, 1, 1)),
            Map.entry("minecraft:coal_ore", new D(Material.COAL, 1, 1)),
            Map.entry("minecraft:deepslate_coal_ore", new D(Material.COAL, 1, 1)),
            Map.entry("minecraft:iron_ore", new D(Material.RAW_IRON, 1, 1)),
            Map.entry("minecraft:deepslate_iron_ore", new D(Material.RAW_IRON, 1, 1)),
            Map.entry("minecraft:copper_ore", new D(Material.RAW_COPPER, 2, 5)),
            Map.entry("minecraft:deepslate_copper_ore", new D(Material.RAW_COPPER, 2, 5)),
            Map.entry("minecraft:gold_ore", new D(Material.RAW_GOLD, 1, 1)),
            Map.entry("minecraft:deepslate_gold_ore", new D(Material.RAW_GOLD, 1, 1)),
            Map.entry("minecraft:diamond_ore", new D(Material.DIAMOND, 1, 1)),
            Map.entry("minecraft:deepslate_diamond_ore", new D(Material.DIAMOND, 1, 1)),
            Map.entry("minecraft:emerald_ore", new D(Material.EMERALD, 1, 1)),
            Map.entry("minecraft:deepslate_emerald_ore", new D(Material.EMERALD, 1, 1)),
            Map.entry("minecraft:lapis_ore", new D(Material.LAPIS_LAZULI, 4, 9)),
            Map.entry("minecraft:deepslate_lapis_ore", new D(Material.LAPIS_LAZULI, 4, 9)),
            Map.entry("minecraft:redstone_ore", new D(Material.REDSTONE, 4, 5)),
            Map.entry("minecraft:deepslate_redstone_ore", new D(Material.REDSTONE, 4, 5)),
            Map.entry("minecraft:nether_quartz_ore", new D(Material.QUARTZ, 1, 1)),
            Map.entry("minecraft:nether_gold_ore", new D(Material.GOLD_NUGGET, 2, 6)),
            Map.entry("minecraft:glowstone", new D(Material.GLOWSTONE_DUST, 2, 4)),
            Map.entry("minecraft:clay", new D(Material.CLAY_BALL, 4, 4)),
            Map.entry("minecraft:snow_block", new D(Material.SNOWBALL, 4, 4)),
            Map.entry("minecraft:melon", new D(Material.MELON_SLICE, 3, 7)),
            Map.entry("minecraft:bookshelf", new D(Material.BOOK, 3, 3)),
            Map.entry("minecraft:dead_bush", new D(Material.STICK, 0, 2)),
            Map.entry("minecraft:brown_mushroom_block", new D(Material.BROWN_MUSHROOM, 0, 2)),
            Map.entry("minecraft:red_mushroom_block", new D(Material.RED_MUSHROOM, 0, 2)),
            Map.entry("minecraft:sea_lantern", new D(Material.PRISMARINE_CRYSTALS, 2, 3)));

    /** С «Шёлковым касанием» выпадает сам блок; без — ничего. */
    private static boolean silkOnly(String k) {
        return k.endsWith("glass") || k.endsWith("glass_pane") || k.endsWith("ice") || k.equals("minecraft:budding_amethyst")
                || k.equals("minecraft:turtle_egg") || k.equals("minecraft:sniffer_egg") || k.equals("minecraft:spawner");
    }

    /** Никогда не выпадает: огонь, жидкости, порталы, носители своих блоков модуля blocks. */
    private static boolean never(Block b, String k) {
        return b.isAir() || b.isLiquid() || k.equals("minecraft:fire") || k.equals("minecraft:soul_fire") || k.endsWith("portal")
                || k.equals("minecraft:note_block") || k.equals("minecraft:tripwire") || k.equals("minecraft:bubble_column")
                || (k.endsWith("_leaves") && "true".equals(b.getProperty("persistent")));
    }

    static List<ItemStack> of(Block b, ItemStack tool) {
        String k = b.key().asString();
        List<ItemStack> out = new ArrayList<>(2);
        if (never(b, k)) return out;
        if ("upper".equals(b.getProperty("half"))) return out;                                         // верх двери/цветка/травы
        if ("head".equals(b.getProperty("part"))) return out;                                         // изголовье кровати
        var tc = tool.get(DataComponents.TOOL);
        if (b.registry().requiresTool() && (tc == null || !tc.isCorrectForDrops(b))) return out;
        boolean silk = silk(tool);
        if (silkOnly(k)) {
            if (silk) add(out, b.registry().material(), 1);
            return out;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (k.endsWith("_leaves")) {                                                                   // листва
            boolean shears = tool.material() == Material.SHEARS;
            if (shears || silk) { add(out, b.registry().material(), 1); return out; }
            if (r.nextInt(20) == 0) add(out, sapling(k), 1);
            if ((k.equals("minecraft:oak_leaves") || k.equals("minecraft:dark_oak_leaves")) && r.nextInt(200) == 0) add(out, Material.APPLE, 1);
            if (r.nextInt(50) == 0) add(out, Material.STICK, 1 + r.nextInt(2));
            return out;
        }
        if (k.equals("minecraft:short_grass") || k.equals("minecraft:fern") || k.equals("minecraft:tall_grass") || k.equals("minecraft:large_fern")) {
            if (tool.material() == Material.SHEARS) add(out, b.registry().material(), 1);
            else if (r.nextInt(8) == 0) add(out, Material.WHEAT_SEEDS, 1);
            return out;
        }
        if (k.equals("minecraft:gravel") && !silk && r.nextInt(10) == 0) { add(out, Material.FLINT, 1); return out; }
        if (k.equals("minecraft:snow")) {                                                              // слой снега
            if (tc == null) return out;
            int layers = parse(b.getProperty("layers"), 1);
            add(out, Material.SNOWBALL, layers);
            return out;
        }
        D d = silk ? null : MAP.get(k);
        if (d != null) {
            int n = d.min + (d.max > d.min ? r.nextInt(d.max - d.min + 1) : 0);
            add(out, d.m, n);
            return out;
        }
        int n = "double".equals(b.getProperty("type")) && k.endsWith("_slab") ? 2 : 1;
        add(out, b.registry().material(), n);
        return out;
    }

    private static Material sapling(String leaves) {
        Material m = Material.fromKey(leaves.replace("_leaves", "_sapling"));
        if (m == null) m = Material.fromKey(leaves.replace("_leaves", "_propagule"));
        return m;
    }

    private static boolean silk(ItemStack tool) {
        var en = tool.get(DataComponents.ENCHANTMENTS);
        return en != null && en.level(Enchantment.SILK_TOUCH) > 0;
    }

    private static void add(List<ItemStack> out, Material m, int n) {
        if (m == null || m == Material.AIR || n <= 0) return;
        out.add(ItemStack.of(m, n));
    }

    private static int parse(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
