package ink.hoka.odyssey.client.block;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 0.73 Настоящие блоки мода для своих блоков сервера. Сервер (Minestom) новых блоков не знает — у него свой блок
 * хранится ванильным «носителем» (нотный блок, листва, растяжка). Мод заранее регистрирует запас своих блоков
 * (hoka_odyssey:solid_NNN — твёрдые, clear_NN — твёрдые прозрачные, pass_NN — проходимые) и, получив от сервера
 * список блоков, ставит каждому носителю свой блок из запаса ({@link Remap}): в мире у игрока это отдельный блок —
 * своё название, звук, частицы, никакого поведения нотного блока. Номера ванильных состояний не меняются (блоки мода
 * идут после ванильных), сервер ничего не замечает.
 */
public final class PoolBlocks implements ModInitializer {

    public static final int SOLID = 384, CLEAR = 48, PASS = 96;
    public static final PoolBlock[] solid = new PoolBlock[SOLID], clear = new PoolBlock[CLEAR], pass = new PoolBlock[PASS];

    /** Блок запаса: название — от сервера (своего блока, к которому привязан). */
    public static final class PoolBlock extends Block {
        public volatile String title;
        /** id своего блока сервера (F3: hoka_odyssey:<id>). */
        public volatile String customId;

        /** 0.84: хитбокс от сервера (shape своего блока, уже повёрнутый) — коллизия и рамка; null — полный куб. */
        public volatile VoxelShape shape;
        final boolean passable;

        PoolBlock(Properties p, boolean passable) { super(p); this.passable = passable; }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            VoxelShape s = shape;
            return s != null ? s : super.getShape(state, level, pos, ctx);
        }

        @Override
        protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            VoxelShape s = shape;
            return s != null && !passable ? s : super.getCollisionShape(state, level, pos, ctx);
        }

        @Override
        public net.minecraft.network.chat.MutableComponent getName() {
            String t = title;
            return t != null ? Component.literal(t) : super.getName();
        }
    }

    @Override
    public void onInitialize() {
        for (int i = 0; i < SOLID; i++) solid[i] = reg(String.format("solid_%03d", i), 0);
        for (int i = 0; i < CLEAR; i++) clear[i] = reg(String.format("clear_%02d", i), 1);
        for (int i = 0; i < PASS; i++) pass[i] = reg(String.format("pass_%02d", i), 2);
        HokaTab.register();                                                 // раздел креатива со своими предметами
    }

    private static PoolBlock reg(String name, int kind) {
        Identifier id = Identifier.fromNamespaceAndPath("hoka_odyssey", name);
        BlockBehaviour.Properties p = BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))
                .mapColor(MapColor.STONE).strength(1.5f, 6f).sound(SoundType.STONE);
        if (kind >= 1) p = p.noOcclusion().isViewBlocking((s, l, pos) -> false).isSuffocating((s, l, pos) -> false)
                .isRedstoneConductor((s, l, pos) -> false);
        if (kind >= 1) p = p.dynamicShape();      // 0.84: форма от сервера (shape), выше блока — без кэша формы у ванили
        if (kind == 2) p = p.noCollision().instabreak();
        return Registry.register(BuiltInRegistries.BLOCK, id, new PoolBlock(p, kind == 2));
    }

    /** Свободный блок запаса для носителя (по виду носителя) с номером n в своём виде; null — запас кончился / не наш вид. */
    static BlockState forCarrier(BlockState carrier, int[] counters) {
        String id = BuiltInRegistries.BLOCK.getKey(carrier.getBlock()).getPath();
        PoolBlock[] arr;
        int k;
        if (id.equals("note_block")) { arr = solid; k = 0; }
        else if (id.endsWith("_leaves")) { arr = clear; k = 1; }
        else if (id.equals("tripwire")) { arr = pass; k = 2; }
        else return null;
        if (arr[0] == null || counters[k] >= arr.length) return null;
        return arr[counters[k]++].defaultBlockState();
    }

    static boolean isPool(Block b) { return b instanceof PoolBlock; }

    /** Хитбокс по описанию (px, уже повёрнутый) или null — полный куб. */
    static VoxelShape shapeOf(double[] box) {
        if (box == null) return null;
        return Shapes.box(box[0] / 16, box[1] / 16, box[2] / 16, box[3] / 16, box[4] / 16, box[5] / 16);
    }

    /** Перед раскладкой блоков: формы всех блоков запаса — снова полный куб (привязка к блокам сервера новая). */
    static void resetShapes() {
        for (PoolBlock[] arr : new PoolBlock[][]{solid, clear, pass}) for (PoolBlock b : arr) if (b != null) b.shape = null;
    }
}
