package ink.hoka.odyssey.hosounds;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ink.hoka.odyssey.api.OdysseyModule;
import ink.hoka.odyssey.clientmods.ClientModsApi;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandExecutor;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Свои звуки: описание в config.yml и .ogg в assets/ → моду (файл {@code sounds/sounds.json} + файлы
 * {@code sounds/…}). Сервер играет их обычным пакетом звука с именем {@code hoka:<id>} — мод знает такие имена.
 */
public final class HoSoundsModule extends OdysseyModule implements HoSounds {

    static final String NS = "hoka";

    private volatile Map<String, JsonObject> defs = Map.of();
    private ClientModsApi client;
    private int publishedTo;

    @Override
    protected void onEnable() {
        client = services().get(ClientModsApi.class).orElseThrow();
        logger().info("свои звуки: {}", reload());
        services().register(HoSounds.class, this);
        registerCommand(new HoSoundCommand());
        scheduler().repeat(() -> {                                     // clientmods перезагружен — отдать заново
            ClientModsApi now = services().get(ClientModsApi.class).orElse(null);
            if (now != null && System.identityHashCode(now) != publishedTo) { client = now; publish(); }
        }, 100, 100);
    }

    @Override
    protected void onDisable() {
        services().unregister(HoSounds.class);
        try { client.clearFiles(id()); client.publishFiles(); } catch (Throwable ignored) { }
    }

    synchronized String reload() {
        config().reload();
        Map<String, JsonObject> d = new LinkedHashMap<>();
        for (String id : config().keys("sounds")) {
            String p = "sounds." + id + ".";
            JsonObject o = new JsonObject();
            JsonArray files = new JsonArray();
            if (config().get(p + "files") instanceof List<?> list) {
                for (Object f : list) {
                    JsonObject fo = new JsonObject();
                    if (f instanceof Map<?, ?> m) {
                        Object file = m.get("file");
                        if (file == null) continue;
                        fo.addProperty("file", "sounds/" + file);
                        for (String k : new String[]{"volume", "pitch"})
                            if (m.get(k) instanceof Number n) fo.addProperty(k, n.floatValue());
                        if (m.get("weight") instanceof Number n) fo.addProperty("weight", n.intValue());
                    } else fo.addProperty("file", "sounds/" + f);
                    files.add(fo);
                }
            }
            if (files.isEmpty()) { logger().warn("звук {}: нет files", id); continue; }
            o.add("files", files);
            String sub = config().getString(p + "subtitle", null);
            if (sub != null) o.addProperty("subtitle", sub);
            o.addProperty("range", config().getInt(p + "range", 16));
            o.addProperty("stream", config().getBoolean(p + "stream", false));
            d.put(id, o);
        }
        defs = d;
        int files = publish();
        return d.size() + " звуков, " + files + " файлов";
    }

    private int publish() {
        ClientModsApi api = client;
        api.clearFiles(id());
        Path assets = dataFolder().resolve("assets");
        int files = 0;
        try {
            Files.createDirectories(assets);
            try (Stream<Path> s = Files.walk(assets)) {
                for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    api.putFile(id(), "sounds/" + assets.relativize(f).toString().replace(java.io.File.separatorChar, '/'), Files.readAllBytes(f));
                    files++;
                }
            }
        } catch (IOException e) {
            logger().error("assets не прочитаны: {}", e.toString());
        }
        JsonObject sounds = new JsonObject();
        for (var e : defs.entrySet()) sounds.add(NS + ":" + e.getKey(), e.getValue());
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("sounds", sounds);
        api.putFile(id(), "sounds/sounds.json", new Gson().toJson(root).getBytes(StandardCharsets.UTF_8));
        api.publishFiles();
        publishedTo = System.identityHashCode(api);
        return files;
    }

    private static Sound sound(String id, float volume, float pitch) {
        return Sound.sound(Key.key(NS, id), Sound.Source.MASTER, volume, pitch);
    }

    @Override public void play(Player p, String id) {
        play(p, id, p.getPosition(), 1f, 1f);
    }

    @Override public void play(Player p, String id, Point at, float volume, float pitch) {
        p.playSound(sound(id, volume, pitch), at.x(), at.y(), at.z());
    }

    @Override public void playAt(Instance instance, Point at, String id, float volume, float pitch) {
        instance.playSound(sound(id, volume, pitch), at.x(), at.y(), at.z());
    }

    @Override public void stop(Player p, String id) {
        if (id == null) for (String k : defs.keySet()) p.stopSound(SoundStop.named(Key.key(NS, k)));
        else p.stopSound(SoundStop.named(Key.key(NS, id)));
    }

    @Override public boolean exists(String id) { return defs.containsKey(id); }

    private final class HoSoundCommand extends Command {
        HoSoundCommand() {
            super("hosound");
            setCondition((s, c) -> !(s instanceof Player p) || server().isOp(p));
            setDefaultExecutor((s, c) -> {
                s.sendMessage(Component.text("Свои звуки: " + defs.size() + ".  /hosound play <id> [громкость] [высота] · stop · reload", NamedTextColor.AQUA));
                for (var e : defs.entrySet())
                    s.sendMessage(Component.text("  " + NS + ":" + e.getKey() + " — файлов " + e.getValue().getAsJsonArray("files").size()
                            + (e.getValue().get("stream").getAsBoolean() ? ", поток" : ""), NamedTextColor.GRAY));
            });
            addSyntax((s, c) -> s.sendMessage(Component.text("Перечитано и отдано моду: " + reload(), NamedTextColor.GREEN)), ArgumentType.Literal("reload"));
            addSyntax((s, c) -> { if (s instanceof Player p) stop(p, null); }, ArgumentType.Literal("stop"));
            var idArg = ArgumentType.Word("id");
            idArg.setSuggestionCallback((s, c, sug) -> { for (String k : defs.keySet()) sug.addEntry(new SuggestionEntry(k)); });
            var vol = ArgumentType.Float("громкость").between(0f, 10f);
            var pitch = ArgumentType.Float("высота").between(0.5f, 2f);
            CommandExecutor play = (s, c) -> {
                if (!(s instanceof Player p)) return;
                String id = c.get(idArg);
                if (!exists(id)) { s.sendMessage(Component.text("Нет звука " + id, NamedTextColor.RED)); return; }
                playAt(p.getInstance(), p.getPosition(), id, c.has(vol) ? c.get(vol) : 1f, c.has(pitch) ? c.get(pitch) : 1f);
            };
            addSyntax(play, ArgumentType.Literal("play"), idArg);
            addSyntax(play, ArgumentType.Literal("play"), idArg, vol);
            addSyntax(play, ArgumentType.Literal("play"), idArg, vol, pitch);
        }
    }
}
