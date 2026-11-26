package ink.hoka.odyssey.server.tall;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Высокий мир −15008…15007 (этап 1 — прототип, см. TALL-WORLD.md). Включается, если хоть у одной карты в map.yml
 * стоит {@code tall: true}. Тогда:
 * <ul>
 *   <li>в реестре измерений есть hoka:tall (выше ванильного предела — проверку Minestom обходим, собирая запись
 *       напрямую);</li>
 *   <li>координаты блоков в пакетах — в формате мода (X/Z 24 бита, Y 16): NetworkBufferTypeImpl$BlockPositionType;</li>
 *   <li>каждому игроку на этапе входа — сигнал «hoka_odyssey:tall» (мод переключает формат).</li>
 * </ul>
 * ⚠️ Клиент без мода Hoka Odyssey 0.27+ на такой сервер не войдёт (тип измерения выше ванильного).
 */
public final class TallWorld {

    /** Для выгрузки пустующих столбцов (Maps.reapColumns): когда столбец последний раз видели. */
    public static void seen(TallColumn c, long now) { c.st().seenAt = now; }

    public static long seenAt(TallColumn c) { return c.st().seenAt; }

    private TallWorld() {}

    private static final Logger LOG = LoggerFactory.getLogger("tall");
    public static final int MIN_Y = -15008, HEIGHT = 30016;            // кратно 16: −15008…15007
    /** Высокий мир включён (формат позиций в сети — наш). */
    public static volatile boolean enabled;
    /** Тип измерения каждой высокой карты (своё солнце/свет): карта → ключ hoka:tall_<карта>. */
    private static final java.util.Map<String, RegistryKey<DimensionType>> KEYS = new java.util.HashMap<>();

    /**
     * При запуске (до открытия порта): высокие карты (map.yml {@code tall: true}) — каждой свой тип измерения.
     * Настройки в map.yml: {@code sun} (true — солнце и тени от него, false — неба нет, свет неба не считается),
     * {@code ambient} (0…1 — нижняя граница яркости: 0.5 = везде не темнее 50%, поверх светят факелы и т.п.).
     */
    public static void init(Path mapsDir) {
        if (!Files.isDirectory(mapsDir)) return;
        try (Stream<Path> s = Files.list(mapsDir)) {
            DimensionType base = DimensionType.builder().build();
            // с солнцем — основа ванильный overworld: его timelines (день/луна) и атрибуты (цвет неба/тумана). Без них
            // у клиента 1.21.11 солнце навсегда в зените (SUN_ANGLE 0), ночи нет (SKY_LIGHT_LEVEL 15), туман и низ неба чёрные
            DimensionType overworld = overworld();
            for (Path d : s.filter(Files::isDirectory).toList()) {
                Path yml = d.resolve("map.yml");
                if (!Files.isRegularFile(yml)) continue;
                var meta = new ink.hoka.odyssey.api.ModuleConfig(yml);
                if (!meta.getBoolean("tall", false)) continue;
                String name = d.getFileName().toString();
                boolean sun = meta.getBoolean("sun", true);
                float ambient = (float) Math.max(0, Math.min(1, meta.getDouble("ambient", 0)));
                var key = MinecraftServer.getDimensionTypeRegistry().register(Key.key("hoka:tall_" + name.replaceAll("[^a-z0-9_]", "_")),
                        tall(sun && overworld != null ? overworld : base, MIN_Y, HEIGHT, sun, ambient));
                KEYS.put(name, key);
                if (meta.getBoolean("cubes", false)) CUBE_MAPS.add(name);
                LOG.info("высокая карта «{}»: {}…{}, солнце {}, свет не ниже {}%", name, MIN_Y, MIN_Y + HEIGHT - 1,
                        sun ? "есть" : "нет", Math.round(ambient * 100));
            }
            if (!KEYS.isEmpty()) {
                enabled = true;
                LOG.info("высокий мир включён (нужен мод Hoka Odyssey 0.27+)");
            }
        } catch (Exception e) {
            LOG.error("высокий мир не включён: {}", e.toString());
        }
    }

    /** Карты с кубическими чанками (map.yml {@code cubes: true}) и их миры. */
    private static final java.util.Set<String> CUBE_MAPS = new java.util.HashSet<>();
    private static final java.util.Set<net.minestom.server.instance.Instance> CUBE_INSTANCES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean cubesMap(String map) { return CUBE_MAPS.contains(map); }
    public static void markCubes(net.minestom.server.instance.Instance i) { CUBE_INSTANCES.add(i); }
    public static boolean isCubes(net.minestom.server.instance.Instance i) { return i != null && CUBE_INSTANCES.contains(i); }

    /** Тип измерения высокой карты или null (не высокая / не включена). */
    public static RegistryKey<DimensionType> key(String map) { return KEYS.get(map); }

    /** Столбцы, у которых сменились блоки: свет разослать на следующем тике (раз за тик, сколько бы блоков ни сменилось). */
    private static final java.util.Set<TallColumn> DIRTY = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicBoolean FLUSH = new java.util.concurrent.atomic.AtomicBoolean();

    static void lightDirty(TallColumn c) {
        DIRTY.add(c);
        if (FLUSH.compareAndSet(false, true)) MinecraftServer.getSchedulerManager().scheduleNextTick(TallWorld::flushLight);
    }

    /** Столбец только что загружен: свет слать не нужно (setBlock при заливке пометил его зря). */
    static void loaded(TallColumn c) { DIRTY.remove(c); }

    /** Столбцов со светом в очереди (для /hocubes stats). */
    static int dirtyCount() { return DIRTY.size(); }

    private static void flushLight() {
        FLUSH.set(false);
        for (TallColumn c : java.util.List.copyOf(DIRTY)) {
            DIRTY.remove(c);
            if (c.isLoaded()) c.sendLight();
        }
    }

    /** Граница мира: ±500 000 по горизонтали (временно; упаковка координат держит до ±8 388 607). */
    public static net.minestom.server.instance.WorldBorder border() {
        return new net.minestom.server.instance.WorldBorder(1_000_000, 0, 0, 5, 15);
    }

    /**
     * Тип измерения выше ванильного предела: проверку билдера Minestom обходим (запись record напрямую).
     * sun = false — неба нет (как у Нижнего мира: свет неба не считается, тени от солнца нет, время на свет не влияет);
     * ambient — нижняя граница яркости блоков (0…1).
     */
    /** Ванильный overworld из реестра Minestom (null — нет или без timelines). */
    static DimensionType overworld() {
        try {
            DimensionType o = MinecraftServer.getDimensionTypeRegistry().get(DimensionType.OVERWORLD);
            if (o == null || o.timelines() == null || o.timelines().size() == 0) {
                LOG.warn("нет ванильного overworld с timelines — на высоких картах не будет смены дня и ночи");
                return null;
            }
            return o;
        } catch (RuntimeException e) {
            LOG.warn("overworld из реестра: {}", e.toString());
            return null;
        }
    }

    static DimensionType tall(DimensionType base, int minY, int height, boolean sun, float ambient) throws Exception {
        Class<?> c = base.getClass();
        var comps = c.getRecordComponents();
        Object[] v = new Object[comps.length];
        Class<?>[] t = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) {
            t[i] = comps[i].getType();
            var m = comps[i].getAccessor();
            m.setAccessible(true);
            v[i] = m.invoke(base);
            String n = comps[i].getName();
            if (n.equals("minY")) v[i] = minY;
            if (n.equals("height") || n.equals("logicalHeight")) v[i] = height;
            if (n.equals("hasSkylight")) v[i] = sun;
            if (n.equals("ambientLight")) v[i] = ambient;
            // без солнца низы/бока блоков при DEFAULT почти чёрные (тёмные «пятна» под площадками) — ровно, как в Незере
            if (n.equals("cardinalLight") && !sun) v[i] = DimensionType.CardinalLight.NETHER;
        }
        var ctor = c.getDeclaredConstructor(t);
        ctor.setAccessible(true);
        return (DimensionType) ctor.newInstance(v);
    }

    /** Площадки-ориентиры 9×9 вокруг (0, 0) на этих высотах (камень, в центре светокамень). */
    private static final int[] PLATFORMS = {-15000, -12000, -6000, -2000, 2000, 6000, 12000, 15000};

    /** Высоты площадок-ориентиров (массив общий — не менять). */
    public static int[] platforms() { return PLATFORMS; }
}
