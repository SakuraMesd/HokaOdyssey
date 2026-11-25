package ink.hoka.odyssey.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Дальность по высоте (CUBIC-CHUNKS.md, этап 5): сколько кубов (секций 16 блоков) вверх и вниз от игрока грузить и
 * рисовать на кубовых картах. По горизонтали — обычная «Дальность прорисовки» игры. Ползунок — рядом с ней: у Sodium
 * на странице «Общие» (compat/SodiumOptionsMixin), без Sodium — в ванильных настройках графики (VideoOptionsMixin).
 * Хранится в config/hoka_odyssey.json. Серверу — C2S {@code hoka_odyssey:wc} op 2 VIEW (varint) при входе и при смене.
 */
public final class VerticalView {

    private VerticalView() {}

    public static final int MIN = 4, MAX = 32, DEFAULT = 10;
    static final int OP_VIEW = 2;
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("hoka_odyssey.json");

    private static int value = load();
    private static int sent = -1;

    public static int get() { return value; }

    /** Новое значение (без записи на диск — у Sodium запись в {@link #save}). */
    public static void set(int v) { value = Math.max(MIN, Math.min(MAX, v)); }

    /** Записать в конфиг и сообщить серверу. */
    public static void save() {
        put("verticalDistance", value);
        send(false);
    }

    /** Записать одно значение в config/hoka_odyssey.json, не трогая остальные (там и «Кэш кубов», CubeCacheSetting). */
    static synchronized void put(String key, int v) {
        try {
            JsonObject o = read();
            o.addProperty(key, v);
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, o.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            HokaOdysseyClient.LOG.warn("Hoka Odyssey: не записал {} ({})", FILE, e.toString());
        }
    }

    /** Весь конфиг (нет/битый — пустой). */
    static JsonObject read() {
        try {
            if (Files.exists(FILE)) return JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception ignored) {
        }
        return new JsonObject();
    }

    private static int load() {
        try {
            if (!Files.exists(FILE)) return DEFAULT;
            JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            int v = o.has("verticalDistance") ? o.get("verticalDistance").getAsInt() : DEFAULT;
            return Math.max(MIN, Math.min(MAX, v));
        } catch (Exception e) {
            return DEFAULT;
        }
    }

    /** Серверу: op 2 VIEW. force — после входа (сервер не знает значение); иначе только если изменилось. */
    public static void send(boolean force) {
        if (!ClientPlayNetworking.canSend(Net.WC.TYPE)) return;
        if (!force && value == sent) return;
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer(8));
        b.writeByte(OP_VIEW);
        b.writeVarInt(value);
        byte[] data = new byte[b.readableBytes()];
        b.readBytes(data);
        ClientPlayNetworking.send(new Net.WC(data));
        sent = value;
    }

    /** Новое соединение — сервер значения ещё не знает. */
    static void reset() { sent = -1; }

    static Component label() { return Component.translatable("options.hoka_odyssey.vertical"); }

    static Component tooltip() { return Component.translatable("options.hoka_odyssey.vertical.tooltip"); }

    static Component valueText(int v) { return Component.translatable("options.hoka_odyssey.vertical.value", v); }

    /** Ползунок для ванильного экрана графики (без Sodium). Каждый раз новый — с текущим значением (могли сменить у Sodium). */
    public static OptionInstance<Integer> option() {
        return new OptionInstance<>("options.hoka_odyssey.vertical", OptionInstance.cachedConstantTooltip(tooltip()),
                (caption, v) -> Options.genericValueLabel(caption, valueText(v)),
                new OptionInstance.IntRange(MIN, MAX, false), value, v -> { set(v); save(); });
    }
}
