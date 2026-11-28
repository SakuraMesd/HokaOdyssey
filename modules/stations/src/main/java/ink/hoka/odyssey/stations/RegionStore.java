package ink.hoka.odyssey.stations;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Файл регионов {@code module/stations/regions.json}:
 * <pre>
 * { "version": 1, "seq": 7,
 *   "regions": [ { "id": "alpha", "map": "cosmos", "title": "Станция Альфа", "owner": null, "gravity": "normal",
 *                  "created": 3, "boxes": [[x1,y1,z1, x2,y2,z2], …], "flags": {} } ] }
 * </pre>
 * Запись атомарная: временный файл → rename. Битый файл не перезаписывается: {@link #load} бросает {@link Broken},
 * модуль запрещает сохранение, пока файл не починят и не сделают /hostation reload.
 */
public final class RegionStore {

    /** Прочитанное: регионы и счётчик создания. */
    public record Data(List<Region> regions, long seq) {}

    /** Файл есть, но прочитать нельзя. */
    public static final class Broken extends Exception {
        public Broken(String msg, Throwable cause) { super(msg, cause); }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    private final Path file;

    public RegionStore(Path file) { this.file = file; }

    public Path file() { return file; }

    /** Нет файла — пусто. Битый (не JSON, нет полей, плохая коробка) — {@link Broken}, файл не трогаем. */
    public Data load() throws Broken {
        if (!Files.isRegularFile(file)) return new Data(List.of(), 0);
        String text;
        try { text = Files.readString(file, StandardCharsets.UTF_8); }
        catch (IOException e) { throw new Broken("не прочитать " + file + ": " + e.getMessage(), e); }
        try {
            return parse(text);
        } catch (RuntimeException e) {
            throw new Broken("битый " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    static Data parse(String text) {
        if (text.isBlank()) return new Data(List.of(), 0);
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        long seq = root.has("seq") ? root.get("seq").getAsLong() : 0;
        List<Region> out = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        JsonArray arr = root.has("regions") ? root.getAsJsonArray("regions") : new JsonArray();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            String id = o.get("id").getAsString();
            if (!Region.validId(id)) throw new IllegalArgumentException("плохой id региона «" + id + "»");
            if (seen.put(id, true) != null) throw new IllegalArgumentException("два региона с id «" + id + "»");
            String map = o.get("map").getAsString();
            String title = o.has("title") && !o.get("title").isJsonNull() ? o.get("title").getAsString() : id;
            String owner = o.has("owner") && !o.get("owner").isJsonNull() ? o.get("owner").getAsString() : null;
            Grav g = o.has("gravity") ? Grav.parse(o.get("gravity").getAsString()) : Grav.NORMAL;
            if (g == null) throw new IllegalArgumentException("регион " + id + ": gravity — normal | vacuum | map");
            long created = o.has("created") ? o.get("created").getAsLong() : 0;
            List<Box> boxes = new ArrayList<>();
            for (JsonElement be : o.getAsJsonArray("boxes")) {
                JsonArray a = be.getAsJsonArray();
                if (a.size() != 6) throw new IllegalArgumentException("регион " + id + ": коробка — 6 чисел");
                boxes.add(Box.of(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt(), a.get(4).getAsInt(), a.get(5).getAsInt()));
            }
            Map<String, String> flags = new LinkedHashMap<>();
            if (o.has("flags") && o.get("flags").isJsonObject())
                for (var f : o.getAsJsonObject("flags").entrySet()) flags.put(f.getKey(), f.getValue().isJsonNull() ? "" : f.getValue().getAsString());
            out.add(new Region(id, map, title, owner, g, boxes, created, flags));
            seq = Math.max(seq, created);
        }
        out.sort(Comparator.comparingLong(Region::created));
        return new Data(out, seq);
    }

    /** Текст файла (в тике — дёшево; писать — {@link #write} в отдельном потоке). */
    public static String serialize(Collection<Region> regions, long seq) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("seq", seq);
        JsonArray arr = new JsonArray();
        List<Region> sorted = new ArrayList<>(regions);
        sorted.sort(Comparator.comparingLong(Region::created));
        for (Region r : sorted) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("map", r.map());
            o.addProperty("title", r.title());
            o.addProperty("owner", r.owner());
            o.addProperty("gravity", r.gravity().key());
            o.addProperty("created", r.created());
            JsonArray boxes = new JsonArray();
            for (Box b : r.boxes()) {
                JsonArray a = new JsonArray();
                a.add(b.minX()); a.add(b.minY()); a.add(b.minZ()); a.add(b.maxX()); a.add(b.maxY()); a.add(b.maxZ());
                boxes.add(a);
            }
            o.add("boxes", boxes);
            JsonObject flags = new JsonObject();
            for (var f : r.flags().entrySet()) flags.addProperty(f.getKey(), f.getValue());
            o.add("flags", flags);
            arr.add(o);
        }
        root.add("regions", arr);
        return GSON.toJson(root) + "\n";
    }

    /** Записать атомарно: regions.json.tmp → rename (где rename не атомарный — обычная замена). */
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
