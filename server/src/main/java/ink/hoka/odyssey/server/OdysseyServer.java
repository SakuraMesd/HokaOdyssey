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
    @Override public java.util.Optional<String> mapName(net.minestom.server.instance.Instance instance) {
        for (var m : maps.loadedMaps()) if (m.instance == instance) return java.util.Optional.of(m.name);
        return java.util.Optional.empty();
    }

    public static void main(String[] args) throws IOException {
        // остановку по сигналу (SIGTERM/Ctrl+C) делаем сами — чтобы модули выключились до остановки Minestom
        System.setProperty("minestom.shutdown-on-signal", "false");
        Path eula = Path.of("eula.txt");                                    // как у Paper; Minestom её не требует
        if (!Files.exists(eula)) Files.writeString(eula, "eula=true\n", StandardCharsets.UTF_8);

        MinecraftServer server = MinecraftServer.init(new Auth.Online());

        // карты — maps/<имя>/ (Anvil); основная — maps/world. Операторы — ops.json
        var maps = new ink.hoka.odyssey.server.maps.Maps(Path.of("maps"));
        var ops = new ink.hoka.odyssey.server.perms.Ops(Path.of("ops.json"));
        OdysseyServer core = new OdysseyServer(maps, ops);
        var main = maps.defaultMap();
        maps.startMaintenance();

        GlobalEventHandler events = MinecraftServer.getGlobalEventHandler();
        // основная карта при входе; модуль может выбрать другую в своём обработчике того же события (он вызывается позже)
        events.addListener(AsyncPlayerConfigurationEvent.class, e -> {
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

        // ванильные правила установки блоков (повороты, соединения, двери/кровати, открывание)
        int rules = ink.hoka.odyssey.server.blocks.BlockRules.registerAll();
        events.addChild(ink.hoka.odyssey.server.blocks.MultiBlocks.node());
        LOG.info("правила установки блоков: {}", rules);

        // модули: включаются до открытия порта — их команды и слушатели готовы к первому игроку
        core.modules = new ModuleManager(Path.of("module"), core);
        cm.register(new ModuleCommand(core.modules, ops));
        registerStop(core);
        Runtime.getRuntime().addShutdownHook(new Thread(core::shutdown, "shutdown-hook"));
        core.modules.loadAll();
        startConsole();

        int port = parsePort(System.getenv("SERVER_PORT"));
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
        try { modules.disableAll(); } catch (Throwable e) { LOG.error("ошибка при выключении модулей", e); }
        try { maps.saveAllBlocking(); } catch (Throwable e) { LOG.error("ошибка при сохранении карт", e); }
        try { MinecraftServer.stopCleanly(); } catch (Throwable e) { LOG.error("ошибка при остановке Minestom", e); }
    }

    /** stop/end из консоли (кнопка «Стоп» в панели): модули выключаются в обратном порядке. */
    private static void registerStop(OdysseyServer core) {
        Command stop = new Command("stop", "end");
        stop.setCondition((sender, cmd) -> !(sender instanceof Player));
        stop.setDefaultExecutor((sender, ctx) -> {
            core.modules.disableAll();                                         // в тике: модули снимают своё спокойно
            Thread.ofPlatform().name("shutdown").start(() -> System.exit(0)); // дальше — shutdown(), не из потока тика
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
