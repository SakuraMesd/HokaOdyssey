package ink.hoka.odyssey.client.block;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ячейки в атласе блоков под текстуры с сервера. Мод при запуске игры один раз резервирует в атласе пустые
 * квадраты (assets/minecraft/atlases/blocks.json: s16_*, s32_*, … s256_*); пришла текстура — её пиксели (с
 * мип-уровнями) пишутся прямо в видеокарту на место ячейки. Ни перезагрузки ресурсов, ни пересборки атласа.
 * Если атлас всё же пересобран (F3+T, смена пака) — {@link #begin} это видит и всё раскладывается заново.
 */
final class BlockSlots {

    private BlockSlots() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey/blocks");
    static final int[] SIZES = {16, 32, 64, 128, 256};

    /** Текстура в ячейке: спрайт и какая доля ячейки занята картинкой (она в левом верхнем углу). */
    record Slot(TextureAtlasSprite sprite, float fu, float fv) {
        float u(float f) { return sprite.getU0() + (sprite.getU1() - sprite.getU0()) * f * fu; }
        float v(float f) { return sprite.getV0() + (sprite.getV1() - sprite.getV0()) * f * fv; }
    }

    private record Used(int size, int index, int hash, Slot slot) {}

    private static TextureAtlas atlas;
    private static GpuTexture atlasTex;
    private static final Map<Integer, List<TextureAtlasSprite>> FREE_ALL = new HashMap<>();
    private static final Map<String, Used> USED = new HashMap<>();          // ключ текстуры → ячейка
    private static final Set<String> TOUCHED = new HashSet<>();

    /** Перед раскладкой. false — ячеек нет (мод без своих ресурсов?). */
    static boolean begin(TextureAtlas a) {
        if (a != atlas || a.getTexture() != atlasTex) {
            atlas = a;
            atlasTex = a.getTexture();
            USED.clear();
            FREE_ALL.clear();
            int total = 0;
            for (int s : SIZES) {
                List<TextureAtlasSprite> l = new ArrayList<>();
                for (int i = 0; ; i++) {
                    TextureAtlasSprite sp = a.getSprite(Identifier.fromNamespaceAndPath("hoka_odyssey", "block/s" + s + "_" + i));
                    if (sp == a.missingSprite()) break;
                    l.add(sp);
                }
                FREE_ALL.put(s, l);
                total += l.size();
            }
            LOG.info("ячеек под свои блоки в атласе: {}", total);
        }
        TOUCHED.clear();
        return !FREE_ALL.getOrDefault(16, List.of()).isEmpty();
    }

    /** Атлас сменился с прошлой раскладки (нужна новая). */
    static boolean stale(TextureAtlas a) {
        return a != atlas || a.getTexture() != atlasTex;
    }

    /** Ячейка под PNG (ключ — путь файла, у модели — путь#номер). null — не PNG или ячейки кончились. */
    static Slot slot(String key, byte[] png) {
        if (png == null || png.length == 0) return null;
        TOUCHED.add(key);
        int hash = Arrays.hashCode(png);
        Used u = USED.get(key);
        if (u != null && u.hash == hash) return u.slot;
        NativeImage img;
        try {
            img = NativeImage.read(png);
        } catch (Exception e) {
            LOG.warn("{}: не PNG ({})", key, e.toString());
            return null;
        }
        try {
            int w = img.getWidth(), h = img.getHeight(), need = Math.max(w, h);
            int size = -1;
            for (int s : SIZES) if (s >= need) { size = s; break; }
            if (size < 0) {
                LOG.warn("{}: {}×{} — больше 256, такие текстуры блокам пока нельзя", key, w, h);
                return null;
            }
            int index;
            if (u != null && u.size >= size) { index = u.index; size = u.size; }
            else {
                if (u != null) USED.remove(key);
                index = -1;
                int want = size;
                for (int s : SIZES) {                                          // 0.85: нет своего размера — в ячейку побольше
                    if (s < want) continue;
                    index = free(s);
                    if (index >= 0) { size = s; break; }
                }
                if (index < 0) {
                    LOG.warn("{}: кончились ячейки {}×{} и больше", key, want, want);
                    return null;
                }
                if (size != want) LOG.info("{}: ячеек {}×{} нет — взята {}×{}", key, want, want, size, size);
            }
            TextureAtlasSprite sp = FREE_ALL.get(size).get(index);
            upload(sp, img, size);
            Slot slot = new Slot(sp, w / (float) size, h / (float) size);
            USED.put(key, new Used(size, index, hash, slot));
            return slot;
        } finally {
            img.close();
        }
    }

    /** После раскладки: ячейки текстур, которые больше не нужны, — снова свободны. */
    static void end() {
        USED.keySet().removeIf(k -> !TOUCHED.contains(k));
    }

    private static int free(int size) {
        boolean[] busy = new boolean[FREE_ALL.get(size).size()];
        for (Used u : USED.values()) if (u.size == size) busy[u.index] = true;
        for (int i = 0; i < busy.length; i++) if (!busy[i]) return i;
        return -1;
    }

    private static void upload(TextureAtlasSprite sp, NativeImage src, int size) {
        GpuTexture tex = atlasTex;
        int x0 = Math.round(sp.getU0() * tex.getWidth(0)), y0 = Math.round(sp.getV0() * tex.getHeight(0));
        NativeImage cur = new NativeImage(size, size, true);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
            cur.setPixel(x, y, x < src.getWidth() && y < src.getHeight() ? src.getPixel(x, y) : 0);
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        int mips = tex.getMipLevels();
        for (int m = 0; m < mips && (size >> m) > 0; m++) {
            if (m > 0) {
                NativeImage next = down(cur);
                cur.close();
                cur = next;
            }
            int s = size >> m;
            enc.writeToTexture(tex, cur, m, 0, x0 >> m, y0 >> m, s, s, 0, 0);
        }
        cur.close();
    }

    /** Мип вдвое меньше: цвет — среднее непрозрачных пикселей, прозрачность — среднее. */
    private static NativeImage down(NativeImage a) {
        int s = a.getWidth() / 2;
        NativeImage b = new NativeImage(s, s, true);
        for (int y = 0; y < s; y++) for (int x = 0; x < s; x++) {
            int r = 0, g = 0, bl = 0, al = 0, n = 0;
            for (int k = 0; k < 4; k++) {
                int p = a.getPixel(x * 2 + (k & 1), y * 2 + (k >> 1));
                int pa = p >>> 24;
                al += pa;
                if (pa == 0) continue;
                r += (p >> 16) & 255;
                g += (p >> 8) & 255;
                bl += p & 255;
                n++;
            }
            int out = n == 0 ? 0 : ((al / 4) << 24) | ((r / n) << 16) | ((g / n) << 8) | (bl / n);
            b.setPixel(x, y, out);
        }
        return b;
    }
}
