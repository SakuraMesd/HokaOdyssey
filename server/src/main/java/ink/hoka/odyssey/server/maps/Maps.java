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
        m = load(DEFAULT);
        // основная карта грузится ДО модулей: её генератор обязан быть встроенным (flat/void); планеты — отдельные карты
        if (m == null) throw new IllegalStateException("основная карта «" + DEFAULT + "» не загрузилась — генератор в map.yml "
                + "должен быть встроенным (flat/void), генераторы модулей — только для отдельных карт (см. лог)");
        return m;
    }

    /** Карты, которые сейчас переводятся в другой формат (/hocubes convert) — не загружать. */
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    /**
     * Карты, которые сейчас выгружаются (из loaded уже убраны, но ещё сохраняются): не загружать заново и не
     * конвертировать — иначе второй мир/конвертер прочтёт файлы до последнего сохранения старого.
     */
    private final Set<String> unloading = ConcurrentHashMap.newKeySet();

    /** Занять выгруженную карту под конвертацию: false — загружена, выгружается или уже занята. */
    public synchronized boolean lockForConvert(String name) {
        if (loaded.containsKey(name) || unloading.contains(name)) return false;
        return busy.add(name);
    }

    public void unlockConvert(String name) { busy.remove(name); }

    /** Карта по имени — загружается при необходимости. null — такой папки нет (или карта сейчас конвертируется). */
    public synchronized GameMap load(String name) {
        GameMap m = loaded.get(name);
        if (m != null) return m;
        if (busy.contains(name)) { LOG.warn("карта «{}» сейчас конвертируется — не загружаю", name); return null; }
        if (unloading.contains(name)) { LOG.warn("карта «{}» сейчас выгружается — не загружаю", name); return null; }
        Path d = dir.resolve(name);
        if (!NAME.matcher(name).matches() || !Files.isDirectory(d)) return null;
        ModuleConfig meta = new ModuleConfig(d.resolve("map.yml"));
        Generator gen = Optional.ofNullable(Generator.of(meta.getString("generator", "flat"))).orElse(Generator.FLAT);
        InstanceContainer inst;
        ink.hoka.odyssey.server.tall.gen.CubeGens.Resolved rg = null;
        if (meta.getBoolean("tall", false) && ink.hoka.odyssey.server.tall.TallWorld.key(name) != null && isCubeStore(meta)) {
            rg = ink.hoka.odyssey.server.tall.gen.CubeGens.resolve(name, d, meta);
            if (rg.error() != null) { LOG.error("карта «{}» не загружена: {}", name, rg.error()); return null; }
            // занять генератор сразу (запрет выгрузки модуля); модуль успели выгрузить между resolve и сюда — не грузим
            if (!ink.hoka.odyssey.server.tall.gen.GeneratorRegistry.acquire("загружена карта " + name, rg.gen().provider)) {
                LOG.error("карта «{}» не загружена: генератор «{}» только что выгружен/заменён", name, rg.gen().id());
                return null;
            }
        }
        if (meta.getBoolean("tall", false) && ink.hoka.odyssey.server.tall.TallWorld.key(name) != null) {
            // высокий мир: Anvil не держит такую высоту — своё хранение (maps/<карта>/tall/), свет — свой (TallChunk)
            // store: cubes — регионы кубов (шаг 7, /hocubes convert), .htw из tall/ — запасной источник; иначе файл .htw на столбец
            net.minestom.server.instance.ChunkLoader loader = isCubeStore(meta)
                    ? new ink.hoka.odyssey.server.tall.CubeRegionLoader(d, d.resolve("tall"))
                    : new ink.hoka.odyssey.server.tall.TallLoader(d.resolve("tall"));
            // шаг 10: кубы — по запросу (CubeStore), только на кубовом протоколе (игроку шлются лишь READY-кубы);
            // map.yml cube_lazy: false — по-старому, все кубы столбца при его загрузке
            if (loader instanceof ink.hoka.odyssey.server.tall.CubeRegionLoader cl) {
                cl.setLazy(ink.hoka.odyssey.server.tall.TallWorld.cubesMap(name) && meta.getBoolean("cube_lazy", true));
                // шаг 11: кубы без интереса 30 с — из памяти (только с ленивой загрузкой); map.yml cube_unload: false — держать
                cl.setUnload(meta.getBoolean("cube_unload", true));
                cl.setGen(rg.gen());                                   // генератор планеты (map.yml generator) — до первого столбца
            } else if (!"flat".equals(meta.getString("generator", "flat")))
                LOG.warn("карта «{}» на .htw: генераторы планет — только для store: cubes, здесь всегда flat", name);
            inst = MinecraftServer.getInstanceManager().createInstanceContainer(ink.hoka.odyssey.server.tall.TallWorld.key(name), loader);
            inst.setWorldBorder(ink.hoka.odyssey.server.tall.TallWorld.border());
            // store: cubes — CubeColumn (шаг 9: свой Chunk, кубы разреженно); .htw — TallChunk, как раньше
            if (isCubeStore(meta)) inst.setChunkSupplier(ink.hoka.odyssey.server.tall.CubeColumn::generated);
            else inst.setChunkSupplier(ink.hoka.odyssey.server.tall.TallChunk::generated);   // свой свет, разреженные секции, своя генерация
            if (ink.hoka.odyssey.server.tall.TallWorld.cubesMap(name)) ink.hoka.odyssey.server.tall.TallWorld.markCubes(inst);
            // генератор Minestom не ставим: он пишет во все 1876 секций (TallChunk.generated генерирует сам)
        } else {
            inst = MinecraftServer.getInstanceManager().createInstanceContainer(new AnvilLoader(d));
            inst.setChunkSupplier(LightingChunk::new);
            inst.setGenerator(generator(gen));
        }
        m = new GameMap(name, inst, d, meta);
        loaded.put(name, m);
        if (rg != null) {
            ink.hoka.odyssey.server.tall.gen.CubeGens.markSeen(name, meta, rg);
        }
        LOG.info("карта «{}» загружена ({}{})", name, rg != null ? rg.gen().tag() : gen.key(), isCubeStore(meta)
                ? ", регионы кубов" + (ink.hoka.odyssey.server.tall.CubeStore.lazy(inst) ? ", кубы по запросу" : "")
                        + (ink.hoka.odyssey.server.tall.CubeStore.unloading(inst) ? " и выгрузка" : "") : "");
        return m;
    }

    /** Карта хранится регионами кубов (map.yml {@code store: cubes}). */
    static boolean isCubeStore(ModuleConfig meta) { return "cubes".equalsIgnoreCase(meta.getString("store", "htw")); }

    /** Папка карты (для конвертера /hocubes convert). */
    public Path dir(String name) { return dir.resolve(name); }

    /** map.yml карты (для конвертера: store: cubes). */
    public ModuleConfig meta(String name) { return new ModuleConfig(dir.resolve(name).resolve("map.yml")); }

    /** После выгрузки карты: закрыть её файлы регионов (иначе их не переименовать/удалить). */
    private static void closeFiles(GameMap m) {
        if (m.instance.getChunkLoader() instanceof ink.hoka.odyssey.server.tall.CubeRegionLoader l) l.close();
        ink.hoka.odyssey.server.tall.Cubes.forget(m.instance);                // закрепления кубов выгруженной карты
        ink.hoka.odyssey.server.tall.gen.GeneratorRegistry.release("загружена карта " + m.name);   // модуль-генератор можно перезагружать
    }

    /** Генератор загруженной карты (для pregen/gentest/size) или null. */
    public ink.hoka.odyssey.server.tall.gen.ModuleCubeGen liveGen(String name) {
        GameMap m = loaded.get(name);
        return m == null ? null : ink.hoka.odyssey.server.tall.gen.CubeGens.module(m.instance);
    }

    /** Карта загружена или выгружается (её файлы ещё открыты). */
    public boolean inUse(String name) { return loaded.containsKey(name) || unloading.contains(name); }

    /** Окружение для /hocubes pregen: папки, map.yml, замок как у convert, генератор загруженной карты. */
    public ink.hoka.odyssey.server.tall.gen.Pregen.Env pregenEnv() {
        Maps self = this;
        return new ink.hoka.odyssey.server.tall.gen.Pregen.Env() {
            @Override public Path dir(String map) { return self.dir(map); }
            @Override public ModuleConfig meta(String map) { return self.meta(map); }
            @Override public boolean lock(String map) { return self.lockForConvert(map); }
            @Override public void unlock(String map) { self.unlockConvert(map); }
            @Override public ink.hoka.odyssey.server.tall.gen.ModuleCubeGen liveGen(String map) { return self.liveGen(map); }
            @Override public java.util.SortedSet<String> names() { return self.names(); }
        };
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
        meta.set("seed", new java.security.SecureRandom().nextLong());       // сид генератора (высокие карты-планеты)
        meta.set("created", new java.util.Date().toString());
        double y = gen == Generator.VOID ? 65 : 41;
        meta.set("spawn.x", 0.5); meta.set("spawn.y", y); meta.set("spawn.z", 0.5);
        meta.set("spawn.yaw", 0.0); meta.set("spawn.pitch", 0.0);
        meta.save();
        GameMap m = load(name);
        if (gen == Generator.VOID) {                                              // площадка, чтобы не упасть
            ink.hoka.odyssey.server.tall.Cubes.loadCubes(m.instance, new net.minestom.server.coordinate.Vec(-2, 64, -2), new net.minestom.server.coordinate.Vec(2, 64, 2)).thenRun(() -> {
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
            if (m != null) { closeFiles(m); MinecraftServer.getSchedulerManager().scheduleNextTick(() -> MinecraftServer.getInstanceManager().unregisterInstance(m.instance)); }
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
        // раз в 10 с (было 30): в быстром полёте столбцы без кубов копились десятками тысяч, а тик Minestom обходит все
        MinecraftServer.getSchedulerManager().buildTask(this::reapColumns).delay(TaskSchedule.seconds(10)).repeat(TaskSchedule.seconds(10)).schedule();
        // выгрузка — порциями каждый тик (см. reapColumns)
        MinecraftServer.getSchedulerManager().buildTask(this::drainReap).repeat(TaskSchedule.tick(1)).schedule();
    }

    private void unloadIdle() {
        long now = System.currentTimeMillis();
        for (GameMap m : List.copyOf(loaded.values())) {
            if (DEFAULT.equals(m.name)) continue;
            if (!m.instance.getPlayers().isEmpty()) { m.emptySince = now; continue; }
            if (now - m.emptySince < IDLE_UNLOAD_MS) continue;
            synchronized (this) { if (loaded.remove(m.name) == null) continue; unloading.add(m.name); }
            save(m).thenRun(() -> MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
                try {
                    if (!m.instance.getPlayers().isEmpty() || unsaved(m) > 0) {   // зашли/не сохранилось — карта остаётся
                        loaded.put(m.name, m);
                        m.emptySince = System.currentTimeMillis();
                        return;
                    }
                    MinecraftServer.getInstanceManager().unregisterInstance(m.instance);
                    closeFiles(m);
                    LOG.info("карта «{}» выгружена (пустая 10 мин)", m.name);
                } finally { unloading.remove(m.name); }
            }));
        }
    }

    /**
     * Выгрузить карту сейчас (/homap unload): игроков — на основную, сохранить, убрать из памяти. Основную нельзя.
     * Будущее завершается, когда карта выгружена и её файлы закрыты.
     */
    public synchronized CompletableFuture<Void> unload(String name) {
        if (DEFAULT.equals(name)) return CompletableFuture.failedFuture(new IllegalArgumentException("основную карту «world» выгрузить нельзя"));
        GameMap m = loaded.remove(name);
        if (m == null) return CompletableFuture.failedFuture(new IllegalArgumentException("карта «" + name + "» не загружена"));
        unloading.add(name);
        GameMap def = defaultMap();
        List<CompletableFuture<Void>> fs = new ArrayList<>();
        for (Player p : m.instance.getPlayers()) {
            p.sendMessage(net.kyori.adventure.text.Component.text("Карта «" + name + "» выгружена — вы на основной карте.", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
            fs.add(p.setInstance(def.instance, def.spawn()));
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)).thenCompose(v -> save(m)).whenComplete((v, e) ->
                MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
                    try {
                        int left = unsaved(m);
                        if (left > 0 || !m.instance.getPlayers().isEmpty()) {   // не всё записалось — из памяти не убираем
                            loaded.put(m.name, m);
                            done.completeExceptionally(new IllegalStateException("не сохранено столбцов: " + left
                                    + " (см. лог) — карта оставлена загруженной"));
                            return;
                        }
                        MinecraftServer.getInstanceManager().unregisterInstance(m.instance);
                        closeFiles(m);
                        LOG.info("карта «{}» выгружена командой", m.name);
                        done.complete(null);
                    } catch (Throwable t) { done.completeExceptionally(t); }
                    finally { unloading.remove(m.name); }
                }));
        return done;
    }

    /** Столбцов высоких карт, правки которых ещё не на диске (сохранение не удалось). */
    private static int unsaved(GameMap m) {
        int n = 0;
        for (var ch : m.instance.getChunks())
            if (ch instanceof ink.hoka.odyssey.server.tall.TallColumn c && c.isModified()) n++;
        return n;
    }

    /** Столбцов высоких карт выгружено всего / пропущено из-за сущностей (для /hocubes stats); чанков обычных карт выгружено. */
    public static volatile long columnsReaped, columnsKeptForEntities, plainReaped;
    /** Сейчас в очереди выгрузки (сохранены/сохраняются, ждут порции). */
    public static int reapQueued() { return REAP_N.get(); }

    /** Выгрузок столбцов за тик не больше: -Dhoka.maps.reap-per-tick (по умолчанию 48). */
    static final int REAP_PER_TICK = Math.max(1, Integer.getInteger("hoka.maps.reap-per-tick", 48));
    /** Обычная карта: чанк без игроков рядом дольше этого — из памяти. */
    static final long PLAIN_IDLE_MS = 60_000;
    /** Основная карта: чанки вокруг точки появления (Чебышёв) не выгружаем — вход без ожидания. */
    static final int SPAWN_KEEP = 2;

    /** Сохранённый столбец, ждущий выгрузки (порциями в тике, drainReap). */
    private record Reap(GameMap m, net.minestom.server.instance.Chunk c, boolean byCubes) {}
    private final java.util.concurrent.ConcurrentLinkedQueue<Reap> reapQ = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger REAP_N = new java.util.concurrent.atomic.AtomicInteger();
    /** Столбцы в сохранении/очереди — второй раз не ставить. */
    private final Set<net.minestom.server.instance.Chunk> reapPending = ConcurrentHashMap.newKeySet();
    /** Обычные карты: когда чанк последний раз был рядом с игроком (только поток планировщика). */
    private final Map<net.minestom.server.instance.Chunk, Long> plainSeen = new WeakHashMap<>();

    /**
     * Раз в 10 с — кандидаты на выгрузку; сохраняются (в фоне), а выгружает их тик порциями по REAP_PER_TICK
     * (drainReap): каждая выгрузка в Minestom — ArrayList.remove и ArrayDeque.remove по всем партициям
     * (ThreadDispatcherImpl.processUnloadedPartition), тысяча разом — O(n²), длинный тик (s55: 23% длинных тиков).
     * <p>Высокие карты: столбец, который никто не видит дольше минуты, — из памяти (изменённый сначала на диск). Не
     * трогаем полосу верфи кораблей (якоря и плоты) и столбцы с сущностями (выгрузка Minestom их удалила бы).
     * Карта с выгрузкой кубов (шаг 11): ленивый столбец — когда в нём не осталось кубов (их выгрузил CubeStore),
     * нет игроков-зрителей и сущностей; минуту не ждём — кубы уже прождали своё.</p>
     * <p>Обычные карты (Anvil, LightingChunk): чанк без игроков рядом дольше минуты — тоже (было: основная карта
     * держала ~4,5 тыс. чанков от каждого входа навсегда, их перебирал тик Minestom и писало автосохранение).</p>
     */
    private void reapColumns() {
        long now = System.currentTimeMillis();
        for (GameMap m : List.copyOf(loaded.values())) {
            var inst = m.instance;
            for (var ch : List.copyOf(inst.getChunks())) {
                if (reapPending.contains(ch)) continue;
                if (!(ch instanceof ink.hoka.odyssey.server.tall.TallColumn tc)) { reapPlain(m, ch, now); continue; }
                var c = ch;
                if (c.getChunkX() * 16 >= ink.hoka.odyssey.server.tall.TallChunk.YARD_X0) continue;
                if (ink.hoka.odyssey.server.tall.Cubes.isColumnPinned(inst, c.getChunkX(), c.getChunkZ())) continue;   // закреплено (корабль…)
                if (c instanceof ink.hoka.odyssey.server.tall.CubeColumn cc && cc.busy()) continue;   // кубы грузятся / их ждут
                // не только getViewers(): кольцо 33 обзора игрока в зрителях столбца не числится (CHUNK_VIEW_DISTANCE 32),
                // а скелет у клиента есть — выгрузка оставляла бы у него пустоту, которую Minestom уже не перешлёт
                if (ink.hoka.odyssey.server.tall.CubeStreamer.watched(tc)) { ink.hoka.odyssey.server.tall.TallWorld.seen(tc, now); continue; }
                boolean byCubes = c instanceof ink.hoka.odyssey.server.tall.CubeColumn cc && cc.isLazy()
                        && ink.hoka.odyssey.server.tall.CubeStore.unloading(inst);
                if (byCubes) { if (!((ink.hoka.odyssey.server.tall.CubeColumn) c).noCubes()) continue; }   // кубы ещё в памяти
                else if (now - ink.hoka.odyssey.server.tall.TallWorld.seenAt(tc) < 60_000) continue;
                if (!inst.getChunkEntities(c).isEmpty()) { columnsKeptForEntities++; ink.hoka.odyssey.server.tall.TallWorld.seen(tc, now); continue; }
                enqueueReap(m, c, byCubes);
            }
        }
    }

    /** Обычная карта: чанк без игроков рядом минуту (кроме точки появления основной) и без сущностей — в выгрузку. */
    private void reapPlain(GameMap m, net.minestom.server.instance.Chunk c, long now) {
        if (DEFAULT.equals(m.name)) {
            Pos sp = m.spawn();
            if (Math.max(Math.abs(c.getChunkX() - (sp.blockX() >> 4)), Math.abs(c.getChunkZ() - (sp.blockZ() >> 4))) <= SPAWN_KEEP) return;
        }
        if (ink.hoka.odyssey.server.tall.CubeStreamer.nearAnyPlayer(m.instance, c.getChunkX(), c.getChunkZ())) { plainSeen.put(c, now); return; }
        Long seen = plainSeen.get(c);
        if (seen == null) { plainSeen.put(c, now); return; }               // первый обход — отсчёт с него
        if (now - seen < PLAIN_IDLE_MS) return;
        if (!m.instance.getChunkEntities(c).isEmpty()) { plainSeen.put(c, now); return; }
        enqueueReap(m, c, false);
    }

    private void enqueueReap(GameMap m, net.minestom.server.instance.Chunk c, boolean byCubes) {
        if (!reapPending.add(c)) return;
        m.instance.saveChunkToStorage(c).whenComplete((v, e) -> {
            if (e != null) {
                reapPending.remove(c);
                LOG.warn("карта «{}»: столбец {} {} не сохранён перед выгрузкой: {}", m.name, c.getChunkX(), c.getChunkZ(), e.toString());
                return;
            }
            reapQ.add(new Reap(m, c, byCubes));
            REAP_N.incrementAndGet();
        });
    }

    /** Тик: выгрузить до REAP_PER_TICK сохранённых столбцов (проверки — заново: за время сохранения всё могло смениться). */
    private void drainReap() {
        int done = 0;
        for (int polled = 0; done < REAP_PER_TICK && polled < 4 * REAP_PER_TICK; polled++) {   // отказы дёшевы, но не бесконечно
            Reap r = reapQ.poll();
            if (r == null) return;
            REAP_N.decrementAndGet();
            reapPending.remove(r.c());
            try { if (unloadReaped(r)) done++; }
            catch (RuntimeException ex) { LOG.warn("выгрузка столбца {} {}: {}", r.c().getChunkX(), r.c().getChunkZ(), ex.toString()); }
        }
    }

    private boolean unloadReaped(Reap r) {
        var c = r.c();
        var inst = r.m().instance;
        if (!c.isLoaded() || loaded.get(r.m().name) != r.m()) return false;   // карта выгружена/заменена — не трогать
        if (!inst.getChunkEntities(c).isEmpty()) return false;
        if (c instanceof ink.hoka.odyssey.server.tall.TallColumn tc) {
            if (ink.hoka.odyssey.server.tall.CubeStreamer.watched(tc) || tc.isModified()) return false;
            if (ink.hoka.odyssey.server.tall.Cubes.isColumnPinned(inst, c.getChunkX(), c.getChunkZ())) return false;
            if (c instanceof ink.hoka.odyssey.server.tall.CubeColumn cc && cc.busy()) return false;
            if (r.byCubes() && !((ink.hoka.odyssey.server.tall.CubeColumn) c).noCubes()) return false;   // куб успели загрузить
            inst.unloadChunk(c);
            columnsReaped++;
        } else {
            // флага «изменён» у обычного чанка нет: подошёл игрок (мог править) — не выгружать, сохранится позже
            if (ink.hoka.odyssey.server.tall.CubeStreamer.nearAnyPlayer(inst, c.getChunkX(), c.getChunkZ())) return false;
            inst.unloadChunk(c);
            plainReaped++;
        }
        return true;
    }

    /** Отметить, что на карте кто-то есть (чтобы не выгрузить сразу после входа). */
    public void touch(GameMap m) { m.emptySince = System.currentTimeMillis(); }
}
