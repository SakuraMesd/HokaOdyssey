package ink.hoka.odyssey.server.blocks;

import net.minestom.server.coordinate.Point;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.trait.InstanceEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

/**
 * Блоки из двух частей и открывание:
 * <ul>
 *   <li>дверь — верхняя половина над нижней; кровать — изголовье в сторону взгляда. Нет места — не ставится;</li>
 *   <li>сломали одну часть — убирается и вторая;</li>
 *   <li>двери, люки, калитки открываются по клику (железные — только редстоуном, как в игре).</li>
 * </ul>
 */
public final class MultiBlocks {

    private MultiBlocks() {}

    public static EventNode<InstanceEvent> node() {
        EventNode<InstanceEvent> n = EventNode.type("odyssey:multiblocks", net.minestom.server.event.EventFilter.INSTANCE);
        n.addListener(PlayerBlockPlaceEvent.class, MultiBlocks::onPlace);
        n.addListener(PlayerBlockBreakEvent.class, MultiBlocks::onBreak);
        n.addListener(PlayerBlockInteractEvent.class, MultiBlocks::onInteract);
        return n;
    }

    private static void onPlace(PlayerBlockPlaceEvent e) {
        Block b = e.getBlock();
        String name = b.key().value();
        Instance inst = e.getInstance();
        Point pos = e.getBlockPosition();
        if (name.endsWith("_door") && "lower".equals(b.getProperty("half"))) {
            Point up = pos.add(0, 1, 0);
            if (!replaceable(inst.getBlock(up))) { e.setCancelled(true); return; }
            inst.scheduleNextTick(i -> { if (BlockRules.same(i.getBlock(pos), b)) i.setBlock(up, i.getBlock(pos).withProperty("half", "upper")); });
        } else if (name.endsWith("_bed") && "foot".equals(b.getProperty("part"))) {
            Dir f = BlockRules.facing(b);
            Point head = f.rel(pos);
            if (!replaceable(inst.getBlock(head))) { e.setCancelled(true); return; }
            inst.scheduleNextTick(i -> { if (BlockRules.same(i.getBlock(pos), b)) i.setBlock(head, b.withProperty("part", "head")); });
        }
    }

    private static void onBreak(PlayerBlockBreakEvent e) {
        Block b = e.getBlock();
        String name = b.key().value();
        Instance inst = e.getInstance();
        Point pos = e.getBlockPosition();
        Point other = null;
        if (name.endsWith("_door")) other = pos.add(0, "lower".equals(b.getProperty("half")) ? 1 : -1, 0);
        else if (name.endsWith("_bed")) {
            Dir f = BlockRules.facing(b);
            other = ("foot".equals(b.getProperty("part")) ? f : f.opposite()).rel(pos);
        }
        if (other == null) return;
        Point o = other;
        inst.scheduleNextTick(i -> { if (BlockRules.same(i.getBlock(o), b)) i.setBlock(o, Block.AIR); });
    }

    private static void onInteract(PlayerBlockInteractEvent e) {
        Block b = e.getBlock();
        String name = b.key().value();
        if (name.startsWith("iron_") || !b.properties().containsKey("open")) return;
        if (!(name.endsWith("_door") || name.endsWith("_trapdoor") || name.endsWith("_fence_gate"))) return;
        if (e.getPlayer().isSneaking() && !e.getPlayer().getItemInMainHand().isAir()) return;   // Shift с блоком — ставим, а не открываем
        Instance inst = e.getInstance();
        Point pos = e.getBlockPosition();
        String open = "true".equals(b.getProperty("open")) ? "false" : "true";
        Block nb = b.withProperty("open", open);
        if (name.endsWith("_fence_gate") && "true".equals(open)) {             // калитка открывается от игрока
            Dir f = BlockRules.facing(b), look = Dir.look(e.getPlayer().getPosition());
            if (f == look.opposite()) nb = nb.withProperty("facing", look.key());
        }
        inst.setBlock(pos, nb);
        if (name.endsWith("_door")) {
            Point o = pos.add(0, "lower".equals(b.getProperty("half")) ? 1 : -1, 0);
            Block ob = inst.getBlock(o);
            if (BlockRules.same(ob, b)) inst.setBlock(o, ob.withProperty("open", open));
        }
        e.setBlockingItemUse(true);
    }

    private static boolean replaceable(Block b) {
        return b.isAir() || b.registry().isReplaceable();
    }
}
