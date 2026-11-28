package ink.hoka.odyssey.holo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ink.hoka.odyssey.api.OdysseyModule;
import ink.hoka.odyssey.clientmods.ClientModsApi;
import ink.hoka.odyssey.hoitems.HoItems;
import ink.hoka.odyssey.holo.Hologram.Action;
import ink.hoka.odyssey.houi.HoUi;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.NetworkBuffer;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Голограммы: панели в мире, которые рисует клиентский модуль holo (кубоидный космический стиль). Сервер хранит
 * голограммы ({@code module/holo/holograms.json}), шлёт моду ближние ({@link Net}), считает плейсхолдеры для каждого
 * игрока, проверяет нажатия ({@link ClickGuard}) и выполняет действия, принимает правки редактора (только OP).
 * Игрок без мода голограмм не видит (ванильного запасного вида нет).
 */
public final class HoloModule extends OdysseyModule implements HoloApi {

    static final String MODULE = "holo";
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    final Map<String, Hologram> holos = new ConcurrentHashMap<>();
    final Map<String, Hologram> templates = new ConcurrentHashMap<>();
    private HoloStore store, tstore;
    private volatile boolean broken, tbroken;
    private final AtomicReference<String> pending = new AtomicReference<>(), tpending = new AtomicReference<>();

    private ClientModsApi api;
    final Placeholders<Player, UUID> ph = new Placeholders<>(Player::getUuid);
    private ClickGuard guard;
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private final List<ClickListener> listeners = new CopyOnWriteArrayList<>();
    private volatile BiPredicate<Player, String> perms = (p, n) -> false;
    private final Set<String> images = new java.util.concurrent.ConcurrentSkipListSet<>();

    private int checkPeriod = 10;
    private double margin = 16;
    private String themesJson = "{}";
    private long tick;

    /** Что отправлено игроку: карта, голограммы, страницы, нужные плейсхолдеры. */
    static final class Viewer {
        String map;
        final Set<String> sent = new HashSet<>();
        final Map<String, Integer> pages = new HashMap<>();
        Set<String> wanted = Set.of();
        boolean force;
    }

    @Override
    protected void onEnable() {
        readConfig();
        store = new HoloStore(dataFolder().resolve("holograms.json"));
        tstore = new HoloStore(dataFolder().resolve("templates.json"));
        String err = loadAll();
        if (err != null) logger().error("{} — сохранение выключено до /hoholo reload", err);
        logger().info("голограмм: {}, шаблонов: {}", holos.size(), templates.size());
        builtInPlaceholders();

        api = services().get(ClientModsApi.class).orElse(null);
        if (api == null) logger().warn("нет clientmods — голограммы никто не увидит");
        else {
            publishImages();
            api.onReady(MODULE, p -> {                                          // модуль мода загрузился (или обновился)
                reset(p);
                sync(p);
            });
            api.on(MODULE, (p, data) -> scheduler().run(() -> onPacket(p, data)));
        }
        services().register(HoloApi.class, this);
        events().addListener(PlayerDisconnectEvent.class, e -> forget(e.getPlayer().getUuid()));
        registerCommand(new HoloCommand(this));
        scheduler().repeat(this::tick, 1, 1);
    }

    @Override
    protected void onDisable() {
        if (api != null) {
            for (Player p : online()) if (api.isReady(p, MODULE)) api.send(p, MODULE, Net.clear());
            api.off(MODULE);
            api.clearFiles(MODULE);
        }
        services().unregister(HoloApi.class);
        flush();
        flushTemplates();
        listeners.clear();
        viewers.clear();
        ph.clear();
    }

    private void readConfig() {
        checkPeriod = Math.max(1, config().getInt("check-period", 10));
        margin = Math.max(0, config().getDouble("send-margin", 16));
        guard = new ClickGuard(config().getDouble("click-reach", 8), config().getInt("default-cooldown", 4));
        JsonObject th = new JsonObject();
        for (String t : Hologram.THEMES) {
            JsonObject o = new JsonObject();
            for (String k : List.of("bg", "frame", "glow", "accent", "text")) {
                String v = config().getString("themes." + t + "." + k, null);
                if (v != null) o.addProperty(k, v.replace("#", ""));
            }
            th.add(t, o);
        }
        themesJson = HoloCodec.COMPACT.toJson(th);
    }

    // ───────────────────────────── файлы ─────────────────────────────

    /** Прочитать голограммы и шаблоны. null — ок, иначе текст ошибки. */
    String loadAll() {
        String err = null;
        Map<String, Hologram> temp = new LinkedHashMap<>();
        for (Hologram h : holos.values()) if (h.temporary) temp.put(h.id, h);
        try {
            List<Hologram> list = store.load();
            holos.clear();
            for (Hologram h : list) holos.put(h.id, h);
            broken = false;
        } catch (HoloStore.Broken e) {
            holos.clear();
            broken = true;
            err = e.getMessage();
        }
        holos.putAll(temp);
        try {
            boolean fresh = !Files.isRegularFile(tstore.file());                // встроенные — только в новый файл (удалённые не возвращаются)
            Map<String, Hologram> t = tstore.loadTemplates();
            boolean add = false;
            if (fresh) for (var e : Templates.builtIn().entrySet()) if (t.putIfAbsent(e.getKey(), e.getValue()) == null) add = true;
            templates.clear();
            templates.putAll(t);
            tbroken = false;
            if (add) savedTemplates();
        } catch (HoloStore.Broken e) {
            templates.clear();
            templates.putAll(Templates.builtIn());
            tbroken = true;
            err = err == null ? e.getMessage() : err + "; " + e.getMessage();
        }
        return err;
    }

    boolean broken() { return broken; }

    /** После правки: текст файла — сейчас, запись — в отдельном потоке. */
    void saved() {
        if (broken) return;
        pending.set(HoloStore.serialize(sorted()));
        scheduler().async(this::flush);
    }

    private void savedTemplates() {
        if (tbroken) return;
        tpending.set(HoloStore.serializeTemplates(new java.util.TreeMap<>(templates)));
        scheduler().async(this::flushTemplates);
    }

    private void flush() { write(store, pending); }

    private void flushTemplates() { write(tstore, tpending); }

    private void write(HoloStore s, AtomicReference<String> ref) {
        String text = ref.getAndSet(null);
        if (text == null) return;
        try { s.write(text); }
        catch (Exception e) {
            logger().error("не записать {}: {}", s.file(), e.toString());
            ref.compareAndSet(null, text);
        }
    }

    List<Hologram> sorted() {
        List<Hologram> l = new ArrayList<>(holos.values());
        l.sort(Comparator.comparing(h -> h.id));
        return l;
    }

    /** Картинки module/holo/images/*.png → файлы мода «holo/img/имя». */
    void publishImages() {
        images.clear();
        if (api == null) return;
        api.clearFiles(MODULE);
        Path dir = dataFolder().resolve("images");
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                for (Path f : s.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".png")).toList()) {
                    String name = f.getFileName().toString();
                    if (!name.matches("[A-Za-z0-9_\\-.]{1,80}")) { logger().warn("картинка {}: плохое имя файла", name); continue; }
                    api.putFile(MODULE, "holo/img/" + name, Files.readAllBytes(f));
                    images.add(name);
                }
            } catch (IOException e) {
                logger().warn("картинки: {}", e.toString());
            }
        }
        api.publishFiles();
    }

    // ───────────────────────────── плейсхолдеры ─────────────────────────────

    private void builtInPlaceholders() {
        Function<String, Integer> per = n -> config().getInt("placeholders." + n, 20);
        ph.register("player", per.apply("player"), MODULE, Player::getUsername);
        ph.register("online", per.apply("online"), MODULE, p -> String.valueOf(MinecraftServer.getConnectionManager().getOnlinePlayers().size()));
        ph.register("time", per.apply("time"), MODULE, p -> LocalTime.now().format(TIME));
        ph.register("map", per.apply("map"), MODULE, p -> { String m = mapOf(p); return m == null ? "" : m; });
        ph.register("x", per.apply("x"), MODULE, p -> String.valueOf(p.getPosition().blockX()));
        ph.register("y", per.apply("y"), MODULE, p -> String.valueOf(p.getPosition().blockY()));
        ph.register("z", per.apply("z"), MODULE, p -> String.valueOf(p.getPosition().blockZ()));
    }

    // ───────────────────────────── игроки ─────────────────────────────

    static Collection<Player> online() { return MinecraftServer.getConnectionManager().getOnlinePlayers(); }

    String mapOf(Player p) {
        Instance i = p.getInstance();
        return i == null ? null : server().mapName(i).orElse(null);
    }

    boolean isOp(Player p) { return server().isOp(p); }

    /** Всё отправленное — заново (страницы игрока сохраняются). */
    private void reset(Player p) {
        Viewer v = viewers.get(p.getUuid());
        if (v != null) { v.map = null; v.sent.clear(); v.wanted = Set.of(); }
        ph.forget(p.getUuid());
    }

    private void forget(UUID id) {
        viewers.remove(id);
        ph.forget(id);
        guard.forget(id.toString());
    }

    ClickGuard.Who who(Player p) {
        String map = mapOf(p);
        Pos pos = p.getPosition();
        boolean op = isOp(p);
        BiPredicate<Player, String> pc = perms;
        return new ClickGuard.Who() {
            public String map() { return map == null ? "" : map; }
            public double x() { return pos.x(); }
            public double y() { return pos.y() + p.getEyeHeight(); }
            public double z() { return pos.z(); }
            public boolean op() { return op; }
            public boolean perm(String node) { try { return pc.test(p, node); } catch (RuntimeException e) { return false; } }
        };
    }

    private boolean ready(Player p) { return api != null && api.isReady(p, MODULE); }

    private void hello(Player p) {
        String map = mapOf(p);
        api.send(p, MODULE, Net.hello(isOp(p), map == null ? "" : map, themesJson));
    }

    private void tick() {
        tick++;
        if (api == null) return;
        boolean check = tick % checkPeriod == 0;
        for (Player p : online()) {
            if (!ready(p)) continue;
            try {
                if (check) sync(p);
                Viewer v = viewers.get(p.getUuid());
                if (v == null) continue;
                Map<String, String> due = ph.due(p, v.wanted, tick, v.force);
                v.force = false;
                if (!due.isEmpty()) api.send(p, MODULE, Net.values(due));
            } catch (RuntimeException e) {
                logger().warn("голограммы для {}: {}", p.getUsername(), e.toString());
            }
        }
    }

    /** Должна ли голограмма быть у игрока (с запасом extra к дальности). */
    private boolean wants(Hologram h, String map, ClickGuard.Who w, double extra) {
        if (!h.map.equals(map) || !ClickGuard.visible(h, w)) return false;
        double r = h.range + extra;
        return h.distSq(w.x(), w.y(), w.z()) <= r * r;
    }

    /** Ближние голограммы — моду; ушедшие далеко — убрать; смена карты — всё заново. */
    void sync(Player p) {
        if (!ready(p)) return;
        Viewer v = viewers.computeIfAbsent(p.getUuid(), k -> new Viewer());
        String map = mapOf(p);
        if (map == null) return;
        if (!map.equals(v.map)) {                                              // первый раз (в т.ч. после reload модуля) или смена карты
            if (v.map != null) api.send(p, MODULE, Net.clear());
            hello(p);
            v.map = map;
            v.sent.clear();
            ph.forget(p.getUuid());
            rewant(v);
        }
        ClickGuard.Who w = who(p);
        boolean changed = false;
        for (Hologram h : holos.values()) {
            boolean has = v.sent.contains(h.id);
            if (!has && wants(h, map, w, margin)) {
                sendHolo(p, v, h);
                changed = true;
            } else if (has && !wants(h, map, w, margin * 2)) {
                v.sent.remove(h.id);
                api.send(p, MODULE, Net.remove(h.id, false));
                changed = true;
            }
        }
        if (changed) rewant(v);
    }

    private void sendHolo(Player p, Viewer v, Hologram h) {
        v.sent.add(h.id);
        int page = Math.floorMod(v.pages.getOrDefault(h.id, 0), Math.max(1, h.pages.size()));
        api.send(p, MODULE, Net.set(HoloCodec.compact(h), page, items(h)));
        v.force = true;
    }

    private void rewant(Viewer v) {
        Set<String> w = new LinkedHashSet<>();
        for (String id : v.sent) {
            Hologram h = holos.get(id);
            if (h != null) w.addAll(h.placeholders());
        }
        v.wanted = w;
        v.force = true;
    }

    /** Предметы голограммы в сетевом виде игры (свои — через hoitems). */
    Map<String, byte[]> items(Hologram h) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (var pg : h.pages) for (var l : pg.lines) for (var e : l.elements) {
            if (e.type != Hologram.Type.ITEM || e.item == null || out.containsKey(e.item)) continue;
            ItemStack it = resolveItem(e.item);
            try { out.put(e.item, NetworkBuffer.makeArray(buf -> buf.write(ItemStack.NETWORK_TYPE, it), MinecraftServer.process())); }
            catch (RuntimeException ex) { logger().warn("предмет {}: {}", e.item, ex.toString()); }
        }
        return out;
    }

    ItemStack resolveItem(String id) {
        String s = id.trim();
        if (s.startsWith("hoka:") || !s.contains(":")) {
            String hid = s.startsWith("hoka:") ? s.substring(5) : s;
            Optional<HoItems> hi = hoItems();
            if (hi.isPresent()) {
                ItemStack it = hi.get().item(hid, 1);
                if (it != null) return it;
            }
        }
        Material m = Material.fromKey(s.contains(":") ? s : "minecraft:" + s);
        return ItemStack.of(m == null ? Material.BARRIER : m);
    }

    private Optional<HoItems> hoItems() {
        try { return services().get(HoItems.class); }
        catch (LinkageError e) { return Optional.empty(); }                   // hoitems не установлен
    }

    /** Голограмма изменилась (или новая): тем, у кого она есть или должна быть, — заново; кому больше не положена — убрать. */
    void changed(Hologram h) {
        if (api == null) return;
        for (Player p : online()) {
            if (!ready(p)) continue;
            Viewer v = viewers.get(p.getUuid());
            if (v == null || v.map == null) continue;
            boolean had = v.sent.remove(h.id);
            if (wants(h, v.map, who(p), margin * (had ? 2 : 1))) sendHolo(p, v, h);
            else if (had) api.send(p, MODULE, Net.remove(h.id, false));
            rewant(v);
        }
    }

    void removed(String id) {
        if (api == null) return;
        for (Player p : online()) {
            Viewer v = viewers.get(p.getUuid());
            if (v == null) continue;
            v.pages.remove(id);
            if (v.sent.remove(id)) {
                api.send(p, MODULE, Net.remove(id, true));
                rewant(v);
            }
        }
    }

    // ───────────────────────────── пакеты мода ─────────────────────────────

    private void onPacket(Player p, byte[] data) {
        try (DataInputStream in = Net.in(data)) {
            int op = in.readUnsignedByte();
            if (op == Net.C_CLICK) {
                String id = in.readUTF();
                int page = in.readInt(), line = in.readInt(), elem = in.readInt();
                boolean right = in.readUnsignedByte() == 1;
                click(p, id, page, line, elem, right);
                return;
            }
            if (!isOp(p)) {                                                     // всё остальное — редактор
                api.send(p, MODULE, Net.result(false, "Редактор голограмм — только для OP"));
                return;
            }
            switch (op) {
                case Net.C_SAVE -> editorSave(p, Net.str(in, 1 << 20));
                case Net.C_DELETE -> {
                    String id = in.readUTF();
                    Hologram h = holos.get(id);
                    if (h == null || h.temporary) { api.send(p, MODULE, Net.result(false, "Нет голограммы «" + id + "»")); return; }
                    remove(id);
                    api.send(p, MODULE, Net.result(true, "Голограмма «" + id + "» удалена"));
                }
                case Net.C_TP -> {
                    Hologram h = holos.get(in.readUTF());
                    if (h != null) teleport(p, h);
                }
                case Net.C_TPL_SAVE -> {
                    String name = in.readUTF().trim();
                    String json = Net.str(in, 1 << 20);
                    if (name.isEmpty() || name.length() > 48) { api.send(p, MODULE, Net.result(false, "Имя шаблона — 1…48 символов")); return; }
                    try {
                        templates.put(name, HoloCodec.parse(json));
                        savedTemplates();
                        api.send(p, MODULE, Net.result(true, "Шаблон «" + name + "» сохранён"));
                        api.send(p, MODULE, Net.info(info()));
                    } catch (RuntimeException e) {
                        api.send(p, MODULE, Net.result(false, "Шаблон: " + e.getMessage()));
                    }
                }
                case Net.C_TPL_DELETE -> {
                    if (templates.remove(in.readUTF()) != null) savedTemplates();
                    api.send(p, MODULE, Net.info(info()));
                }
                case Net.C_INFO_REQ -> api.send(p, MODULE, Net.info(info()));
                default -> { }
            }
        } catch (IOException | RuntimeException e) {
            logger().warn("holo: битый пакет от {}: {}", p.getUsername(), e.toString());
        }
    }

    /** Правка из редактора: разобрать, проверить, заменить, записать, разослать. */
    void editorSave(Player p, String json) {
        Hologram h;
        try { h = HoloCodec.parse(json); }
        catch (RuntimeException e) {
            String id;
            try { id = com.google.gson.JsonParser.parseString(json).getAsJsonObject().get("id").getAsString(); } catch (RuntimeException x) { id = ""; }
            api.send(p, MODULE, Net.result(false, "Не сохранено: " + e.getMessage(), id));
            return;
        }
        Hologram old = holos.get(h.id);
        if (old != null && old.temporary) {
            api.send(p, MODULE, Net.result(false, "«" + h.id + "» — временная голограмма модуля, её правит код", h.id));
            return;
        }
        h.creatorOp = true;                                                     // правил OP — проверено выше
        holos.put(h.id, h);
        saved();
        changed(h);
        api.send(p, MODULE, Net.result(true, (old == null ? "Создана «" : "Сохранена «") + h.id + "»" + (broken ? " (файл битый — не записано!)" : ""), h.id));
    }

    void click(Player p, String id, int page, int line, int elem, boolean right) {
        Hologram h = holos.get(id);
        Viewer v = viewers.get(p.getUuid());
        int cur = v == null ? 0 : v.pages.getOrDefault(id, 0);
        if (h != null && page != Math.floorMod(cur, Math.max(1, h.pages.size()))) return;   // нажали на чужую страницу
        ClickGuard.Result r = guard.check(h, p.getUuid().toString(), who(p), page, line, elem, right, tick);
        if (r.verdict() != ClickGuard.Verdict.OK) {
            if (r.verdict() == ClickGuard.Verdict.DENIED) p.sendActionBar(Component.text("Нет доступа", NamedTextColor.RED));
            return;
        }
        for (Action a : r.actions()) run(p, h, a);
        for (ClickListener l : listeners) {
            try { l.click(p, h, page, line, elem, right); }
            catch (RuntimeException e) { logger().warn("слушатель нажатий: {}", e.toString()); }
        }
    }

    void run(Player p, Hologram h, Action a) {
        String val = ph.fill(p, a.value());
        try {
            switch (a.type()) {
                case CMD -> MinecraftServer.getCommandManager().execute(p, strip(val));
                case CONSOLE -> {
                    if (h.creatorOp) MinecraftServer.getCommandManager().executeServerCommand(strip(val));
                }
                case MSG -> p.sendMessage(MiniLite.parse(val));
                case SOUND -> {
                    String[] s = val.trim().split("\\s+");
                    float vol = s.length > 1 ? Float.parseFloat(s[1]) : 1f, pitch = s.length > 2 ? Float.parseFloat(s[2]) : 1f;
                    p.playSound(Sound.sound(Key.key(s[0]), Sound.Source.MASTER, vol, pitch));
                }
                case TP -> tpAction(p, val);
                case UI -> {
                    Optional<HoUi> ui;
                    try { ui = services().get(HoUi.class); } catch (LinkageError e) { ui = Optional.empty(); }
                    if (ui.isEmpty() || !ui.get().open(p, val.trim(), new JsonObject()))
                        p.sendMessage(Component.text("Окно «" + val.trim() + "» не открыть", NamedTextColor.RED));
                }
                case PAGE -> {
                    int n = Math.max(1, h.pages.size()), cur = page(p, h.id);
                    String s = val.trim();
                    int next = s.equals("next") ? cur + 1 : s.equals("prev") ? cur - 1 : Integer.parseInt(s) - 1;
                    setPage(p, h.id, Math.floorMod(next, n));
                }
            }
        } catch (RuntimeException e) {
            logger().warn("голограмма {}: действие {} «{}»: {}", h.id, a.type().key(), val, e.toString());
        }
    }

    private static String strip(String s) {
        s = s.trim();
        return s.startsWith("/") ? s.substring(1) : s;
    }

    /** «x y z [yaw pitch]» или «карта x y z [yaw pitch]». */
    private void tpAction(Player p, String val) {
        String[] s = val.trim().split("\\s+");
        int k = 0;
        String map = null;
        if (s.length > 0 && !s[0].matches("-?[0-9.]+")) map = s[k++];
        double x = Double.parseDouble(s[k]), y = Double.parseDouble(s[k + 1]), z = Double.parseDouble(s[k + 2]);
        float yaw = s.length > k + 3 ? Float.parseFloat(s[k + 3]) : p.getPosition().yaw();
        float pitch = s.length > k + 4 ? Float.parseFloat(s[k + 4]) : p.getPosition().pitch();
        Pos to = new Pos(x, y, z, yaw, pitch);
        if (map == null) { p.teleport(to); return; }
        String m = map;
        server().map(m).ifPresentOrElse(inst -> server().transfer(p, inst, to),
                () -> p.sendMessage(Component.text("Нет карты «" + m + "»", NamedTextColor.RED)));
    }

    void teleport(Player p, Hologram h) {
        double yr = Math.toRadians(h.yaw);
        Pos to = new Pos(h.x - Math.sin(yr) * -2.5, h.y - 1, h.z + Math.cos(yr) * -2.5).withLookAt(new Pos(h.x, h.y, h.z));
        if (h.facing != Hologram.Facing.FIXED) to = new Pos(h.x + 2.5, h.y - 1, h.z).withLookAt(new Pos(h.x, h.y, h.z));
        Pos target = to;
        server().map(h.map).ifPresentOrElse(inst -> server().transfer(p, inst, target),
                () -> p.sendMessage(Component.text("Нет карты «" + h.map + "»", NamedTextColor.RED)));
    }

    /** JSON для редактора: картинки, свои предметы, плейсхолдеры, карты, шаблоны. */
    String info() {
        JsonObject o = new JsonObject();
        JsonArray im = new JsonArray();
        images.forEach(im::add);
        o.add("images", im);
        JsonArray it = new JsonArray();
        for (String id : hoItemIds()) it.add("hoka:" + id);
        o.add("items", it);
        JsonArray pl = new JsonArray();
        for (String n : ph.names()) {
            JsonObject x = new JsonObject();
            x.addProperty("name", n);
            x.addProperty("period", ph.period(n));
            pl.add(x);
        }
        o.add("placeholders", pl);
        JsonArray maps = new JsonArray();
        server().mapNames().forEach(maps::add);
        o.add("maps", maps);
        JsonObject t = new JsonObject();
        for (var e : new java.util.TreeMap<>(templates).entrySet()) t.add(e.getKey(), HoloCodec.toJson(e.getValue()));
        o.add("templates", t);
        JsonArray hs = new JsonArray();                                         // все (кроме временных) — список редактора
        for (Hologram h : sorted()) if (!h.temporary) hs.add(HoloCodec.toJson(h));
        o.add("holograms", hs);
        o.addProperty("broken", broken);
        return HoloCodec.COMPACT.toJson(o);
    }

    /** id своих предметов: из module/hoitems/config.yml (у сервиса hoitems списка нет). */
    List<String> hoItemIds() {
        Path f = dataFolder().toAbsolutePath().getParent().resolve("hoitems").resolve("config.yml");
        if (!Files.isRegularFile(f)) return List.of();
        try {
            var cfg = new ink.hoka.odyssey.api.ModuleConfig(f);
            return cfg.keys("items").stream().sorted().toList();
        } catch (RuntimeException e) {
            logger().debug("hoitems/config.yml: {}", e.toString());
        }
        return List.of();
    }

    /** Открыть редактор у игрока-OP. */
    void openEditor(Player p, String id) {
        if (!ready(p)) {
            p.sendMessage(Component.text("Нужен мод Hoka Odyssey с модулем holo", NamedTextColor.RED));
            return;
        }
        api.send(p, MODULE, Net.info(info()));
        api.send(p, MODULE, Net.edit(id));
    }

    // ───────────────────────────── HoloApi ─────────────────────────────

    @Override
    public void put(Hologram h, boolean persistent) {
        if (!Hologram.validId(h.id)) throw new IllegalArgumentException("плохой id «" + h.id + "»");
        h.temporary = !persistent;
        holos.put(h.id, h);
        if (persistent) saved();
        changed(h);
    }

    @Override public Optional<Hologram> get(String id) { return Optional.ofNullable(holos.get(id)); }

    @Override public Collection<Hologram> all() { return List.copyOf(holos.values()); }

    @Override
    public void update(Hologram h) {
        holos.put(h.id, h);
        if (!h.temporary) saved();
        changed(h);
    }

    @Override
    public boolean remove(String id) {
        Hologram h = holos.remove(id);
        if (h == null) return false;
        if (!h.temporary) saved();
        removed(id);
        return true;
    }

    @Override
    public void placeholder(String owner, String name, int periodTicks, Function<Player, String> fn) {
        ph.register(name, periodTicks, owner, fn);
        for (Viewer v : viewers.values()) if (v.wanted.contains(name)) v.force = true;
    }

    @Override public void removePlaceholders(String owner) { ph.unregisterOwner(owner); }

    @Override public void onClick(ClickListener l) { listeners.add(l); }

    @Override public void offClick(ClickListener l) { listeners.remove(l); }

    @Override public void permissions(BiPredicate<Player, String> check) { perms = check == null ? (p, n) -> false : check; }

    @Override
    public int page(Player p, String id) {
        Viewer v = viewers.get(p.getUuid());
        return v == null ? 0 : v.pages.getOrDefault(id, 0);
    }

    @Override
    public void setPage(Player p, String id, int page) {
        Viewer v = viewers.computeIfAbsent(p.getUuid(), k -> new Viewer());
        Hologram h = holos.get(id);
        int n = h == null ? 1 : Math.max(1, h.pages.size());
        int pg = Math.floorMod(page, n);
        v.pages.put(id, pg);
        if (ready(p)) api.send(p, MODULE, Net.page(id, pg));
    }

    void reloadAll(net.minestom.server.command.CommandSender s) {
        config().reload();
        readConfig();
        String err = loadAll();
        publishImages();
        for (Player p : online()) {
            if (!ready(p)) continue;
            api.send(p, MODULE, Net.clear());
            reset(p);
            sync(p);
        }
        if (err != null) s.sendMessage(Component.text(err + " — сохранение выключено", NamedTextColor.RED));
        else s.sendMessage(Component.text("Голограмм: " + holos.size() + ", шаблонов: " + templates.size() + ", картинок: " + images.size(), NamedTextColor.GREEN));
    }
}
