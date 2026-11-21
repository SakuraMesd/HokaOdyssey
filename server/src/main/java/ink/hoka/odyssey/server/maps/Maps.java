package ink.hoka.odyssey.server.maps;

import ink.hoka.odyssey.api.ModuleConfig;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.anvil.AnvilLoader;
import net.minestom.server.instance.block.Block;
import net.minestom.server.timer.TaskSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Карты (миры) — папки в maps/: maps/&lt;имя&gt;/region/*.mca (формат Anvil, как у ванильного мира) + map.yml
 * (генерация, точка появления). Основная карта — «world», в неё попадают при входе.
 * Карта загружается при первом обращении, пустая (без игроков) выгружается через 10 минут.
 * Сохранение: раз в 5 минут, при выгрузке и при остановке сервера.
 */
public final class Maps {

    public static final String DEFAULT = "world";
    public static final Pattern NAME = Pattern.compile("[a-z0-9_\\-]{1,32}");
    private static final Logger LOG = LoggerFactory.getLogger("maps");
    private static final long IDLE_UNLOAD_MS = 10 * 60_000;

    /** Тип генерации новой карты. */
    public enum Generator {
        FLAT("плоская: трава на y=40"), VOID("пустота с площадкой 5×5 на y=64");
        public final String desc;
        Generator(String d) { this.desc = d; }
        public String key() { return name().toLowerCase(); }
        public static Generator of(String s) {
            for (Generator g : values()) if (g.key().equalsIgnoreCase(s)) return g;
            return null;
        }
    }

    public static final class GameMap {
        public final String name;
        public final InstanceContainer instance;
        final Path dir;
        final ModuleConfig meta;
        volatile long emptySince = System.currentTimeMillis();

        GameMap(String name, InstanceContainer instance, Path dir, ModuleConfig meta) {
            this.name = name; this.instance = instance; this.dir = dir; this.meta = meta;
        }

        public Pos spawn() {
            return new Pos(meta.getDouble("spawn.x", 0.5), meta.getDouble("spawn.y", 41), meta.getDouble("spawn.z", 0.5),
                    (float) meta.getDouble("spawn.yaw", 0), (float) meta.getDouble("spawn.pitch", 0));
        }

        public void setSpawn(Pos p) {
            meta.set("spawn.x", p.x()); meta.set("spawn.y", p.y()); meta.set("spawn.z", p.z());
            meta.set("spawn.yaw", (double) p.yaw()); meta.set("spawn.pitch", (double) p.pitch());
            meta.save();
        }

        public String generator() { return meta.getString("generator", "flat"); }
    }

    private final Path dir;
    private final Map<String, GameMap> loaded = new ConcurrentHashMap<>();

    public Maps(Path dir) { this.dir = dir; }

    // ───────────────────────────── чтение / загрузка ─────────────────────────────

    /** Все карты в maps/ (загруженные и нет). */
    public SortedSet<String> names() {
        SortedSet<String> out = new TreeSet<>();
        if (Files.isDirectory(dir)) try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(n -> NAME.matcher(n).matches()).forEach(out::add);
        } catch (IOException e) { LOG.warn("не прочитать {}: {}", dir, e.toString()); }
        return out;
    }

    public Optional<GameMap> loadedMap(String name) { return Optional.ofNullable(loaded.get(name)); }

    public Collection<GameMap> loadedMaps() { return loaded.values(); }

    public boolean exists(String name) { return Files.isDirectory(dir.resolve(name)); }

    /** Основная карта (создаётся при первом запуске). */
    public GameMap defaultMap() {
        GameMap m = loaded.get(DEFAULT);
        if (m != null) return m;
        if (!exists(DEFAULT)) return create(DEFAULT, Generator.FLAT);
        return load(DEFAULT);
    }

    /** Карта по имени — загружается при необходимости. null — такой папки нет. */
    public synchronized GameMap load(String name) {
        GameMap m = loaded.get(name);
        if (m != null) return m;
        Path d = dir.resolve(name);
        if (!NAME.matcher(name).matches() || !Files.isDirectory(d)) return null;
        ModuleConfig meta = new ModuleConfig(d.resolve("map.yml"));
        Generator gen = Optional.ofNullable(Generator.of(meta.getString("generator", "flat"))).orElse(Generator.FLAT);
        InstanceContainer inst = MinecraftServer.getInstanceManager().createInstanceContainer(new AnvilLoader(d));
        inst.setChunkSupplier(LightingChunk::new);
        inst.setGenerator(generator(gen));
        m = new GameMap(name, inst, d, meta);
        loaded.put(name, m);
        LOG.info("карта «{}» загружена ({})", name, gen.key());
        return m;
    }

    private static net.minestom.server.instance.generator.Generator generator(Generator g) {
        return switch (g) {
            case FLAT -> unit -> {
                unit.modifier().fillHeight(0, 1, Block.BEDROCK);
                unit.modifier().fillHeight(1, 38, Block.STONE);
                unit.modifier().fillHeight(38, 40, Block.DIRT);
                unit.modifier().fillHeight(40, 41, Block.GRASS_BLOCK);
            };
            case VOID -> unit -> { };
        };
    }

    // ───────────────────────────── создать / удалить ─────────────────────────────

    /** Новая карта. Исключение — если имя занято или неверное. */
    public synchronized GameMap create(String name, Generator gen) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("имя карты: a-z 0-9 _ - (до 32 символов)");
        Path d = dir.resolve(name);
        if (Files.exists(d)) throw new IllegalArgumentException("карта «" + name + "» уже есть");
        try { Files.createDirectories(d); } catch (IOException e) { throw new IllegalStateException("не создать " + d + ": " + e.getMessage()); }
        ModuleConfig meta = new ModuleConfig(d.resolve("map.yml"));
        meta.set("generator", gen.key());
        meta.set("created", new java.util.Date().toString());
        double y = gen == Generator.VOID ? 65 : 41;
        meta.set("spawn.x", 0.5); meta.set("spawn.y", y); meta.set("spawn.z", 0.5);
        meta.set("spawn.yaw", 0.0); meta.set("spawn.pitch", 0.0);
        meta.save();
        GameMap m = load(name);
        if (gen == Generator.VOID) {                                              // площадка, чтобы не упасть
            m.instance.loadChunk(0, 0).thenRun(() -> {
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) m.instance.setBlock(x, 64, z, Block.STONE);
            });
        }
        LOG.info("карта «{}» создана ({})", name, gen.key());
        return m;
    }

    /**
     * Удалить карту: игроков — на основную карту, выгрузить, стереть папку. Основную удалить нельзя.
     * Возвращает будущее, которое завершается после удаления файлов.
     */
    public synchronized CompletableFuture<Void> delete(String name) {
        if (DEFAULT.equals(name)) return CompletableFuture.failedFuture(new IllegalArgumentException("основную карту «world» удалить нельзя"));
        if (!exists(name)) return CompletableFuture.failedFuture(new IllegalArgumentException("карты «" + name + "» нет"));
        GameMap m = loaded.remove(name);
        CompletableFuture<Void> moved = CompletableFuture.completedFuture(null);
        if (m != null) {
            GameMap def = defaultMap();
            List<CompletableFuture<Void>> fs = new ArrayList<>();
            for (Player p : m.instance.getPlayers()) {
                p.sendMessage(net.kyori.adventure.text.Component.text("Карта «" + name + "» удалена — вы на основной карте.", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                fs.add(p.setInstance(def.instance, def.spawn()));
            }
            moved = CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new));
        }
        Path d = dir.resolve(name);
        return moved.thenRun(() -> {
            if (m != null) MinecraftServer.getSchedulerManager().scheduleNextTick(() -> MinecraftServer.getInstanceManager().unregisterInstance(m.instance));
            deleteTree(d);
            LOG.info("карта «{}» удалена", name);
        });
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            LOG.error("не удалить {}: {}", root, e.toString());
        }
    }

    // ───────────────────────────── сохранение / выгрузка ─────────────────────────────

    public CompletableFuture<Void> save(GameMap m) {
        return m.instance.saveInstance().thenCompose(v -> m.instance.saveChunksToStorage())
                .exceptionally(e -> { LOG.error("карта «{}» не сохранена: {}", m.name, e.toString()); return null; });
    }

    /** Сохранить все загруженные и дождаться (при остановке). */
    public void saveAllBlocking() {
        List<CompletableFuture<Void>> fs = new ArrayList<>();
        for (GameMap m : loaded.values()) fs.add(save(m));
        try { CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS); }
        catch (Exception e) { LOG.error("сохранение карт при остановке: {}", e.toString()); }
        LOG.info("карты сохранены: {}", loaded.size());
    }

    /** Автосохранение раз в 5 минут и выгрузка пустых карт (кроме основной) через 10 минут. */
    public void startMaintenance() {
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            for (GameMap m : loaded.values()) save(m);
        }).delay(TaskSchedule.minutes(5)).repeat(TaskSchedule.minutes(5)).schedule();
        MinecraftServer.getSchedulerManager().buildTask(this::unloadIdle).delay(TaskSchedule.minutes(1)).repeat(TaskSchedule.minutes(1)).schedule();
    }

    private void unloadIdle() {
        long now = System.currentTimeMillis();
        for (GameMap m : List.copyOf(loaded.values())) {
            if (DEFAULT.equals(m.name)) continue;
            if (!m.instance.getPlayers().isEmpty()) { m.emptySince = now; continue; }
            if (now - m.emptySince < IDLE_UNLOAD_MS) continue;
            loaded.remove(m.name);
            save(m).thenRun(() -> MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
                if (m.instance.getPlayers().isEmpty()) MinecraftServer.getInstanceManager().unregisterInstance(m.instance);
                LOG.info("карта «{}» выгружена (пустая 10 мин)", m.name);
            }));
        }
    }

    /** Отметить, что на карте кто-то есть (чтобы не выгрузить сразу после входа). */
    public void touch(GameMap m) { m.emptySince = System.currentTimeMillis(); }
}
