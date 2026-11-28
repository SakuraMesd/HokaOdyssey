package ink.hoka.odyssey.clientmods;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ink.hoka.odyssey.api.OdysseyModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerPluginMessageEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.jar.JarFile;

/**
 * Клиентские модули мода Hoka Odyssey. Берёт jar из {@code module/clientmods/jars/} (в каждом client-module.json),
 * подписывает их список закрытым ключом {@code signing.key} и раздаёт игрокам с модом по каналу ядра
 * (протокол — client/core/.../Net.java). Новый/изменённый клиентский модуль → {@code /hoclient reload} — у игроков
 * подгрузится без перезахода и без переустановки мода.
 */
public final class ClientModsModule extends OdysseyModule implements ClientModsApi {

    static final String CH_S2C = "hoka_odyssey:s", CH_C2S = "hoka_odyssey:c";
    static final int PROTOCOL = 1;

    record Jar(String id, String version, String sha, byte[] bytes) {}

    private volatile List<Jar> jars = List.of();
    /** Ядро мода для автообновления (module/clientmods/core/*.jar — самая новая версия) или null. */
    private volatile Jar coreJar;
    private volatile byte[] manifestPacket;                                     // готовый пакет MANIFEST (подписан)
    // файлы для модулей: владелец → путь → байты; объекты sha → байты; подписанный пакет FILES
    private final Map<String, Map<String, byte[]>> modFiles = new ConcurrentHashMap<>();
    private volatile Map<String, byte[]> fileObjects = Map.of();
    private volatile byte[] filesPacket;
    private final Map<UUID, Set<String>> ready = new ConcurrentHashMap<>();     // игрок (с модом) → загруженные модули
    // вход без лишнего обмена: канал объявлен; список отправлен сам (ядро известно по рукопожатию); клиент ответил NEED/READY
    private final Set<UUID> announced = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pushed = ConcurrentHashMap.newKeySet();
    private final Set<UUID> answered = ConcurrentHashMap.newKeySet();
    /** Версия ядра мода у игрока (из рукопожатия или HELLO). */
    private final Map<UUID, String> coreOf = new ConcurrentHashMap<>();
    /**
     * Ядро 0.88: сервер шлёт блоки мода своими номерами (без носителей). Старее ядро такой номер не знает — Minecraft
     * рвёт связь «ошибкой протокола». Поэтому игроку со старым ядром, когда новое ядро ему ушло, — выход с просьбой
     * перезапустить игру.
     */
    static final String DIRECT_BLOCKS_CORE = "0.88.0";
    private final Map<String, List<BiConsumer<Player, byte[]>>> handlers = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<Player>>> readyHandlers = new ConcurrentHashMap<>();
    private final Map<Integer, BiConsumer<Player, byte[]>> coreHandlers = new ConcurrentHashMap<>();
    // модели на сущностях-носителях: id сущности → что на ней (рассылается игрокам с модом, при входе — заново)
    record ModelState(String path, float scale, String anim, float speed) {}
    private final Map<Integer, ModelState> models = new ConcurrentHashMap<>();
    private final List<net.minestom.server.entity.Entity> testCarriers = new CopyOnWriteArrayList<>();

    @Override
    protected void onEnable() {
        rescan();
        services().register(ClientModsApi.class, this);
        // jar переносимых генераторов планет (ядро) — нашими файлами: подписанный список, клиент кэширует по sha
        services().register(ink.hoka.odyssey.api.GenFilesSink.class, this::publishFiles);
        rescanFiles();
        // объявить канал ядра: мод видит его и здоровается (так ведёт себя и Paper с плагинами). Раньше — по
        // PlayerSpawnEvent, т.е. ПОСЛЕ первой пачки столбцов; теперь при входе в мир (AddEntityToInstanceEvent идёт в
        // Entity.setInstance до sendPendingChunks). Если ядро мода уже известно по рукопожатию на этапе входа — список
        // модулей и файлов уходит сразу же, не дожидаясь HELLO (минус обмен с клиентом: ~1 RTT + очередь пакетов клиента)
        events().addListener(net.minestom.server.event.instance.AddEntityToInstanceEvent.class, e -> {
            if (!(e.getEntity() instanceof Player p) || !announced.add(p.getUuid())) return;
            p.sendPluginMessage("minecraft:register", (CH_C2S + "\0" + CH_S2C).getBytes(StandardCharsets.UTF_8));
            server().clientCore(p).ifPresent(ver -> push(p, ver));
        });
        // модели — после появления (сущности-носители клиенту уже отправлены)
        events().addListener(PlayerSpawnEvent.class, e -> {
            if (e.isFirstSpawn() && pushed.contains(e.getPlayer().getUuid()))
                for (var m : models.entrySet()) sendModel(e.getPlayer(), m.getKey(), m.getValue());
        });
        events().addListener(PlayerPluginMessageEvent.class, e -> {
            if (CH_C2S.equals(e.getIdentifier())) onPacket(e.getPlayer(), e.getMessage());
        });
        events().addListener(PlayerDisconnectEvent.class, e -> {
            UUID u = e.getPlayer().getUuid();
            ready.remove(u);
            announced.remove(u);
            pushed.remove(u);
            coreOf.remove(u);
            answered.remove(u);
        });
        registerCommand(new HoClientCommand());
        // модуль перезагружен на живом сервере — переслать список тем, кто с модом (они поздороваются заново)
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            announced.add(p.getUuid());
            p.sendPluginMessage("minecraft:register", (CH_C2S + "\0" + CH_S2C).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    protected void onDisable() {
        services().unregister(ClientModsApi.class);
        services().unregister(ink.hoka.odyssey.api.GenFilesSink.class);
        ready.clear();
        announced.clear();
        pushed.clear();
        answered.clear();
        for (var e : testCarriers) e.remove();
        testCarriers.clear();
        models.clear();
        handlers.clear();
        readyHandlers.clear();
    }

    // ───────────────────────── список модулей ─────────────────────────

    /** Перечитать jar и подписать новый список. */
    synchronized String rescan() {
        Path dir = dataFolder().resolve("jars");
        List<Jar> list = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
                for (Path p : ds) {
                    try {
                        byte[] bytes = Files.readAllBytes(p);
                        JsonObject desc;
                        try (JarFile jf = new JarFile(p.toFile())) {
                            var en = jf.getEntry("client-module.json");
                            if (en == null) { logger().warn("{}: нет client-module.json — пропускаю", p.getFileName()); continue; }
                            try (InputStream in = jf.getInputStream(en)) {
                                desc = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                            }
                        }
                        list.add(new Jar(desc.get("id").getAsString(), desc.has("version") ? desc.get("version").getAsString() : "?", sha(bytes), bytes));
                    } catch (Exception ex) {
                        logger().warn("{}: не прочитан: {}", p.getFileName(), ex.toString());
                    }
                }
            }
        } catch (IOException e) {
            logger().error("папка {} не читается: {}", dir, e.toString());
        }
        List<String> order = config().getStringList("order");
        list.sort(Comparator.comparingInt((Jar j) -> order.contains(j.id()) ? order.indexOf(j.id()) : Integer.MAX_VALUE).thenComparing(Jar::id));
        jars = List.copyOf(list);

        JsonObject root = new JsonObject();
        root.addProperty("protocol", PROTOCOL);
        JsonArray arr = new JsonArray();
        for (Jar j : list) {
            JsonObject o = new JsonObject();
            o.addProperty("id", j.id());
            o.addProperty("version", j.version());
            o.addProperty("sha", j.sha());
            o.addProperty("size", j.bytes().length);
            arr.add(o);
        }
        root.add("modules", arr);
        coreJar = findCore();
        if (coreJar != null) {
            JsonObject c = new JsonObject();
            c.addProperty("version", coreJar.version());
            c.addProperty("sha", coreJar.sha());
            c.addProperty("size", coreJar.bytes().length);
            root.add("core", c);
        }
        byte[] body = root.toString().getBytes(StandardCharsets.UTF_8);
        byte[] sig = sign(body);
        if (sig == null) {
            manifestPacket = null;
            return "нет ключа подписи (" + dataFolder().resolve("signing.key") + ") — модули не раздаются";
        }
        manifestPacket = packet(1, out -> {
            out.writeInt(body.length);
            out.write(body);
            out.writeInt(sig.length);
            out.write(sig);
        });
        String names = list.stream().map(j -> j.id() + " " + j.version()).reduce((a, b) -> a + ", " + b).orElse("—")
                + (coreJar != null ? "; ядро мода " + coreJar.version() : "");
        logger().info("клиентских модулей: {} ({})", list.size(), names);
        return "модулей " + list.size() + ": " + names;
    }

    /**
     * Ядро мода для автообновления: jar из module/clientmods/core/ с наибольшей версией (из fabric.mod.json).
     * У игроков со старой версией мод скачает его и заменит себя при выходе из игры.
     */
    private Jar findCore() {
        Path dir = dataFolder().resolve("core");
        Jar best = null;
        try {
            Files.createDirectories(dir);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
                for (Path p : ds) {
                    try (JarFile jf = new JarFile(p.toFile())) {
                        var en = jf.getEntry("fabric.mod.json");
                        if (en == null) continue;
                        JsonObject fm;
                        try (InputStream in = jf.getInputStream(en)) {
                            fm = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                        }
                        if (!"hoka_odyssey".equals(fm.get("id").getAsString())) continue;
                        String v = fm.get("version").getAsString();
                        if (best != null && compareVersions(v, best.version()) <= 0) continue;
                        byte[] bytes = Files.readAllBytes(p);
                        best = new Jar("core", v, sha(bytes), bytes);
                    } catch (Exception ex) {
                        logger().warn("ядро {}: не прочитано: {}", p.getFileName(), ex.toString());
                    }
                }
            }
        } catch (IOException e) {
            logger().warn("папка {}: {}", dir, e.toString());
        }
        return best;
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("[.+-]"), y = b.split("[.+-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? intOr0(x[i]) : 0, q = i < y.length ? intOr0(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int intOr0(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private byte[] sign(byte[] body) {
        try {
            Path keyFile = dataFolder().resolve("signing.key");
            if (!Files.isRegularFile(keyFile)) {
                logger().error("нет {} — без подписи мод ничего не загрузит", keyFile);
                return null;
            }
            byte[] der = Base64.getDecoder().decode(Files.readString(keyFile).trim());
            PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(body);
            return s.sign();
        } catch (Exception e) {
            logger().error("подпись не удалась: {}", e.toString());
            return null;
        }
    }

    /**
     * Файлы для клиентских модулей: от серверных модулей ({@link #putFile}) + папка {@code module/clientmods/files/}
     * (путь файла относительно неё). Не ресурсы игры — модули берут их сами (ctx.file / texture), без перезагрузок.
     */
    synchronized String rescanFiles() {
        java.util.TreeMap<String, byte[]> files = new java.util.TreeMap<>();
        Path dir = dataFolder().resolve("files");
        if (Files.isDirectory(dir)) {
            try (var walk = Files.walk(dir)) {
                for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator)
                    files.put(dir.relativize(f).toString().replace(java.io.File.separatorChar, '/'), Files.readAllBytes(f));
            } catch (IOException e) {
                logger().warn("папка files не прочитана: {}", e.toString());
            }
        }
        for (var m : modFiles.values()) files.putAll(m);
        try { files.putAll(server().generatorFiles()); }                        // jar переносимых генераторов (ядро)
        catch (RuntimeException e) { logger().warn("jar генераторов не взяты: {}", e.toString()); }
        Map<String, byte[]> objects = new HashMap<>();
        JsonObject map = new JsonObject();
        long total = 0;
        for (var e : files.entrySet()) {
            String h = sha(e.getValue());
            objects.put(h, e.getValue());
            map.addProperty(e.getKey(), h);
            total += e.getValue().length;
        }
        JsonObject root = new JsonObject();
        root.add("files", map);
        byte[] body = root.toString().getBytes(StandardCharsets.UTF_8);
        byte[] sig = sign(body);
        fileObjects = objects;
        filesPacket = sig == null ? null : packet(5, out -> {
            out.writeInt(body.length);
            out.write(body);
            out.writeInt(sig.length);
            out.write(sig);
        });
        return files.size() + " (" + total / 1024 + " КБ)";
    }

    void broadcastFiles() {
        byte[] f = filesPacket;
        if (f == null) return;
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers())
            if (hasMod(p)) p.sendPluginMessage(CH_S2C, f);
    }

    @Override public void putFile(String owner, String path, byte[] data) {
        modFiles.computeIfAbsent(owner, k -> new ConcurrentHashMap<>()).put(path.replace('\\', '/'), data.clone());
    }

    @Override public void removeFile(String owner, String path) {
        Map<String, byte[]> m = modFiles.get(owner);
        if (m != null) m.remove(path);
    }

    @Override public void clearFiles(String owner) { modFiles.remove(owner); }

    @Override public void publishFiles() {
        rescanFiles();
        broadcastFiles();
    }

    /** Разослать новый список всем игрокам с модом (после /hoclient reload). */
    void broadcastManifest() {
        byte[] m = manifestPacket;
        if (m == null) return;
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers())
            if (hasMod(p)) p.sendPluginMessage(CH_S2C, m);
    }

    // ───────────────────────── протокол ─────────────────────────

    private void onPacket(Player p, byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int op = in.readUnsignedByte();
            switch (op) {
                case 1 -> {                                                     // HELLO
                    int proto = in.readInt();
                    String ver = in.readUTF();
                    if (pushed.contains(p.getUuid())) {
                        // список уже ушёл при входе (ядро 0.57 и старше всё равно здороваются). Не слать заново — иначе
                        // клиент второй раз попросит то, что ещё качается. Запасной путь: клиент так и не ответил на
                        // присланный список (ни NEED, ни READY) — переслать через 2 с, как раньше по HELLO
                        logger().info("{}: мод Hoka Odyssey {} (протокол {}), список модулей ушёл при входе", p.getUsername(), ver, proto);
                        if (!answered.contains(p.getUuid())) scheduler().later(() -> {
                            if (p.isOnline() && pushed.contains(p.getUuid()) && !answered.contains(p.getUuid())) {
                                logger().warn("{}: на список модулей ответа нет — шлю заново", p.getUsername());
                                sendLists(p);                           // в нём сброс моделей — разослать их заново
                                for (var m : models.entrySet()) sendModel(p, m.getKey(), m.getValue());
                            }
                        }, 40);
                        return;
                    }
                    ready.put(p.getUuid(), ConcurrentHashMap.newKeySet());
                    coreOf.put(p.getUuid(), ver);
                    logger().info("{}: мод Hoka Odyssey {} (протокол {})", p.getUsername(), ver, proto);
                    warnOldCore(p, ver);
                    sendLists(p);
                    for (var e : models.entrySet()) sendModel(p, e.getKey(), e.getValue());
                }
                case 2 -> {                                                     // NEED
                    answered.add(p.getUuid());
                    int n = Math.min(in.readInt(), 100000);
                    List<String> shas = new ArrayList<>();
                    for (int i = 0; i < n; i++) shas.add(in.readUTF());
                    sendJars(p, shas);
                }
                case 3 -> {                                                     // READY
                    int n = Math.min(in.readInt(), 256);
                    Set<String> now = ConcurrentHashMap.newKeySet();
                    for (int i = 0; i < n; i++) now.add(in.readUTF());
                    answered.add(p.getUuid());
                    ready.put(p.getUuid(), now);
                    logger().info("{}: клиентские модули загружены: {}", p.getUsername(), now);
                    server().joinStage(p, "модули");
                    for (String id : now) {
                        // и обновлённый модуль (тот же id) — тоже: у него новое состояние, ему нужно всё заново
                        for (Consumer<Player> h : readyHandlers.getOrDefault(id, List.of())) safe(id, () -> h.accept(p));
                    }
                }
                case 4 -> {                                                     // MSG
                    String id = in.readUTF();
                    int len = in.readInt();
                    if (len < 0 || len > (1 << 20)) return;
                    byte[] b = new byte[len];
                    in.readFully(b);
                    for (BiConsumer<Player, byte[]> h : handlers.getOrDefault(id, List.of())) safe(id, () -> h.accept(p, b));
                }
                default -> {
                    BiConsumer<Player, byte[]> h = coreHandlers.get(op);
                    if (h == null) { logger().warn("{}: неизвестная операция {}", p.getUsername(), op); return; }
                    byte[] rest = in.readAllBytes();
                    safe("core:" + op, () -> h.accept(p, rest));
                }
            }
        } catch (Exception e) {
            logger().warn("{}: пакет мода не разобран: {}", p.getUsername(), e.toString());
        }
    }

    /** Список модулей, файлов и сброс моделей — ответ на HELLO (или сразу при входе, см. {@link #push}). */
    private void sendLists(Player p) {
        if (manifestPacket != null) p.sendPluginMessage(CH_S2C, manifestPacket);
        if (filesPacket != null) p.sendPluginMessage(CH_S2C, filesPacket);
        p.sendPluginMessage(CH_S2C, packet(6, out -> out.writeByte(4)));
    }

    /** Ядро у игрока старше раздаваемого — скачается само; предупредить. */
    private void warnOldCore(Player p, String ver) {
        Jar core = coreJar;
        if (core != null && compareVersions(ver, core.version()) < 0)
            p.sendMessage(Component.text("Мод Hoka Odyssey обновляется до " + core.version()
                    + " — перезапустите игру, когда скачается (без перезапуска часть нового не работает).", NamedTextColor.YELLOW));
    }

    /**
     * Вход игрока, чьё ядро ответило на рукопожатие (hoka_odyssey:hi, ядро 0.29+): всё, что раньше ждало HELLO, — сразу,
     * следом за minecraft:register (клиент разбирает пакеты по порядку — к приходу списка канал ему уже объявлен).
     * Модели — по PlayerSpawnEvent.
     */
    private void push(Player p, String ver) {
        if (!pushed.add(p.getUuid())) return;
        ready.put(p.getUuid(), ConcurrentHashMap.newKeySet());
        coreOf.put(p.getUuid(), ver);
        logger().info("{}: мод Hoka Odyssey {} (из рукопожатия) — список модулей сразу", p.getUsername(), ver);
        warnOldCore(p, ver);
        sendLists(p);
    }

    private void sendJars(Player p, List<String> shas) {
        int chunk = Math.clamp(config().getInt("chunk", 262144), 4096, 1 << 20);
        Map<String, Jar> bySha = new HashMap<>();
        for (Jar j : jars) bySha.put(j.sha(), j);
        Jar core = coreJar;
        if (core != null) bySha.put(core.sha(), core);
        Map<String, byte[]> fobjs = fileObjects;
        for (String sha : shas) {
            Jar j = bySha.get(sha);
            final byte[] b = j != null ? j.bytes() : fobjs.get(sha);
            if (b == null) continue;
            if (core != null && sha.equals(core.sha())) kickAfterCore(p, core);
            for (int off = 0; off < b.length; off += chunk) {
                int o = off, len = Math.min(chunk, b.length - off);
                p.sendPluginMessage(CH_S2C, packet(2, out -> {
                    out.writeUTF(sha);
                    out.writeInt(b.length);
                    out.writeInt(o);
                    out.writeInt(len);
                    out.write(b, o, len);
                }));
            }
        }
    }

    /**
     * Новое ядро ушло игроку (куски идут по порядку, выход — после них): если его ядро не знает блоков мода по номерам
     * (старее {@link #DIRECT_BLOCKS_CORE}), а новое знает — через 3 с выход с просьбой перезапустить игру.
     */
    private void kickAfterCore(Player p, Jar core) {
        String ver = coreOf.get(p.getUuid());
        if (ver == null || compareVersions(ver, DIRECT_BLOCKS_CORE) >= 0 || compareVersions(core.version(), DIRECT_BLOCKS_CORE) < 0) return;
        logger().info("{}: ядро {} → {} ушло, выход для перезапуска", p.getUsername(), ver, core.version());
        scheduler().later(() -> {
            if (p.isOnline()) p.kick(Component.text("Мод Hoka Odyssey обновлён до " + core.version()
                    + ". Перезапустите игру — со старым модом новые блоки не видны.", NamedTextColor.YELLOW));
        }, 60);
    }

    private void safe(String id, Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            logger().error("обработчик клиентского модуля {} упал", id, e);
        }
    }

    interface W { void write(DataOutputStream out) throws IOException; }

    static byte[] packet(int op, W w) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(op);
            w.write(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ───────────────────────── ClientModsApi ─────────────────────────

    @Override public boolean hasMod(Player p) { return ready.containsKey(p.getUuid()); }

    @Override public boolean isReady(Player p, String module) {
        Set<String> s = ready.get(p.getUuid());
        return s != null && s.contains(module);
    }

    @Override public void send(Player p, String module, byte[] data) {
        if (!isReady(p, module)) return;
        p.sendPluginMessage(CH_S2C, packet(3, out -> {
            out.writeUTF(module);
            out.writeInt(data.length);
            out.write(data);
        }));
    }

    @Override public void sendCore(Player p, int op, byte[] payload) {
        if (!hasMod(p)) return;
        p.sendPluginMessage(CH_S2C, packet(op, out -> out.write(payload)));
    }

    @Override public void onCore(int op, BiConsumer<Player, byte[]> handler) { coreHandlers.put(op, handler); }

    @Override public void offCore(int op) { coreHandlers.remove(op); }

    @Override public void on(String module, BiConsumer<Player, byte[]> handler) {
        handlers.computeIfAbsent(module, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override public void onReady(String module, Consumer<Player> handler) {
        readyHandlers.computeIfAbsent(module, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override public void off(String module) {
        handlers.remove(module);
        readyHandlers.remove(module);
    }

    // ───────────────────────── модели ─────────────────────────

    private void sendModel(Player p, int id, ModelState m) {
        p.sendPluginMessage(CH_S2C, packet(6, out -> {
            out.writeByte(1);
            out.writeInt(id);
            out.writeUTF(m.path());
            out.writeFloat(m.scale());
        }));
        if (m.anim() != null) p.sendPluginMessage(CH_S2C, animPacket(id, m.anim(), m.speed()));
    }

    private static byte[] animPacket(int id, String anim, float speed) {
        return packet(6, out -> {
            out.writeByte(2);
            out.writeInt(id);
            out.writeUTF(anim == null ? "" : anim);
            out.writeFloat(speed);
        });
    }

    private void toMods(byte[] pkt) {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers())
            if (hasMod(p)) p.sendPluginMessage(CH_S2C, pkt);
    }

    @Override public void attachModel(net.minestom.server.entity.Entity e, String path, float scale) {
        ModelState m = new ModelState(path, scale, null, 1);
        models.put(e.getEntityId(), m);
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) if (hasMod(p)) sendModel(p, e.getEntityId(), m);
    }

    @Override public void playModelAnim(net.minestom.server.entity.Entity e, String anim, float speed) {
        ModelState m = models.get(e.getEntityId());
        if (m == null) return;
        models.put(e.getEntityId(), new ModelState(m.path(), m.scale(), anim, speed));
        toMods(animPacket(e.getEntityId(), anim, speed));
    }

    @Override public void detachModel(net.minestom.server.entity.Entity e) {
        if (models.remove(e.getEntityId()) == null) return;
        toMods(packet(6, out -> { out.writeByte(3); out.writeInt(e.getEntityId()); }));
    }

    // ───────────────────────── команда ─────────────────────────

    private final class HoClientCommand extends Command {
        HoClientCommand() {
            super("hoclient");
            setCondition((s, c) -> !(s instanceof Player p) || server().isOp(p));
            setDefaultExecutor((s, c) -> {
                s.sendMessage(Component.text("Клиентские модули: " + jars.size() + (manifestPacket == null ? " (НЕ подписаны — нет ключа)" : ""), NamedTextColor.AQUA));
                for (Jar j : jars) s.sendMessage(Component.text("  " + j.id() + " " + j.version() + "  " + j.bytes().length / 1024 + " КБ  " + j.sha().substring(0, 12), NamedTextColor.GRAY));
                long mods = MinecraftServer.getConnectionManager().getOnlinePlayers().stream().filter(ClientModsModule.this::hasMod).count();
                s.sendMessage(Component.text("Игроков с модом: " + mods + ".  /hoclient reload — перечитать и разослать", NamedTextColor.GRAY));
            });
            addSyntax((s, c) -> {
                String r = rescan() + "; файлы: " + rescanFiles();
                broadcastManifest();
                broadcastFiles();
                s.sendMessage(Component.text("Перечитано и разослано: " + r, NamedTextColor.GREEN));
            }, ArgumentType.Literal("reload"));
            var text = ArgumentType.StringArray("текст");
            addSyntax((s, c) -> {
                byte[] b = String.join(" ", c.get(text)).getBytes(StandardCharsets.UTF_8);
                int n = 0;
                for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
                    if (isReady(p, "hello")) { send(p, "hello", b); n++; }
                }
                s.sendMessage(Component.text("hello отправлен игрокам: " + n, NamedTextColor.GREEN));
            }, ArgumentType.Literal("hello"), text);
            var path = ArgumentType.String("путь");
            var scale = ArgumentType.Float("масштаб");
            net.minestom.server.command.builder.CommandExecutor spawn = (s, c) -> {
                if (!(s instanceof Player p)) return;
                var e = new net.minestom.server.entity.Entity(net.minestom.server.entity.EntityType.INTERACTION);
                var meta = (net.minestom.server.entity.metadata.other.InteractionMeta) e.getEntityMeta();
                meta.setWidth(0.01f);
                meta.setHeight(0.01f);
                e.setNoGravity(true);
                var pos = p.getPosition();
                e.setInstance(p.getInstance(), new net.minestom.server.coordinate.Pos(pos.x(), pos.y(), pos.z(), pos.yaw() + 180, 0));
                testCarriers.add(e);
                float sc = c.has(scale) ? c.get(scale) : 1f;
                attachModel(e, c.get(path), sc);
                s.sendMessage(Component.text("Модель " + c.get(path) + " на носителе #" + e.getEntityId() + " (масштаб " + sc + ")", NamedTextColor.GREEN));
            };
            addSyntax(spawn, ArgumentType.Literal("model"), path);
            addSyntax(spawn, ArgumentType.Literal("model"), path, scale);
            var anim = ArgumentType.String("анимация");
            addSyntax((s, c) -> {
                String a = c.get(anim);
                for (var e : testCarriers) playModelAnim(e, "stop".equals(a) ? null : a, 1f);
                s.sendMessage(Component.text("Анимация " + a + " на " + testCarriers.size() + " моделях", NamedTextColor.GREEN));
            }, ArgumentType.Literal("anim"), anim);
            addSyntax((s, c) -> {
                int n = testCarriers.size();
                for (var e : testCarriers) { detachModel(e); e.remove(); }
                testCarriers.clear();
                s.sendMessage(Component.text("Убрано моделей: " + n, NamedTextColor.GREEN));
            }, ArgumentType.Literal("unmodel"));
        }
    }

    /** Ответы шаблонного клиентского модуля hello — в лог (проверка связи клиент → сервер). */
    @Override
    protected void onLoad() {}

    {
        on("hello", (p, b) -> logger().info("{} → hello: {}", p.getUsername(), new String(b, StandardCharsets.UTF_8)));
    }
}
