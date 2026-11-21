package ink.hoka.odyssey.server;

import ink.hoka.odyssey.api.*;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Контекст одного модуля: запоминает всё, что модуль зарегистрировал (узел событий, команды, задачи, потоки,
 * сервисы), чтобы {@link #cleanup()} снял это при выключении.
 */
final class ModuleContextImpl implements ModuleContext {

    private final ModuleDescriptor descriptor;
    private final ModuleManager manager;
    private final ModuleClassLoader loader;
    private final Path dataFolder;
    private final Logger logger;
    private final EventNode<Event> events;
    private final List<Command> commands = new ArrayList<>();
    private final Set<Task> tasks = ConcurrentHashMap.newKeySet();
    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
    private final ServiceRegistry.View services;
    private final Scheduler scheduler = new Scheduler();
    private ModuleConfig config;
    private volatile boolean closed;

    ModuleContextImpl(ModuleDescriptor d, ModuleManager manager, ModuleClassLoader loader, Path dataFolder, ServiceRegistry services) {
        this.descriptor = d;
        this.manager = manager;
        this.loader = loader;
        this.dataFolder = dataFolder;
        this.logger = LoggerFactory.getLogger(d.id());
        this.events = EventNode.all("module:" + d.id());
        this.services = services.view(d.id());
        MinecraftServer.getGlobalEventHandler().addChild(events);
    }

    @Override public ModuleDescriptor descriptor() { return descriptor; }
    @Override public Logger logger() { return logger; }
    @Override public EventNode<Event> events() { return events; }
    @Override public ModuleScheduler scheduler() { return scheduler; }
    @Override public Services services() { return services; }
    @Override public Optional<OdysseyModule> module(String id) { return manager.enabledModule(id); }
    @Override public OdysseyServerApi server() { return manager.serverApi(); }

    @Override
    public synchronized void registerCommand(Command command) {
        check();
        MinecraftServer.getCommandManager().register(command);
        commands.add(command);
    }

    @Override
    public Path dataFolder() {
        try { Files.createDirectories(dataFolder); } catch (IOException e) { throw new IllegalStateException(e); }
        return dataFolder;
    }

    @Override
    public synchronized ModuleConfig config() {
        if (config == null) {
            Path file = dataFolder().resolve("config.yml");
            if (!Files.exists(file)) {                                     // по умолчанию — config.yml из своего jar
                URL def = loader.findResource("config.yml");
                if (def != null) try (InputStream in = def.openStream()) { Files.copy(in, file); }
                catch (IOException e) { logger.warn("config.yml из jar не скопирован: {}", e.toString()); }
            }
            config = new ModuleConfig(file);
        }
        return config;
    }

    private void check() {
        if (closed) throw new IllegalStateException("модуль " + descriptor.id() + " уже выключен");
    }

    /**
     * Снять всё, что зарегистрировал модуль. Async-потоки прерываются и ждутся до 3 с — после этого загрузчик
     * классов модуля закрывается, и код старой версии работать не должен.
     */
    void cleanup() {
        synchronized (this) {
            closed = true;
            MinecraftServer.getGlobalEventHandler().removeChild(events);
            for (Command c : commands) MinecraftServer.getCommandManager().unregister(c);
            commands.clear();
        }
        for (Task t : tasks) t.cancel();
        tasks.clear();
        List<Thread> live = new ArrayList<>(threads);
        for (Thread t : live) t.interrupt();
        long until = System.nanoTime() + 3_000_000_000L;
        for (Thread t : live) {
            long left = until - System.nanoTime();
            if (left > 0) try { t.join(java.time.Duration.ofNanos(left)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        for (Thread t : live) if (t.isAlive()) logger.warn("async-поток {} не остановился за 3 с — проверьте обработку interrupt", t.getName());
        threads.clear();
        manager.services().removeOwner(descriptor.id());
    }

    private final class Scheduler implements ModuleScheduler {

        private Task track(Task t) {
            tasks.removeIf(x -> !x.isAlive());                                        // отработавшие/отменённые
            tasks.add(t);
            if (closed) { t.cancel(); tasks.remove(t); }                              // гонка с выключением
            return t;
        }

        @Override
        public Task run(Runnable r) {
            check();
            return track(MinecraftServer.getSchedulerManager().buildTask(safe(r)).schedule());
        }

        @Override
        public Task later(Runnable r, int ticks) {
            check();
            return track(MinecraftServer.getSchedulerManager().buildTask(safe(r)).delay(TaskSchedule.tick(Math.max(1, ticks))).schedule());
        }

        @Override
        public Task repeat(Runnable r, int delayTicks, int periodTicks) {
            check();
            return track(MinecraftServer.getSchedulerManager().buildTask(safe(r))
                    .delay(TaskSchedule.tick(Math.max(1, delayTicks))).repeat(TaskSchedule.tick(Math.max(1, periodTicks))).schedule());
        }

        @Override
        public void async(Runnable r) {
            check();
            Thread t = Thread.ofVirtual().name("module-" + descriptor.id() + "-async").unstarted(() -> {
                try { r.run(); } catch (Throwable e) { if (!closed) logger.error("ошибка в async-задаче", e); }
                finally { threads.remove(Thread.currentThread()); }
            });
            threads.add(t);
            if (closed) { threads.remove(t); return; }                                // гонка с выключением
            t.start();
        }

        /** Ошибка в задаче пишется в лог модуля и не роняет планировщик. */
        private Runnable safe(Runnable r) {
            return () -> {
                try { r.run(); } catch (Throwable e) { logger.error("ошибка в задаче", e); }
            };
        }
    }
}
