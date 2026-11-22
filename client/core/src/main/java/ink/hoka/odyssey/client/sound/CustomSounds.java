package ink.hoka.odyssey.client.sound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Свои звуки. Сервер присылает {@value #DEFS} (звук {@code hoka:<id>} → файлы .ogg, громкость, высота, вес,
 * поток) и сами .ogg. Сервер играет звук обычным пакетом звука с именем {@code hoka:<id>} — мод знает его
 * ({@link #event}), а файл берёт из кэша объектов по sha ({@link #wrap}). Ни sounds.json в паке, ни перезагрузки.
 */
public final class CustomSounds {

    private CustomSounds() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey/sounds");
    public static final String DEFS = "sounds/sounds.json";
    /** Пространство имён файлов звуков: hoka_odyssey_snd:&lt;sha&gt; → «sounds/&lt;sha&gt;.ogg». */
    static final String NS_FILE = "hoka_odyssey_snd";

    public interface Source {
        byte[] get(String path);
        String sha(String path);
        Path object(String sha);
        void listen(String path, Consumer<byte[]> fn);
    }

    private static Source source;
    private static final Set<String> LISTENING = new HashSet<>();
    private static boolean dirty;
    private static volatile Map<Identifier, WeighedSoundEvents> EVENTS = Map.of();

    public static void init(Source s) { source = s; }

    public static void connect() { listen(DEFS); }

    public static void clear() {
        EVENTS = Map.of();
        LISTENING.clear();
        dirty = false;
    }

    /** Звук по имени (миксин SoundManager.getSoundEvent). null — не наш. */
    public static WeighedSoundEvents event(Identifier id) {
        Map<Identifier, WeighedSoundEvents> m = EVENTS;
        return m.isEmpty() ? null : m.get(id);
    }

    private static void listen(String path) {
        if (LISTENING.add(path)) source.listen(path, b -> dirty = true);
    }

    public static void tick() {
        if (!dirty || source == null) return;
        dirty = false;
        try {
            rebuild();
        } catch (Exception e) {
            LOG.error("свои звуки не собраны", e);
        }
    }

    private static void rebuild() {
        byte[] defs = source.get(DEFS);
        if (defs == null) { EVENTS = Map.of(); return; }
        JsonObject root = JsonParser.parseString(new String(defs, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<Identifier, WeighedSoundEvents> out = new HashMap<>();
        int files = 0;
        for (var e : root.getAsJsonObject("sounds").entrySet()) {
            Identifier id = Identifier.parse(e.getKey());
            JsonObject d = e.getValue().getAsJsonObject();
            WeighedSoundEvents ev = new WeighedSoundEvents(id, d.has("subtitle") ? d.get("subtitle").getAsString() : null);
            boolean stream = d.has("stream") && d.get("stream").getAsBoolean();
            int range = d.has("range") ? d.get("range").getAsInt() : 16;
            for (JsonElement fe : d.getAsJsonArray("files")) {
                JsonObject f = fe.getAsJsonObject();
                String path = f.get("file").getAsString();
                listen(path);
                String sha = source.sha(path);
                if (sha == null) { LOG.warn("{}: нет файла {}", id, path); continue; }
                ev.addSound(new Sound(Identifier.fromNamespaceAndPath(NS_FILE, sha),
                        ConstantFloat.of(f.has("volume") ? f.get("volume").getAsFloat() : 1f),
                        ConstantFloat.of(f.has("pitch") ? f.get("pitch").getAsFloat() : 1f),
                        f.has("weight") ? f.get("weight").getAsInt() : 1, Sound.Type.FILE, stream, false, range));
                files++;
            }
            out.put(id, ev);
        }
        EVENTS = Map.copyOf(out);
        LOG.info("свои звуки: {} (файлов {})", out.size(), files);
    }

    /**
     * Поставщик ресурсов для библиотеки звуков: «hoka_odyssey_snd:sounds/&lt;sha&gt;.ogg» — файл из кэша объектов,
     * остальное — как было. Файл по sha неизменен, поэтому кэш буферов игры ему не мешает.
     */
    public static ResourceProvider wrap(ResourceProvider base) {
        return loc -> {
            if (!NS_FILE.equals(loc.getNamespace())) return base.getResource(loc);
            String p = loc.getPath();
            if (source == null || !p.startsWith("sounds/") || !p.endsWith(".ogg")) return Optional.empty();
            Path f = source.object(p.substring(7, p.length() - 4));
            if (!Files.isRegularFile(f)) return Optional.empty();
            return Optional.of(new Resource(null, () -> Files.newInputStream(f)));
        };
    }
}
