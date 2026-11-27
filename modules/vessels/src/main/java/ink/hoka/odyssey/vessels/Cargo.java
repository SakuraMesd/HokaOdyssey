package ink.hoka.odyssey.vessels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Груз корабля: хранилище 27, улучшения 8, топливо 4 (слоты меню). Запас топлива — сумма предметов «Топливо» в слотах
 * топлива, ёмкость — 4 × размер стопки; расход списывает предметы из слотов (с последнего). Предметы хранятся
 * целиком (со всеми компонентами) — ItemStack.CODEC в JSON.
 */
final class Cargo {

    static final int STORAGE = 27, UPGRADES = 8, FUEL = 4;

    final ItemStack[] storage = air(STORAGE), upgrades = air(UPGRADES), fuel = air(FUEL);

    private static ItemStack[] air(int n) {
        ItemStack[] a = new ItemStack[n];
        java.util.Arrays.fill(a, ItemStack.AIR);
        return a;
    }

    // ───────────────────────── топливо ─────────────────────────

    /** Запас топлива (штук). */
    int fuelUnits() {
        int n = 0;
        for (ItemStack it : fuel) if (VesselItems.isFuel(it)) n += it.amount();
        return n;
    }

    /** Полный бак (штук): 4 слота × размер стопки топлива. */
    static int capacity(int stackSize) { return FUEL * Math.max(1, stackSize); }

    /** Списать n штук (с последнего слота). Не хватает — ничего не списывает, false. */
    boolean consumeFuel(int n) {
        if (n <= 0) return true;
        if (fuelUnits() < n) return false;
        for (int i = FUEL - 1; i >= 0 && n > 0; i--) {
            ItemStack it = fuel[i];
            if (!VesselItems.isFuel(it)) continue;
            int take = Math.min(n, it.amount());
            fuel[i] = it.amount() - take <= 0 ? ItemStack.AIR : it.withAmount(it.amount() - take);
            n -= take;
        }
        return true;
    }

    /** Стоимость варпа (штук): доля полного бака × множитель улучшений, вверх, не меньше 1 (если доля > 0). */
    static int warpCost(int capacity, double fraction, double mul) {
        if (fraction <= 0) return 0;
        return Math.max(1, (int) Math.ceil(capacity * fraction * mul - 1e-9));
    }

    // ───────────────────────── улучшения ─────────────────────────

    /** Сколько улучшений каждого вида (по слоту — одно, даже если в слоте стопка). */
    int[] upgradeCounts() {
        int[] c = new int[Upgrades.Type.values().length];
        for (ItemStack it : upgrades) {
            Upgrades.Type t = VesselItems.upgradeOf(it);
            if (t != null) c[t.ordinal()]++;
        }
        return c;
    }

    // ───────────────────────── всё вместе ─────────────────────────

    boolean isEmpty() {
        for (ItemStack[] a : new ItemStack[][]{storage, upgrades, fuel}) for (ItemStack it : a) if (!it.isAir()) return false;
        return true;
    }

    /** Все непустые предметы (для выпадения при удалении корабля). */
    List<ItemStack> all() {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack[] a : new ItemStack[][]{storage, upgrades, fuel}) for (ItemStack it : a) if (!it.isAir()) out.add(it);
        return out;
    }

    void clear() {
        java.util.Arrays.fill(storage, ItemStack.AIR);
        java.util.Arrays.fill(upgrades, ItemStack.AIR);
        java.util.Arrays.fill(fuel, ItemStack.AIR);
    }

    // ───────────────────────── JSON ─────────────────────────

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("storage", arr(storage));
        o.add("upgrades", arr(upgrades));
        o.add("fuel", arr(fuel));
        return o;
    }

    /** Прочитать; предмет не разобрался — исключение (файл тогда считается битым и не перезаписывается). */
    static Cargo fromJson(JsonObject o) {
        Cargo c = new Cargo();
        if (o == null) return c;
        read(o.getAsJsonArray("storage"), c.storage);
        read(o.getAsJsonArray("upgrades"), c.upgrades);
        read(o.getAsJsonArray("fuel"), c.fuel);
        return c;
    }

    static JsonElement item(ItemStack it) {
        if (it == null || it.isAir()) return JsonNull.INSTANCE;
        return ItemStack.CODEC.encode(Transcoder.JSON, it).orElseThrow();
    }

    static ItemStack item(JsonElement e) {
        if (e == null || e.isJsonNull()) return ItemStack.AIR;
        return ItemStack.CODEC.decode(Transcoder.JSON, e).orElseThrow();
    }

    private static JsonArray arr(ItemStack[] a) {
        JsonArray r = new JsonArray();
        for (ItemStack it : a) r.add(item(it));
        return r;
    }

    private static void read(JsonArray src, ItemStack[] dst) {
        if (src == null) return;
        for (int i = 0; i < Math.min(src.size(), dst.length); i++) dst[i] = item(src.get(i));
    }
}
