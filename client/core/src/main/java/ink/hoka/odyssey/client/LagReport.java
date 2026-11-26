package ink.hoka.odyssey.client;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 0.57: задержка клиента — сколько Ping сервера ждал в очереди пакетов главного потока (пришёл в сетевой поток →
 * обработан главным). Раз в секунду серверу C2S {@code hoka_odyssey:wc} op 7 LAG: varint последняя (мс), varint
 * наибольшая за 5 с (мс), varint длина очереди кубов CubeQueue. Таб сервера показывает её отдельно от сети.
 */
public final class LagReport {

    private LagReport() {}

    static final int OP_LAG = 7;
    private static final ConcurrentHashMap<Integer, Long> ARRIVED = new ConcurrentHashMap<>();
    /** Кольцо замеров за 5 с: время (нс) и задержка (мс). Только главный поток. */
    private static final long[] AT = new long[64];
    private static final int[] MS = new int[64];
    private static int n, last;
    private static long sentAt;

    /** Сетевой поток: Ping пришёл. */
    public static void arrived(int id) {
        if (ARRIVED.size() > 256) ARRIVED.clear();
        ARRIVED.put(id, System.nanoTime());
    }

    /** Главный поток: Ping обработан. */
    public static void handled(int id) {
        Long t = ARRIVED.remove(id);
        if (t == null) return;
        long now = System.nanoTime();
        last = (int) Math.min(60_000, (now - t) / 1_000_000L);
        int k = n++ & 63;
        AT[k] = now;
        MS[k] = last;
    }

    /** Наибольшая задержка за 5 с. */
    static int max5() {
        long now = System.nanoTime();
        int m = 0;
        for (int i = 0; i < 64; i++) if (AT[i] != 0 && now - AT[i] < 5_000_000_000L) m = Math.max(m, MS[i]);
        return m;
    }

    /** Каждый кадр: раз в секунду — серверу. */
    public static void frame() {
        if (!TallWorld.net) return;
        long now = System.nanoTime();
        if (now - sentAt < 1_000_000_000L) return;
        sentAt = now;
        if (!ClientPlayNetworking.canSend(Net.WC.TYPE)) return;
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer(16));
        b.writeByte(OP_LAG);
        b.writeVarInt(Math.max(0, last));
        b.writeVarInt(max5());
        b.writeVarInt(Math.max(0, CubeQueue.size()));
        byte[] data = new byte[b.readableBytes()];
        b.readBytes(data);
        ClientPlayNetworking.send(new Net.WC(data));
    }

    public static String stats() { return "задержка клиента: последняя " + last + " мс, макс за 5 с " + max5() + " мс"; }
}
