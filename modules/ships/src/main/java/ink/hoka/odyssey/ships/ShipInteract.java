package ink.hoka.odyssey.ships;

import com.google.gson.JsonParser;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Клики по стоящему кораблю-модели (его блоков в мире нет — их рисует мод, мод же находит, куда смотрит игрок):
 * двери/люки/калитки открываются, сундуки и бочки хранят вещи (вместе с кораблём, в ships.json), клик по месту
 * пилота — взлёт, блоком в руке — поставить блок, ЛКМ — сломать. Все изменения — сразу всем (пакет CELL).
 * Права: строить — владелец и OP (build.others), двери и сундуки — все (interact.others).
 */
final class ShipInteract {

    private static final int[][] DIR = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};   // как Direction: down up north south west east
    private static final String[] H = {"north", "east", "south", "west"};
    private static final int[][] HV = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}};

    private final ShipsModule mod;
    /** Открытые сундуки: ключ «владелец:x,y,z» → общее окно (все смотрят в одно). */
    private final Map<String, Inventory> open = new HashMap<>();
    private final Map<Inventory, String[]> openKey = new HashMap<>();

    ShipInteract(ShipsModule mod) { this.mod = mod; }

    void enable() {
        mod.events().addListener(InventoryCloseEvent.class, e -> {
            if (!(e.getInventory() instanceof Inventory inv)) return;
            String[] k = openKey.get(inv);
            if (k == null) return;
            // окно ещё смотрят — сохраним при последнем
            if (inv.getViewers().size() > 1 || (inv.getViewers().size() == 1 && !inv.getViewers().contains(e.getPlayer()))) { save(inv); return; }
            save(inv);
            open.remove(k[0] + ":" + k[1]);
            openKey.remove(inv);
        });
    }

    void disable() {
        for (Inventory inv : new ArrayList<>(openKey.keySet())) {
            save(inv);
            for (Player p : new ArrayList<>(inv.getViewers())) p.closeInventory();
        }
        open.clear();
        openKey.clear();
    }

    // ───────────────────────── поиск ─────────────────────────

    private Ship shipByAnchor(int anchor) {
        for (Ship s : mod.ships.values()) {
            Entity a = mod.parked.get(s.owner);
            if (a != null && a.getEntityId() == anchor && s.state == Ship.State.MODEL) return s;
        }
        return null;
    }

    private static int find(Ship s, int dx, int dy, int dz) {
        for (int i = 0; i < s.mparts.size(); i++) {
            int[] q = s.mparts.get(i);
            if (q[0] == dx && q[1] == dy && q[2] == dz) return i;
        }
        return -1;
    }

    private static Block blockAt(Ship s, int dx, int dy, int dz) {
        int i = find(s, dx, dy, dz);
        return i < 0 ? null : Block.fromState(s.mblocks.get(i));
    }

    /** Центр клетки корабля в мире. */
    private Vec cellWorld(Ship s, int dx, int dy, int dz) {
        float[] q = Rot.quat(s.myaw, s.mpitch, s.mroll, s.mturn);
        double[] r = Rot.rotate(q, dx, dy - s.mseatH + 0.5, dz);
        return new Vec(s.mx + r[0], s.my + r[1], s.mz + r[2]);
    }

    private boolean reach(Player p, Ship s, int dx, int dy, int dz) {
        if (mod.instanceOf(s) != p.getInstance()) return false;
        double max = mod.config().getDouble("interact.reach", 7);
        return p.getPosition().add(0, p.getEyeHeight(), 0).distanceSquared(cellWorld(s, dx, dy, dz)) <= max * max;
    }

    private boolean canBuild(Player p, Ship s) {
        return mod.canControl(p, s) || mod.config().getBoolean("build.others", false);
    }

    private boolean canUse(Player p, Ship s) {
        return mod.canControl(p, s) || mod.config().getBoolean("interact.others", true);
    }

    // ───────────────────────── ПКМ ─────────────────────────

    /** ПКМ по клетке (dx,dy,dz), грань face (как Direction), точка попадания в клетке (0..1, система корабля). */
    void use(Player p, int anchor, int dx, int dy, int dz, int face, float hx, float hy, float hz) {
        Ship s = shipByAnchor(anchor);
        if (s == null || !reach(p, s, dx, dy, dz)) return;
        Block b = blockAt(s, dx, dy, dz);
        if (b == null) return;
        // место пилота — взлёт
        if (dx == 0 && dz == 0 && (dy == 0 || dy == -1)) {
            if (!mod.canControl(p, s)) { p.sendActionBar(Component.text("Это корабль игрока " + s.ownerName, NamedTextColor.RED)); return; }
            mod.takeOff(p, s);
            return;
        }
        String id = b.key().value();
        if (toggles(id)) {
            if (!canUse(p, s)) { deny(p, s); return; }
            toggle(s, dx, dy, dz, b, id);
            return;
        }
        if (id.equals("chest") || id.equals("trapped_chest") || id.equals("barrel")) {
            if (!canUse(p, s)) { deny(p, s); return; }
            openChest(p, s, dx, dy, dz, b, id);
            return;
        }
        // блок в руке — поставить рядом
        ItemStack hand = p.getItemInMainHand();
        Block place = hand.material().isBlock() ? hand.material().block() : null;
        if (place == null || place.isAir()) return;
        if (!canBuild(p, s)) { p.sendActionBar(Component.text("Строить на корабле может только его владелец", NamedTextColor.RED)); return; }
        if (face < 0 || face >= 6) return;
        int nx = dx + DIR[face][0], ny = dy + DIR[face][1], nz = dz + DIR[face][2];
        if (find(s, nx, ny, nz) >= 0) return;
        int max = mod.config().getInt("templates.max-blocks", 3000);
        if (s.mparts.size() >= max) { p.sendActionBar(Component.text("Корабль больше не вырастет (не больше " + max + " блоков)", NamedTextColor.RED)); return; }
        Block st = orient(place, p, s, face, hy);
        List<int[]> changed = new ArrayList<>();
        if (id(place).endsWith("_door")) {
            if (find(s, nx, ny + 1, nz) >= 0) return;
            add(s, nx, ny + 1, nz, with(st, "half", "upper"));
            changed.add(new int[]{nx, ny + 1, nz});
            st = with(st, "half", "lower");
        }
        add(s, nx, ny, nz, st);
        changed.add(new int[]{nx, ny, nz});
        if (p.getGameMode() != GameMode.CREATIVE) p.setItemInMainHand(hand.consume(1));
        sound(s, nx, ny, nz, "block.stone.place");
        commit(s, changed);
    }

    // ───────────────────────── ЛКМ ─────────────────────────

    void breakBlock(Player p, int anchor, int dx, int dy, int dz) {
        Ship s = shipByAnchor(anchor);
        if (s == null || !reach(p, s, dx, dy, dz)) return;
        Block b = blockAt(s, dx, dy, dz);
        if (b == null) return;
        if (!canBuild(p, s)) { p.sendActionBar(Component.text("Ломать корабль может только его владелец", NamedTextColor.RED)); return; }
        if (dx == 0 && dz == 0 && (dy == 0 || dy == -1)) { p.sendActionBar(Component.text("Место пилота не сломать", NamedTextColor.RED)); return; }
        if (s.mparts.size() <= 2) return;
        List<int[]> changed = new ArrayList<>();
        changed.add(new int[]{dx, dy, dz});
        String id = id(b);
        if (id.endsWith("_door")) {                                             // вторая половина двери
            int oy = "upper".equals(b.getProperty("half")) ? dy - 1 : dy + 1;
            Block o = blockAt(s, dx, oy, dz);
            if (o != null && id(o).equals(id)) { remove(s, dx, oy, dz); changed.add(new int[]{dx, oy, dz}); }
        }
        // содержимое сундука — игроку
        String key = cellKey(dx, dy, dz);
        if (s.mchests != null && s.mchests.containsKey(key)) {
            closeChest(s, key);
            for (String js : s.mchests.remove(key)) {
                ItemStack it = item(js);
                if (!it.isAir() && !p.getInventory().addItemStack(it)) drop(p, it);
            }
        }
        remove(s, dx, dy, dz);
        if (p.getGameMode() != GameMode.CREATIVE) {
            Material m = b.registry().material();
            if (m != null && m != Material.AIR) {
                ItemStack it = ItemStack.of(m);
                if (!p.getInventory().addItemStack(it)) drop(p, it);
            }
        }
        sound(s, dx, dy, dz, "block.stone.break");
        commit(s, changed);
    }

    // ───────────────────────── двери, люки, калитки ─────────────────────────

    private static boolean toggles(String id) {
        return (id.endsWith("_door") || id.endsWith("_trapdoor") || id.endsWith("fence_gate")) && !id.startsWith("iron_");
    }

    private void toggle(Ship s, int dx, int dy, int dz, Block b, String id) {
        boolean opened = !"true".equals(b.getProperty("open"));
        String v = String.valueOf(opened);
        List<int[]> changed = new ArrayList<>();
        set(s, dx, dy, dz, with(b, "open", v));
        changed.add(new int[]{dx, dy, dz});
        if (id.endsWith("_door")) {
            int oy = "upper".equals(b.getProperty("half")) ? dy - 1 : dy + 1;
            Block o = blockAt(s, dx, oy, dz);
            if (o != null && id(o).equals(id)) { set(s, dx, oy, dz, with(o, "open", v)); changed.add(new int[]{dx, oy, dz}); }
        }
        String snd = id.endsWith("_trapdoor") ? "block.wooden_trapdoor." : id.endsWith("fence_gate") ? "block.fence_gate." : "block.wooden_door.";
        sound(s, dx, dy, dz, snd + (opened ? "open" : "close"));
        commit(s, changed);
    }

    // ───────────────────────── сундуки ─────────────────────────

    private static String cellKey(int dx, int dy, int dz) { return dx + "," + dy + "," + dz; }

    private void openChest(Player p, Ship s, int dx, int dy, int dz, Block b, String id) {
        String key = cellKey(dx, dy, dz);
        int size = 27;
        // двойной сундук: соседняя половина (как у игры: left — по часовой от facing, right — против)
        String type = b.getProperty("type"), facing = b.getProperty("facing");
        if (!id.equals("barrel") && type != null && !type.equals("single") && facing != null) {
            int f = java.util.Arrays.asList(H).indexOf(facing);
            if (f >= 0) {
                int d = type.equals("left") ? (f + 1) & 3 : (f + 3) & 3;
                int ox = dx + HV[d][0], oz = dz + HV[d][2];
                Block o = blockAt(s, ox, dy, oz);
                if (o != null && id(o).equals(id)) {
                    String ok = cellKey(ox, dy, oz);
                    if (ok.compareTo(key) < 0) key = ok;                        // одна пара — одно окно
                    size = 54;
                }
            }
        }
        String full = s.owner + ":" + key;
        Inventory inv = open.get(full);
        if (inv == null) {
            inv = new Inventory(size == 54 ? InventoryType.CHEST_6_ROW : InventoryType.CHEST_3_ROW,
                    Component.text(id.equals("barrel") ? "Бочка" : size == 54 ? "Большой сундук" : "Сундук"));
            List<String> items = s.mchests == null ? null : s.mchests.get(key);
            if (items != null) for (int i = 0; i < Math.min(items.size(), inv.getSize()); i++) inv.setItemStack(i, item(items.get(i)));
            open.put(full, inv);
            openKey.put(inv, new String[]{s.owner, key});
        }
        p.openInventory(inv);
        sound(s, dx, dy, dz, id.equals("barrel") ? "block.barrel.open" : "block.chest.open");
    }

    private void closeChest(Ship s, String key) {
        Inventory inv = open.remove(s.owner + ":" + key);
        if (inv == null) return;
        save(inv);
        openKey.remove(inv);
        for (Player v : new ArrayList<>(inv.getViewers())) v.closeInventory();
    }

    private void save(Inventory inv) {
        String[] k = openKey.get(inv);
        if (k == null) return;
        Ship s = mod.ships.get(k[0]);
        if (s == null) return;
        List<String> items = new ArrayList<>(inv.getSize());
        for (int i = 0; i < inv.getSize(); i++) items.add(json(inv.getItemStack(i)));
        if (s.mchests == null) s.mchests = new HashMap<>();
        s.mchests.put(k[1], items);
        mod.saveShips();
    }

    private static String json(ItemStack it) {
        if (it == null || it.isAir()) return "";
        return ItemStack.CODEC.encode(Transcoder.JSON, it).orElseThrow().toString();
    }

    private static ItemStack item(String js) {
        if (js == null || js.isEmpty()) return ItemStack.AIR;
        try {
            return ItemStack.CODEC.decode(Transcoder.JSON, JsonParser.parseString(js)).orElse(ItemStack.AIR);
        } catch (Exception e) {
            return ItemStack.AIR;
        }
    }

    private void drop(Player p, ItemStack it) {
        net.minestom.server.entity.ItemEntity e = new net.minestom.server.entity.ItemEntity(it);
        e.setInstance(p.getInstance(), p.getPosition().add(0, 1, 0));
    }

    // ───────────────────────── постановка блока ─────────────────────────

    /** Состояние ставимого блока: поворот по взгляду игрока (в системе корабля), ось по грани, верх/низ по точке. */
    private Block orient(Block b, Player p, Ship s, int face, float hy) {
        String id = id(b);
        // взгляд игрока → в систему корабля
        float[] q = Rot.quat(s.myaw, s.mpitch, s.mroll, s.mturn);
        float[] inv = {-q[0], -q[1], -q[2], q[3]};
        Vec look = p.getPosition().direction();
        double[] l = Rot.rotate(inv, look.x(), look.y(), look.z());
        int hd = Math.abs(l[0]) > Math.abs(l[2]) ? (l[0] > 0 ? 1 : 3) : (l[2] > 0 ? 2 : 0);   // куда смотрит: n e s w
        if (b.getProperty("facing") != null) {
            boolean along = id.contains("stairs") || id.endsWith("_door") || id.endsWith("fence_gate");
            String f = H[along ? hd : (hd + 2) & 3];
            if (id.endsWith("_trapdoor") && face >= 2) f = H[faceToH(face)];
            b = with(b, "facing", f);
        }
        if (b.getProperty("axis") != null) b = with(b, "axis", face <= 1 ? "y" : face <= 3 ? "z" : "x");
        boolean top = face == 0 || (face >= 2 && hy > 0.5f);
        if (id.endsWith("_slab")) b = with(b, "type", top ? "top" : "bottom");
        if ((id.contains("stairs") || id.endsWith("_trapdoor")) && b.getProperty("half") != null) b = with(b, "half", top ? "top" : "bottom");
        if (b.getProperty("waterlogged") != null) b = with(b, "waterlogged", "false");
        return b;
    }

    /** Грань (2..5: north south west east) → индекс в H. */
    private static int faceToH(int face) {
        return switch (face) { case 2 -> 0; case 3 -> 2; case 4 -> 3; default -> 1; };
    }

    private static Block with(Block b, String k, String v) {
        try {
            return b.getProperty(k) == null ? b : b.withProperty(k, v);
        } catch (IllegalArgumentException e) {
            return b;
        }
    }

    private static String id(Block b) { return b.key().value(); }

    // ───────────────────────── изменение корабля ─────────────────────────

    private static void add(Ship s, int dx, int dy, int dz, Block b) {
        s.mparts.add(new int[]{dx, dy, dz});
        s.mblocks.add(b.state());
    }

    private static void set(Ship s, int dx, int dy, int dz, Block b) {
        int i = find(s, dx, dy, dz);
        if (i >= 0) s.mblocks.set(i, b.state());
    }

    private static void remove(Ship s, int dx, int dy, int dz) {
        int i = find(s, dx, dy, dz);
        if (i < 0) return;
        s.mparts.remove(i);
        s.mblocks.remove(i);
    }

    /** Сохранить и разослать изменённые клетки всем. */
    private void commit(Ship s, List<int[]> cells) {
        mod.saveShips();
        Entity a = mod.parked.get(s.owner);
        if (a == null) return;
        for (int[] c : cells) {
            Block b = blockAt(s, c[0], c[1], c[2]);
            mod.client.cell(a.getEntityId(), c[0], c[1], c[2], b == null ? 0 : b.stateId());
        }
    }

    private void sound(Ship s, int dx, int dy, int dz, String key) {
        Instance inst = mod.instanceOf(s);
        if (inst == null) return;
        Vec w = cellWorld(s, dx, dy, dz);
        inst.playSound(Sound.sound(Key.key(key), Sound.Source.BLOCK, 1f, 1f), w.x(), w.y(), w.z());
    }

    private static void deny(Player p, Ship s) {
        p.sendActionBar(Component.text("Это корабль игрока " + s.ownerName, NamedTextColor.RED));
    }

    @SuppressWarnings("unused")
    private static Pos pos(Vec v) { return new Pos(v.x(), v.y(), v.z()); }
}
