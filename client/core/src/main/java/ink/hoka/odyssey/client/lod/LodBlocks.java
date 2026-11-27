package ink.hoka.odyssey.client.lod;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Блоки для дальней прорисовки: как блок выглядит издалека. Каждой грани — её текстура из атласа блоков (та же, что у
 * настоящей земли, с теми же мипмапами: вблизи 16 пикселей, дальше 8, 4, 2, 1), признак оттенка (трава/листва/вода — по
 * биому) и средний цвет текстуры (для смешивания земли с кронами). Вид блока: пропустить (воздух, мелкие растения),
 * твёрдый куб (всё с формой, листва) или вода (полупрозрачная поверхность). Потокобезопасно: модели и атлас после
 * загрузки ресурсов не меняются; при перезагрузке ресурсов — {@link #clear}.
 */
final class LodBlocks {

    private LodBlocks() {}

    static final byte SKIP = 0, SOLID = 1, WATER = 2;
    static final int TINT_NONE = 0, TINT_GRASS = 1, TINT_FOLIAGE = 2, TINT_WATER = 3, TINT_BIRCH = 4, TINT_SPRUCE = 5, TINT_LILY = 6;
    /** Порядок граней = Direction.ordinal(): DOWN, UP, NORTH, SOUTH, WEST, EAST. */
    static final Direction[] DIRS = Direction.values();

    /** Грань: начало спрайта в атласе (u0, v0), размер в пикселях, оттенок, средний цвет текстуры (ARGB, без оттенка). */
    record Face(float u0, float v0, int w, int h, int tint, int avg) {}

    static final class Info {
        final byte kind;
        final Face[] faces;

        Info(byte kind, Face[] faces) { this.kind = kind; this.faces = faces; }
    }

    private static final Info AIR = new Info(SKIP, null);
    private static final ConcurrentHashMap<Integer, Info> INFO = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<TextureAtlasSprite, Integer> AVG = new ConcurrentHashMap<>();
    private static volatile Field imageField;
    private static volatile boolean imageTried;

    static void clear() { INFO.clear(); AVG.clear(); }

    static Info info(int stateId) {
        if (stateId == 0) return AIR;
        Info i = INFO.get(stateId);
        if (i != null) return i;
        i = compute(stateId);
        INFO.put(stateId, i);
        return i;
    }

    private static Info compute(int id) {
        BlockState st;
        try { st = Block.stateById(id); } catch (RuntimeException e) { return AIR; }
        if (st == null || st.isAir()) return AIR;
        byte kind;
        boolean water = st.getFluidState().is(FluidTags.WATER);
        boolean shaped;
        try { shaped = !st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty(); } catch (Throwable e) { shaped = true; }
        if (st.is(BlockTags.LEAVES) || st.isSolidRender() || (shaped && !st.is(Blocks.WATER))) kind = SOLID;
        else if (water) kind = WATER;                                         // вода, водоросли, морская трава — поверхность воды
        else if (st.is(Blocks.LAVA)) kind = SOLID;
        else return AIR;                                                      // трава, цветы, факелы — издалека не видно
        if (kind == WATER) st = Blocks.WATER.defaultBlockState();
        Face[] f = new Face[6];
        try {
            var model = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(st);
            List<BlockModelPart> parts = model.collectParts(RandomSource.create(42));
            for (Direction d : DIRS) f[d.ordinal()] = face(parts, d, st);
            TextureAtlasSprite p = model.particleIcon();
            Face fallback = null;
            for (Face x : f) if (x != null) { fallback = x; break; }
            if (fallback == null && p != null) fallback = face(p, false, st);
            if (fallback == null) return AIR;
            // нет своей грани: верх/низ — от любой горизонтальной/верхней, бока — от любой боковой
            for (Direction d : DIRS) if (f[d.ordinal()] == null) {
                Face alt = d.getAxis() == Direction.Axis.Y ? first(f, Direction.UP, Direction.DOWN, Direction.NORTH) : first(f, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP);
                f[d.ordinal()] = alt != null ? alt : fallback;
            }
        } catch (Throwable e) {
            return AIR;
        }
        return new Info(kind, f);
    }

    private static Face first(Face[] f, Direction... ds) {
        for (Direction d : ds) if (f[d.ordinal()] != null) return f[d.ordinal()];
        return null;
    }

    private static Face face(List<BlockModelPart> parts, Direction d, BlockState st) {
        for (int pass = 0; pass < 2; pass++)
            for (BlockModelPart p : parts)
                for (BakedQuad q : p.getQuads(pass == 0 ? d : null)) {
                    if (pass == 1 && q.direction() != d) continue;
                    if (q.sprite() == null) continue;
                    return face(q.sprite(), q.isTinted(), st);
                }
        return null;
    }

    private static Face face(TextureAtlasSprite s, boolean tinted, BlockState st) {
        int w = s.contents().width(), h = s.contents().height();
        return new Face(s.getU0(), s.getV0(), w, h, tinted || st.is(Blocks.WATER) ? tint(st) : TINT_NONE, avg(s));
    }

    private static int tint(BlockState st) {
        Block b = st.getBlock();
        if (b == Blocks.BIRCH_LEAVES) return TINT_BIRCH;
        if (b == Blocks.SPRUCE_LEAVES) return TINT_SPRUCE;
        if (b == Blocks.LILY_PAD) return TINT_LILY;
        if (b == Blocks.WATER || st.getFluidState().is(FluidTags.WATER) && !st.isSolidRender()) return TINT_WATER;
        if (st.is(BlockTags.LEAVES) || b == Blocks.VINE) return TINT_FOLIAGE;
        return TINT_GRASS;                                                    // трава, папоротник, тростник
    }

    /** Цвет оттенка (RGB) для биома; TINT_NONE — белый. */
    static int tintColor(int kind, int biomeId, Holder<Biome>[] biomes) {
        if (kind == TINT_NONE) return 0xFFFFFF;
        Biome b = null;
        if (biomes != null && biomes.length > 0) {
            Holder<Biome> h = biomes[biomeId >= 0 && biomeId < biomes.length ? biomeId : 0];
            if (h != null) b = h.value();
        }
        return switch (kind) {
            case TINT_GRASS -> b == null ? 0x79C05A : b.getGrassColor(0, 0) & 0xFFFFFF;
            case TINT_FOLIAGE -> b == null ? 0x59AE30 : b.getFoliageColor() & 0xFFFFFF;
            case TINT_WATER -> b == null ? 0x3F76E4 : b.getWaterColor() & 0xFFFFFF;
            case TINT_BIRCH -> 0x80A755;
            case TINT_SPRUCE -> 0x619961;
            default -> 0x208030;
        };
    }

    /** Средний цвет непрозрачных пикселей спрайта (первый кадр), ARGB; 0 — не вышло. */
    private static int avg(TextureAtlasSprite s) {
        Integer v = AVG.get(s);
        if (v != null) return v;
        int c = 0;
        try {
            NativeImage img = image(s.contents());
            if (img != null) {
                int w = Math.min(s.contents().width(), img.getWidth()), h = Math.min(s.contents().height(), img.getHeight());
                long r = 0, g = 0, b = 0, n = 0;
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int p = img.getPixel(x, y);
                    if ((p >>> 24) < 128) continue;
                    r += p >> 16 & 255; g += p >> 8 & 255; b += p & 255; n++;
                }
                if (n > 0) c = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        } catch (Throwable ignored) {
        }
        if (c == 0) c = 0xFF808080;
        AVG.put(s, c);
        return c;
    }

    private static NativeImage image(SpriteContents c) throws Exception {
        if (!imageTried) {
            synchronized (LodBlocks.class) {
                if (!imageTried) {
                    for (Field f : SpriteContents.class.getDeclaredFields())
                        if (f.getType() == NativeImage.class) { f.setAccessible(true); imageField = f; break; }
                    imageTried = true;
                }
            }
        }
        Field f = imageField;
        return f == null ? null : (NativeImage) f.get(c);
    }

    static int mulRgb(int c, int t) {
        int r = (c >> 16 & 255) * (t >> 16 & 255) / 255, g = (c >> 8 & 255) * (t >> 8 & 255) / 255, b = (c & 255) * (t & 255) / 255;
        return r << 16 | g << 8 | b;
    }
}
