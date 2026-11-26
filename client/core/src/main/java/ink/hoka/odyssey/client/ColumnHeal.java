package ink.hoka.odyssey.client;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 0.63.1: страховка от столбцов, которых у клиента нет, а сервер считает отправленными (CubeStreamer: маска sent,
 * кубы — «доставлены», досылать нечего). Раньше куб/метка такого столбца просто отбрасывались (CubeReceiver.dropped),
 * и столбец оставался дырой навсегда: ваниль не пускает в незагруженный столбец, на месте рельефа — пустота.
 * <ul>
 *   <li>отброс куба по отсутствию столбца — {@link #lost}: столбец, забытый недавно (Forget / вытеснен / вне зоны
 *       обзора), — «поздний» куб, это нормально ({@link CubeReceiver#late}); иначе — потеря, столбец просим заново;</li>
 *   <li>сторож раз в 0,5 с: столбец в обзоре, которого нет дольше 3 с (и он не ждёт в CubeQueue), — тоже просим.
 *       Радиус — по дальним полученным столбцам минус 2 (Minestom шлёт ближние первыми): во время прогрузки дальние
 *       ещё в пути, их не трогаем;</li>
 *   <li>просьба — wc op 10 RESEND_COLUMNS (varint n, n × int cx, int cz): сервер убирает столбец из sent и ставит в
 *       очередь отправки Minestom заново (скелет → новая маска → окно кубов целиком). Старый сервер op 10 пропускает.</li>
 * </ul>
 * Только главный поток.
 */
public final class ColumnHeal {

    private ColumnHeal() {}

    static final int OP_RESEND = 10;
    /** Сколько столбец должен отсутствовать до просьбы; повторы — вдвое реже (до 64 с), без предела числа. */
    static final long MISSING_MS = 3000, RETRY_MS = 4000, FORGOT_MS = 60_000;
    static final int TRIES = 6, PER_SEND = 128;

    /** Забытые столбцы → когда (ms): кубы для них — поздние, не потеря. */
    private static final Long2LongOpenHashMap FORGOT = new Long2LongOpenHashMap();
    /** Нет столбца → с какого времени (ms). */
    private static final Long2LongOpenHashMap MISSING = new Long2LongOpenHashMap();
    /** Просили → (попыток << 48 | время следующей просьбы в мс & 48 бит). */
    private static final Long2LongOpenHashMap ASKED = new Long2LongOpenHashMap();
    private static ClientLevel lastLevel;
    private static long lastScan;
    public static long requested, healed, sends;

    /** Столбец пришёл (скелет применён). */
    public static void arrived(int x, int z) {
        long k = ChunkPos.asLong(x, z);
        FORGOT.remove(k);
        MISSING.remove(k);
        if (ASKED.remove(k) != 0) healed++;
    }

    /** Столбец забыт / вытеснен / отброшен ванилью как вне обзора: кубы для него дальше — поздние. */
    public static void forgot(int x, int z) {
        if (FORGOT.size() > 16384) FORGOT.clear();
        FORGOT.put(ChunkPos.asLong(x, z), System.currentTimeMillis());
    }

    /**
     * Куб/метка/сгенерированный куб для столбца (x, z), которого нет. true — потеря (считается в dropped, столбец
     * попросим), false — поздний (столбец недавно забыт).
     */
    static boolean lost(ClientLevel level, int x, int z) {
        long k = ChunkPos.asLong(x, z);
        long now = System.currentTimeMillis();
        long f = FORGOT.isEmpty() ? 0 : FORGOT.get(k);
        if (f != 0 && now - f < FORGOT_MS) { CubeReceiver.late++; return false; }
        CubeReceiver.dropped++;
        if (level == lastLevel && !MISSING.containsKey(k)) MISSING.put(k, now - MISSING_MS + 1000);   // просьба через ~1 с
        return true;
    }

    /** Кадр (FrameWatchMixin): смена мира — сброс; раз в 0,5 с — сторож и просьбы. */
    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != lastLevel) { lastLevel = level; reset(); }
        if (level == null || mc.player == null || !TallWorld.net || level.getSectionsCount() <= 64) return;
        long now = System.currentTimeMillis();
        if (now - lastScan < 500) return;
        lastScan = now;
        if (!FORGOT.isEmpty()) FORGOT.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > FORGOT_MS);
        scan(mc, level, now);
    }

    private static void scan(Minecraft mc, ClientLevel level, long now) {
        int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z;
        int e = Math.max(2, Math.min(33, mc.options.getEffectiveRenderDistance()));
        var src = level.getChunkSource();
        // дальний полученный столбец: Minestom шлёт по расстоянию — дыры ищем только ближе него на 2
        int far = -1;
        LongArrayList miss = new LongArrayList();
        for (int dx = -e; dx <= e; dx++)
            for (int dz = -e; dz <= e; dz++) {
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                if (src.getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false) != null) { if (d > far) far = d; }
                else miss.add(ChunkPos.asLong(pcx + dx, pcz + dz));
            }
        int r = far - 2;
        Long2LongOpenHashMap keep = new Long2LongOpenHashMap();
        for (int i = 0; i < miss.size(); i++) {
            long k = miss.getLong(i);
            int x = ChunkPos.getX(k), z = ChunkPos.getZ(k);
            boolean near = Math.max(Math.abs(x - pcx), Math.abs(z - pcz)) <= r;
            long since = MISSING.get(k);                                  // потерянный по кубу — и дальше r, но в обзоре
            if (!near && since == 0) continue;
            if (YardColumns.isYard(x)) continue;                           // верфь — столбцы клиента, сервер их не шлёт
            if (CubeQueue.waitsColumn(x, z)) continue;                     // скелет уже в очереди
            keep.put(k, since == 0 ? now : since);
        }
        MISSING.clear();
        MISSING.putAll(keep);
        if (!ASKED.isEmpty()) ASKED.keySet().removeIf(k -> !MISSING.containsKey(k));   // пришёл / ушёл из обзора — забыть
        if (MISSING.isEmpty() || !ClientPlayNetworking.canSend(Net.WC.TYPE)) return;
        LongArrayList out = new LongArrayList();
        for (var en : MISSING.long2LongEntrySet()) {
            long k = en.getLongKey();
            if (now - en.getLongValue() < MISSING_MS) continue;
            long a = ASKED.get(k);
            int tries = (int) (a >>> 48);
            if (a != 0 && now < (a & 0xFFFF_FFFF_FFFFL)) continue;          // без предела попыток: бросить = дыра навсегда
            out.add(k);
        }
        if (out.isEmpty()) return;
        // ближние первыми
        long[] arr = out.toLongArray();
        Long[] boxed = new Long[arr.length];
        for (int i = 0; i < arr.length; i++) boxed[i] = arr[i];
        java.util.Arrays.sort(boxed, java.util.Comparator.comparingInt(k -> Math.max(Math.abs(ChunkPos.getX(k) - pcx), Math.abs(ChunkPos.getZ(k) - pcz))));
        int n = Math.min(PER_SEND, boxed.length);
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer(8 + n * 8));
        b.writeByte(OP_RESEND);
        b.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            long k = boxed[i];
            b.writeInt(ChunkPos.getX(k));
            b.writeInt(ChunkPos.getZ(k));
            long a = ASKED.get(k);
            int tries = Math.min(TRIES, (int) (a >>> 48) + 1);              // интервал 4, 8 … 64 с, дальше — раз в 64 с
            ASKED.put(k, ((long) tries << 48) | ((now + (RETRY_MS << Math.min(4, tries - 1))) & 0xFFFF_FFFF_FFFFL));
        }
        byte[] d = new byte[b.readableBytes()];
        b.readBytes(d);
        ClientPlayNetworking.send(new Net.WC(d));
        requested += n;
        sends++;
    }

    public static void reset() {
        FORGOT.clear();
        MISSING.clear();
        ASKED.clear();
        lastScan = 0;
    }

    public static String stats() {
        return "столбцов запрошено заново " + requested + " (пришло " + healed + ", нет сейчас " + MISSING.size() + ")";
    }
}
