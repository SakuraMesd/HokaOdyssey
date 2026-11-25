package ink.hoka.odyssey.server;

import ink.hoka.odyssey.api.ModuleDescriptor;
import ink.hoka.odyssey.api.OdysseyModule;
import ink.hoka.odyssey.api.OdysseyServerApi;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Модули — jar в папке module/. Каждый: module.yml в корне jar (см. {@link ModuleDescriptor}) + класс-наследник
 * {@link OdysseyModule}. Порядок включения — по зависимостям. Данные модуля — module/&lt;id&gt;/.
 * Jar копируется в module/.loaded/ и грузится из копии: исходный файл можно заменять при работающем сервере
 * (сборка разработчика не упрётся в занятый файл), а /homodule reload подхватит новую версию.
 * Изменения (включение/выключение) — только из потока тика (консоль/команды) и при старте/остановке;
 * чтение списка модулей из других потоков — через неизменяемый снимок.
 */
final class ModuleManager {

    private static final Logger LOG = LoggerFactory.getLogger("modules");
    private static final Pattern ID = Pattern.compile("[a-z0-9_\\-]+");

    enum State { ENABLED, FAILED }

    static final class Loaded {
        final ModuleDescriptor d;
        final Path jar;              // исходный jar в module/
        Path copy;                   // копия в module/.loaded/, из которой грузятся классы
        ModuleClassLoader loader;
        ModuleContextImpl ctx;
        OdysseyModule module;
        State state;
        String error;

        Loaded(ModuleDescriptor d, Path jar) { this.d = d; this.jar = jar; }
    }

    private final Path dir;
    private final Path loadedDir;
    private final OdysseyServerApi api;
    private final ServiceRegistry services = new ServiceRegistry();
    /** Включённые модули в порядке включения (меняется только под lock). */
    private final LinkedHashMap<String, Loaded> enabled = new LinkedHashMap<>();
    /** Модули, которые не включились (для /module list и повторной попытки). */
    private final LinkedHashMap<String, Loaded> failed = new LinkedHashMap<>();
    /** Снимок для чтения из любых потоков. */
    private volatile List<Loaded> snapshot = List.of();
    private volatile List<Loaded> failedSnapshot = List.of();
    private final Object lock = new Object();
    private int copySeq;

    ModuleManager(Path dir, OdysseyServerApi api) {
        this.dir = dir;
        this.loadedDir = dir.resolve(".loaded");
        this.api = api;
    }

    /**
     * Защиты от выгрузки: по набору id модулей (вся цепочка unload) — текст отказа или null. Ставит ядро
     * (генераторы планет: карта с генератором модуля загружена или идёт pregen). disableAll их не спрашивает.
     */
    private final List<java.util.function.Function<Set<String>, String>> guards = new java.util.concurrent.CopyOnWriteArrayList<>();

    void addUnloadGuard(java.util.function.Function<Set<String>, String> g) { guards.add(g); }

    /** Модуль id и зависящие от него — можно ли выгрузить: null или почему нельзя. */
    private String guard(String id) {
        if (!enabled.containsKey(id)) return null;
        Set<String> chain = new LinkedHashSet<>();
        chain.add(id);
        for (Loaded l : dependents(List.of(id))) chain.add(l.d.id());
        for (var g : guards) {
            String r = g.apply(chain);
            if (r != null) return r;
        }
        return null;
    }

    /** Отказ выгрузки (команда покажет текст). */
    static final class Refused extends RuntimeException {
        Refused(String m) { super(m); }
    }

    ServiceRegistry services() { return services; }
    OdysseyServerApi serverApi() { return api; }
    Path dir() { return dir; }

    Optional<OdysseyModule> enabledModule(String id) {
        for (Loaded l : snapshot) if (l.d.id().equals(id)) return Optional.of(l.module);
        return Optional.empty();
    }

    List<ModuleDescriptor> enabledDescriptors() { return snapshot.stream().map(l -> l.d).toList(); }
    List<Loaded> enabledList() { return snapshot; }
    List<Loaded> failedList() { return failedSnapshot; }

    private void publish() {
        snapshot = List.copyOf(enabled.values());
        failedSnapshot = List.copyOf(failed.values());
    }

    // ───────────────────────────── загрузка всех при старте ─────────────────────────────

    void loadAll() {
        synchronized (lock) {
            try { Files.createDirectories(dir); }
            catch (IOException e) { LOG.error("папка {} недоступна: {}", dir, e.toString()); return; }
            cleanLoadedDir();
            Map<String, Loaded> found = scan();
            for (String id : order(found, Set.of())) enableOne(found.get(id), found);
            publish();
            LOG.info("модулей включено: {}{}", enabled.size(), failed.isEmpty() ? "" : ", с ошибкой: " + failed.size());
        }
    }

    /** Копии прошлого запуска. Каждый файл отдельно: занятый/странный файл не мешает загрузке модулей. */
    private void cleanLoadedDir() {
        if (!Files.isDirectory(loadedDir)) return;
        try (Stream<Path> s = Files.list(loadedDir)) {
            for (Path p : s.toList()) {
                try { Files.deleteIfExists(p); } catch (IOException e) { LOG.warn("не удалить старую копию {}: {}", p.getFileName(), e.toString()); }
            }
        } catch (IOException e) {
            LOG.warn("не прочитать {}: {}", loadedDir, e.toString());
        }
    }

    /** Все jar в module/ → описания. Два jar с одним id — оба в ошибку (непонятно, какой из них нужен). */
    private Map<String, Loaded> scan() {
        Map<String, Loaded> found = new TreeMap<>();
        Set<String> dup = new HashSet<>();
        List<Path> jars;
        try (Stream<Path> s = Files.list(dir)) {
            jars = s.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p)).sorted().toList();
        } catch (IOException e) {
            LOG.error("не прочитать {}: {}", dir, e.toString());
            return found;
        }
        for (Path jar : jars) {
            try {
                ModuleDescriptor d = readDescriptor(jar);
                Loaded prev = found.get(d.id());
                if (prev != null || dup.contains(d.id())) {
                    if (prev != null) LOG.error("два jar с id «{}»: {} и {} — уберите лишний; модуль не включён", d.id(), prev.jar.getFileName(), jar.getFileName());
                    found.remove(d.id());
                    dup.add(d.id());
                    continue;
                }
                found.put(d.id(), new Loaded(d, jar));
            } catch (Exception e) {
                LOG.error("{}: {}", jar.getFileName(), e.getMessage());
            }
        }
        return found;
    }

    /**
     * Порядок включения: зависимости раньше. Цикл ищется только по depends (жёстким); softdepends — лишь подсказка
     * порядка: если мягкое ребро замыкает цикл, оно просто игнорируется. already — уже включённые (их не трогаем).
     */
    private List<String> order(Map<String, Loaded> found, Set<String> already) {
        List<String> out = new ArrayList<>();
        Set<String> done = new HashSet<>(already), visiting = new HashSet<>();
        for (String id : found.keySet()) visit(id, found, done, visiting, out, new ArrayDeque<>());
        return out;
    }

    private void visit(String id, Map<String, Loaded> found, Set<String> done, Set<String> visiting, List<String> out, Deque<String> path) {
        if (done.contains(id) || !found.containsKey(id) || visiting.contains(id)) return;
        visiting.add(id);
        path.addLast(id);
        Loaded l = found.get(id);
        for (String dep : l.d.depends()) {
            if (visiting.contains(dep)) {
                List<String> cyc = new ArrayList<>(path);
                cyc.add(dep);
                fail(l, "цикл depends: " + String.join(" → ", cyc));
                continue;
            }
            visit(dep, found, done, visiting, out, path);
        }
        for (String dep : l.d.softDepends()) if (!visiting.contains(dep)) visit(dep, found, done, visiting, out, path);
        path.removeLast();
        visiting.remove(id);
        if (done.add(id)) out.add(id);
    }

    // ───────────────────────────── включение / выключение одного ─────────────────────────────

    /** Загрузить и включить один модуль. found — модули этой же пачки (для сообщений о зависимостях). */
    private boolean enableOne(Loaded l, Map<String, Loaded> found) {
        if (l.state == State.FAILED) return false;                                     // уже отмечен (цикл)
        for (String dep : l.d.depends()) {
            if (!enabled.containsKey(dep)) {
                String why = found.containsKey(dep) || failed.containsKey(dep) ? "зависимость «" + dep + "» не включилась" : "нет зависимости «" + dep + "» (depends в module.yml)";
                return fail(l, why);
            }
        }
        try {
            Files.createDirectories(loadedDir);
            Path copy = loadedDir.resolve(l.d.id() + "-" + (++copySeq) + ".jar");
            Files.copy(l.jar, copy, StandardCopyOption.REPLACE_EXISTING);
            l.copy = copy;
            List<ModuleClassLoader> deps = new ArrayList<>();
            collectDeps(l.d, deps, new HashSet<>());
            l.loader = new ModuleClassLoader(l.d.id(), copy.toUri().toURL(), getClass().getClassLoader(), deps);
            Class<?> main = Class.forName(l.d.main(), true, l.loader);
            if (!OdysseyModule.class.isAssignableFrom(main))
                throw new IllegalStateException("main " + l.d.main() + " не наследует OdysseyModule");
            l.module = (OdysseyModule) main.getDeclaredConstructor().newInstance();
            l.ctx = new ModuleContextImpl(l.d, this, l.loader, dir.resolve(l.d.id()), services);
            l.module.attach(l.ctx);
            l.module.load();
            l.module.enable();
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
            LOG.error("модуль {} не включился", l.d.display(), c);
            if (l.ctx != null) l.ctx.cleanup();
            closeLoader(l);
            return fail(l, c.toString());
        }
        l.state = State.ENABLED;
        failed.remove(l.d.id());
        enabled.put(l.d.id(), l);
        LOG.info("включён {}{}", l.d.display(), l.d.authors().isEmpty() ? "" : " — " + String.join(", ", l.d.authors()));
        return true;
    }

    private void collectDeps(ModuleDescriptor d, List<ModuleClassLoader> out, Set<String> seen) {
        for (String dep : concat(d.depends(), d.softDepends())) {
            Loaded l = enabled.get(dep);
            if (l == null || !seen.add(dep)) continue;
            out.add(l.loader);
            collectDeps(l.d, out, seen);
        }
    }

    private boolean fail(Loaded l, String why) {
        l.state = State.FAILED;
        l.error = why;
        failed.put(l.d.id(), l);
        LOG.error("модуль {} пропущен: {}", l.d.display(), why);
        return false;
    }

    private void disableOne(Loaded l) {
        try { l.module.disable(); }
        catch (Throwable e) { LOG.error("ошибка при выключении {}", l.d.display(), e); }
        l.ctx.cleanup();
        closeLoader(l);
        enabled.remove(l.d.id());
        LOG.info("выключен {}", l.d.display());
    }

    private void closeLoader(Loaded l) {
        if (l.loader != null) {
            try { l.loader.close(); } catch (IOException ignored) { }
            l.loader = null;
        }
        if (l.copy != null) {
            try { Files.deleteIfExists(l.copy); } catch (IOException ignored) { }    // на Windows может быть занята — уберётся при следующем старте
            l.copy = null;
        }
    }

    /** При остановке сервера: в обратном порядке. Повторный вызов ничего не делает. */
    void disableAll() {
        synchronized (lock) {
            List<Loaded> rev = new ArrayList<>(enabled.values());
            Collections.reverse(rev);
            for (Loaded l : rev) disableOne(l);
            publish();
        }
    }

    // ───────────────────────────── команды /module ─────────────────────────────

    /** Включённые модули, которые (прямо или через другие) зависят от ids. В порядке включения. */
    private List<Loaded> dependents(Collection<String> ids) {
        Set<String> hit = new HashSet<>(ids);
        List<Loaded> out = new ArrayList<>();
        for (Loaded l : enabled.values()) {
            if (hit.contains(l.d.id())) continue;
            if (concat(l.d.depends(), l.d.softDepends()).stream().anyMatch(hit::contains)) { hit.add(l.d.id()); out.add(l); }
        }
        return out;
    }

    /** Выключить модуль и зависящие от него. Возвращает выключенные (в порядке включения). */
    List<String> unload(String id) {
        synchronized (lock) {
            String no = guard(id);
            if (no != null) throw new Refused(no);
            List<String> off = unloadChain(id).stream().map(x -> x.d.id()).toList();
            publish();
            refreshCommands();
            return off;
        }
    }

    private List<Loaded> unloadChain(String id) {
        Loaded l = enabled.get(id);
        if (l == null) return List.of();
        List<Loaded> chain = new ArrayList<>();
        chain.add(l);
        chain.addAll(dependents(List.of(id)));
        List<Loaded> rev = new ArrayList<>(chain);
        Collections.reverse(rev);
        for (Loaded x : rev) disableOne(x);
        return chain;
    }

    /**
     * Перезагрузить модуль (и зависящие от него) с диска: выключить, перечитать их jar (тот же файл, что был загружен),
     * включить в порядке по новым module.yml. Потом — повторная попытка модулей, которые не включались из-за них.
     */
    String reload(String id) {
        synchronized (lock) {
            Map<String, Path> jars = new LinkedHashMap<>();
            String no = guard(id);
            if (no != null) return "нельзя: " + no;
            if (enabled.containsKey(id)) {
                for (Loaded l : unloadChain(id)) jars.put(l.d.id(), l.jar);
            } else if (failed.containsKey(id)) {
                jars.put(id, failed.get(id).jar);
                failed.remove(id);
                freshlyEnabled.add(id);
            } else {
                return "модуль «" + id + "» не найден";
            }
            Map<String, Loaded> batch = new TreeMap<>();
            StringBuilder r = new StringBuilder();
            for (var e : jars.entrySet()) {
                try {
                    ModuleDescriptor d = readDescriptor(e.getValue());
                    if (!d.id().equals(e.getKey())) { r.append(e.getKey()).append(": в jar теперь id «").append(d.id()).append("»; "); continue; }
                    batch.put(d.id(), new Loaded(d, e.getValue()));
                } catch (Exception ex) {
                    r.append(e.getKey()).append(": ").append(ex.getMessage()).append("; ");
                }
            }
            enableBatch(batch, r);
            retryFailed(r);
            reloadSoftDependents(r);
            publish();
            refreshCommands();
            return r.toString();
        }
    }

    /** Загрузить новый jar из module/ (по имени файла), не трогая остальные. */
    String loadFile(String fileName) {
        synchronized (lock) {
            Path jar = dir.resolve(fileName.endsWith(".jar") ? fileName : fileName + ".jar");
            if (!Files.isRegularFile(jar)) return "нет файла " + jar;
            StringBuilder r = new StringBuilder();
            try {
                ModuleDescriptor d = readDescriptor(jar);
                if (enabled.containsKey(d.id())) return "модуль «" + d.id() + "» уже включён — /homodule reload " + d.id();
                failed.remove(d.id());
                freshlyEnabled.add(d.id());
                enableBatch(new TreeMap<>(Map.of(d.id(), new Loaded(d, jar))), r);
            } catch (Exception e) {
                return "ошибка: " + e.getMessage();
            }
            retryFailed(r);
            reloadSoftDependents(r);
            publish();
            refreshCommands();
            return r.toString();
        }
    }

    private void enableBatch(Map<String, Loaded> batch, StringBuilder r) {
        for (String m : order(batch, enabled.keySet())) {
            Loaded l = batch.get(m);
            r.append(m).append(enableOne(l, batch) ? ": ок; " : ": ошибка (" + l.error + "); ");
        }
    }

    /** Модули, впервые включённые этой операцией (раньше были выключены или с ошибкой). */
    private final Set<String> freshlyEnabled = new HashSet<>();

    /**
     * Включённые раньше модули с softdepends на только что появившийся модуль — перезагрузить: их загрузчик классов
     * создан без него и его классов не видит.
     */
    private void reloadSoftDependents(StringBuilder r) {
        Set<String> fresh = new HashSet<>();
        for (String id : freshlyEnabled) if (enabled.containsKey(id)) fresh.add(id);
        freshlyEnabled.clear();
        if (fresh.isEmpty()) return;
        List<String> stale = new ArrayList<>();
        for (Loaded l : enabled.values())
            if (!fresh.contains(l.d.id()) && l.d.softDepends().stream().anyMatch(fresh::contains)) stale.add(l.d.id());
        for (String s : stale) {
            if (!enabled.containsKey(s)) continue;                                      // уже выключен вместе с другим
            String no = guard(s);
            if (no != null) { r.append(s).append(": не перезагружен (softdepends) — ").append(no).append("; "); continue; }
            Map<String, Loaded> batch = new TreeMap<>();
            for (Loaded x : unloadChain(s)) {
                try { batch.put(x.d.id(), new Loaded(readDescriptor(x.jar), x.jar)); } catch (Exception ignored) { }
            }
            r.append("перезагружены из-за softdepends: ");
            enableBatch(batch, r);
        }
    }

    /** Повторить модули из failed, у которых теперь есть все depends (до тех пор, пока что-то включается). */
    private void retryFailed(StringBuilder r) {
        boolean progress = true;
        while (progress && !failed.isEmpty()) {
            progress = false;
            Map<String, Loaded> ready = new TreeMap<>();
            for (Loaded f : List.copyOf(failed.values())) {
                if (!f.d.depends().stream().allMatch(enabled::containsKey)) continue;
                try {
                    ModuleDescriptor d = readDescriptor(f.jar);
                    if (!d.id().equals(f.d.id())) continue;
                    failed.remove(f.d.id());
                    freshlyEnabled.add(d.id());
                    ready.put(d.id(), new Loaded(d, f.jar));
                } catch (Exception ignored) { }
            }
            if (ready.isEmpty()) break;
            StringBuilder part = new StringBuilder();
            enableBatch(ready, part);
            for (String id : ready.keySet()) if (enabled.containsKey(id)) progress = true;
            r.append("повторно: ").append(part);
        }
    }

    /** Список команд у клиентов обновляется только при входе — после изменений модулей шлём заново. */
    private static void refreshCommands() {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) p.refreshCommands();
    }

    // ───────────────────────────── module.yml ─────────────────────────────

    @SuppressWarnings("unchecked")
    static ModuleDescriptor readDescriptor(Path jar) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("module.yml");
            if (e == null) throw new IOException("в jar нет module.yml — это не модуль");
            Map<String, Object> m;
            try (InputStream in = z.getInputStream(e)) {
                Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
                if (!(o instanceof Map)) throw new IOException("module.yml пустой или не словарь");
                m = (Map<String, Object>) o;
            }
            String id = str(m, "id", null), main = str(m, "main", null);
            if (id == null || !ID.matcher(id).matches()) throw new IOException("module.yml: id обязателен, только a-z 0-9 _ -");
            if (main == null) throw new IOException("module.yml: нет main (класс модуля)");
            return new ModuleDescriptor(id, str(m, "name", id), str(m, "version", "0.0.0"), main, list(m, "authors"),
                    str(m, "description", ""), list(m, "depends"), list(m, "softdepends"));
        }
    }

    private static String str(Map<String, Object> m, String k, String def) {
        Object o = m.get(k);
        return o == null ? def : String.valueOf(o);
    }

    private static List<String> list(Map<String, Object> m, String k) {
        Object o = m.get(k);
        if (o == null) return List.of();
        if (o instanceof List<?> l) return l.stream().map(String::valueOf).toList();
        return List.of(String.valueOf(o));
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
