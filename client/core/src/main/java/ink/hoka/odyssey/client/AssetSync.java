package ink.hoka.odyssey.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlagSet;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Ресурсы сервера без ресурспака: подписанный список (путь → sha256), файлы докачиваются кусками в общий кэш
 * объектов, раскладываются в {@code .minecraft/hoka_odyssey/pack} — невидимый обязательный пак мода (его вклеивает
 * {@link ink.hoka.odyssey.client.mixin.PackRepositoryMixin}). Изменилось что-то — перезагрузка ресурсов игры.
 * Новые текстуры/модели/звуки = только сервер, мод не пересобирается.
 */
public final class AssetSync {

    public static final String PACK_ID = "hoka_odyssey_server";   // не "hoka_odyssey": так зовётся пак ресурсов самого мода
    static final Path PACK = ModuleManager.ROOT.resolve("pack");
    private static final Path INDEX = ModuleManager.ROOT.resolve("pack-index.json");
    private static final Path ACTIVE = ModuleManager.ROOT.resolve("active");

    /** Пак мода в списке ресурсов игры (наш сервер). Помним между запусками — не перезагружать ресурсы при каждом входе. */
    private static volatile boolean active = Files.exists(ACTIVE);

    private static Map<String, String> wanted;                                  // ждём докачки

    private AssetSync() {}

    public static boolean active() { return active; }

    static void manifest(DataInputStream in) throws Exception {
        byte[] body = new byte[in.readInt()];
        in.readFully(body);
        byte[] sig = new byte[in.readInt()];
        in.readFully(sig);
        if (!ModuleManager.verify(body, sig)) {
            HokaOdysseyClient.LOG.error("Hoka Odyssey: подпись списка ресурсов НЕ сходится — не применяю");
            return;
        }
        JsonObject files = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("files");
        Map<String, String> map = new TreeMap<>();
        if (files != null) for (var e : files.entrySet()) {
            String path = e.getKey();
            if (!safe(path)) continue;
            map.put(path, e.getValue().getAsString());
        }
        wanted = map;
        List<String> need = new ArrayList<>();
        for (String sha : new java.util.HashSet<>(map.values())) if (!ModuleManager.hasObject(sha)) need.add(sha);
        HokaOdysseyClient.LOG.info("Hoka Odyssey: ресурсов {}, докачать {}", map.size(), need.size());
        if (!need.isEmpty()) ModuleManager.need(need);
        tryApply();
    }

    /** Все файлы на месте — разложить в пак и перезагрузить ресурсы, если что-то поменялось (или пак ещё не подключён). */
    static void tryApply() {
        Map<String, String> w = wanted;
        if (w == null) return;
        for (String sha : w.values()) if (!ModuleManager.hasObject(sha)) return;
        wanted = null;
        Map<String, String> cur = readIndex();
        boolean changed = !w.equals(cur);
        try {
            if (changed) {
                for (var e : w.entrySet()) {
                    if (e.getValue().equals(cur.get(e.getKey())) && Files.exists(PACK.resolve(e.getKey()))) continue;
                    Path dst = PACK.resolve(e.getKey());
                    Files.createDirectories(dst.getParent());
                    Files.copy(ModuleManager.object(e.getValue()), dst, StandardCopyOption.REPLACE_EXISTING);
                }
                for (String old : cur.keySet()) if (!w.containsKey(old)) Files.deleteIfExists(PACK.resolve(old));
                writeIndex(w);
            }
            if (!Files.exists(ACTIVE)) Files.writeString(ACTIVE, "1");
        } catch (IOException e) {
            HokaOdysseyClient.LOG.error("Hoka Odyssey: ресурсы не разложены", e);
            return;
        }
        boolean reload = changed || !active;
        active = true;
        if (reload) {
            HokaOdysseyClient.LOG.info("Hoka Odyssey: ресурсы обновлены ({} файлов) — перезагрузка", w.size());
            Minecraft.getInstance().reloadResourcePacks();
        }
    }

    /** Пак мода для списка ресурсов игры (null — ещё нечего подключать). */
    public static Pack createPack() {
        if (!Files.isDirectory(PACK)) return null;
        PackLocationInfo info = new PackLocationInfo(PACK_ID, Component.literal("Hoka Odyssey"), PackSource.BUILT_IN, Optional.empty());
        Pack.ResourcesSupplier sup = new Pack.ResourcesSupplier() {
            @Override public PackResources openPrimary(PackLocationInfo l) { return new PathPackResources(l, PACK); }
            @Override public PackResources openFull(PackLocationInfo l, Pack.Metadata m) { return new PathPackResources(l, PACK); }
        };
        Pack.Metadata meta = new Pack.Metadata(Component.literal("Ресурсы сервера Hoka Odyssey"), PackCompatibility.COMPATIBLE,
                FeatureFlagSet.of(), List.of());
        return new Pack(info, sup, meta, new PackSelectionConfig(true, Pack.Position.TOP, true));
    }

    private static boolean safe(String p) {
        return !p.isEmpty() && !p.contains("..") && !p.startsWith("/") && !p.contains("\\") && !p.contains(":")
                && (p.startsWith("assets/") || p.equals("pack.mcmeta") || p.equals("pack.png"));
    }

    private static Map<String, String> readIndex() {
        Map<String, String> m = new HashMap<>();
        try {
            if (Files.isRegularFile(INDEX)) {
                JsonObject o = JsonParser.parseString(Files.readString(INDEX)).getAsJsonObject();
                for (var e : o.entrySet()) m.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (Exception ignored) { }
        return m;
    }

    private static void writeIndex(Map<String, String> m) throws IOException {
        JsonObject o = new JsonObject();
        for (var e : m.entrySet()) o.addProperty(e.getKey(), e.getValue());
        Files.createDirectories(INDEX.getParent());
        Files.writeString(INDEX, o.toString());
    }
}
