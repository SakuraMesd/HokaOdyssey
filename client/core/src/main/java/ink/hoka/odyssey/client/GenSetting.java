package ink.hoka.odyssey.client;

import com.google.gson.JsonObject;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * 0.59: настройка «Генерация мира» — сколько потоков генерируют нетронутые кубы на клиенте: 0 — Выкл (сервер шлёт
 * кубы, как раньше), 1–8, AUTO — по ядрам: clamp(ядер − 2, 1, 4). Хранится в config/hoka_odyssey.json (clientGen).
 */
public final class GenSetting {

    private GenSetting() {}

    public static final int AUTO = 9;
    private static int value = load();

    public static int get() { return value; }

    public static void set(int v) { value = Math.max(0, Math.min(AUTO, v)); }

    /** Потоков сейчас (0 — выключено). */
    static int threads() {
        if (value == 0) return 0;
        if (value == AUTO) return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
        return value;
    }

    public static void save() {
        VerticalView.put("clientGen", value);
        ClientGen.settingChanged();
    }

    private static int load() {
        JsonObject o = VerticalView.read();
        try { return o.has("clientGen") ? Math.max(0, Math.min(AUTO, o.get("clientGen").getAsInt())) : AUTO; }
        catch (Exception e) { return AUTO; }
    }

    public static Component valueText(int v) {
        return v == 0 ? Component.translatable("options.hoka_odyssey.client_gen.off")
                : v == AUTO ? Component.translatable("options.hoka_odyssey.client_gen.auto", threadsAuto())
                : Component.translatable("options.hoka_odyssey.client_gen.value", v);
    }

    private static int threadsAuto() { return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2)); }

    /** Ползунок ванильного экрана графики. */
    public static OptionInstance<Integer> option() {
        return new OptionInstance<>("options.hoka_odyssey.client_gen",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.hoka_odyssey.client_gen.tooltip")),
                (caption, v) -> Options.genericValueLabel(caption, valueText(v)),
                new OptionInstance.IntRange(0, AUTO, false), value, v -> { set(v); save(); });
    }
}
