package ink.hoka.odyssey.server;

import ink.hoka.odyssey.api.ModuleDescriptor;
import ink.hoka.odyssey.api.OdysseyServerApi;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Ядро Hoka Odyssey: Minestom (Minecraft 1.21.11) + модули из папки module/ (см. MODULES.md).
 * Порт — из SERVER_PORT (задаёт Pterodactyl), иначе 25565. Вход — с проверкой аккаунтов Mojang.
 */
public final class OdysseyServer implements OdysseyServerApi {

    public static final String VERSION = "0.4.0";
    private static final Logger LOG = LoggerFactory.getLogger("odyssey");
    private static final long START = System.nanoTime();

    private final ink.hoka.odyssey.server.maps.Maps maps;
    private final ink.hoka.odyssey.server.perms.Ops ops;
    private ModuleManager modules;

    private OdysseyServer(ink.hoka.odyssey.server.maps.Maps maps, ink.hoka.odyssey.server.perms.Ops ops) { this.maps = maps; this.ops = ops; }

    @Override public InstanceContainer world() { return maps.defaultMap().instance; }
    @Override public Pos spawn() { return maps.defaultMap().spawn(); }
    @Override public List<ModuleDescriptor> modules() { return modules.enabledDescriptors(); }
    @Override public String version() { return VERSION; }
    @Override public boolean isOp(Player p) { return ops.isOp(p); }
    @Override public java.util.Optional<InstanceContainer> map(String name) {
        var m = maps.load(name);
        return m == null ? java.util.Optional.empty() : java.util.Optional.of(m.instance);
    }
    @Override public java.util.SortedSet<String> mapNames() { return maps.names(); }
    @Override public java.util.SortedSet<String> generatorIds() { return ink.hoka.odyssey.server.tall.gen.GeneratorRegistry.ids(); }
    @Override public java.util.Optional<String> mapName(net.minestom.server.instance.Instance instance) {
        for (var m : maps.loadedMaps()) if (m.instance == instance) return java.util.Optional.of(m.name);
        return java.util.Optional.empty();
    }

    // кубы (§4.10): на ленивых кубовых картах — по-настоящему (CubeStore, шаг 10), иначе поверх столбцов (tall/Cubes)
    @Override public boolean isCubeLoaded(net.minestom.server.instance.Instance i, int x, int y, int z) { return ink.hoka.odyssey.server.tall.Cubes.isCubeLoaded(i, x, y, z); }
    @Override public boolean areCubesLoaded(net.minestom.server.instance.Instance i, net.minestom.server.coordinate.Point a, net.minestom.server.coordinate.Point b) { return ink.hoka.odyssey.server.tall.Cubes.areCubesLoaded(i, a, b); }
    @Override public java.util.concurrent.CompletableFuture<Void> loadCubes(net.minestom.server.instance.Instance i, net.minestom.server.coordinate.Point a, net.minestom.server.coordinate.Point b) { return ink.hoka.odyssey.server.tall.Cubes.loadCubes(i, a, b); }
    @Override public boolean ensureCubes(net.minestom.server.instance.Instance i, net.minestom.server.coordinate.Point a, net.minestom.server.coordinate.Point b) { return ink.hoka.odyssey.server.tall.Cubes.ensureCubes(i, a, b); }
    @Override public void pinCubes(net.minestom.server.instance.Instance i, net.minestom.server.coordinate.Point a, net.minestom.server.coordinate.Point b, Object owner) { ink.hoka.odyssey.server.tall.Cubes.pin(i, a, b, owner); }
    @Override public void unpin(Object owner) { ink.hoka.odyssey.server.tall.Cubes.unpin(owner); }

    public static void main(String[] args) throws IOException {
        // остановку по сигналу (SIGTERM/Ctrl+C) делаем сами — чтобы модули выключились до остановки Minestom
        System.setProperty("minestom.shutdown-on-signal", "false");
        // дальность обзора чанков (столбцов) — 32; -Dminestom.chunk-view-distance=N в строке запуска важнее. До init:
        // Minestom читает её в ServerFlag один раз. Клиент берёт min(свою дальность, эту)
        if (System.getProperty("minestom.chunk-view-distance") == null) System.setProperty("minestom.chunk-view-distance", "32");
        // keepalive раз в 2 с (у Minestom 10 с): сетевой пинг в TAB свежее. Новый шлётся только после ответа на прежний —
        // кика за «чужой id» нет; кик без ответа — как был (minestom.keep-alive-kick, 15 с)
        if (System.getProperty("minestom.keep-alive-delay") == null) System.setProperty("minestom.keep-alive-delay", "2000");
        Path eula = Path.of("eula.txt");                                    // как у Paper; Minestom её не требует
        if (!Files.exists(eula)) Files.writeString(eula, "eula=true\n", StandardCharsets.UTF_8);

        MinecraftServer server = MinecraftServer.init(new Auth.Online());

        // карты — maps/<имя>/ (Anvil); основная — maps/world. Операторы — ops.json
        ink.hoka.odyssey.server.tall.TallWorld.init(Path.of("maps"));     // высокий мир — до карт и до входа игроков
        var maps = new ink.hoka.odyssey.server.maps.Maps(Path.of("maps"));
        var ops = new ink.hoka.odyssey.server.perms.Ops(Path.of("ops.json"));
        OdysseyServer core = new OdysseyServer(maps, ops);
        var main = maps.defaultMap();
        maps.startMaintenance();

        GlobalEventHandler events = MinecraftServer.getGlobalEventHandler();
        // основная карта при входе; модуль может выбрать другую в своём обработчике того же события (он вызывается позже)
        events.addListener(AsyncPlayerConfigurationEvent.class, e -> {
            var via = ink.hoka.odyssey.server.net.ProxyProtocol.proxyOf(e.getPlayer().getPlayerConnection());
            if (via != null) LOG.info("{} вошёл через прокси {}, реальный IP {}", e.getPlayer().getUsername(), via,
                    e.getPlayer().getPlayerConnection().getRemoteAddress());
            if (ink.hoka.odyssey.server.tall.TallWorld.enabled)               // моду: позиции блоков — в формате высокого мира + рукопожатие
                ink.hoka.odyssey.server.tall.Handshake.onConfiguration(e);
            e.setSpawningInstance(main.instance);
            e.getPlayer().setRespawnPoint(main.spawn());
        });
        events.addListener(PlayerSpawnEvent.class, e -> {
            if (!e.isFirstSpawn()) return;
            e.getPlayer().setGameMode(GameMode.CREATIVE);
            ops.apply(e.getPlayer());                                          // OP — уровень прав 4, команды /ho…
        });
        var cm = MinecraftServer.getCommandManager();
        cm.register(new ink.hoka.odyssey.server.maps.HoMapCommand(maps, ops));
        cm.register(new ink.hoka.odyssey.server.perms.HoOpCommand(ops));
        cm.register(new ink.hoka.odyssey.server.tall.HoTallCommand(maps, ops));
        cm.register(new ink.hoka.odyssey.server.tall.HoCubesCommand(maps, ops));
        ink.hoka.odyssey.server.tall.Handshake.register();
        ink.hoka.odyssey.server.tall.CubeStreamer.register();
        DebugTab.register();                                              // ВРЕМЕННО: пинг/TPS/MSPT в TAB (-Dhoka.tab.debug=false — выкл)
        ink.hoka.odyssey.server.tall.CubeEntities.register();             // шаг 10: сущности у незагруженных кубов — заморозка
        ink.hoka.odyssey.server.tall.CubeStore.register();                // шаг 11: выгрузка кубов без интереса

        // ванильные правила установки блоков (повороты, соединения, двери/кровати, открывание)
        int rules = ink.hoka.odyssey.server.blocks.BlockRules.registerAll();
        events.addChild(ink.hoka.odyssey.server.blocks.MultiBlocks.node());
        ink.hoka.odyssey.server.blocks.PickBlock.register(events);           // колёсико — выбрать блок (сервер кладёт предмет)
        LOG.info("правила установки блоков: {}", rules);

        // модули: включаются до открытия порта — их команды и слушатели готовы к первому игроку
        core.modules = new ModuleManager(Path.of("module"), core);
        // генератор планеты модуля в работе (карта загружена / pregen) — модуль не выгружать (MODULES.md «Генератор планеты»)
        core.modules.addUnloadGuard(ink.hoka.odyssey.server.tall.gen.GeneratorRegistry::guard);
        cm.register(new ModuleCommand(core.modules, ops));
        // выброс/подбор предметов — модуль items (tearsmilk); ядро своё не вешает (было: дубль → дюп)
        registerStop(core);
        Runtime.getRuntime().addShutdownHook(new Thread(core::shutdown, "shutdown-hook"));
        core.modules.loadAll();
        ink.hoka.odyssey.server.tall.gen.Pregen.resumeAll(maps.pregenEnv());   // незаконченные pregen — после модулей (их генераторы)
        ink.hoka.odyssey.server.tall.gen.Pregen.registerTickMonitor();
        startConsole();

        int port = parsePort(System.getenv("SERVER_PORT"));
        // HAProxy PROXY protocol (туннель хостинга → настоящий IP): -Dhoka.proxy.trusted=IP,CIDR; пусто — выкл
        ink.hoka.odyssey.server.net.ProxyProtocol.configureFromSystem();
        server.start("0.0.0.0", port);
        LOG.info("Hoka Odyssey {} (Minestom, Minecraft 1.21.11) на порту {}", VERSION, port);
        // по этой строке панель Pterodactyl (яйцо Paper) понимает, что сервер запущен
        System.out.printf("Done (%.3fs)! For help, type \"help\"%n", (System.nanoTime() - START) / 1e9);
    }

    private final java.util.concurrent.atomic.AtomicBoolean stopping = new java.util.concurrent.atomic.AtomicBoolean();

    /** Единая остановка (команда stop → System.exit → хук; или сигнал ОС → хук): модули, затем Minestom. Один раз. */
    private void shutdown() {
        if (!stopping.compareAndSet(false, true)) return;
        LOG.info("Остановка Hoka Odyssey…");
        // карты — ДО модулей: сохранение сверяет кубы с генератором (sameAsGen), а генератор может быть из модуля;
        // второй проход — то, что модули поменяли при выключении (быстро: только изменённые столбцы)
        try { ink.hoka.odyssey.server.tall.gen.Pregen.pauseAll(); } catch (Throwable e) { LOG.error("ошибка при остановке pregen", e); }
        try { maps.saveAllBlocking(); } catch (Throwable e) { LOG.error("ошибка при сохранении карт", e); }
        try { modules.disableAll(); } catch (Throwable e) { LOG.error("ошибка при выключении модулей", e); }
        try { maps.saveAllBlocking(); } catch (Throwable e) { LOG.error("ошибка при сохранении карт", e); }
        try { MinecraftServer.stopCleanly(); } catch (Throwable e) { LOG.error("ошибка при остановке Minestom", e); }
    }

    /** stop/end из консоли (кнопка «Стоп» в панели): модули выключаются в обратном порядке. */
    private static void registerStop(OdysseyServer core) {
        Command stop = new Command("stop", "end");
        stop.setCondition((sender, cmd) -> !(sender instanceof Player));
        stop.setDefaultExecutor((sender, ctx) -> {
            // сначала карты (генератор модуля ещё жив; сохранение ждём не в тике), потом модули — в тике, спокойно;
            // повторное сохранение — в shutdown() после disableAll
            Thread.ofPlatform().name("shutdown").start(() -> {
                try { ink.hoka.odyssey.server.tall.gen.Pregen.pauseAll(); } catch (Throwable e) { LOG.error("pregen при остановке", e); }
                try { core.maps.saveAllBlocking(); } catch (Throwable e) { LOG.error("ошибка при сохранении карт", e); }
                var off = new java.util.concurrent.CompletableFuture<Void>();
                MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
                    try { core.modules.disableAll(); } finally { off.complete(null); }
                });
                try { off.get(30, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { LOG.error("выключение модулей: {}", e.toString()); }
                System.exit(0);                                                // дальше — shutdown()
            });
        });
        MinecraftServer.getCommandManager().register(stop);
    }

    /** Команды из stdin (консоль панели). Выполняются в тике сервера, как у игроков. */
    private static void startConsole() {
        Thread t = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    String cmd = line.trim();
                    if (cmd.isEmpty()) continue;
                    MinecraftServer.getSchedulerManager().scheduleNextTick(() ->
                            MinecraftServer.getCommandManager().execute(MinecraftServer.getCommandManager().getConsoleSender(), cmd));
                }
            } catch (IOException ignored) { }
        }, "console");
        t.setDaemon(true);
        t.start();
    }

    private static int parsePort(String s) {
        try { return s == null ? 25565 : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 25565; }
    }
}
