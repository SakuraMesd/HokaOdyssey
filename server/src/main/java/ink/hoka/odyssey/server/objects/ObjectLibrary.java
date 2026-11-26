package ink.hoka.odyssey.server.objects;

import ink.hoka.odyssey.gen.GenJson;
import ink.hoka.odyssey.gen.GenObject;
import ink.hoka.odyssey.gen.GenObjectCodec;
import ink.hoka.odyssey.gen.GenObjectMeta;
import ink.hoka.odyssey.gen.GenObjects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Библиотека объектов на диске: {@code objects/<категория>/<имя>.hobj} + {@code <имя>.json} (метаданные, можно править
 * руками и /hoobj reload). Удаление — в {@code objects/.trash/}. Кэш в памяти; запись атомарная (tmp → move).
 */
public final class ObjectLibrary {

    private static final Logger LOG = LoggerFactory.getLogger("objects");
    private static volatile ObjectLibrary global;

    /** Библиотека ядра (папка objects/ сервера) или null (тесты без неё). */
    public static ObjectLibrary global() { return global; }
    public static void setGlobal(ObjectLibrary l) { global = l; }

    private final Path root;
    private final TreeMap<String, GenObject> objects = new TreeMap<>();
    private final TreeMap<String, Long> sizes = new TreeMap<>();
    private final TreeMap<String, String> errors = new TreeMap<>();

    public ObjectLibrary(Path root) {
        this.root = root;
        scan();
    }

    public Path root() { return root; }

    public Path file(String id) { return root.resolve(cat(id)).resolve(name(id) + ".hobj"); }
    public Path metaFile(String id) { return root.resolve(cat(id)).resolve(name(id) + ".json"); }

    static String cat(String id) { return id.substring(0, id.indexOf('/')); }
    static String name(String id) { return id.substring(id.indexOf('/') + 1); }

    /** Проверить id "кат/имя" (исключение с понятным текстом). */
    public static String checkId(String id) {
        if (!GenObjects.validId(id)) throw new IllegalArgumentException("имя объекта — <категория>/<имя>, a-z 0-9 _ - (до 32 каждое), а не «" + id + "»");
        return id;
    }

    /** Пересканировать папку. Ответ — сколько объектов (битые — в {@link #errors}). */
    public synchronized int scan() {
        objects.clear();
        sizes.clear();
        errors.clear();
        if (!Files.isDirectory(root)) return 0;
        try (var cats = Files.list(root)) {
            for (Path cd : (Iterable<Path>) cats.sorted()::iterator) {
                String c = cd.getFileName().toString();
                if (!Files.isDirectory(cd) || !GenObjects.NAME.matcher(c).matches()) continue;
                try (var fs = Files.list(cd)) {
                    for (Path f : (Iterable<Path>) fs.sorted()::iterator) {
                        String fn = f.getFileName().toString();
                        if (!fn.endsWith(".hobj")) continue;
                        String n = fn.substring(0, fn.length() - 5);
                        String id = c + "/" + n;
                        if (!GenObjects.validId(id)) { errors.put(id, "имя файла не a-z 0-9 _ -"); continue; }
                        try { load(id); }
                        catch (Exception e) { errors.put(id, e.getMessage()); }
                    }
                }
            }
        } catch (IOException e) {
            LOG.error("папка {} не прочитана: {}", root, e.toString());
        }
        if (!errors.isEmpty()) LOG.warn("объекты с ошибками: {}", errors);
        return objects.size();
    }

    private GenObject load(String id) throws IOException {
        byte[] b = Files.readAllBytes(file(id));
        GenObject o = GenObjectCodec.readFile(id, b, readMeta(id));
        objects.put(id, o);
        sizes.put(id, (long) b.length);
        errors.remove(id);
        return o;
    }

    /** Метаданные объекта (нет файла — пусто). Битый JSON — IOException. */
    public Map<String, Object> readMeta(String id) throws IOException {
        Path f = metaFile(id);
        if (!Files.exists(f)) return Map.of();
        try { return GenJson.parseObject(Files.readString(f, StandardCharsets.UTF_8)); }
        catch (RuntimeException e) { throw new IOException(f.getFileName() + ": " + e.getMessage()); }
    }

    public synchronized SortedSet<String> categories() {
        TreeSet<String> r = new TreeSet<>();
        for (String id : objects.keySet()) r.add(cat(id));
        return r;
    }

    /** Объекты категории по id. */
    public synchronized List<GenObject> category(String c) {
        List<GenObject> r = new ArrayList<>();
        for (GenObject o : objects.subMap(c + "/", c + "/￿").values()) r.add(o);
        return r;
    }

    public synchronized GenObject get(String id) { return objects.get(id); }
    public synchronized List<String> ids() { return new ArrayList<>(objects.keySet()); }
    public synchronized long fileSize(String id) { return sizes.getOrDefault(id, 0L); }
    public synchronized Map<String, String> errors() { return Collections.unmodifiableMap(new TreeMap<>(errors)); }

    /** Сохранить объект (id из obj). Есть файл и не force — исключение. Нет .json — создаётся {"weight":1}. */
    public synchronized GenObject save(GenObject obj, boolean force) throws IOException {
        String id = checkId(obj.id());
        String lim = GenObjects.checkLimits(obj);
        if (lim != null) throw new IllegalArgumentException("объект не годится для генерации: " + lim);
        Path f = file(id);
        if (Files.exists(f) && !force) throw new IllegalStateException("объект " + id + " уже есть — добавьте force, чтобы перезаписать");
        Files.createDirectories(f.getParent());
        write(f, GenObjectCodec.writeFile(obj));
        if (!Files.exists(metaFile(id))) writeMeta(id, Map.of("weight", 1));
        return load(id);
    }

    /** Удалить: .hobj и .json → objects/.trash/&lt;кат&gt;/&lt;имя&gt;-&lt;время&gt;.*. Ответ — путь в корзине. */
    public synchronized Path delete(String id) throws IOException {
        checkId(id);
        Path f = file(id);
        if (!Files.exists(f)) throw new IllegalArgumentException("нет объекта " + id);
        String t = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path tr = root.resolve(".trash").resolve(cat(id));
        Files.createDirectories(tr);
        Path to = tr.resolve(name(id) + "-" + t + ".hobj");
        Files.move(f, to, StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(metaFile(id))) Files.move(metaFile(id), tr.resolve(name(id) + "-" + t + ".json"), StandardCopyOption.REPLACE_EXISTING);
        objects.remove(id);
        sizes.remove(id);
        errors.remove(id);
        return to;
    }

    /** /hoobj set: ключ и значение строкой (проверка типа — GenObjectMeta.parseValue). */
    public synchronized GenObject setMeta(String id, String key, String value) throws IOException {
        need(id);
        Object v = GenObjectMeta.parseValue(key, value);
        Map<String, Object> m = new LinkedHashMap<>(readMeta(id));
        m.put(key, v);
        writeMeta(id, m);
        return load(id);
    }

    public synchronized GenObject unsetMeta(String id, String key) throws IOException {
        need(id);
        Map<String, Object> m = new LinkedHashMap<>(readMeta(id));
        if (m.remove(key) == null) throw new IllegalArgumentException("ключа «" + key + "» нет");
        writeMeta(id, m);
        return load(id);
    }

    /** Новый якорь (координаты рамки объекта). */
    public synchronized GenObject setAnchor(String id, int ax, int ay, int az) throws IOException {
        GenObject o = need(id).withAnchor(ax, ay, az);
        String lim = GenObjects.checkLimits(o);
        if (lim != null) throw new IllegalArgumentException("с таким якорем объект не годится: " + lim);
        write(file(id), GenObjectCodec.writeFile(o));
        return load(id);
    }

    private GenObject need(String id) {
        checkId(id);
        GenObject o = objects.get(id);
        if (o == null) throw new IllegalArgumentException("нет объекта " + id);
        return o;
    }

    /** .json: ключ на строку, по алфавиту (удобно править руками). */
    void writeMeta(String id, Map<String, Object> m) throws IOException {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : new TreeMap<>(m).entrySet()) {
            sb.append(first ? "\n" : ",\n").append("  ").append(GenJson.write(e.getKey())).append(": ").append(GenJson.write(e.getValue()));
            first = false;
        }
        sb.append(first ? "}" : "\n}").append('\n');
        Files.createDirectories(metaFile(id).getParent());
        write(metaFile(id), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path f, byte[] b) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.write(tmp, b);
        try { Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING); }
    }
}
