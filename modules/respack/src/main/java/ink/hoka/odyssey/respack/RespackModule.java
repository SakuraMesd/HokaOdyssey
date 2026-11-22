package ink.hoka.odyssey.respack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import ink.hoka.odyssey.api.OdysseyModule;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerResourcePackStatusEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Общий ресурспак сервера (см. {@link ResourcePackApi}). Сборка — детерминированный zip (одинаковые файлы → тот же
 * SHA-1, клиент не качает заново), раздача — встроенный HTTP (http.port), игрокам — при входе и после пересборки.
 */
public final class RespackModule extends OdysseyModule implements ResourcePackApi {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("hoka:respack".getBytes(StandardCharsets.UTF_8));
    private static final int PACK_FORMAT = 75;                                  // 1.21.11

    /** Файлы модулей: владелец → путь → содержимое. */
    private final Map<String, Map<String, byte[]>> files = new ConcurrentHashMap<>();
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private volatile byte[] zip;
    private volatile String sha1hex;
    private HttpServer http;
    private ExecutorService httpPool;
    private CompletableFuture<Void> pending;
    private volatile Map<String, byte[]> lastFiles = Map.of();                  // файлы последнего собранного пака
    private final List<Runnable> builtListeners = new CopyOnWriteArrayList<>();
    private volatile java.util.function.Predicate<Player> skip = p -> false;
    private final CompletableFuture<Void> vanillaReady = new CompletableFuture<>();
    private volatile ZipFile vanillaZip;

    @Override
    protected void onEnable() {
        services().register(ResourcePackApi.class, this);
        startHttp();
        events().addListener(PlayerSpawnEvent.class, e -> {
            if (!e.isFirstSpawn() || !config().getBoolean("pack.send-on-join", true)) return;
            Player pl = e.getPlayer();
            // чуть подождать: мод Hoka Odyssey успеет поздороваться — ему пак не нужен (фильтр setSendFilter)
            scheduler().later(() -> { if (pl.isOnline()) send(pl); }, Math.max(0, config().getInt("pack.join-delay-ticks", 30)));
        });
        events().addListener(PlayerResourcePackStatusEvent.class, e -> {
            if (!PACK_ID.equals(e.getPackUuid())) return;
            ResourcePackStatus st = e.getStatus();
            if (st == ResourcePackStatus.SUCCESSFULLY_LOADED) loaded.add(e.getPlayer().getUuid());
            else if (!st.intermediate()) {
                loaded.remove(e.getPlayer().getUuid());
                if (config().getBoolean("pack.required", false))
                    e.getPlayer().kick(Component.text("Для игры нужен ресурспак сервера (статус: " + st.name().toLowerCase() + ")"));
            }
        });
        events().addListener(PlayerDisconnectEvent.class, e -> loaded.remove(e.getPlayer().getUuid()));
        scheduler().async(this::downloadVanilla);
        rebuild();
    }

    @Override
    protected void onDisable() {
        if (http != null) http.stop(0);
        if (httpPool != null) httpPool.shutdownNow();
        try { if (vanillaZip != null) vanillaZip.close(); } catch (IOException ignored) { }
    }

    // ───────────────────────────── API ─────────────────────────────

    @Override
    public void put(String owner, String path, byte[] data) {
        files.computeIfAbsent(owner, k -> new ConcurrentHashMap<>()).put(normalize(path), data.clone());
    }

    @Override
    public void remove(String owner, String path) {
        Map<String, byte[]> m = files.get(owner);
        if (m != null) m.remove(normalize(path));
    }

    @Override
    public void clear(String owner) { files.remove(owner); }

    @Override
    public synchronized CompletableFuture<Void> rebuild() {
        if (pending != null && !pending.isDone()) return pending;                  // уже запланирована
        CompletableFuture<Void> f = new CompletableFuture<>();
        pending = f;
        scheduler().later(() -> scheduler().async(() -> {
            try {
                build();
                scheduler().run(() -> {
                    for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) send(p);
                    f.complete(null);
                });
            } catch (Throwable e) {
                logger().error("пак не собран: {}", e.toString());
                f.completeExceptionally(e);
            }
        }), 10);                                                                   // полсекунды: несколько put подряд — одна сборка
        return f;
    }

    @Override
    public boolean hasPack(Player player) { return loaded.contains(player.getUuid()); }

    @Override
    public Optional<String> url() {
        String h = sha1hex;
        return h == null ? Optional.empty() : Optional.of(base() + "/" + h + ".zip");
    }

    @Override
    public Optional<byte[]> vanilla(String path) {
        try { vanillaReady.get(5, TimeUnit.MINUTES); } catch (Exception e) { return Optional.empty(); }
        ZipFile z = vanillaZip;
        if (z == null) return Optional.empty();
        try {
            ZipEntry e = z.getEntry(path);
            if (e == null) return Optional.empty();
            try (InputStream in = z.getInputStream(e)) { return Optional.of(in.readAllBytes()); }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public CompletableFuture<Void> vanillaReady() { return vanillaReady; }

    @Override public Map<String, byte[]> snapshot() { return lastFiles; }

    @Override public void onBuilt(Runnable r) { builtListeners.add(r); }

    @Override public void setSendFilter(java.util.function.Predicate<Player> skip) { this.skip = skip == null ? p -> false : skip; }

    // ───────────────────────────── сборка ─────────────────────────────

    private static String normalize(String p) {
        p = p.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        if (p.contains("..")) throw new IllegalArgumentException("путь с .. : " + p);
        return p;
    }

    private void build() throws Exception {
        TreeMap<String, byte[]> all = new TreeMap<>();
        for (var e : files.entrySet()) for (var f : e.getValue().entrySet()) {
            byte[] prev = all.put(f.getKey(), f.getValue());
            if (prev != null) logger().warn("файл {} кладут несколько модулей — взят последний ({})", f.getKey(), e.getKey());
        }
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", PACK_FORMAT);
        pack.addProperty("min_format", PACK_FORMAT);
        pack.addProperty("max_format", 99);
        pack.addProperty("description", config().getString("pack.description", "Hoka Odyssey"));
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        all.put("pack.mcmeta", root.toString().getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (var e : all.entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setTime(0);                                                     // детерминированный zip
                z.putNextEntry(ze);
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        byte[] data = bos.toByteArray();
        String h = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        zip = data;
        sha1hex = h;
        lastFiles = Collections.unmodifiableMap(new TreeMap<>(all));
        for (Runnable r : builtListeners) {
            try { r.run(); } catch (Throwable ex) { logger().error("слушатель сборки пака упал", ex); }
        }
        logger().info("пак собран: {} файлов, {} КБ → {}", all.size(), data.length / 1024, url().orElse("?"));
    }

    private void send(Player p) {
        String h = sha1hex;
        if (h == null) return;
        try {
            if (skip.test(p)) return;
        } catch (Throwable ignored) { }
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(base() + "/" + h + ".zip"), h);
        p.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(info).replace(false)
                .required(config().getBoolean("pack.required", false))
                .prompt(Component.text(config().getString("pack.prompt", "Ресурспак сервера"), net.kyori.adventure.text.format.NamedTextColor.AQUA)));
    }

    private String base() {
        String b = config().getString("http.public-url", "http://127.0.0.1:" + config().getInt("http.port", 25574));
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    // ───────────────────────────── HTTP ─────────────────────────────

    private void startHttp() {
        int port = config().getInt("http.port", 25574);
        try {
            http = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 16);
            httpPool = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "respack-http"); t.setDaemon(true); return t; });
            http.setExecutor(httpPool);
            http.createContext("/", ex -> {
                try (ex) {
                    String path = ex.getRequestURI().getPath();
                    byte[] data = zip;
                    String h = sha1hex;
                    boolean ok = data != null && ("/pack.zip".equals(path) || ("/" + h + ".zip").equals(path));
                    if (!ok || !"GET".equals(ex.getRequestMethod())) {
                        ex.sendResponseHeaders(404, -1);
                        return;
                    }
                    ex.getResponseHeaders().set("Content-Type", "application/zip");
                    ex.sendResponseHeaders(200, data.length);
                    try (OutputStream out = ex.getResponseBody()) { out.write(data); }
                }
            });
            http.start();
            logger().info("раздача пака: порт {}, адрес для игроков {}", port, base());
        } catch (IOException e) {
            logger().error("HTTP на порту {} не запустился: {} — пак не раздаётся", port, e.toString());
        }
    }

    // ───────────────────────────── ванильный клиент ─────────────────────────────

    /** Скачать клиент нужной версии с серверов Mojang (один раз, с проверкой SHA-1) и открыть как zip. */
    private void downloadVanilla() {
        String ver = config().getString("vanilla.version", "1.21.11");
        Path file = dataFolder().resolve("vanilla").resolve("client-" + ver + ".jar");
        try {
            HttpClient hc = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
            if (!Files.isRegularFile(file)) {
                JsonObject manifest = JsonParser.parseString(get(hc, "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")).getAsJsonObject();
                String versionUrl = null;
                for (JsonElement v : manifest.getAsJsonArray("versions"))
                    if (ver.equals(v.getAsJsonObject().get("id").getAsString())) versionUrl = v.getAsJsonObject().get("url").getAsString();
                if (versionUrl == null) throw new IOException("версии " + ver + " нет в манифесте Mojang");
                JsonObject client = JsonParser.parseString(get(hc, versionUrl)).getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject("client");
                String url = client.get("url").getAsString(), sha = client.get("sha1").getAsString();
                logger().info("скачиваю клиент {} с серверов Mojang ({} МБ)…", ver, client.get("size").getAsLong() / 1_048_576);
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".part");
                HttpResponse<Path> r = hc.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).build(), HttpResponse.BodyHandlers.ofFile(tmp));
                if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
                String got = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(tmp)));
                if (!got.equalsIgnoreCase(sha)) { Files.deleteIfExists(tmp); throw new IOException("SHA-1 не совпал"); }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            vanillaZip = new ZipFile(file.toFile());
            logger().info("ванильные ассеты {} готовы", ver);
            vanillaReady.complete(null);
        } catch (Throwable e) {
            logger().error("ванильный клиент не получен: {} — модели из блоков не будут собираться", e.toString());
            vanillaReady.completeExceptionally(e);
        }
    }

    private static String get(HttpClient hc, String url) throws IOException, InterruptedException {
        HttpResponse<String> r = hc.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " " + url);
        return r.body();
    }
}
