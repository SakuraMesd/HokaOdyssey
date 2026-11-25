package ink.hoka.odyssey.ships;

import com.google.gson.JsonParser;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.codec.Transcoder;
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
 * Клики по кораблю — стоящему моделью и летящему (его блоков в мире нет — их рисует мод, мод же находит, куда
 * смотрит игрок): двери/люки/калитки открываются, сундуки и бочки хранят вещи (вместе с кораблём, в ships.json),
 * клик по месту пилота стоящего корабля — взлёт, блоком в руке — поставить блок, ЛКМ — сломать. Все изменения —
 * сразу всем (пакет CELL), в полёте — и пилоту; при стоянке они остаются в корабле.
 * Права: строить — владелец и OP (build.others), двери и сундуки — все (interact.others).
 */
final class ShipInteract {

    private static final int[][] DIR = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};   // как Direction: down up north south west east
    private static final String[] H = {"north", "east", "south", "west"};
    private static final int[][] HV = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}};

    /** Клетки корабля: стоящего моделью (поля m*) или летящего (части полёта). */
    private interface Cells {
        Ship ship();

        Block get(int dx, int dy, int dz);

        void set(int dx, int dy, int dz, Block b);                 // null — убрать

        int count();

        /** Поворот система корабля → мир (как Rot.quat) и точка пилота, высота сиденья. */
        float[] rot();

        Vec pivot();

        double seatH();

        Instance instance();

        int anchor();

        boolean flying();
    }

    private final ShipsModule mod;
    /** Открытые сундуки: ключ «владелец:x,y,z» → общее окно (все смотрят в одно). */
    private final Map<String, Inventory> open = new HashMap<>();
    private final Map<Inventory, String[]> openKey = new HashMap<>();

    ShipInteract(ShipsModule mod) { this.mod = mod; }

    /** Открытые сундуки кораблей из верфи: окно → {якорь, клетка} (для крышки). */
    private final Map<Inventory, int[]> lids = new java.util.concurrent.ConcurrentHashMap<>();

    void enable() {
        mod.events().addListener(InventoryCloseEvent.class, e -> {
            if (e.getInventory() instanceof Inventory inv && lids.containsKey(inv)) {
                mod.scheduler().run(() -> {
                    int[] l = lids.get(inv);
                    if (l == null) return;
                    int n = inv.getViewers().size();
                    mod.client.lid(l[0], l[1], l[2], l[3], n);
                    if (n == 0) lids.remove(inv);
                });
            }
        });
        mod.events().addListener(InventoryCloseEvent.class, e -> {
            if (!(e.getInventory() instanceof Inventory inv)) return;
            String[] k = openKey.get(inv);
            if (k == null) return;
            save(inv);
            boolean others = false;
            for (Player v : inv.getViewers()) if (v != e.getPlayer()) { others = true; break; }
            if (others) return;                                                // окно ещё смотрят
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

    private Cells byAnchor(int anchor) {
        for (Ship s : mod.ships.values()) {
            if (s.state == Ship.State.MODEL || (s.flight != null && s.yard != null)) {   // из верфи — и в полёте те же клетки плота
                Entity a = mod.parked.get(s.owner);
                if (a != null && a.getEntityId() == anchor) return yard(model(s, a));
            }
            Flight f = s.flight;
            if (f != null && f.anchorAll() != null && f.anchorAll().getEntityId() == anchor) return yard(flight(s, f));
        }
        return null;
    }

    /** Корабль из верфи: клетка = настоящий блок плота (читаем и пишем там), копия для мода — как раньше. */
    private Cells yard(Cells base) {
        Ship s = base.ship();
        if (s.yard == null) return base;
        Instance inst = base.instance();
        Shipyard.ensureLoaded(mod, inst, s);                                    // плот мог выгрузиться — блоки должны быть
        return new Cells() {
            @Override public Ship ship() { return s; }
            @Override public Block get(int dx, int dy, int dz) {
                Block b = inst.getBlock(Shipyard.cell(s, dx, dy, dz));
                return b.isAir() ? null : b;
            }
            @Override public void set(int dx, int dy, int dz, Block b) {
                inst.setBlock(Shipyard.cell(s, dx, dy, dz), b == null ? Block.AIR : b);   // плот — правда (и в полёте)
                mod.yardStream.changed(s, dx, dy, dz);
                base.set(dx, dy, dz, b);
                if (s.flight != null) s.flight.cellsChanged();
            }
            @Override public int count() { return base.count(); }
            @Override public float[] rot() { return base.rot(); }
            @Override public Vec pivot() { return base.pivot(); }
            @Override public double seatH() { return base.seatH(); }
            @Override public Instance instance() { return inst; }
            @Override public int anchor() { return base.anchor(); }
            @Override public boolean flying() { return base.flying(); }
        };
    }

    private Cells model(Ship s, Entity a) {
        return new Cells() {
            @Override public Ship ship() { return s; }

            @Override public Block get(int dx, int dy, int dz) {
                int i = find(s, dx, dy, dz);
                return i < 0 ? null : Block.fromState(s.mblocks.get(i));
            }

            @Override public void set(int dx, int dy, int dz, Block b) {
                int i = find(s, dx, dy, dz);
                if (b == null) {
                    if (i >= 0) { s.mparts.remove(i); s.mblocks.remove(i); }
                } else if (i >= 0) s.mblocks.set(i, b.state());
                else { s.mparts.add(new int[]{dx, dy, dz}); s.mblocks.add(b.state()); }
            }

            @Override public int count() { return s.mparts.size(); }
            @Override public float[] rot() { return Rot.quat(s.myaw, s.mpitch, s.mroll, s.mturn); }
            @Override public Vec pivot() { return new Vec(s.mx, s.my, s.mz); }
            @Override public double seatH() { return s.mseatH; }
            @Override public Instance instance() { return mod.instanceOf(s); }
            @Override public int anchor() { return a.getEntityId(); }
            @Override public boolean flying() { return s.state == Ship.State.FLYING; }
        };
    }

    private static Cells flight(Ship s, Flight f) {
        return new Cells() {
            @Override public Ship ship() { return s; }
            @Override public Block get(int dx, int dy, int dz) { return f.cell(dx, dy, dz); }
            @Override public void set(int dx, int dy, int dz, Block b) { f.setCell(dx, dy, dz, b); }
            @Override public int count() { return f.cellCount(); }

            @Override public float[] rot() {
                double[] q = f.pose();
                return Rot.quat(q[3], q[4], q[5], f.blockTurn());
            }

            @Override public Vec pivot() {
                double[] q = f.pose();
                return new Vec(q[0], q[1], q[2]);
            }

            @Override public double seatH() { return f.seatHeight(); }
            @Override public Instance instance() { return f.instance(); }
            @Override public int anchor() { return f.anchorAll().getEntityId(); }
            @Override public boolean flying() { return true; }
        };
    }

    private static int find(Ship s, int dx, int dy, int dz) {
        for (int i = 0; i < s.mparts.size(); i++) {
            int[] q = s.mparts.get(i);
            if (q[0] == dx && q[1] == dy && q[2] == dz) return i;
        }
        return -1;
    }

    /** Центр клетки корабля в мире. */
    private static Vec cellWorld(Cells c, int dx, int dy, int dz) {
        double[] r = Rot.rotate(c.rot(), dx, dy - c.seatH() + 0.5, dz);
        Vec p = c.pivot();
        return new Vec(p.x() + r[0], p.y() + r[1], p.z() + r[2]);
    }

    private boolean reach(Player p, Cells c, int dx, int dy, int dz) {
        if (c.instance() != p.getInstance()) return false;
        double max = mod.config().getDouble("interact.reach", 7) + (c.flying() ? 2 : 0);   // в полёте — запас на задержку
        return p.getPosition().add(0, p.getEyeHeight(), 0).distanceSquared(cellWorld(c, dx, dy, dz)) <= max * max;
    }

    private boolean canBuild(Player p, Ship s) {
        return mod.canControl(p, s) || mod.config().getBoolean("build.others", false);
    }

    private boolean canUse(Player p, Ship s) {
        return mod.canControl(p, s) || mod.config().getBoolean("interact.others", true);
    }

    // ───────────────────────── ПКМ ─────────────────────────

    /** Колёсико по клетке корабля: как у игры «выбрать блок» (ядро сервера кладёт предмет; ctrl — с содержимым). */
    void pick(Player p, int anchor, int dx, int dy, int dz, boolean ctrl) {
        Cells c = byAnchor(anchor);
        if (c == null || c.instance() != p.getInstance()) return;
        Block b = c.get(dx, dy, dz);
        if (b == null || b.isAir()) return;
        var pos = c.ship().yard != null ? Shipyard.cell(c.ship(), dx, dy, dz) : new net.minestom.server.coordinate.BlockVec(0, 0, 0);
        net.minestom.server.event.EventDispatcher.call(new net.minestom.server.event.player.PlayerPickBlockEvent(p, c.instance(), b, pos, ctrl));
    }

    /** ПКМ по клетке (dx,dy,dz), грань face (как Direction), точка попадания в клетке (0..1, система корабля). */
    void use(Player p, int anchor, int dx, int dy, int dz, int face, float hx, float hy, float hz) {
        Cells c = byAnchor(anchor);
        if (c == null || !reach(p, c, dx, dy, dz)) return;
        Ship s = c.ship();
        Block b = c.get(dx, dy, dz);
        if (b == null) return;
        String id = id(b);
        if (toggles(id)) {
            if (!canUse(p, s)) { deny(p, s); return; }
            toggle(c, dx, dy, dz, b, id);
            return;
        }
        if (id.equals("chest") || id.equals("trapped_chest") || id.equals("barrel")) {
            if (!canUse(p, s)) { deny(p, s); return; }
            if (s.yard != null) {                                               // настоящий сундук в плоте — модуль контейнеров
                var pos = Shipyard.cell(s, dx, dy, dz);
                net.minestom.server.event.EventDispatcher.call(new net.minestom.server.event.player.PlayerBlockInteractEvent(p,
                        net.minestom.server.entity.PlayerHand.MAIN, c.instance(), b, pos, pos, net.minestom.server.instance.block.BlockFace.TOP));
                if (p.getOpenInventory() instanceof Inventory inv && !id.equals("barrel")) {   // крышка — моду (копия сундука корабля)
                    lids.put(inv, new int[]{c.anchor(), dx, dy, dz});
                    mod.client.lid(c.anchor(), dx, dy, dz, inv.getViewers().size());
                }
                return;
            }
            openChest(p, c, dx, dy, dz, b, id);
            return;
        }
        // место пилота стоящего корабля — взлёт
        if (!c.flying() && dx == 0 && dz == 0 && (dy == 0 || dy == -1)) {
            if (!mod.canControl(p, s)) { deny(p, s); return; }
            mod.takeOff(p, s);
            return;
        }
        // блок в руке — поставить рядом
        ItemStack hand = p.getItemInMainHand();
        Block place = hand.material().isBlock() ? hand.material().block() : null;
        if (place == null || place.isAir()) return;
        if (!canBuild(p, s)) { p.sendActionBar(Component.text("Строить на корабле может только его владелец", NamedTextColor.RED)); return; }
        if (face < 0 || face >= 6) return;
        int nx = dx + DIR[face][0], ny = dy + DIR[face][1], nz = dz + DIR[face][2];
        if (c.get(nx, ny, nz) != null) return;
        int max = mod.config().getInt("templates.max-blocks", 3000);
        if (c.count() >= max) { p.sendActionBar(Component.text("Корабль больше не вырастет (не больше " + max + " блоков)", NamedTextColor.RED)); return; }
        Block st = orient(place, p, c, face, hy);
        List<int[]> changed = new ArrayList<>();
        if (id(place).endsWith("_door")) {
            if (c.get(nx, ny + 1, nz) != null) return;
            c.set(nx, ny + 1, nz, with(st, "half", "upper"));
            changed.add(new int[]{nx, ny + 1, nz});
            st = with(st, "half", "lower");
        }
        c.set(nx, ny, nz, st);
        changed.add(new int[]{nx, ny, nz});
        if (p.getGameMode() != GameMode.CREATIVE) p.setItemInMainHand(hand.consume(1));
        sound(c, nx, ny, nz, "block.stone.place");
        commit(c, changed);
    }

    // ───────────────────────── ЛКМ ─────────────────────────

    void breakBlock(Player p, int anchor, int dx, int dy, int dz) {
        Cells c = byAnchor(anchor);
        if (c == null || !reach(p, c, dx, dy, dz)) return;
        Ship s = c.ship();
        Block b = c.get(dx, dy, dz);
        if (b == null) return;
        if (!canBuild(p, s)) { p.sendActionBar(Component.text("Ломать корабль может только его владелец", NamedTextColor.RED)); return; }
        if (dx == 0 && dz == 0 && (dy == 0 || dy == -1)) { p.sendActionBar(Component.text("Место пилота не сломать", NamedTextColor.RED)); return; }
        if (c.count() <= 2) return;
        List<int[]> changed = new ArrayList<>();
        changed.add(new int[]{dx, dy, dz});
        String id = id(b);
        if (id.endsWith("_door")) {                                             // вторая половина двери
            int oy = "upper".equals(b.getProperty("half")) ? dy - 1 : dy + 1;
            Block o = c.get(dx, oy, dz);
            if (o != null && id(o).equals(id)) { c.set(dx, oy, dz, null); changed.add(new int[]{dx, oy, dz}); }
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
        c.set(dx, dy, dz, null);
        if (p.getGameMode() != GameMode.CREATIVE) {
            Material m = b.registry().material();
            if (m != null && m != Material.AIR) {
                ItemStack it = ItemStack.of(m);
                if (!p.getInventory().addItemStack(it)) drop(p, it);
            }
        }
        sound(c, dx, dy, dz, "block.stone.break");
        commit(c, changed);
    }

    // ───────────────────────── двери, люки, калитки ─────────────────────────

    private static boolean toggles(String id) {
        return (id.endsWith("_door") || id.endsWith("_trapdoor") || id.endsWith("fence_gate")) && !id.startsWith("iron_");
    }

    private void toggle(Cells c, int dx, int dy, int dz, Block b, String id) {
        boolean opened = !"true".equals(b.getProperty("open"));
        String v = String.valueOf(opened);
        List<int[]> changed = new ArrayList<>();
        c.set(dx, dy, dz, with(b, "open", v));
        changed.add(new int[]{dx, dy, dz});
        if (id.endsWith("_door")) {
            int oy = "upper".equals(b.getProperty("half")) ? dy - 1 : dy + 1;
            Block o = c.get(dx, oy, dz);
            if (o != null && id(o).equals(id)) { c.set(dx, oy, dz, with(o, "open", v)); changed.add(new int[]{dx, oy, dz}); }
        }
        String snd = id.endsWith("_trapdoor") ? "block.wooden_trapdoor." : id.endsWith("fence_gate") ? "block.fence_gate." : "block.wooden_door.";
        sound(c, dx, dy, dz, snd + (opened ? "open" : "close"));
        commit(c, changed);
    }

    // ───────────────────────── сундуки ─────────────────────────

    private static String cellKey(int dx, int dy, int dz) { return dx + "," + dy + "," + dz; }

    private void openChest(Player p, Cells c, int dx, int dy, int dz, Block b, String id) {
        Ship s = c.ship();
        String key = cellKey(dx, dy, dz);
        int size = 27;
        // двойной сундук: соседняя половина (как у игры: left — по часовой от facing, right — против)
        String type = b.getProperty("type"), facing = b.getProperty("facing");
        if (!id.equals("barrel") && type != null && !type.equals("single") && facing != null) {
            int f = java.util.Arrays.asList(H).indexOf(facing);
            if (f >= 0) {
                int d = type.equals("left") ? (f + 1) & 3 : (f + 3) & 3;
                int ox = dx + HV[d][0], oz = dz + HV[d][2];
                Block o = c.get(ox, dy, oz);
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
        sound(c, dx, dy, dz, id.equals("barrel") ? "block.barrel.open" : "block.chest.open");
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
        var at = p.getPosition().add(0, 1, 0);
        if (!mod.deck.place(e, p.getInstance(), at, net.minestom.server.coordinate.Vec.ZERO))   // на палубе — в верфь
            { e.scheduleRemove(java.time.Duration.ofMinutes(5)); e.setInstance(p.getInstance(), at); }
    }

    // ───────────────────────── постановка блока ─────────────────────────

    /** Состояние ставимого блока: поворот по взгляду игрока (в системе корабля), ось по грани, верх/низ по точке. */
    private static Block orient(Block b, Player p, Cells c, int face, float hy) {
        String id = id(b);
        float[] q = c.rot();
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

    /** Сохранить (стоящий — сразу; летящий — при стоянке) и разослать изменённые клетки всем. */
    private void commit(Cells c, List<int[]> cells) {
        if (!c.flying()) mod.saveShips();
        int a = c.anchor();
        for (int[] q : cells) {
            Block b = c.get(q[0], q[1], q[2]);
            mod.client.cell(a, q[0], q[1], q[2], b == null ? 0 : b.stateId());
        }
    }

    private static void sound(Cells c, int dx, int dy, int dz, String key) {
        Instance inst = c.instance();
        if (inst == null) return;
        Vec w = cellWorld(c, dx, dy, dz);
        inst.playSound(Sound.sound(Key.key(key), Sound.Source.BLOCK, 1f, 1f), w.x(), w.y(), w.z());
    }

    private static void deny(Player p, Ship s) {
        p.sendActionBar(Component.text("Это корабль игрока " + s.ownerName, NamedTextColor.RED));
    }
}
