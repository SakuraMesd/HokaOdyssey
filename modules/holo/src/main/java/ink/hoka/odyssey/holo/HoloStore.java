package ink.hoka.odyssey.holo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Файлы модуля: {@code holograms.json} — {@code {"version": 1, "holograms": [голограмма…]}} (формат — {@link HoloCodec}),
 * {@code templates.json} — {@code {"version": 1, "templates": {"имя": голограмма}}}. Запись атомарная (tmp → rename),
 * битый файл не перезаписывается: {@link #load} бросает {@link Broken}, модуль запрещает сохранение до /hoholo reload.
 */
public final class HoloStore {

    public static final class Broken extends Exception {
        public Broken(String msg, Throwable cause) { super(msg, cause); }
    }

    private final Path file;

    public HoloStore(Path file) { this.file = file; }

    public Path file() { return file; }

    public List<Hologram> load() throws Broken {
        String text = read();
        if (text == null) return List.of();
        try { return parse(text); }
        catch (RuntimeException e) { throw new Broken("битый " + file.getFileName() + ": " + e.getMessage(), e); }
    }

    public Map<String, Hologram> loadTemplates() throws Broken {
        String text = read();
        if (text == null) return new LinkedHashMap<>();
        try { return parseTemplates(text); }
        catch (RuntimeException e) { throw new Broken("битый " + file.getFileName() + ": " + e.getMessage(), e); }
    }

    private String read() throws Broken {
        if (!Files.isRegularFile(file)) return null;
        try { return Files.readString(file, StandardCharsets.UTF_8); }
        catch (IOException e) { throw new Broken("не прочитать " + file + ": " + e.getMessage(), e); }
    }

    static List<Hologram> parse(String text) {
        if (text.isBlank()) return List.of();
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        List<Hologram> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonArray arr = root.has("holograms") ? root.getAsJsonArray("holograms") : new JsonArray();
        for (JsonElement el : arr) {
            Hologram h = HoloCodec.parse(el.getAsJsonObject());
            if (!seen.add(h.id)) throw new IllegalArgumentException("две голограммы с id «" + h.id + "»");
            out.add(h);
        }
        return out;
    }

    static Map<String, Hologram> parseTemplates(String text) {
        Map<String, Hologram> out = new LinkedHashMap<>();
        if (text.isBlank()) return out;
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        if (root.has("templates"))
            for (var e : root.getAsJsonObject("templates").entrySet()) out.put(e.getKey(), HoloCodec.parse(e.getValue().getAsJsonObject()));
        return out;
    }

    /** Текст файла голограмм (временные не пишутся). */
    public static String serialize(Collection<Hologram> holos) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray arr = new JsonArray();
        for (Hologram h : holos) if (!h.temporary) arr.add(HoloCodec.toJson(h));
        root.add("holograms", arr);
        return HoloCodec.PRETTY.toJson(root) + "\n";
    }

    public static String serializeTemplates(Map<String, Hologram> t) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject m = new JsonObject();
        for (var e : t.entrySet()) m.add(e.getKey(), HoloCodec.toJson(e.getValue()));
        root.add("templates", m);
        return HoloCodec.PRETTY.toJson(root) + "\n";
    }

    /** Записать атомарно: файл.tmp → rename (где rename не атомарный — обычная замена). */
    public synchronized void write(String text) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
