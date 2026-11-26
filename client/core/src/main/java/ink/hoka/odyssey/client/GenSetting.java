package ink.hoka.odyssey.client;

import com.google.gson.JsonObject;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * 0.59: настройка «Генерация мира» — сколько потоков генерируют нетронутые кубы на клиенте: 0 — Выкл (сервер шлёт
 * кубы, как раньше), 1–8, AUTO — по ядрам: 0.63 — ядер − 1, не меньше 2 (было clamp(ядер − 2, 1, 4)).
 * Хранится в config/hoka_odyssey.json (clientGen).
 * <p>0.63: «Кэш генерации на диске, МБ» (genDiskMb): сгенерированные кубы на диске (GenDiskCache), 0 — выкл.</p>
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
        if (value == AUTO) return threadsAuto();
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

    /** 0.63: ядер − 1, не меньше 2 (потоки низкого приоритета; при долгих кадрах GenPool сам берёт меньше). */
    static int threadsAuto() { return Math.max(2, Math.min(16, Runtime.getRuntime().availableProcessors() - 1)); }

    // ───────────── 0.63: кэш генерации на диске ─────────────

    public static final int DISK_MAX = 8192, DISK_STEP = 128, DISK_DEFAULT = 512;
    private static int diskMb = loadDisk();

    public static int diskMb() { return diskMb; }

    public static void setDisk(int mb) { diskMb = Math.max(0, Math.min(DISK_MAX, mb)); }

    public static void saveDisk() {
        VerticalView.put("genDiskMb", diskMb);
        ClientGen.diskSettingChanged();
    }

    private static int loadDisk() {
        JsonObject o = VerticalView.read();
        try { return o.has("genDiskMb") ? Math.max(0, Math.min(DISK_MAX, o.get("genDiskMb").getAsInt())) : DISK_DEFAULT; }
        catch (Exception e) { return DISK_DEFAULT; }
    }

    public static Component diskText(int mb) {
        return mb == 0 ? Component.translatable("options.hoka_odyssey.gen_disk.off")
                : Component.translatable("options.hoka_odyssey.gen_disk.value", mb);
    }

    /** Ползунок ванильного экрана графики (шаг DISK_STEP МБ). */
    public static OptionInstance<Integer> diskOption() {
        return new OptionInstance<>("options.hoka_odyssey.gen_disk",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.hoka_odyssey.gen_disk.tooltip")),
                (caption, v) -> Options.genericValueLabel(caption, diskText(v * DISK_STEP)),
                new OptionInstance.IntRange(0, DISK_MAX / DISK_STEP, false), diskMb / DISK_STEP, v -> { setDisk(v * DISK_STEP); saveDisk(); });
    }

    /** Ползунок ванильного экрана графики. */
    public static OptionInstance<Integer> option() {
        return new OptionInstance<>("options.hoka_odyssey.client_gen",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.hoka_odyssey.client_gen.tooltip")),
                (caption, v) -> Options.genericValueLabel(caption, valueText(v)),
                new OptionInstance.IntRange(0, AUTO, false), value, v -> { set(v); save(); });
    }
}
