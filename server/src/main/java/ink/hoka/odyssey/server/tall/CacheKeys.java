package ink.hoka.odyssey.server.tall;

import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Ключ карты для кэша кубов клиента (op 12 CACHE, ядро 0.51): случайный long из {@code <папка карты>/cubecache.id}
 * (создаётся при первом использовании — карту удалили и создали заново, ключ другой), смешанный с генератором карты.
 * Копии инстансов без своей папки — случайный ключ на время работы. Совпадение ключей не опасно: клиент всё равно
 * сверяет хэш содержимого каждого куба.
 */
final class CacheKeys {

    private CacheKeys() {}

    private static final Logger LOG = LoggerFactory.getLogger("cubes");
    private static final Map<Instance, Long> KEYS = new WeakHashMap<>();
    private static final SecureRandom RND = new SecureRandom();
    static final String FILE = "cubecache.id";

    /** Ключ инстанса (0 — кэш на нём выключен: не кубовая карта или -Dhoka.cubes.cache=false). */
    static long of(Instance inst) {
        if (inst == null || !CubeCacheModel.ENABLED || !TallWorld.isCubes(inst)) return 0;
        synchronized (KEYS) {
            Long k = KEYS.get(inst);
            if (k != null) return k;
            long v = compute(inst);
            KEYS.put(inst, v);
            return v;
        }
    }

    private static long compute(Instance inst) {
        long id;
        String gen = "";
        if (inst instanceof InstanceContainer ic && ic.getChunkLoader() instanceof CubeRegionLoader l) {
            id = fileId(l.mapDir);
            try { gen = l.gen().getClass().getName(); } catch (RuntimeException ignored) { }
        } else id = RND.nextLong();
        long h = CubeHash.round(CubeHash.SEED ^ 0x4B4559L, id);
        for (byte b : gen.getBytes(StandardCharsets.UTF_8)) h = CubeHash.round(h, b);
        return CubeHash.avalanche(h);
    }

    /** Случайный id карты из файла (нет — создать). Сбой записи — случайный на время работы. */
    static long fileId(Path dir) {
        Path f = dir.resolve(FILE);
        try {
            if (Files.exists(f)) return Long.parseUnsignedLong(Files.readString(f, StandardCharsets.UTF_8).trim(), 16);
        } catch (Exception e) {
            LOG.warn("кэш кубов: битый {} ({}) — заменяю", f, e.toString());
        }
        long v = RND.nextLong();
        try {
            Files.createDirectories(dir);
            Files.writeString(f, Long.toHexString(v), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("кэш кубов: не записал {} ({}) — ключ карты до перезапуска", f, e.toString());
        }
        return v;
    }
}
