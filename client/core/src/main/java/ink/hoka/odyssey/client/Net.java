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

    public record C2S(byte[] data) implements CustomPacketPayload {
        public static final Type<C2S> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NS, "c"));
        @Override public Type<C2S> type() { return TYPE; }
    }

    static void register() {
        PayloadTypeRegistry.playS2C().registerLarge(S2C.TYPE, CustomPacketPayload.codec(
                (S2C p, RegistryFriendlyByteBuf buf) -> buf.writeBytes(p.data()), buf -> new S2C(readAll(buf))), 2 << 20);
        PayloadTypeRegistry.playC2S().register(C2S.TYPE, CustomPacketPayload.codec(
                (C2S p, RegistryFriendlyByteBuf buf) -> buf.writeBytes(p.data()), buf -> new C2S(readAll(buf))));
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
