package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.ModuleConfig;
import ink.hoka.odyssey.server.tall.CubeRegionLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Предгенерация карты (/hocubes pregen). Единица работы — регион 32×32 столбца (как cols/ и genidx/), обход —
 * спиралью от центра, отдельный пул платформенных потоков MIN_PRIORITY (виртуальные заняли бы носители, общие с
 * загрузчиками кубов CubeStore), регулятор по длине тика: &gt;35 мс — меньше потоков, &gt;45 мс — пауза, &lt;25 мс — больше.
 * <ul>
 *   <li>детерминированный генератор: на диск только индекс genidx/*.hgi (пусто/однородно/высоты), кубы НЕ пишутся;</li>
 *   <li>недетерминированный: карта должна быть выгружена (замок как у convert), непустые кубы пишутся в регионы.</li>
 * </ul>
 * Прогресс — pregen.yml в папке карты (раз в 5 с и при паузе); при старте сервера state: running продолжается сам.
 */
public final class Pregen {

    private static final Logger LOG = LoggerFactory.getLogger("pregen");
    static final Map<String, Pregen> ACTIVE = new ConcurrentHashMap<>();

    /** Что pregen нужно от карт (Maps.pregenEnv). */
    public interface Env {
        Path dir(String map);
        ModuleConfig meta(String map);
        /** Занять выгруженную карту (как convert); false — загружена/занята. */
        boolean lock(String map);
        void unlock(String map);
        /** Генератор загруженной карты (чтобы сбросить кэш её индекса) или null. */
        ModuleCubeGen liveGen(String map);
        SortedSet<String> names();
    }

    public enum State { RUNNING, PAUSED, STOPPED, DONE, FAILED }

    public final String map;
    private final Path dir, file;
    private final Env env;
    final PregenProgress prog;
    private final long[] units;
    private final long totalColumns;
    private volatile State state = State.RUNNING;
    private volatile String error;
    private final Consumer<String> out;

    private ModuleCubeGen gen;
    private GenIndexBuilder builder;
    private boolean heights, locked;
    private CubeRegionLoader writer;
    private int cy0, cy1;

    private final AtomicInteger next = new AtomicInteger();
    private final AtomicLong columns = new AtomicLong(), bytes = new AtomicLong(), cubesWritten = new AtomicLong();
    private final AtomicInteger regions = new AtomicInteger();
    private long t0, cols0;
    private final List<Thread> workers = new ArrayList<>();

    // регулятор
    private final Object tl = new Object();
    private volatile int allowed;
    private int active;
    private volatile boolean tickPause;

    private Pregen(String map, Env env, PregenProgress prog, Consumer<String> out) {
        this.map = map;
        this.env = env;
        this.dir = env.dir(map);
        this.file = dir.resolve("pregen.yml");
        this.prog = prog;
        this.out = out;
        int rx0 = Math.floorDiv(Math.min(prog.x1, prog.x2), 32), rx1 = Math.floorDiv(Math.max(prog.x1, prog.x2), 32);
        int rz0 = Math.floorDiv(Math.min(prog.z1, prog.z2), 32), rz1 = Math.floorDiv(Math.max(prog.z1, prog.z2), 32);
        this.units = Spiral.order(rx0, rz0, rx1, rz1, Math.floorDiv(prog.centerX, 32), Math.floorDiv(prog.centerZ, 32));
        this.totalColumns = (long) (Math.abs(prog.x2 - prog.x1) + 1) * (Math.abs(prog.z2 - prog.z1) + 1);
    }

    // ───────────── запуск ─────────────

    /** Число потоков по умолчанию: половина ядер, но оставить тику и загрузчикам хотя бы 2. */
    public static int defaultThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(cores / 2, cores - 2));
    }

    /** Новый pregen (или продолжение). Ответ — текст ошибки или null. */
    public static synchronized String start(String map, Env env, PregenProgress p, Consumer<String> out) {
        if (ACTIVE.containsKey(map)) return "pregen «" + map + "» уже идёт — /hocubes pregen status|pause|stop " + map;
        Pregen pg = new Pregen(map, env, p, out);
        String err = pg.open();
        if (err != null) return err;
        ACTIVE.put(map, pg);
        pg.launch();
        return null;
    }

    /** Собрать генератор, проверить режим. */
    private String open() {
        ModuleConfig meta = env.meta(map);
        if (!meta.getBoolean("tall", false) || !"cubes".equalsIgnoreCase(meta.getString("store", "htw")))
            return "«" + map + "» — не высокая карта на регионах кубов (tall: true, store: cubes)";
        CubeGens.Resolved r = CubeGens.resolve(map, dir, meta);
        if (r.error() != null) return r.error();
        gen = r.gen();
        // занять генератор (запрет выгрузки модуля) и убедиться, что модуль не выгрузили, пока собирали
        if (!GeneratorRegistry.acquire("идёт pregen " + map, gen.provider))
            return "генератор «" + gen.id() + "» только что выгружен/заменён — повтори";
        String err;
        try { err = check(); }
        catch (RuntimeException e) { err = "генератор «" + gen.id() + "» упал: " + e; }
        if (err != null) { GeneratorRegistry.release("идёт pregen " + map); return err; }
        return null;
    }

    /** Проверки после захвата генератора (ошибка — вызывающий отпускает генератор). */
    private String check() {
        String tag = gen.tag();
        if (!prog.gen.isEmpty() && !prog.gen.equals(tag)) return "pregen.yml от генератора " + prog.gen + ", а сейчас " + tag
                + " — /hocubes pregen stop " + map + " и запусти заново";
        if (!prog.gen.isEmpty() && prog.seed != gen.settings.seed()) return "pregen.yml с другим сидом (" + prog.seed
                + ") — /hocubes pregen stop " + map + " и запусти заново";
        // тот же id@версия, а код генератора поменяли: готовые по pregen.yml регионы посчитаны старым — их .hgi ядро
        // не примет (отпечаток), а продолжение их бы пропустило
        if (gen.deterministic()) {
            int fp = gen.fingerprint();
            if (prog.fp != null && prog.fp != fp) return "генератор " + tag + " изменился без смены version (отпечаток " + prog.fp + " → " + fp
                    + ") — увеличь version в модуле или /hocubes pregen stop " + map + " и запусти заново";
            prog.fp = fp;
        }
        prog.gen = tag;
        prog.seed = gen.settings.seed();
        cy0 = gen.minCy; cy1 = gen.maxCy;
        if (!gen.deterministic()) {
            if (prog.ymin != null && prog.ymax != null) { cy0 = Math.max(cy0, prog.ymin >> 4); cy1 = Math.min(cy1, prog.ymax >> 4); }
            if (!env.lock(map)) return "генератор " + gen.id() + " недетерминированный — кубы пишутся на диск, карта должна быть выгружена: /homap unload " + map;
            locked = true;
            writer = new CubeRegionLoader(dir, null);
            writer.setGen(gen);
        } else {
            heights = GenIndexBuilder.needHeights(gen);
            builder = new GenIndexBuilder(gen, heights);
        }
        return null;
    }

    private void launch() {
        int n = Math.max(1, prog.threads > 0 ? prog.threads : defaultThreads());
        prog.threads = n;
        prog.state = "running";
        allowed = n;
        t0 = System.currentTimeMillis();
        cols0 = 0;
        say("pregen «" + map + "»: " + gen.tag() + (gen.deterministic() ? (heights ? ", индекс full (с высотами)" : ", индекс lite")
                : ", НЕдетерминированный — пишу кубы") + ", регионов " + units.length + " (готово " + prog.doneCount() + "), потоков " + n);
        for (int i = 0; i < n; i++) {
            Thread t = Thread.ofPlatform().name("pregen-" + map + "-" + i).daemon(true).priority(Thread.MIN_PRIORITY).unstarted(this::work);
            workers.add(t);
        }
        if (gen.deterministic() && prog.ymin != null)
            say("ymin/ymax для детерминированного генератора не нужны: индекс всегда на всю высоту планеты (" + (gen.minCy << 4) + "…" + ((gen.maxCy << 4) + 15) + ")");
        for (Thread t : workers) t.start();
        Thread.ofPlatform().name("pregen-" + map + "-report").daemon(true).start(this::report);
    }

    private void work() {
        while (state == State.RUNNING) {
            int i = next.getAndIncrement();
            if (i >= units.length) return;
            if (prog.isDone(i)) continue;
            if (!acquire()) return;
            try {
                region(Spiral.x(units[i]), Spiral.z(units[i]));
                prog.markDone(i);
                regions.incrementAndGet();
            } catch (Halted e) {
                return;                                                     // пауза/стоп посреди региона — он не отмечен, продолжится с него
            } catch (GenIndexBuilder.NotDeterministic e) {
                fail("остановлен: " + e.getMessage());
                return;
            } catch (Throwable e) {
                LOG.error("pregen {}: регион {} {}", map, Spiral.x(units[i]), Spiral.z(units[i]), e);
                fail("остановлен: регион " + Spiral.x(units[i]) + " " + Spiral.z(units[i]) + ": " + e);
                return;
            } finally { release(); }
        }
    }

    // ───────────── регион ─────────────

    /** Пауза/стоп посреди региона. */
    private static final class Halted extends RuntimeException {
        Halted() { super(null, null, false, false); }
    }

    /** Столбцы региона (rx, rz) внутри области. */
    void region(int rx, int rz) throws IOException {
        int ax0 = Math.min(prog.x1, prog.x2), ax1 = Math.max(prog.x1, prog.x2);
        int az0 = Math.min(prog.z1, prog.z2), az1 = Math.max(prog.z1, prog.z2);
        int x0 = Math.max(rx * 32, ax0), x1 = Math.min(rx * 32 + 31, ax1);
        int z0 = Math.max(rz * 32, az0), z1 = Math.min(rz * 32 + 31, az1);
        if (writer != null) {
            for (int x = x0; x <= x1 && state == State.RUNNING; x++) for (int z = z0; z <= z1; z++) {
                int n = writer.writeGenerated(x, z, cy0, cy1);
                if (n > 0) cubesWritten.addAndGet(n);
                columns.incrementAndGet();
            }
            if (state != State.RUNNING) throw new Halted();
            checkDisk();
            return;
        }
        Path gd = dir.resolve("genidx"), p = GenIndexFile.path(gd, rx, rz);
        GenIndexFile.Header h = new GenIndexFile.Header(gen.provider.id(), gen.provider.version(), gen.settings.seed(),
                gen.fingerprint(), gen.minCy, gen.maxCy, heights);
        GenIndexFile f = null;
        long old = 0;
        if (Files.isRegularFile(p)) {
            try {
                GenIndexFile e = GenIndexFile.read(p);
                old = Files.size(p);
                if (e.header.sameGen(h) && (!heights || e.header.heights())) f = e;   // дописываем к своему
            } catch (IOException e) { LOG.warn("pregen {}: {} битый — пересчитаю ({})", map, p.getFileName(), e.getMessage()); }
        }
        if (f == null) f = new GenIndexFile(h);
        boolean changed = false;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (state != State.RUNNING) throw new Halted();                 // регион не отмечен — продолжится с него
            if (f.col(x, z) != null) continue;
            f.put(x, z, builder.column(f, x, z));
            columns.incrementAndGet();
            changed = true;
        }
        if (changed) {
            long nb = f.write(p);
            bytes.addAndGet(nb - old);
            ModuleCubeGen live = env.liveGen(map);
            if (live != null && live.index() != null) live.index().invalidate(rx, rz);
        }
    }

    /** Недетерминированный: прогноз объёма против свободного места (80%). */
    private void checkDisk() throws IOException {
        long done = columns.get();
        if (done < 1024 || regions.get() % 16 != 0) return;
        long now = DiskEstimate.dirBytes(dir.resolve("cubes")) + DiskEstimate.dirBytes(dir.resolve("cols"));
        double perCol = (double) now / Math.max(1, done + cols0);
        long left = totalColumns - done;
        long need = (long) (perCol * left);
        long free = Files.getFileStore(dir).getUsableSpace();
        if (need > free * 0.8) throw new IOException(String.format("прогноз ещё %.1f ГБ > 80%% свободного (%.1f ГБ) — остановлен",
                need / 1e9, free / 1e9));
    }

    // ───────────── регулятор ─────────────

    private boolean acquire() {
        synchronized (tl) {
            while ((active >= allowed || tickPause) && state == State.RUNNING) {
                try { tl.wait(500); } catch (InterruptedException e) { return false; }
            }
            if (state != State.RUNNING) return false;
            active++;
            return true;
        }
    }

    private void release() { synchronized (tl) { active--; tl.notifyAll(); } }

    private long lastAdjust;

    /** Длина тика (мс) — подстроить число работающих потоков (не чаще раза в секунду). */
    void onTick(double ms) {
        long now = System.currentTimeMillis();
        if (now - lastAdjust < 1000) return;
        lastAdjust = now;
        synchronized (tl) {
            if (ms > 45) tickPause = true;
            else if (ms > 35) { tickPause = false; allowed = Math.max(1, allowed - 1); }
            else if (ms < 25) { tickPause = false; allowed = Math.min(prog.threads, allowed + 1); }
            else tickPause = false;
            tl.notifyAll();
        }
    }

    private static volatile boolean monitor;

    /** Слушатель длины тика (один на сервер). */
    public static void registerTickMonitor() {
        if (monitor) return;
        monitor = true;
        net.minestom.server.MinecraftServer.getGlobalEventHandler().addListener(net.minestom.server.event.server.ServerTickMonitorEvent.class, e -> {
            if (ACTIVE.isEmpty()) return;
            double ms = e.getTickMonitor().getTickTime();
            for (Pregen p : ACTIVE.values()) p.onTick(ms);
        });
    }

    // ───────────── отчёт, пауза, стоп ─────────────

    private void report() {
        long lastSave = 0, lastSay = System.currentTimeMillis();
        while (true) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            boolean alive = workers.stream().anyMatch(Thread::isAlive);
            long now = System.currentTimeMillis();
            if (now - lastSave >= 5000 || !alive) { save(); lastSave = now; }
            if (!alive) { finish(); return; }
            if (now - lastSay >= 10_000) { say(status()); lastSay = now; }
        }
    }

    /** Все потоки вышли: итог. */
    private void finish() {
        synchronized (tl) {
            if (state == State.RUNNING && prog.doneCount() >= units.length) state = State.DONE;
        }
        closeWriter();
        switch (state) {                                                    // файлы — до снятия из ACTIVE (новый start увидит итог)
            case DONE -> { del(); say("pregen «" + map + "» готов: " + status()); }
            case STOPPED -> { del(); say("pregen «" + map + "» остановлен (готовые индексы остаются в силе)"); }
            case PAUSED -> { save(); say("pregen «" + map + "» на паузе: " + status() + " — /hocubes pregen resume " + map); }
            case FAILED -> { save(); say("pregen «" + map + "» " + error); }
            default -> { save(); say("pregen «" + map + "» прерван: " + status()); }
        }
        GeneratorRegistry.release("идёт pregen " + map);
        synchronized (Pregen.class) { ACTIVE.remove(map, this); }
    }

    /** Недетерминированный: закрыть файлы регионов и отпустить карту (один раз; и из finish, и при остановке сервера). */
    private synchronized void closeWriter() {
        if (!locked) return;
        locked = false;
        try { writer.close(); } finally { env.unlock(map); }
    }

    private void fail(String why) {
        synchronized (tl) {
            if (state == State.RUNNING) { state = State.FAILED; error = why; prog.state = "paused"; }
            tl.notifyAll();
        }
    }

    private void del() { try { Files.deleteIfExists(file); } catch (IOException ignored) { } }

    private synchronized void save() {
        if (state == State.DONE || state == State.STOPPED) return;
        try { prog.save(file); } catch (IOException e) { LOG.warn("pregen {}: pregen.yml не записан: {}", map, e.toString()); }
    }

    /** Строка состояния: регионы, столбцы/с, мс на куб, ETA, байт. */
    public String status() {
        long cols = columns.get(), secs = Math.max(1, (System.currentTimeMillis() - t0) / 1000);
        int done = prog.doneCount();
        double rate = cols / (double) secs;
        long colsLeft = Math.max(0, totalColumns - (long) done * 1024);
        long eta = rate > 0 ? (long) (colsLeft / rate) : -1;
        String perCube = builder != null && builder.cubes.get() > 0
                ? String.format(Locale.ROOT, ", куб %.2f мс (%d кубов)", builder.nanos.get() / 1e6 / builder.cubes.get(), builder.cubes.get()) : "";
        String disk = writer != null ? ", кубов записано " + cubesWritten.get() : String.format(Locale.ROOT, ", индекс +%.1f МБ", bytes.get() / 1048576.0);
        return String.format(Locale.ROOT, "%s: %d/%d рег. (%.1f%%), %.0f стб/с%s, ETA %s%s, потоков %d%s",
                map, done, units.length, 100.0 * done / Math.max(1, units.length), rate, perCube, eta < 0 ? "?" : dur(eta), disk,
                allowed, tickPause ? " (пауза: тик тяжёлый)" : "");
    }

    static String dur(long s) {
        if (s >= 3600) return s / 3600 + "ч" + (s % 3600) / 60 + "м";
        if (s >= 60) return s / 60 + "м" + s % 60 + "с";
        return s + "с";
    }

    private void say(String s) {
        LOG.info(s);
        if (out != null) try { out.accept(s); } catch (Throwable ignored) { }
    }

    /** Остановить потоки и дождаться (не больше 30 с). */
    private boolean halt(State st) {
        synchronized (tl) {
            if (state != State.RUNNING) return false;
            state = st;
            prog.state = st == State.PAUSED ? "paused" : "stopped";
            tl.notifyAll();
        }
        for (Thread t : workers) try { t.join(30_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return true;
    }

    // ───────────── команды ─────────────

    public static Pregen get(String map) { return ACTIVE.get(map); }

    public static Collection<Pregen> active() { return List.copyOf(ACTIVE.values()); }

    public static String pause(String map) {
        Pregen p = ACTIVE.get(map);
        if (p == null) return "pregen «" + map + "» не идёт";
        Thread.ofVirtual().start(() -> p.halt(State.PAUSED));
        return "pregen «" + map + "»: пауза (дорабатывает текущие регионы)";
    }

    public static String stop(String map, Env env) {
        Pregen p = ACTIVE.get(map);
        if (p == null) {
            Path f = env.dir(map).resolve("pregen.yml");
            try { return Files.deleteIfExists(f) ? "pregen «" + map + "»: прогресс удалён" : "pregen «" + map + "» не идёт"; }
            catch (IOException e) { return "не удалить " + f + ": " + e; }
        }
        Thread.ofVirtual().start(() -> p.halt(State.STOPPED));
        return "pregen «" + map + "»: останавливаю";
    }

    /** Продолжить по pregen.yml (пауза или после перезапуска). */
    public static String resume(String map, Env env, Consumer<String> out) {
        Path f = env.dir(map).resolve("pregen.yml");
        if (!Files.isRegularFile(f)) return "у «" + map + "» нет pregen.yml";
        try {
            PregenProgress p = PregenProgress.load(f);
            String err = start(map, env, p, out);
            return err == null ? null : err;
        } catch (Exception e) { return "pregen.yml не прочитан: " + e; }
    }

    /** При остановке сервера: всё на паузу (прогресс сохранится, при старте продолжится). */
    public static void pauseAll() {
        for (Pregen p : List.copyOf(ACTIVE.values())) {
            // только шедший: поставленный на паузу руками или упавший — не возобновлять сам после перезапуска
            if (!p.halt(State.PAUSED)) continue;
            p.prog.state = "running";                                       // продолжить после перезапуска
            p.save();
            p.closeWriter();                                                // report-поток (daemon) может не успеть до выхода
        }
    }

    /** После загрузки модулей: продолжить pregen со state: running. */
    public static void resumeAll(Env env) {
        for (String m : env.names()) {
            Path f = env.dir(m).resolve("pregen.yml");
            if (!Files.isRegularFile(f)) continue;
            try {
                PregenProgress p = PregenProgress.load(f);
                if (!"running".equals(p.state)) continue;
                String err = start(m, env, p, null);
                if (err != null) LOG.error("pregen «{}» не продолжен: {}", m, err);
            } catch (Exception e) { LOG.error("pregen «{}»: pregen.yml не прочитан: {}", m, e.toString()); }
        }
    }

    /** Ждать завершения потоков (тесты). */
    void join() throws InterruptedException { for (Thread t : workers) t.join(); }

    /** Тест: остановить после k регионов (как обрыв). */
    void haltNow() { halt(State.PAUSED); }

    State state() { return state; }

    int regionsTotal() { return units.length; }
}
