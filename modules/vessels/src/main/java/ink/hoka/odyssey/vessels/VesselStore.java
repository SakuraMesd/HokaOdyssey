package ink.hoka.odyssey.vessels;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Файл кораблей module/vessels/vessels.json: {@code {"version":1,"vessels":[{id, kind, map, x, y, z, rot:[x,y,z,w],
 * owner, owner-name, cargo:{storage[27], upgrades[8], fuel[4]}}]}} (предметы — ItemStack.CODEC, пустой слот — null).
 * Запись — во временный файл и переименование (обрыв посреди записи не портит старый файл). Файл не прочитался —
 * {@link #broken}: больше не пишем (копия — vessels.json.broken), чтобы не затереть корабли пустым списком.
 */
final class VesselStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private volatile boolean broken;
    private volatile String brokenWhy;

    VesselStore(Path file) { this.file = file; }

    boolean broken() { return broken; }

    String brokenWhy() { return brokenWhy; }

    /** Прочитать. Нет файла — пусто; битый — пусто и {@link #broken}. */
    List<Vessel> load() {
        List<Vessel> out = new ArrayList<>();
        broken = false;
        brokenWhy = null;
        if (!Files.isRegularFile(file)) return out;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("vessels");
            if (arr == null) throw new IllegalStateException("нет списка vessels");
            for (var el : arr) {
                JsonObject o = el.getAsJsonObject();
                JsonArray r = o.getAsJsonArray("rot");
                Quat q = r != null && r.size() == 4
                        ? new Quat(r.get(0).getAsDouble(), r.get(1).getAsDouble(), r.get(2).getAsDouble(), r.get(3).getAsDouble()).normalized()
                        : Quat.IDENTITY;
                Vessel v = new Vessel(o.get("id").getAsInt(), o.get("kind").getAsString(), o.get("map").getAsString(),
                        o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(), q,
                        Cargo.fromJson(o.getAsJsonObject("cargo")));
                if (o.has("owner") && !o.get("owner").isJsonNull()) v.owner = o.get("owner").getAsString();
                if (o.has("owner-name") && !o.get("owner-name").isJsonNull()) v.ownerName = o.get("owner-name").getAsString();
                if (!Double.isFinite(v.x) || !Double.isFinite(v.y) || !Double.isFinite(v.z)) throw new IllegalStateException("корабль " + v.id + ": плохие координаты");
                out.add(v);
            }
        } catch (Exception e) {
            broken = true;
            brokenWhy = e.toString();
            try { Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { }
            return new ArrayList<>();
        }
        return out;
    }

    /** Снимок кораблей в JSON (в тике — быстро; писать можно потом в фоне). */
    static String json(List<Vessel> list) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray arr = new JsonArray();
        for (Vessel v : list) {
            JsonObject o = new JsonObject();
            o.addProperty("id", v.id);
            o.addProperty("kind", v.kind);
            o.addProperty("map", v.map);
            o.addProperty("x", v.x);
            o.addProperty("y", v.y);
            o.addProperty("z", v.z);
            JsonArray r = new JsonArray();
            r.add(v.rot.x()); r.add(v.rot.y()); r.add(v.rot.z()); r.add(v.rot.w());
            o.add("rot", r);
            if (v.owner != null) o.addProperty("owner", v.owner);
            if (v.ownerName != null) o.addProperty("owner-name", v.ownerName);
            o.add("cargo", v.cargo.toJson());
            arr.add(o);
        }
        root.add("vessels", arr);
        return GSON.toJson(root);
    }

    /** Записать (атомарно). Файл был битым — false, ничего не пишем. */
    synchronized boolean write(String json) throws IOException {
        if (broken) return false;
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }
}
