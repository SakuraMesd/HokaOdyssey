package ink.hoka.odyssey.server.blocks;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockManager;
import net.minestom.server.instance.block.rule.BlockPlacementRule;

import java.util.Map;

/**
 * Ванильные правила установки блоков (Minestom сам их не знает и ставит блок в состоянии по умолчанию).
 * Ориентируемся на getStateForPlacement / updateShape из Minecraft 1.21.11: ступеньки с углами, сундуки с
 * соединением, бочки и раздатчики по взгляду, печи и прочие «лицом к игроку», брёвна по оси, плиты, факелы и
 * таблички на стену, кнопки и рычаги, заборы/панели/стены с соединениями, двери/кровати (вторую половину ставит
 * {@link MultiBlocks}), люки и калитки.
 */
public final class BlockRules {

    private BlockRules() {}

    /** Зарегистрировать правила для всех блоков игры. Возвращает число блоков с правилом. */
    public static int registerAll() {
        BlockManager bm = MinecraftServer.getBlockManager();
        int n = 0;
        for (Block b : Block.values()) {
            BlockPlacementRule r = ruleFor(b);
            if (r != null) { bm.registerBlockPlacementRule(r); n++; }
        }
        return n;
    }

    static BlockPlacementRule ruleFor(Block b) {
        String name = b.key().value();
        Map<String, String> p = b.properties();
        if (name.endsWith("_stairs")) return new Stairs(b);
        if (name.equals("chest") || name.equals("trapped_chest")) return new Chest(b);
        if (name.endsWith("_slab")) return new Slab(b);
        if (name.endsWith("_door")) return new Door(b);
        if (name.endsWith("_bed")) return new Bed(b);
        if (name.endsWith("_trapdoor")) return new Trapdoor(b);
        if (name.endsWith("_fence_gate")) return new FenceGate(b);
        if (name.endsWith("_fence") || name.endsWith("_pane") || name.endsWith("_bars") || name.equals("glass_pane")) return new Connect(b, false);
        if (name.endsWith("_wall") && p.containsKey("up")) return new Connect(b, true);
        Block wall = wallVariant(name);
        if (wall != null) return new StandingOrWall(b, wall);
        if (p.containsKey("face") && p.containsKey("facing")) return new FaceAttached(b);
        if (name.equals("hopper")) return new Hopper(b);
        if (p.containsKey("facing")) {
            if (CLICKED_FACE.contains(name) || name.endsWith("shulker_box") || name.endsWith("amethyst_bud")) return new ClickedFace(b);
            if (supports(b, "facing", "up")) return new LookFacing(b, !name.equals("observer"));
            if (p.keySet().stream().allMatch(HORIZONTAL_OK::contains)) return new HorizontalFacing(b, name.endsWith("anvil"));
        }
        if (p.containsKey("axis") && supports(b, "axis", "y")) return new Axis(b);
        return null;
    }

    private static final java.util.Set<String> CLICKED_FACE = java.util.Set.of(
            "end_rod", "lightning_rod", "amethyst_cluster", "ladder", "tripwire_hook");
    /** Свойства, с которыми «просто лицом к игроку» безопасно (остальные блоки с facing оставляем как есть). */
    private static final java.util.Set<String> HORIZONTAL_OK = java.util.Set.of(
            "facing", "lit", "waterlogged", "open", "powered", "has_book", "honey_level", "eye", "triggered",
            "signal_fire", "delay", "locked", "mode", "slot_0_occupied", "slot_1_occupied", "slot_2_occupied",
            "slot_3_occupied", "slot_4_occupied", "slot_5_occupied", "cracked", "enabled");

    /** Стенной вариант блока (табличка, факел, флаг, голова, коралловый веер) или null. */
    static Block wallVariant(String name) {
        String w = null;
        if (name.equals("torch")) w = "wall_torch";
        else if (name.endsWith("_torch") && !name.contains("wall")) w = name.substring(0, name.length() - "torch".length()) + "wall_torch";
        else if (name.endsWith("_hanging_sign") && !name.contains("wall")) w = name.replace("_hanging_sign", "_wall_hanging_sign");
        else if (name.endsWith("_sign") && !name.contains("wall")) w = name.replace("_sign", "_wall_sign");
        else if (name.endsWith("_banner") && !name.contains("wall")) w = name.replace("_banner", "_wall_banner");
        else if (name.endsWith("_head") && !name.contains("wall") && !name.equals("piston_head")) w = name.replace("_head", "_wall_head");
        else if (name.endsWith("_skull") && !name.contains("wall")) w = name.replace("_skull", "_wall_skull");
        else if (name.endsWith("_coral_fan") && !name.contains("wall")) w = name.replace("_coral_fan", "_coral_wall_fan");
        return w == null ? null : Block.fromKey("minecraft:" + w);
    }

    static boolean supports(Block b, String prop, String value) {
        for (Block s : b.possibleStates()) if (value.equals(s.getProperty(prop))) return true;
        return false;
    }

    static boolean same(Block a, Block b) { return a != null && b != null && a.id() == b.id(); }

    static Dir facing(Block b) { return Dir.of(b.getProperty("facing")); }

    /** Курсор по Y внутри блока (0..1) — верхняя половина? */
    static boolean upper(Point cursor) { return cursor != null && cursor.y() > 0.5; }

    /** Угол поворота стоячих табличек/флагов/голов (16 положений). */
    static int rotation(Pos p, float add) {
        return (int) Math.floor((p.yaw() + add) * 16.0 / 360.0 + 0.5) & 15;
    }

    // ═══════════════════════════════ ступеньки ═══════════════════════════════

    static final class Stairs extends BlockPlacementRule {
        Stairs(Block b) { super(b); }

        @Override
        public Block blockPlace(PlacementState s) {
            Dir face = Dir.of(s.blockFace());
            boolean top = face == Dir.DOWN || (face != Dir.UP && upper(s.cursorPosition()));
            Block b = block.withProperty("facing", Dir.look(s.playerPosition()).key()).withProperty("half", top ? "top" : "bottom");
            return b.withProperty("shape", shape(b, s.instance(), s.placePosition()));
        }

        @Override
        public Block blockUpdate(UpdateState u) {
            return u.currentBlock().withProperty("shape", shape(u.currentBlock(), u.instance(), u.blockPosition()));
        }

        /** StairBlock.getStairsShape. */
        static String shape(Block me, Block.Getter g, Point pos) {
            Dir dir = facing(me);
            String half = me.getProperty("half");
            Block front = g.getBlock(dir.rel(pos));
            if (isStairs(front) && half.equals(front.getProperty("half"))) {
                Dir d2 = facing(front);
                if (d2.axis() != dir.axis() && canTake(me, g, pos, d2.opposite()))
                    return d2 == dir.ccw() ? "outer_left" : "outer_right";
            }
            Block back = g.getBlock(dir.opposite().rel(pos));
            if (isStairs(back) && half.equals(back.getProperty("half"))) {
                Dir d3 = facing(back);
                if (d3.axis() != dir.axis() && canTake(me, g, pos, d3))
                    return d3 == dir.ccw() ? "inner_left" : "inner_right";
            }
            return "straight";
        }

        private static boolean canTake(Block me, Block.Getter g, Point pos, Dir side) {
            Block o = g.getBlock(side.rel(pos));
            return !isStairs(o) || facing(o) != facing(me) || !me.getProperty("half").equals(o.getProperty("half"));
        }

        static boolean isStairs(Block b) { return b != null && b.key().value().endsWith("_stairs"); }
    }

    // ═══════════════════════════════ сундуки ═══════════════════════════════

    static final class Chest extends BlockPlacementRule {
        Chest(Block b) { super(b); }

        @Override
        public Block blockPlace(PlacementState s) {
            Block.Getter g = s.instance();
            Point pos = s.placePosition();
            Dir facing = Dir.look(s.playerPosition()).opposite();
            String type = "single";
            Dir clicked = Dir.of(s.blockFace());
            boolean sneak = s.isPlayerShifting();
            if (clicked.horizontal() && sneak) {                           // Shift + клик по боку сундука — пристроиться к нему
                Dir d = partnerFacing(g, pos, clicked.opposite());
                if (d != null && d.axis() != clicked.axis()) {
                    facing = d;
                    type = d.ccw() == clicked.opposite() ? "right" : "left";
                }
            }
            if (type.equals("single") && !sneak) {
                if (facing == partnerFacing(g, pos, facing.cw())) type = "left";
                else if (facing == partnerFacing(g, pos, facing.ccw())) type = "right";
            }
            return block.withProperty("facing", facing.key()).withProperty("type", type);
        }

        /** Одиночный сундук того же вида в стороне dir → его facing, иначе null. */
        private Dir partnerFacing(Block.Getter g, Point pos, Dir dir) {
            Block n = g.getBlock(dir.rel(pos));
            return same(n, block) && "single".equals(n.getProperty("type")) ? facing(n) : null;
        }

        /** Куда смотрит вторая половина: left → по часовой от facing, right → против. */
        static Dir connected(Block b) {
            Dir f = facing(b);
            return "left".equals(b.getProperty("type")) ? f.cw() : f.ccw();
        }

        @Override
        public Block blockUpdate(UpdateState u) {
            Block me = u.currentBlock();
            Block.Getter g = u.instance();
            Point pos = u.blockPosition();
            Dir facing = facing(me);
            if (!"single".equals(me.getProperty("type"))) {
                Block p = g.getBlock(connected(me).rel(pos));
                if (same(p, me) && facing(p) == facing && !"single".equals(p.getProperty("type")) && connected(p) == connected(me).opposite())
                    return me;
                me = me.withProperty("type", "single");                       // пару сломали/повернули
            }
            for (Dir d : new Dir[]{facing.cw(), facing.ccw()}) {               // кто-то рядом пристроился к нам
                Block n = g.getBlock(d.rel(pos));
                if (same(n, me) && facing(n) == facing && !"single".equals(n.getProperty("type")) && connected(n) == d.opposite())
                    return me.withProperty("type", "left".equals(n.getProperty("type")) ? "right" : "left");
            }
            return me;
        }
    }

    // ═══════════════════════════════ плиты ═══════════════════════════════

    static final class Slab extends BlockPlacementRule {
        Slab(Block b) { super(b); }

        @Override
        public Block blockPlace(PlacementState s) {
            Block existing = s.instance().getBlock(s.placePosition());
            if (same(existing, block)) return existing.withProperty("type", "double");
            Dir face = Dir.of(s.blockFace());
            boolean top = face == Dir.DOWN || (face != Dir.UP && upper(s.cursorPosition()));
            return block.withProperty("type", top ? "top" : "bottom");
        }

        /** Вторая плита в тот же блок (SlabBlock.canBeReplaced). */
        @Override
        public boolean isSelfReplaceable(Replacement r) {
            if (r.material() == null || !r.material().key().equals(block.key())) return false;
            String type = r.block().getProperty("type");
            if ("double".equals(type)) return false;
            if (r.isOffset()) return true;
            Dir face = Dir.of(r.blockFace());
            boolean up = upper(r.cursorPosition());
            return "bottom".equals(type) ? face == Dir.UP || (up && face.horizontal()) : face == Dir.DOWN || (!up && face.horizontal());
        }
    }

    // ═══════════════════════════════ по оси / по направлению ═══════════════════════════════

    /** Брёвна, столбы, цепи, сено, кость-блоки — по оси грани, по которой кликнули. */
    static final class Axis extends BlockPlacementRule {
        Axis(Block b) { super(b); }

        @Override
        public Block blockPlace(PlacementState s) {
            return block.withProperty("axis", String.valueOf(Dir.of(s.blockFace()).axis()));
        }
    }

    /** Бочка, раздатчик, выбрасыватель, поршни, командные блоки — лицом к игроку (6 направлений); наблюдатель — наоборот. */
    static final class LookFacing extends BlockPlacementRule {
        private final boolean towardPlayer;

        LookFacing(Block b, boolean towardPlayer) { super(b); this.towardPlayer = towardPlayer; }

        @Override
        public Block blockPlace(PlacementState s) {
            Dir d = Dir.look3d(s.playerPosition());
            return block.withProperty("facing", (towardPlayer ? d.opposite() : d).key());
        }
    }

    /** Печи, тыквы, ткацкий станок, улей, кафедра, эндер-сундук, керамика… — лицом к игроку; наковальня — боком. */
    static final class HorizontalFacing extends BlockPlacementRule {
        private final boolean anvil;

        HorizontalFacing(Block b, boolean anvil) { super(b); this.anvil = anvil; }

        @Override
        public Block blockPlace(PlacementState s) {
            Dir look = Dir.look(s.playerPosition());
            return block.withProperty("facing", (anvil ? look.cw() : look.opposite()).key());
        }
    }

    /** Шалкеры, стержни, аметист, лестница, крюк — по грани клика (лестница/крюк только на стену). */
    static final class ClickedFace extends BlockPlacementRule {
        ClickedFace(Block b) { super(b); }

        @Override
        public Block blockPlace(PlacementState s) {
            Dir face = Dir.of(s.blockFace());
            if (!supports(block, "facing", face.key())) face = Dir.look(s.playerPosition()).opposite();   // лестница на пол
            return block.withProperty("facing", face.key());
        }
    }

    /** Воронка: выход в блок, по которому кликнули (сверху — вниз). */
    static final class Hopper extends BlockPlacementRule {
        Hopper(Block b) { super(b); }

        @Override
        public Block blockPlace(PlacementState s) {
            Dir d = Dir.of(s.blockFace()).opposite();
            return block.withProperty("facing", d.horizontal() ? d.key() : "down");
        }
    }

    /** Кнопки, рычаги, точило: пол / стена / потолок. */
    static final class FaceAttached extends BlockPlacementRule {
        FaceAttached(Block b) { super(b); }

        @Override
        public Block blockPlace(PlacementState s) {
            Dir face = Dir.of(s.blockFace());
            Dir look = Dir.look(s.playerPosition());
            return switch (face) {
                case UP -> block.withProperty("face", "floor").withProperty("facing", look.key());
                case DOWN -> block.withProperty("face", "ceiling").withProperty("facing", look.key());
                default -> block.withProperty("face", "wall").withProperty("facing", face.key());
            };
        }
    }

    /** Таблички, факелы, флаги, головы, кораллы: на стену — стенной вариант, на пол — стоячий с поворотом за игроком. */
    static final class StandingOrWall extends BlockPlacementRule {
        private final Block wall;

        StandingOrWall(Block b, Block wall) { super(b); this.wall = wall; }

        @Override
        public Block blockPlace(PlacementState s) {
            Dir face = Dir.of(s.blockFace());
            String name = block.key().value();
            if (face.horizontal() && wall != null) {
                Dir f = face;
                if (name.endsWith("_hanging_sign")) {                     // висячая на стене — поперёк грани
                    Dir look = Dir.look(s.playerPosition()).opposite();
                    f = look.axis() != face.axis() ? look : look.cw();
                }
                return wall.withProperty("facing", f.key());
            }
            Block b = block;
            if (b.properties().containsKey("rotation")) {
                float add = name.endsWith("_head") || name.endsWith("_skull") ? 0f : 180f;
                b = b.withProperty("rotation", String.valueOf(rotation(s.playerPosition(), add)));
            }
            if (name.endsWith("_hanging_sign") && b.properties().containsKey("attached")) b = b.withProperty("attached", "false");
            return b;
        }
    }

    // ═══════════════════════════════ заборы / панели / стены ═══════════════════════════════

    static final class Connect extends BlockPlacementRule {
        private final boolean wall;

        Connect(Block b, boolean wall) { super(b); this.wall = wall; }

        @Override
        public Block blockPlace(PlacementState s) { return connect(block, s.instance(), s.placePosition()); }

        @Override
        public Block blockUpdate(UpdateState u) { return connect(u.currentBlock(), u.instance(), u.blockPosition()); }

        private Block connect(Block me, Block.Getter g, Point pos) {
            boolean[] c = new boolean[4];
            for (int i = 0; i < 4; i++) {
                Dir d = Dir.HORIZONTAL[i];
                c[i] = connectsTo(me, g.getBlock(d.rel(pos)), d);
                me = me.withProperty(d.key(), wall ? (c[i] ? "low" : "none") : String.valueOf(c[i]));
            }
            if (wall) {
                boolean straight = (c[0] && c[2] && !c[1] && !c[3]) || (c[1] && c[3] && !c[0] && !c[2]);
                me = me.withProperty("up", String.valueOf(!straight));
            }
            return me;
        }

        private boolean connectsTo(Block me, Block n, Dir towards) {
            if (n == null || n.isAir()) return false;
            String a = me.key().value(), b = n.key().value();
            if (b.endsWith("_fence_gate")) {                                  // калитка поперёк — да
                Dir gf = facing(n);
                return gf != null && gf.axis() != towards.axis();
            }
            if (a.endsWith("_fence")) {
                if (b.endsWith("_fence")) return a.equals("nether_brick_fence") == b.equals("nether_brick_fence");
                return fullSolid(n);
            }
            boolean paneLike = b.endsWith("_pane") || b.endsWith("_bars") || b.equals("glass_pane") || (b.endsWith("_wall") && n.properties().containsKey("up"));
            return paneLike || fullSolid(n);
        }

        private static boolean fullSolid(Block n) {
            String b = n.key().value();
            return n.isSolid() && !b.endsWith("_stairs") && !b.endsWith("_slab") && !b.endsWith("_fence")
                    && !b.endsWith("_door") && !b.endsWith("_trapdoor") && !b.contains("leaves") && !b.endsWith("_bed");
        }
    }

    // ═══════════════════════════════ двери / кровати / люки / калитки ═══════════════════════════════

    /** Нижняя половина двери. Верхнюю ставит {@link MultiBlocks}. */
    static final class Door extends BlockPlacementRule {
        Door(Block b) { super(b); }

        @Override
        public Block blockPlace(PlacementState s) {
            Dir facing = Dir.look(s.playerPosition());
            return block.withProperty("facing", facing.key()).withProperty("half", "lower")
                    .withProperty("hinge", hinge(s, facing)).withProperty("open", "false");
        }

        /** DoorBlock.getHinge: соседняя дверь — петли с другой стороны (двойная дверь), иначе по точке клика. */
        private String hinge(PlacementState s, Dir facing) {
            Block.Getter g = s.instance();
            Point pos = s.placePosition();
            Block left = g.getBlock(facing.ccw().rel(pos)), right = g.getBlock(facing.cw().rel(pos));
            if (isDoor(left) && "lower".equals(left.getProperty("half")) && facing(left) == facing && "left".equals(left.getProperty("hinge"))) return "right";
            if (isDoor(right) && "lower".equals(right.getProperty("half")) && facing(right) == facing && "right".equals(right.getProperty("hinge"))) return "left";
            Point c = s.cursorPosition();
            if (c == null) return "left";
            double cx = c.x() - Math.floor(c.x()), cz = c.z() - Math.floor(c.z());
            int k = facing.x, l = facing.z;
            boolean left1 = (k >= 0 || !(cz < 0.5)) && (k <= 0 || !(cz > 0.5)) && (l >= 0 || !(cx > 0.5)) && (l <= 0 || !(cx < 0.5));
            return left1 ? "left" : "right";
        }

        static boolean isDoor(Block b) { return b != null && b.key().value().endsWith("_door"); }
    }

    /** Ножная часть кровати. Изголовье ставит {@link MultiBlocks}. */
    static final class Bed extends BlockPlacementRule {
        Bed(Block b) { super(b); }

        @Override
        public Block blockPlace(PlacementState s) {
            return block.withProperty("facing", Dir.look(s.playerPosition()).key()).withProperty("part", "foot");
        }
    }

    static final class Trapdoor extends BlockPlacementRule {
        Trapdoor(Block b) { super(b); }

        @Override
        public Block blockPlace(PlacementState s) {
            Dir face = Dir.of(s.blockFace());
            if (face.horizontal())
                return block.withProperty("facing", face.key()).withProperty("half", upper(s.cursorPosition()) ? "top" : "bottom");
            return block.withProperty("facing", Dir.look(s.playerPosition()).opposite().key())
                    .withProperty("half", face == Dir.UP ? "bottom" : "top");
        }
    }

    static final class FenceGate extends BlockPlacementRule {
        FenceGate(Block b) { super(b); }

        @Override
        public Block blockPlace(PlacementState s) {
            return block.withProperty("facing", Dir.look(s.playerPosition()).key());
        }
    }
}
