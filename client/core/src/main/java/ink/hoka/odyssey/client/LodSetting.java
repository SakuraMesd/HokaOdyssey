package ink.hoka.odyssey.client;

import com.google.gson.JsonObject;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * 0.64 «Дальняя прорисовка» (аналог Voxy/Distant Horizons): за пределами обычной «Дальности прорисовки» мир видно
 * упрощённым — чем дальше, тем крупнее «пиксели» (ячейки 2…64 блока). Настройки графики: дальность в чанках (0 — выкл)
 * и качество (насколько рано укрупнять). Ключи lodDistance / lodQuality в config/hoka_odyssey.json.
 */
public final class LodSetting {

    private LodSetting() {}

    public static final int STEP = 32, MAX = 768, DEFAULT = 256;
    public static final int Q_MIN = 1, Q_MAX = 4, Q_DEFAULT = 3;

    private static int distance = loadInt("lodDistance", DEFAULT, 0, MAX);
    private static int quality = loadInt("lodQuality", Q_DEFAULT, Q_MIN, Q_MAX);
    /** Растёт при каждой смене — рендер пересобирает выбор плиток. */
    private static volatile int version;

    /** Дальность в чанках (0 — выкл). */
    public static int distance() { return distance; }

    public static void setDistance(int v) { distance = v <= 0 ? 0 : Math.max(STEP, Math.min(MAX, v)); version++; }

    public static void saveDistance() { VerticalView.put("lodDistance", distance); }

    public static int quality() { return quality; }

    public static void setQuality(int v) { quality = Math.max(Q_MIN, Math.min(Q_MAX, v)); version++; }

    public static void saveQuality() { VerticalView.put("lodQuality", quality); }

    public static int version() { return version; }

    private static int loadInt(String key, int def, int min, int max) {
        try {
            JsonObject o = VerticalView.read();
            int v = o.has(key) ? o.get(key).getAsInt() : def;
            return Math.max(min, Math.min(max, v));
        } catch (Exception e) {
            return def;
        }
    }

    public static Component distanceText(int v) {
        return v <= 0 ? Component.translatable("options.hoka_odyssey.lod.off")
                : Component.translatable("options.hoka_odyssey.lod.value", v, v * 16);
    }

    public static Component qualityText(int v) { return Component.translatable("options.hoka_odyssey.lod_quality." + v); }

    /** Ползунок дальности для ванильного экрана графики (шаг STEP, 0 — выкл). */
    public static OptionInstance<Integer> distanceOption() {
        return new OptionInstance<>("options.hoka_odyssey.lod",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.hoka_odyssey.lod.tooltip")),
                (caption, v) -> Options.genericValueLabel(caption, distanceText(v * STEP)),
                new OptionInstance.IntRange(0, MAX / STEP, false), distance / STEP,
                v -> { setDistance(v * STEP); saveDistance(); });
    }

    public static OptionInstance<Integer> qualityOption() {
        return new OptionInstance<>("options.hoka_odyssey.lod_quality",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.hoka_odyssey.lod_quality.tooltip")),
                (caption, v) -> Options.genericValueLabel(caption, qualityText(v)),
                new OptionInstance.IntRange(Q_MIN, Q_MAX, false), quality,
                v -> { setQuality(v); saveQuality(); });
    }
}
