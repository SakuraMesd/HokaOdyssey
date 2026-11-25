package ink.hoka.odyssey.client;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Канал ядра. Сервер — Minestom (модуль clientmods), тело пакета — «сырые» байты (DataStream, big-endian),
 * первый байт — операция.
 * <pre>
 * S2C hoka_odyssey:s
 *   1 MANIFEST  int len, body (UTF-8 JSON), int len, подпись Ed25519 тела
 *   2 CHUNK     UTF sha256, int всего, int смещение, int len, байты (jar модуля или файл ресурсов)
 *   3 MSG       UTF модуль, int len, байты
 *   4 —         (был ASSETS — пак ресурсов сервера; убран в 0.10, ресурспака нет)
 *   5 FILES     то же для файлов модулей (мимо ресурсов игры: ctx.file / onFile / texture — без перезагрузок)
 * C2S hoka_odyssey:c
 *   1 HELLO     int протокол, UTF версия ядра
 *   2 NEED      int n, n × UTF sha256 — каких объектов (jar модулей, файлов ресурсов) нет в кэше
 *   3 READY     int n, n × UTF id — какие модули загружены
 *   4 MSG       UTF модуль, int len, байты
 * </pre>
 */
public final class Net {

    public static final int PROTOCOL = 1;
    public static final String NS = "hoka_odyssey";

    public record S2C(byte[] data) implements CustomPacketPayload {
        public static final Type<S2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NS, "s"));
        @Override public Type<S2C> type() { return TYPE; }
    }

    /** Сервер (этап входа): мир высокий — позиции блоков в пакетах в нашем формате (TallWorld). */
    public record Tall() implements CustomPacketPayload {
        public static final Type<Tall> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NS, "tall"));
        @Override public Type<Tall> type() { return TYPE; }
    }

    /**
     * Ответ ядра на {@link Tall} (этап входа): версия ядра, есть ли Sodium и его версия. Сервер решает по нему, пускать
     * ли на карты с кубическими чанками (CUBIC-CHUNKS.md §3.8). Строки — как у Minecraft (varint длина + UTF-8).
     */
    public record Hi(String core, boolean sodium, String sodiumVersion) implements CustomPacketPayload {
        public static final Type<Hi> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NS, "hi"));
        @Override public Type<Hi> type() { return TYPE; }
    }

    /** Кубические чанки: кубы и их выгрузка (CubeReceiver; сервер — CubeStreamer). */
    /** parsed — разобранное на сетевом потоке при декодировании (CubeReceiver.preparse, 0.49); null — разобрать на главном. */
    public record W(byte[] data, Object parsed) implements CustomPacketPayload {
        public static final Type<W> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NS, "w"));
        @Override public Type<W> type() { return TYPE; }
    }

    /** Кубические чанки, клиент → сервер (CUBIC-CHUNKS.md §5.2 C2S): op 2 VIEW — дальность по высоте (VerticalView). */
    public record WC(byte[] data) implements CustomPacketPayload {
        public static final Type<WC> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NS, "wc"));
        @Override public Type<WC> type() { return TYPE; }
    }

    public record C2S(byte[] data) implements CustomPacketPayload {
        public static final Type<C2S> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NS, "c"));
        @Override public Type<C2S> type() { return TYPE; }
    }

    static void register() {
        PayloadTypeRegistry.configurationS2C().register(Tall.TYPE, StreamCodec.unit(new Tall()));
        PayloadTypeRegistry.configurationC2S().register(Hi.TYPE, StreamCodec.of(
                (net.minecraft.network.FriendlyByteBuf buf, Hi p) -> { buf.writeUtf(p.core()); buf.writeBoolean(p.sodium()); buf.writeUtf(p.sodiumVersion()); },
                buf -> new Hi(buf.readUtf(), buf.readBoolean(), buf.readUtf())));
        PayloadTypeRegistry.playS2C().registerLarge(S2C.TYPE, CustomPacketPayload.codec(
                (S2C p, RegistryFriendlyByteBuf buf) -> buf.writeBytes(p.data()), buf -> new S2C(readAll(buf))), 2 << 20);
        PayloadTypeRegistry.playS2C().register(W.TYPE, CustomPacketPayload.codec(
                (W p, RegistryFriendlyByteBuf buf) -> buf.writeBytes(p.data()), buf -> { byte[] d = readAll(buf); return new W(d, CubeReceiver.preparse(d)); }));
        PayloadTypeRegistry.playC2S().register(C2S.TYPE, CustomPacketPayload.codec(
                (C2S p, RegistryFriendlyByteBuf buf) -> buf.writeBytes(p.data()), buf -> new C2S(readAll(buf))));
        PayloadTypeRegistry.playC2S().register(WC.TYPE, CustomPacketPayload.codec(
                (WC p, RegistryFriendlyByteBuf buf) -> buf.writeBytes(p.data()), buf -> new WC(readAll(buf))));
    }

    private static byte[] readAll(ByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.readBytes(b);
        return b;
    }

    /** Отправить серверу: op + тело. */
    static void send(int op, Writer w) {
        if (!ClientPlayNetworking.canSend(C2S.TYPE)) return;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(op);
            w.write(out);
        } catch (IOException e) {
            return;
        }
        ClientPlayNetworking.send(new C2S(bytes.toByteArray()));
    }

    interface Writer { void write(DataOutputStream out) throws IOException; }

    /** Для StreamCodec-подсказок типов (не используется). */
    @SuppressWarnings("unused")
    private static StreamCodec<ByteBuf, byte[]> unused() { return null; }
}
