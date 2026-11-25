package ink.hoka.odyssey.containers;

import ink.hoka.odyssey.api.OdysseyModule;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.server.play.BlockActionPacket;
import net.minestom.server.sound.SoundEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Контейнеры в мире: сундук, сундук-ловушка, бочка (двойной сундук — одно окно на 54). Вещи хранятся в NBT самого
 * блока («Items», как у игры), поэтому живут везде, где хранится мир: обычные карты, высокий мир, верфь кораблей.
 * Открыть — ПКМ (с Shift — нет, как у игры: Shift+ПКМ ставит блок). Крышка открывается для всех (BlockAction).
 * Сломали — содержимое выпадает.
 */
public final class ContainersModule extends OdysseyModule {

    /** Открытое окно контейнера: где он (обе половины двойного), что за окно. */
    private record Open(Instance instance, BlockVec main, BlockVec other, Inventory inv) {}

    private final Map<String, Open> open = new ConcurrentHashMap<>();
    private final Map<Inventory, Open> byInv = new ConcurrentHashMap<>();

    private static final String[] H = {"north", "east", "south", "west"};
    private static final int[][] HV = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}};

    @Override
    protected void onEnable() {
        events().addListener(PlayerBlockInteractEvent.class, this::onInteract);
        events().addListener(InventoryCloseEvent.class, e -> {
            if (!(e.getInventory() instanceof Inventory inv)) return;
            Open o = byInv.get(inv);
            if (o == null) return;
            scheduler().run(() -> { if (inv.getViewers().isEmpty()) close(o); else lid(o, inv.getViewers().size()); });
        });
        events().addListener(PlayerDisconnectEvent.class, e -> {
            for (Open o : List.copyOf(open.values())) if (o.inv.getViewers().contains(e.getPlayer())) scheduler().run(() -> { if (o.inv.getViewers().isEmpty()) close(o); });
        });
        events().addListener(PlayerBlockBreakEvent.class, this::onBreak);
        scheduler().repeat(() -> { for (Open o : List.copyOf(open.values())) save(o); }, 20 * 30, 20 * 30);   // на всякий случай
        logger().info("контейнеры: сундук, сундук-ловушка, бочка");
    }

    @Override
    protected void onDisable() {
        for (Open o : List.copyOf(open.values())) {
            for (Player v : new ArrayList<>(o.inv.getViewers())) v.closeInventory();
            close(o);
        }
    }

    private static String id(Block b) { return b.key().value(); }

    private static boolean container(Block b) {
        String id = id(b);
        return id.equals("chest") || id.equals("trapped_chest") || id.equals("barrel");
    }

    private static String key(Instance i, Point p) { return System.identityHashCode(i) + ":" + p.blockX() + "," + p.blockY() + "," + p.blockZ(); }

    // ───────────────────────── открыть ─────────────────────────

    private void onInteract(PlayerBlockInteractEvent e) {
        Block b = e.getBlock();
        if (!container(b)) return;
        Player p = e.getPlayer();
        if (p.isSneaking() && !p.getItemInHand(e.getHand()).isAir()) return;   // Shift+ПКМ с блоком — ставить
        e.setBlockingItemUse(true);
        Instance inst = e.getInstance();
        BlockVec pos = e.getBlockPosition();
        BlockVec other = id(b).equals("barrel") ? null : otherHalf(inst, pos, b);
        BlockVec main = pos;
        if (other != null && compare(other, pos) < 0) { main = other; other = pos; }   // одна пара — одно окно
        String k = key(inst, main);
        Open o = open.get(k);
        if (o == null) {
            int size = other != null ? 54 : 27;
            String title = id(b).equals("barrel") ? "Бочка" : size == 54 ? "Большой сундук" : "Сундук";
            Inventory inv = new Inventory(size == 54 ? InventoryType.CHEST_6_ROW : InventoryType.CHEST_3_ROW, Component.text(title));
            load(inv, inst.getBlock(main), 0);
            if (other != null) load(inv, inst.getBlock(other), 27);
            o = new Open(inst, main, other, inv);
            open.put(k, o);
            byInv.put(inv, o);
            sound(inst, pos, id(b).equals("barrel") ? SoundEvent.BLOCK_BARREL_OPEN : SoundEvent.BLOCK_CHEST_OPEN);
        }
        p.openInventory(o.inv);
        lid(o, o.inv.getViewers().size());
    }

    /** Вторая половина двойного сундука (как у игры: left — по часовой от facing, right — против). */
    private static BlockVec otherHalf(Instance inst, BlockVec pos, Block b) {
        String type = b.getProperty("type"), facing = b.getProperty("facing");
        if (type == null || type.equals("single") || facing == null) return null;
        int f = java.util.Arrays.asList(H).indexOf(facing);
        if (f < 0) return null;
        int d = type.equals("left") ? (f + 1) & 3 : (f + 3) & 3;
        BlockVec o = new BlockVec(pos.blockX() + HV[d][0], pos.blockY(), pos.blockZ() + HV[d][2]);
        Block ob = inst.getBlock(o);
        return id(ob).equals(id(b)) ? o : null;
    }

    private static int compare(Point a, Point b) {
        int c = Integer.compare(a.blockX(), b.blockX());
        return c != 0 ? c : Integer.compare(a.blockZ(), b.blockZ());
    }

    // ───────────────────────── хранение (NBT блока, как у игры) ─────────────────────────

    private static void load(Inventory inv, Block b, int offset) {
        CompoundBinaryTag nbt = b.nbt();
        if (nbt == null) return;
        ListBinaryTag items = nbt.getList("Items", BinaryTagTypes.COMPOUND);
        for (BinaryTag t : items) {
            if (!(t instanceof CompoundBinaryTag c)) continue;
            int slot = c.getByte("Slot") & 0xFF;
            if (slot >= 27 || offset + slot >= inv.getSize()) continue;
            ItemStack it = ItemStack.CODEC.decode(Transcoder.NBT, c.remove("Slot")).orElse(ItemStack.AIR);
            inv.setItemStack(offset + slot, it);
        }
    }

    private static Block withItems(Block b, Inventory inv, int offset) {
        ListBinaryTag.Builder<CompoundBinaryTag> list = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (int s = 0; s < 27 && offset + s < inv.getSize(); s++) {
            ItemStack it = inv.getItemStack(offset + s);
            if (it == null || it.isAir()) continue;
            BinaryTag t = ItemStack.CODEC.encode(Transcoder.NBT, it).orElse(null);
            if (t instanceof CompoundBinaryTag c) list.add(c.putByte("Slot", (byte) s));
        }
        CompoundBinaryTag base = b.nbt() == null ? CompoundBinaryTag.empty() : b.nbt();
        return b.withNbt(base.put("Items", list.build()));
    }

    private void save(Open o) {
        Point to = o.other != null ? o.other : o.main;
        if (!server().areCubesLoaded(o.instance, o.main, to)) {                // куб сундука выгружен — загрузить и сохранить потом (тик не ждёт)
            server().loadCubes(o.instance, o.main, to).thenRun(() -> scheduler().run(() -> save(o)));
            return;
        }
        Block m = o.instance.getBlock(o.main);
        if (container(m)) o.instance.setBlock(o.main, withItems(m, o.inv, 0));
        if (o.other != null) {
            Block x = o.instance.getBlock(o.other);
            if (container(x)) o.instance.setBlock(o.other, withItems(x, o.inv, 27));
        }
    }

    private void close(Open o) {
        if (open.remove(key(o.instance, o.main)) == null) return;
        byInv.remove(o.inv);
        save(o);
        lid(o, 0);
        sound(o.instance, o.main, id(o.instance.getBlock(o.main)).equals("barrel") ? SoundEvent.BLOCK_BARREL_CLOSE : SoundEvent.BLOCK_CHEST_CLOSE);
    }

    // ───────────────────────── сломали — вещи выпадают ─────────────────────────

    private void onBreak(PlayerBlockBreakEvent e) {
        Block b = e.getBlock();
        if (!container(b)) return;
        Instance inst = e.getInstance();
        BlockVec pos = e.getBlockPosition();
        List<ItemStack> drop = new ArrayList<>();
        Open o = null;
        for (Open x : open.values()) if (x.instance == inst && (x.main.samePoint(pos) || (x.other != null && x.other.samePoint(pos)))) o = x;
        if (o != null) {                                                    // открыт — забрать из окна и закрыть
            int from = o.main.samePoint(pos) ? 0 : 27;
            for (int s = 0; s < 27 && from + s < o.inv.getSize(); s++) { drop.add(o.inv.getItemStack(from + s)); o.inv.setItemStack(from + s, ItemStack.AIR); }
            for (Player v : new ArrayList<>(o.inv.getViewers())) v.closeInventory();
            open.remove(key(o.instance, o.main));
            byInv.remove(o.inv);
            Open rest = o;
            scheduler().run(() -> save(rest));                              // вторая половина сохранит своё
        } else {
            Inventory tmp = new Inventory(InventoryType.CHEST_3_ROW, Component.empty());
            load(tmp, b, 0);
            for (int s = 0; s < 27; s++) drop.add(tmp.getItemStack(s));
        }
        for (ItemStack it : drop) {
            if (it == null || it.isAir()) continue;
            ItemEntity ie = new ItemEntity(it);
            ie.setPickupDelay(Duration.ofMillis(500));
            ie.setInstance(inst, new Vec(pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5));
            ie.setVelocity(new Vec(Math.random() * 2 - 1, 2, Math.random() * 2 - 1));
        }
    }

    // ───────────────────────── крышка и звук ─────────────────────────

    /** Крышка сундука (BlockAction 1 = число зрителей): всем, кто видит чанк. */
    private void lid(Open o, int viewers) {
        if (!server().isCubeLoaded(o.instance, o.main)) return;                 // куб выгружен — крышку не трогаем
        Block b = o.instance.getBlock(o.main);
        if (id(b).equals("barrel")) {
            if (b.getProperty("open") != null) {
                boolean want = viewers > 0;
                if (!String.valueOf(want).equals(b.getProperty("open"))) o.instance.setBlock(o.main, b.withProperty("open", String.valueOf(want)));
            }
            return;
        }
        send(o.instance, o.main, b, viewers);
        if (o.other != null && server().isCubeLoaded(o.instance, o.other)) send(o.instance, o.other, o.instance.getBlock(o.other), viewers);
    }

    private static void send(Instance inst, BlockVec pos, Block b, int viewers) {
        var ch = inst.getChunkAt(pos);
        if (ch != null) ch.sendPacketToViewers(new BlockActionPacket(pos, (byte) 1, (byte) Math.min(127, viewers), b));
    }

    private static void sound(Instance inst, Point pos, SoundEvent s) {
        inst.playSound(net.kyori.adventure.sound.Sound.sound(s, net.kyori.adventure.sound.Sound.Source.BLOCK, 0.5f, 0.95f + (float) Math.random() * 0.1f),
                pos.blockX() + 0.5, pos.blockY() + 0.5, pos.blockZ() + 0.5);
    }
}
