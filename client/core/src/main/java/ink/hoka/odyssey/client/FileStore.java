package ink.hoka.odyssey.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Файлы для клиентских модулей — мимо системы ресурсов игры: без пака и без перезагрузок. Сервер шлёт подписанный
 * список (путь → sha256), объекты — в общий кэш, модули получают байты ({@code ctx.file}), подписку на изменения
 * ({@code ctx.onFile}) и текстуры прямо в видеокарту ({@code ctx.texture}) — поменял PNG на сервере, на экране сразу.
 */
final class FileStore {

    private FileStore() {}

    private static Map<String, String> current = Map.of();                     // применённый список: путь → sha
    private static Map<String, String> pending;                                 // ждём докачки

    private record Listener(String owner, String path, Consumer<byte[]> fn) {}
    private static final List<Listener> LISTENERS = new ArrayList<>();

    private static final class Tex {
        final Identifier id;
        String sha;
        Tex(Identifier id) { this.id = id; }
    }
    private static final Map<String, Tex> TEXTURES = new HashMap<>();

    static void manifest(DataInputStream in) throws Exception {
        byte[] body = new byte[in.readInt()];
        in.readFully(body);
        byte[] sig = new byte[in.readInt()];
        in.readFully(sig);
        if (!ModuleManager.verify(body, sig)) {
            HokaOdysseyClient.LOG.error("Hoka Odyssey: подпись списка файлов НЕ сходится — не применяю");
            return;
        }
        JsonObject files = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("files");
        Map<String, String> map = new HashMap<>();
        if (files != null) for (var e : files.entrySet()) map.put(e.getKey(), e.getValue().getAsString());
        pending = map;
        List<String> need = new ArrayList<>();
        for (String sha : new HashSet<>(map.values())) if (!ModuleManager.hasObject(sha)) need.add(sha);
        if (!need.isEmpty()) ModuleManager.need(need);
        tryApply();
    }

    /** Все файлы на месте — применить список, обновить текстуры и сообщить подписчикам об изменившихся путях. */
    static void tryApply() {
        Map<String, String> p = pending;
        if (p == null) return;
        for (String sha : p.values()) if (!ModuleManager.hasObject(sha)) return;
        pending = null;
        Map<String, String> old = current;
        current = Map.copyOf(p);
        Set<String> changed = new HashSet<>();
        for (var e : p.entrySet()) if (!e.getValue().equals(old.get(e.getKey()))) changed.add(e.getKey());
        for (String k : old.keySet()) if (!p.containsKey(k)) changed.add(k);
        if (changed.isEmpty()) return;
        HokaOdysseyClient.LOG.info("Hoka Odyssey: файлов {}, изменилось {}", p.size(), changed.size());
        for (String path : changed) {
            Tex t = TEXTURES.get(path);
            if (t != null) upload(path, t);
            byte[] b = get(path).orElse(null);
            for (Listener l : List.copyOf(LISTENERS)) {
                if (!l.path().equals(path)) continue;
                try {
                    l.fn().accept(b);
                } catch (Throwable ex) {
                    HokaOdysseyClient.LOG.error("Модуль {} упал на файле {}", l.owner(), path, ex);
                    ModuleManager.failed(l.owner());
                }
            }
        }
    }

    static Optional<byte[]> get(String path) {
        String sha = current.get(path);
        if (sha == null) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(ModuleManager.object(sha)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** sha256 файла (как он лежит в кэше объектов) или null. */
    static String sha(String path) {
        return current.get(path);
    }

    static void listen(String owner, String path, Consumer<byte[]> fn) {
        LISTENERS.add(new Listener(owner, path, fn));
        get(path).ifPresent(fn);                                                // уже есть — сразу
    }

    /** Текстура из PNG-файла: id постоянный, содержимое меняется вместе с файлом. Пока файла нет — прозрачная 1×1. */
    static Identifier texture(String path) {
        Tex t = TEXTURES.get(path);
        if (t == null) {
            t = new Tex(Identifier.fromNamespaceAndPath(Net.NS, "dyn/" + sanitize(path)));
            TEXTURES.put(path, t);
            upload(path, t);
        }
        return t.id;
    }

    private static void upload(String path, Tex t) {
        String sha = current.get(path);
        NativeImage img = null;
        if (sha != null) {
            try {
                img = NativeImage.read(Files.readAllBytes(ModuleManager.object(sha)));
            } catch (Exception e) {
                HokaOdysseyClient.LOG.warn("Hoka Odyssey: {} — не PNG: {}", path, e.toString());
            }
        }
        if (img == null) {
            img = new NativeImage(1, 1, true);
            img.setPixel(0, 0, 0);
        }
        t.sha = sha;
        var tm = Minecraft.getInstance().getTextureManager();
        tm.release(t.id);
        NativeImage fin = img;
        tm.register(t.id, new DynamicTexture(() -> "hoka_odyssey " + path, fin));
    }

    static void removeOwner(String owner) {
        LISTENERS.removeIf(l -> l.owner().equals(owner));
    }

    /** Выход с сервера: забыть список, освободить текстуры. */
    static void clear() {
        current = Map.of();
        pending = null;
        LISTENERS.clear();
        var tm = Minecraft.getInstance().getTextureManager();
        for (Tex t : TEXTURES.values()) tm.release(t.id);
        TEXTURES.clear();
    }

    private static String sanitize(String p) {
        StringBuilder sb = new StringBuilder(p.length());
        for (char c : p.toLowerCase().toCharArray()) sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-' ? c : '_');
        return sb.toString();
    }
}
