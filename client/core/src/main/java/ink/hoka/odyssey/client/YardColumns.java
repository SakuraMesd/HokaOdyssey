package ink.hoka.odyssey.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Столбцы верфи кораблей на клиенте (SHIPYARD.md §3.4). Верфь далеко от игрока (X ≥ 400 000), обычный кэш чанков
 * держит только кольцо вокруг игрока и такие столбцы выбросил бы. Здесь — отдельная карта столбцов на каждый мир;
 * ClientChunkCache.getChunk её видит (миксин), поэтому блоки верфи для игры — настоящие: getBlockState, столкновения,
 * модели, свет. Заполняет их CubeReceiver (пакет кубов сервера), забывает — op 6.
 */
public final class YardColumns {

    private YardColumns() {}

    /** Полоса верфи (модуль ships сервера: Shipyard.X0 − 64) в столбцах. */
    public static final int MIN_CHUNK_X = (400_000 - 64) >> 4;

    private static final Map<ClientLevel, Long2ObjectOpenHashMap<LevelChunk>> BY_LEVEL = new WeakHashMap<>();

    public static boolean isYard(int chunkX) { return chunkX >= MIN_CHUNK_X; }

    private static Long2ObjectOpenHashMap<LevelChunk> map(ClientLevel level) {
        BY_LEVEL.keySet().removeIf(l -> l != level);                            // столбцы держат свой мир — старый не отпустился бы
        return BY_LEVEL.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
    }

    public static LevelChunk get(ClientLevel level, int x, int z) {
        var m = BY_LEVEL.get(level);
        return m == null ? null : m.get(ChunkPos.asLong(x, z));
    }

    /** Столбец верфи (создать пустой, если нет). */
    public static LevelChunk getOrCreate(ClientLevel level, int x, int z) {
        var m = map(level);
        long k = ChunkPos.asLong(x, z);
        LevelChunk c = m.get(k);
        if (c == null) {
            c = new LevelChunk(level, new ChunkPos(x, z));
            c.setLoaded(true);
            m.put(k, c);
            level.onChunkLoaded(new ChunkPos(x, z));                            // сущности палубы (предметы) тикают как в мире
            if (m.size() == 1) org.slf4j.LoggerFactory.getLogger("HokaOdyssey").info("верфь: пришли блоки корабля (столбец {} {})", x, z);
        }
        return c;
    }

    public static void forget(ClientLevel level, int x, int z) {
        var m = BY_LEVEL.get(level);
        if (m == null) return;
        LevelChunk c = m.remove(ChunkPos.asLong(x, z));
        if (c != null) { level.unload(c); c.setLoaded(false); }
    }

    public static int count(ClientLevel level) {
        var m = BY_LEVEL.get(level);
        return m == null ? 0 : m.size();
    }
}
