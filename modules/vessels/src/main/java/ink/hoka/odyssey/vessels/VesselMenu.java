package ink.hoka.odyssey.vessels;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.entity.Player;
import net.minestom.server.event.inventory.InventoryItemChangeEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.List;

/**
 * Меню корабля (G) — окно-сундук на 5 рядов (houi не умеет слоты предметов): настоящие слоты, перетаскивание,
 * Shift-клик. Раскладка:
 * <pre>
 * ряды 1–3   хранилище 27
 * ряд 4      [Улучшения ▸] 8 слотов улучшений
 * ряд 5      [Топливо ▸] 4 слота топлива  ·  [бак] [варп] [орудие] — сводка
 * </pre>
 * В слоты улучшений кладутся только улучшения (по одному), в слоты топлива — только топливо. Shift-клик из своего
 * инвентаря раскладывает сам: топливо — в бак, улучшения — в слоты улучшений, остальное (и что не влезло) — в
 * хранилище. Окно одно на корабль (владелец и OP видят одно и то же); груз корабля — {@link Cargo}, окно его зеркало.
 */
final class VesselMenu {

    static final int UP_LABEL = 27, UP0 = 28, FUEL_LABEL = 36, FUEL0 = 37, GAP = 41, INFO_FUEL = 42, INFO_WARP = 43, INFO_GUN = 44;

    enum Role { STORAGE, UPGRADE, FUEL, DECOR }

    final Vessel vessel;
    final Inventory inv;
    private final VesselsModule mod;
    /** Сейчас окно само переписывает слоты из груза — изменения не возвращать в груз. */
    private boolean syncing;

    VesselMenu(VesselsModule mod, Vessel v) {
        this.mod = mod;
        this.vessel = v;
        this.inv = new Inventory(InventoryType.CHEST_5_ROW,
                Component.text("Корабль #" + v.id + " · " + v.kind + (v.ownerName != null ? " · " + v.ownerName : "")));
        inv.setItemStack(UP_LABEL, label(Material.CYAN_STAINED_GLASS_PANE, "Улучшения ▸", NamedTextColor.AQUA,
                List.of("Двигатель+, Бак+, Орудие+, Варп-катушка.", "Одинаковые складываются.")));
        inv.setItemStack(FUEL_LABEL, label(Material.ORANGE_STAINED_GLASS_PANE, "Топливо ▸", NamedTextColor.GOLD,
                List.of("Бак — 4 слота. Варп тратит топливо отсюда.")));
        inv.setItemStack(GAP, label(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY, List.of()));
        refresh();
    }

    /** Роль слота окна. */
    static Role role(int slot) {
        if (slot >= 0 && slot < Cargo.STORAGE) return Role.STORAGE;
        if (slot >= UP0 && slot < UP0 + Cargo.UPGRADES) return Role.UPGRADE;
        if (slot >= FUEL0 && slot < FUEL0 + Cargo.FUEL) return Role.FUEL;
        return Role.DECOR;
    }

    /** Можно ли положить предмет в слот этой роли. */
    static boolean accepts(Role r, ItemStack it) {
        if (it == null || it.isAir()) return true;
        return switch (r) {
            case STORAGE -> true;
            case UPGRADE -> VesselItems.upgradeOf(it) != null;
            case FUEL -> VesselItems.isFuel(it);
            case DECOR -> false;
        };
    }

    /** Наибольшая стопка в слоте этой роли (улучшение — одно на слот). */
    static int maxIn(Role r, ItemStack it) { return r == Role.UPGRADE ? 1 : Math.max(1, it.maxStackSize()); }

    // ───────────────────────── груз ↔ окно ─────────────────────────

    /** Груз → окно (сервер поменял груз: топливо ушло на варп и т.п.) и сводка. */
    void refresh() {
        syncing = true;
        try {
            Cargo c = vessel.cargo;
            for (int i = 0; i < Cargo.STORAGE; i++) put(i, c.storage[i]);
            for (int i = 0; i < Cargo.UPGRADES; i++) put(UP0 + i, c.upgrades[i]);
            for (int i = 0; i < Cargo.FUEL; i++) put(FUEL0 + i, c.fuel[i]);
            info();
        } finally {
            syncing = false;
        }
    }

    private void put(int slot, ItemStack it) {
        if (!inv.getItemStack(slot).equals(it)) inv.setItemStack(slot, it);
    }

    /** Сводка: бак, варп, орудие. */
    void info() {
        VesselsModule.Status s = mod.status(vessel);
        inv.setItemStack(INFO_FUEL, label(Material.GLOWSTONE_DUST, "Бак: " + s.fuel() + " / " + s.capacity(), NamedTextColor.GOLD,
                List.of("Варп стоит " + s.warpCost() + " топлива.", s.burnPerMinute() > 0 ? "В полёте: " + s.burnPerMinute() + " в минуту." : "Обычный полёт топливо не тратит.")));
        inv.setItemStack(INFO_WARP, label(Material.ENDER_PEARL, "Варп: " + Math.round(s.warpDistance()) + " блоков", NamedTextColor.LIGHT_PURPLE,
                List.of("Зарядка " + fmt(s.warpChargeSec()) + " с, перезарядка " + fmt(s.warpReloadSec()) + " с.", "R — прыжок (пилот).")));
        inv.setItemStack(INFO_GUN, label(Material.BLAZE_ROD, "Орудие: урон " + fmt(s.gunDamage()), NamedTextColor.RED,
                List.of("Перезарядка " + fmt(s.gunReloadSec()) + " с, дальность " + Math.round(s.gunRange()) + ".", "ЛКМ — огонь (пилот).",
                        "Скорость ×" + fmt(s.speedMul()) + ".")));
    }

    private static String fmt(double v) {
        return Math.abs(v - Math.round(v)) < 0.005 ? String.valueOf(Math.round(v)) : String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static ItemStack label(Material m, String name, NamedTextColor color, List<String> lore) {
        return ItemStack.builder(m)
                .customName(Component.text(name, color).decoration(TextDecoration.ITALIC, false))
                .lore(lore.stream().map(l -> (Component) Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList())
                .build();
    }

    /** Слот окна поменялся (кликом игрока) — в груз; не то положили (обходной путь) — вернуть игроку. */
    void onChange(InventoryItemChangeEvent e) {
        if (syncing) return;
        int slot = e.getSlot();
        Role r = role(slot);
        if (r == Role.DECOR) return;
        ItemStack it = e.getNewItem();
        if (!accepts(r, it) || it.amount() > maxIn(r, it)) {                    // страховка: сюда нельзя — выгрузить лишнее
            ItemStack keep = accepts(r, it) ? it.withAmount(maxIn(r, it)) : ItemStack.AIR;
            ItemStack extra = keep.isAir() ? it : it.withAmount(it.amount() - keep.amount());
            syncing = true;
            try { inv.setItemStack(slot, keep); } finally { syncing = false; }
            it = keep;
            giveBack(extra);
        }
        Cargo c = vessel.cargo;
        switch (r) {
            case STORAGE -> c.storage[slot] = it;
            case UPGRADE -> c.upgrades[slot - UP0] = it;
            case FUEL -> c.fuel[slot - FUEL0] = it;
            default -> { }
        }
        mod.cargoChanged(vessel);
    }

    /** Лишнее — в хранилище, иначе первому, кто смотрит окно, иначе — на землю у корабля. */
    private void giveBack(ItemStack extra) {
        if (extra.isAir()) return;
        ItemStack rest = place(extra, storageSlots());
        if (rest.isAir()) return;
        for (Player p : inv.getViewers()) {
            if (p.getInventory().addItemStack(rest)) return;
        }
        mod.dropNear(vessel, rest);
    }

    // ───────────────────────── клики ─────────────────────────

    /** Проверка клика, пока открыто это окно (event — по окну или по инвентарю игрока под ним). */
    void onClick(InventoryPreClickEvent e) {
        Player p = e.getPlayer();
        Click c = e.getClick();
        ItemStack cursor = p.getInventory().getCursorItem();
        if (e.getInventory() == inv) {
            if (c instanceof Click.Drag d) {
                for (int s : d.slots()) if (s < inv.getSize() && !accepts(role(s), cursor)) { e.setCancelled(true); return; }
                return;
            }
            int slot = e.getSlot();
            Role r = role(slot);
            if (r == Role.DECOR) { e.setCancelled(true); return; }
            switch (c) {
                case Click.Left l -> { if (!accepts(r, cursor)) e.setCancelled(true); else if (r == Role.UPGRADE) oneUpgrade(e, p, slot, cursor); }
                case Click.Right rr -> { if (!accepts(r, cursor)) e.setCancelled(true); }
                case Click.HotbarSwap h -> { if (!accepts(r, p.getInventory().getItemStack(h.hotbarSlot()))) e.setCancelled(true); }
                case Click.OffhandSwap o -> { if (!accepts(r, p.getItemInOffHand())) e.setCancelled(true); }
                default -> { }
            }
            return;
        }
        if (e.getInventory() instanceof PlayerInventory pi && (c instanceof Click.LeftShift || c instanceof Click.RightShift)) {
            e.setCancelled(true);                                               // Shift-клик снизу — раскладываем сами
            int slot = e.getSlot();
            ItemStack it = pi.getItemStack(slot);
            if (it.isAir()) return;
            ItemStack rest = shiftIn(it);
            pi.setItemStack(slot, rest);
        }
    }

    /** Левый клик стопкой улучшений по пустому слоту улучшения — кладём одно, остальное остаётся на курсоре. */
    private void oneUpgrade(InventoryPreClickEvent e, Player p, int slot, ItemStack cursor) {
        if (cursor.isAir() || cursor.amount() <= 1) return;
        e.setCancelled(true);
        ItemStack here = inv.getItemStack(slot);
        if (!here.isAir()) return;
        inv.setItemStack(slot, cursor.withAmount(1));
        p.getInventory().setCursorItem(cursor.withAmount(cursor.amount() - 1));
    }

    /** Разложить предмет из инвентаря игрока по окну; вернуть, что не влезло. */
    ItemStack shiftIn(ItemStack it) {
        ItemStack rest = it;
        if (VesselItems.isFuel(rest)) rest = place(rest, range(FUEL0, Cargo.FUEL));
        else if (VesselItems.upgradeOf(rest) != null) rest = place(rest, range(UP0, Cargo.UPGRADES));
        if (!rest.isAir()) rest = place(rest, storageSlots());
        return rest;
    }

    private static int[] range(int from, int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = from + i;
        return a;
    }

    private static int[] storageSlots() { return range(0, Cargo.STORAGE); }

    /** Положить в слоты: сначала долить в такие же стопки, потом в пустые. Вернуть остаток. */
    ItemStack place(ItemStack it, int[] slots) {
        int left = it.amount();
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int s : slots) {
                if (left <= 0) break;
                Role r = role(s);
                if (!accepts(r, it)) continue;
                ItemStack here = inv.getItemStack(s);
                int max = maxIn(r, it);
                if (pass == 0 && !here.isAir() && here.isSimilar(it) && here.amount() < max) {
                    int add = Math.min(left, max - here.amount());
                    inv.setItemStack(s, here.withAmount(here.amount() + add));
                    left -= add;
                } else if (pass == 1 && here.isAir()) {
                    int add = Math.min(left, max);
                    inv.setItemStack(s, it.withAmount(add));
                    left -= add;
                }
            }
        }
        return left <= 0 ? ItemStack.AIR : it.withAmount(left);
    }

    /** Закрыть у всех (корабль убран / модуль выгружается). */
    void closeAll() {
        for (Player p : List.copyOf(inv.getViewers())) p.closeInventory();
    }
}
