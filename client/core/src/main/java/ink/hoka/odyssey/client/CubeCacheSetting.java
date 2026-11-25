package ink.hoka.odyssey.client;

import com.google.gson.JsonObject;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Настройка «Кэш кубов, МБ» (0.51): сколько памяти держать под кубы, ушедшие из обзора (CubeCache). 0 — выключен.
 * По умолчанию куча/12 в пределах 64…256 МБ (куча 3 ГБ → 256). Хранится в config/hoka_odyssey.json (cubeCacheMb),
 * ползунок — рядом с «Дальностью по высоте» (Sodium и ваниль).
 */
public final class CubeCacheSetting {

    private CubeCacheSetting() {}

    public static final int MAX = 2048, STEP = 32;
    public static final int DEFAULT = (int) Math.max(64, Math.min(256, Runtime.getRuntime().maxMemory() / 12 >> 20));

    private static int value = load();

    public static int get() { return value; }

    public static void set(int v) { value = Math.max(0, Math.min(MAX, v)); }

    /** Записать и сообщить серверу (HAVE с новым бюджетом). */
    public static void save() {
        VerticalView.put("cubeCacheMb", value);
        CubeCache.settingChanged();
    }

    private static int load() {
        JsonObject o = VerticalView.read();
        try { return o.has("cubeCacheMb") ? Math.max(0, Math.min(MAX, o.get("cubeCacheMb").getAsInt())) : DEFAULT; }
        catch (Exception e) { return DEFAULT; }
    }

    static Component valueText(int mb) {
        return mb == 0 ? Component.translatable("options.hoka_odyssey.cube_cache.off")
                : Component.translatable("options.hoka_odyssey.cube_cache.value", mb);
    }

    /** Ползунок ванильного экрана графики (шаг STEP МБ). */
    public static OptionInstance<Integer> option() {
        return new OptionInstance<>("options.hoka_odyssey.cube_cache",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.hoka_odyssey.cube_cache.tooltip")),
                (caption, v) -> Options.genericValueLabel(caption, valueText(v * STEP)),
                new OptionInstance.IntRange(0, MAX / STEP, false), value / STEP, v -> { set(v * STEP); save(); });
    }
}
