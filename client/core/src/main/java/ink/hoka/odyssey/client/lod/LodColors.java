package ink.hoka.odyssey.client.lod;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Цвет блока издалека: среднее по текстуре верхней грани (и боковой — для склонов), как у Distant Horizons. Трава,
 * листва и вода — с оттенком биома (биом приходит из генератора: дальние места не загружены). Кэш по (состояние, биом).
 * Читается из потоков сборки сеток: модели и атлас после загрузки ресурсов не меняются (сброс — {@link #clear}).
 */
final class LodColors {

    private LodColors() {}

    /** Ключ (state << 16 | биом+1) → верх (ARGB) << 32 | бок (ARGB). */
    private static final ConcurrentHashMap<Long, Long> CACHE = new ConcurrentHashMap<>();
    /** Среднее текстуры (ARGB, без оттенка) по спрайту; 0 — пусто. */
    private static final ConcurrentHashMap<TextureAtlasSprite, Integer> SPRITES = new ConcurrentHashMap<>();
    private static volatile Field imageField;
    private static volatile boolean imageTried;

    static void clear() { CACHE.clear(); SPRITES.clear(); }

    private static final int TINT_NONE = 0, TINT_GRASS = 1, TINT_FOLIAGE = 2, TINT_WATER = 3, TINT_FIXED = 4;

    /** Верх и бок ячейки: старшие 32 бита — верх, младшие — бок (оба ARGB, непрозрачные); 0 — нечего рисовать. */
    static long of(int stateId, int biomeId, Holder<Biome>[] biomes) {
        long key = (long) stateId << 16 | (biomeId + 1 & 0xFFFF);
        Long v = CACHE.get(key);
        if (v != null) return v;
        long r = compute(stateId, biomeId, biomes);
        CACHE.put(key, r);
        return r;
    }

    /** Цвет воды биома (ARGB). */
    static int water(int biomeId, Holder<Biome>[] biomes) {
        Biome b = biome(biomeId, biomes);
        int c = b == null ? 0x3F76E4 : b.getWaterColor();
        return 0xFF000000 | mul(c, 0.72f);                                    // текстура воды серая — её средняя яркость
    }

    private static Biome biome(int id, Holder<Biome>[] biomes) {
        if (biomes == null || biomes.length == 0) return null;
        int i = id < 0 || id >= biomes.length ? 0 : id;
        Holder<Biome> h = biomes[i];
        return h == null ? null : h.value();
    }

    private static long compute(int stateId, int biomeId, Holder<Biome>[] biomes) {
        BlockState st;
        try { st = Block.stateById(stateId); } catch (RuntimeException e) { st = null; }
        if (st == null || st.isAir()) return 0;
        int top = 0, side = 0;
        boolean tTop = false, tSide = false;
        try {
            var model = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(st);
            List<BlockModelPart> parts = model.collectParts(RandomSource.create(42));
            int[] a = face(parts, Direction.UP), b = face(parts, Direction.NORTH);
            if (a != null) { top = a[0]; tTop = a[1] != 0; }
            if (b != null) { side = b[0]; tSide = b[1] != 0; }
            if (a == null && b == null) {
                Integer p = avg(model.particleIcon());
                if (p != null) { top = side = p; tTop = tSide = true; }
            }
            if (a == null && b != null) { top = side; tTop = tSide; }
            if (b == null && a != null) { side = top; tSide = tTop; }
        } catch (Throwable ignored) {
        }
        if (top == 0 && side == 0) {                                          // нет модели — цвет карты
            int m = 0x7F7F7F;
            try { m = st.getMapColor(null, null).col; } catch (Throwable ignored) {}
            top = side = 0xFF000000 | m;
        }
        int kind = tint(st);
        if (kind != TINT_NONE) {
            int t = tintColor(kind, st, biome(biomeId, biomes));
            if (tTop) top = mulRgb(top, t);
            if (tSide) side = mulRgb(side, t);
        }
        return (long) (top | 0xFF000000) << 32 | ((side | 0xFF000000) & 0xFFFFFFFFL);
    }

    /** Среднее по квадам грани: [цвет, тонируется]. */
    private static int[] face(List<BlockModelPart> parts, Direction d) {
        long r = 0, g = 0, b = 0, n = 0;
        boolean tinted = false;
        for (int pass = 0; pass < 2 && n == 0; pass++) {                      // 2-й проход — грани без cullface (кусты, крест)
            for (BlockModelPart p : parts) {
                for (BakedQuad q : p.getQuads(pass == 0 ? d : null)) {
                    if (pass == 1 && q.direction() != d) continue;
                    Integer c = avg(q.sprite());
                    if (c == null) continue;
                    r += c >> 16 & 255; g += c >> 8 & 255; b += c & 255; n++;
                    if (q.isTinted()) tinted = true;
                }
            }
        }
        if (n == 0) return null;
        return new int[]{0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n), tinted ? 1 : 0};
    }

    /** Среднее непрозрачных пикселей спрайта (первый кадр). */
    private static Integer avg(TextureAtlasSprite s) {
        if (s == null) return null;
        Integer v = SPRITES.get(s);
        if (v != null) return v == 0 ? null : v;
        int c = 0;
        try {
            NativeImage img = image(s.contents());
            if (img != null) {
                int w = Math.min(s.contents().width(), img.getWidth()), h = Math.min(s.contents().height(), img.getHeight());
                long r = 0, g = 0, b = 0, n = 0;
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int p = img.getPixel(x, y);                               // ARGB
                    if ((p >>> 24) < 128) continue;
                    r += p >> 16 & 255; g += p >> 8 & 255; b += p & 255; n++;
                }
                if (n > 0) c = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        } catch (Throwable ignored) {
        }
        SPRITES.put(s, c);
        return c == 0 ? null : c;
    }

    /** Исходная картинка спрайта: поле типа NativeImage (имена в игре промежуточные — ищем по типу). */
    private static NativeImage image(SpriteContents c) throws Exception {
        if (!imageTried) {
            synchronized (LodColors.class) {
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

    private static int tint(BlockState st) {
        Block b = st.getBlock();
        if (b == Blocks.GRASS_BLOCK || b == Blocks.SHORT_GRASS || b == Blocks.TALL_GRASS || b == Blocks.FERN || b == Blocks.LARGE_FERN
                || b == Blocks.SUGAR_CANE) return TINT_GRASS;
        if (b == Blocks.OAK_LEAVES || b == Blocks.JUNGLE_LEAVES || b == Blocks.ACACIA_LEAVES || b == Blocks.DARK_OAK_LEAVES
                || b == Blocks.MANGROVE_LEAVES || b == Blocks.VINE) return TINT_FOLIAGE;
        if (b == Blocks.BIRCH_LEAVES || b == Blocks.SPRUCE_LEAVES || b == Blocks.LILY_PAD) return TINT_FIXED;
        if (b == Blocks.WATER) return TINT_WATER;
        return TINT_NONE;
    }

    private static int tintColor(int kind, BlockState st, Biome b) {
        Block k = st.getBlock();
        return switch (kind) {
            case TINT_GRASS -> b == null ? 0x79C05A : b.getGrassColor(0, 0);
            case TINT_FOLIAGE -> b == null ? 0x59AE30 : b.getFoliageColor();
            case TINT_WATER -> b == null ? 0x3F76E4 : b.getWaterColor();
            default -> k == Blocks.BIRCH_LEAVES ? 0x80A755 : k == Blocks.SPRUCE_LEAVES ? 0x619961 : 0x208030;
        };
    }

    static int mulRgb(int c, int t) {
        int r = (c >> 16 & 255) * (t >> 16 & 255) / 255, g = (c >> 8 & 255) * (t >> 8 & 255) / 255, b = (c & 255) * (t & 255) / 255;
        return c & 0xFF000000 | r << 16 | g << 8 | b;
    }

    static int mul(int c, float k) {
        int r = Math.min(255, (int) ((c >> 16 & 255) * k)), g = Math.min(255, (int) ((c >> 8 & 255) * k)), b = Math.min(255, (int) ((c & 255) * k));
        return c & 0xFF000000 | r << 16 | g << 8 | b;
    }
}
