package ink.hoka.odyssey.vessels;

import com.google.gson.Gson;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.entity.Player;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Мастерская корабля (кнопка «Мастерская» в меню G): рецепты из конфига (recipes:) — топливо из кристаллов,
 * ремкомплект, улучшения. Ингредиенты берутся из груза корабля (27 слотов хранилища), результат кладётся в груз
 * (топливо — сначала в бак); нет места — ничего не тратится, сообщение. Предмет в рецепте — ключ:
 * <ul>
 *   <li>{@code vessel:<вид>} — предметы кораблей: fuel, crystal, repair, engine, tank, gun, warp;</li>
 *   <li>{@code hoka:<id>} — свой блок модуля blocks (руды астероидов: hoka:iron_ore …);</li>
 *   <li>{@code item:<id>} — свой предмет модуля hoitems;</li>
 *   <li>{@code minecraft:<id>} — ванильный предмет без своих меток.</li>
 * </ul>
 */
final class Workshop {

    /** Метка модуля blocks на предмете своего блока (тот же ключ, что BlocksModule.ITEM_TAG). */
    static final Tag<String> BLOCK_TAG = Tag.String("hoka_block");
    /** Рецептов не больше (окно мастерской у мода — 2 столбца по 4). */
    static final int MAX = 8;

    /** Рецепт: id, что получается (ключ), сколько, что нужно (ключ → сколько), название. */
    record Recipe(String id, String result, int count, Map<String, Integer> needs) {}

    /** Итог: сделано, не хватает, нет места в грузе. */
    enum Result { OK, MISSING, NO_ROOM, BAD }

    private Workshop() {}

    // ───────────────────────── ключи предметов ─────────────────────────

    /** Ключ предмета (см. шапку) или null — пусто. */
    static String keyOf(ItemStack it) {
        if (it == null || it.isAir()) return null;
        String v = VesselItems.kindOf(it);
        if (v != null) return "vessel:" + v;
        String b = it.getTag(BLOCK_TAG);
        if (b != null) return "hoka:" + b;
        String h = it.getTag(VesselItems.HOITEM);
        if (h != null) return "item:" + h;
        return it.material().key().asString();
    }

    /** Названия руд астероидов (blocks-space.yml) — для подписей рецептов; остальное — из item-names конфига. */
    static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("hoka:coal_ore", "Угольная руда"), Map.entry("hoka:copper_ore", "Медная руда"),
            Map.entry("hoka:iron_ore", "Железная руда"), Map.entry("hoka:silicon_ore", "Кремниевая руда"),
            Map.entry("hoka:nickel_ore", "Никелевая руда"), Map.entry("hoka:cobalt_ore", "Кобальтовая руда"),
            Map.entry("hoka:silver_ore", "Серебряная руда"), Map.entry("hoka:gold_ore", "Золотая руда"),
            Map.entry("hoka:platinum_ore", "Платиновая руда"), Map.entry("hoka:uranium_ore", "Урановая руда"),
            Map.entry("hoka:magnesium_ore", "Магниевая руда"), Map.entry("hoka:meteor_rock", "Метеоритная порода"),
            Map.entry("hoka:space_rock", "Астероидный камень"), Map.entry("hoka:space_regolith", "Реголит"),
            Map.entry("vessel:fuel", "Топливо"), Map.entry("vessel:crystal", "Топливный кристалл"),
            Map.entry("vessel:repair", "Ремкомплект"), Map.entry("vessel:engine", "Двигатель+"), Map.entry("vessel:tank", "Бак+"),
            Map.entry("vessel:gun", "Орудие+"), Map.entry("vessel:warp", "Варп-катушка"));

    /** Название по ключу: свои имена (конфиг) → таблица → хвост ключа. */
    static String name(String key, Map<String, String> custom) {
        if (custom != null && custom.containsKey(key)) return custom.get(key);
        String n = NAMES.get(key);
        if (n != null) return n;
        int c = key.indexOf(':');
        return (c >= 0 ? key.substring(c + 1) : key).replace('_', ' ');
    }

    // ───────────────────────── конфиг ─────────────────────────

    /** recipes: конфига → рецепты (не больше {@link #MAX}); плохие — пропуск и строка в errors. */
    static List<Recipe> parse(Object section, List<String> errors) {
        List<Recipe> out = new ArrayList<>();
        if (!(section instanceof Map<?, ?> all)) return out;
        for (var e : all.entrySet()) {
            String id = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> m)) { errors.add(id + ": не раздел"); continue; }
            Object res = m.get("result");
            if (res == null) { errors.add(id + ": нет result"); continue; }
            int count = (int) Math.max(1, Math.min(64, VesselKind.num(m, "count", 1)));
            Map<String, Integer> needs = new LinkedHashMap<>();
            if (m.get("needs") instanceof Map<?, ?> nm) for (var ne : nm.entrySet()) {
                int n = (int) VesselKind.num(nm, String.valueOf(ne.getKey()), 0);
                if (n > 0) needs.put(String.valueOf(ne.getKey()), n);
            }
            if (needs.isEmpty()) { errors.add(id + ": нет needs"); continue; }
            if (out.size() >= MAX) { errors.add(id + ": рецептов больше " + MAX + " — лишние пропущены"); continue; }
            out.add(new Recipe(id, res.toString(), count, Map.copyOf(needs)));
        }
        return out;
    }

    // ───────────────────────── сделать ─────────────────────────

    /** Сколько в хранилище предметов с этим ключом. */
    static int have(ItemStack[] storage, String key) {
        int n = 0;
        for (ItemStack it : storage) if (key.equals(keyOf(it))) n += it.amount();
        return n;
    }

    /** Хватает ли на times раз. */
    static boolean enough(ItemStack[] storage, Recipe r, int times) {
        for (var e : r.needs().entrySet()) if (have(storage, e.getKey()) < e.getValue() * times) return false;
        return true;
    }

    /**
     * Сделать по рецепту times раз из груза: списать ингредиенты из хранилища, положить результат (топливо — сначала
     * в бак, остальное — в хранилище). Не хватает / нет места — груз не меняется. make — предмет по ключу и числу.
     */
    static Result craft(Cargo c, Recipe r, int times, Function<String, ItemStack> make) {
        if (times <= 0) return Result.BAD;
        if (!enough(c.storage, r, times)) return Result.MISSING;
        ItemStack sample = make.apply(r.result());
        if (sample == null || sample.isAir()) return Result.BAD;
        ItemStack[] st = c.storage.clone(), fuel = c.fuel.clone();
        for (var e : r.needs().entrySet()) take(st, e.getKey(), e.getValue() * times);
        int left = r.count() * times;
        if (VesselItems.isFuel(sample)) left = put(fuel, sample, left);
        left = put(st, sample, left);
        if (left > 0) return Result.NO_ROOM;
        System.arraycopy(st, 0, c.storage, 0, st.length);
        System.arraycopy(fuel, 0, c.fuel, 0, fuel.length);
        return Result.OK;
    }

    /** Сколько раз можно сделать (по ингредиентам), не больше max. */
    static int maxTimes(ItemStack[] storage, Recipe r, int max) {
        int t = max;
        for (var e : r.needs().entrySet()) t = Math.min(t, have(storage, e.getKey()) / e.getValue());
        return Math.max(0, t);
    }

    /** Списать n предметов с ключом (с последних слотов). */
    private static void take(ItemStack[] a, String key, int n) {
        for (int i = a.length - 1; i >= 0 && n > 0; i--) {
            if (!key.equals(keyOf(a[i]))) continue;
            int k = Math.min(n, a[i].amount());
            a[i] = a[i].amount() - k <= 0 ? ItemStack.AIR : a[i].withAmount(a[i].amount() - k);
            n -= k;
        }
    }

    /** Положить n штук sample: долить в такие же, потом в пустые. Вернуть, сколько не влезло. */
    private static int put(ItemStack[] a, ItemStack sample, int n) {
        int max = Math.max(1, sample.maxStackSize());
        for (int pass = 0; pass < 2 && n > 0; pass++) {
            for (int i = 0; i < a.length && n > 0; i++) {
                if (pass == 0 && !a[i].isAir() && a[i].isSimilar(sample) && a[i].amount() < max) {
                    int k = Math.min(n, max - a[i].amount());
                    a[i] = a[i].withAmount(a[i].amount() + k);
                    n -= k;
                } else if (pass == 1 && a[i].isAir()) {
                    int k = Math.min(n, max);
                    a[i] = sample.withAmount(k);
                    n -= k;
                }
            }
        }
        return n;
    }

    // ───────────────────────── окно ─────────────────────────

    /**
     * Окно мастерской — сундук на 6 рядов: 0–7 — рецепты (значок — что получится, в описании — что нужно и сколько
     * есть), 18–44 — груз корабля (только посмотреть), 45 — «Назад» к меню корабля. Клики: ЛКМ — сделать 1 раз,
     * Shift — сколько хватит (до 8). У игрока с модом — окно в стиле HUD (S2C SHOP), слоты те же.
     */
    static final class Menu {
        static final int CARGO0 = 18, BACK = 45, HELP = 8;

        final Vessel vessel;
        final Inventory inv;
        final String title;
        private final VesselsModule mod;

        Menu(VesselsModule mod, Vessel v) {
            this.mod = mod;
            this.vessel = v;
            this.title = "Мастерская · корабль #" + v.id;
            this.inv = new Inventory(InventoryType.CHEST_6_ROW, Component.text(title));
            for (int i = 9; i < 18; i++) inv.setItemStack(i, label(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY, List.of()));
            for (int i = 46; i < 54; i++) inv.setItemStack(i, label(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY, List.of()));
            inv.setItemStack(BACK, label(Material.ARROW, "◂ Назад", NamedTextColor.AQUA, List.of("К меню корабля.")));
            inv.setItemStack(HELP, label(Material.BOOK, "Мастерская", NamedTextColor.YELLOW,
                    List.of("Берёт из груза корабля (ряды ниже),", "кладёт результат туда же (топливо — в бак).",
                            "ЛКМ — сделать 1 раз, Shift — сколько хватит (до 8).")));
            refresh();
        }

        /** Рецепты (с тем, что есть в грузе) и груз — в окно; моду — S2C SHOP. */
        void refresh() {
            List<Recipe> rs = mod.recipes();
            Map<String, String> names = mod.itemNames();
            List<Map<String, Object>> js = new ArrayList<>();
            for (int i = 0; i < MAX; i++) {
                if (i >= rs.size()) { inv.setItemStack(i, ItemStack.AIR); continue; }
                Recipe r = rs.get(i);
                ItemStack res = mod.make(r.result(), r.count());
                boolean ok = enough(vessel.cargo.storage, r, 1);
                List<String> lore = new ArrayList<>();
                List<Map<String, Object>> needs = new ArrayList<>();
                for (var e : r.needs().entrySet()) {
                    int h = have(vessel.cargo.storage, e.getKey());
                    String n = name(e.getKey(), names);
                    lore.add((h >= e.getValue() ? "✔ " : "✘ ") + n + ": " + e.getValue() + " (в грузе " + h + ")");
                    needs.add(Map.of("name", n, "need", e.getValue(), "have", h));
                }
                lore.add(ok ? "ЛКМ — сделать, Shift — сколько хватит" : "Не хватает — положите в груз корабля");
                String nm = name(r.result(), names) + (r.count() > 1 ? " ×" + r.count() : "");
                ItemStack icon = res == null ? ItemStack.of(Material.BARRIER) : res;
                List<Component> l = lore.stream().map(s -> (Component) Component.text(s,
                        s.startsWith("✘") ? NamedTextColor.RED : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList();
                inv.setItemStack(i, icon.withAmount(Math.max(1, Math.min(icon.maxStackSize(), r.count())))
                        .withCustomName(Component.text(nm, ok ? NamedTextColor.GREEN : NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
                        .withLore(l));
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("name", name(r.result(), names));
                o.put("count", r.count());
                o.put("ok", ok);
                o.put("needs", needs);
                js.add(o);
            }
            for (int i = 0; i < Cargo.STORAGE; i++) {
                ItemStack it = vessel.cargo.storage[i];
                if (!inv.getItemStack(CARGO0 + i).equals(it)) inv.setItemStack(CARGO0 + i, it);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("window", (int) inv.getWindowId());
            root.put("title", title);
            root.put("id", vessel.id);
            root.put("kind", vessel.kind);
            root.put("recipes", js);
            String json = new Gson().toJson(root);
            for (Player p : inv.getViewers()) mod.client.shop(p, json);
            lastJson = json;
        }

        /** Последняя сводка (моду — перед открытием окна). */
        String lastJson;

        /** Клик в окне: всё отменяется; рецепт — сделать; «Назад» — меню корабля. */
        void onClick(InventoryPreClickEvent e) {
            e.setCancelled(true);
            if (e.getInventory() != inv) return;
            Player p = e.getPlayer();
            int slot = e.getSlot();
            if (slot == BACK) { mod.scheduler().run(() -> mod.openMenu(p, vessel.id)); return; }
            List<Recipe> rs = mod.recipes();
            if (slot < 0 || slot >= Math.min(MAX, rs.size())) return;
            boolean shift = e.getClick() instanceof Click.LeftShift || e.getClick() instanceof Click.RightShift;
            if (!(e.getClick() instanceof Click.Left || e.getClick() instanceof Click.Right || shift)) return;
            Recipe r = rs.get(slot);
            mod.scheduler().run(() -> mod.craft(p, vessel, r, shift));
        }

        void closeAll() {
            for (Player p : List.copyOf(inv.getViewers())) p.closeInventory();
        }

        private static ItemStack label(Material m, String name, NamedTextColor color, List<String> lore) {
            return ItemStack.builder(m)
                    .customName(Component.text(name, color).decoration(TextDecoration.ITALIC, false))
                    .lore(lore.stream().map(l -> (Component) Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList())
                    .build();
        }
    }
}
