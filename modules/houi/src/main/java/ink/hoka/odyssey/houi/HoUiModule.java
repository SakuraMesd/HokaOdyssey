package ink.hoka.odyssey.houi;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import ink.hoka.odyssey.api.OdysseyModule;
import ink.hoka.odyssey.clientmods.ClientModsApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Окна: раскладки {@code menus/*.json} и картинки {@code assets/**} отдаются моду файлами ({@code ui/menus/<id>.json},
 * {@code ui/<путь>}); окно открывается командой ядру (S2C op 7), нажатия и закрытие приходят обратно (C2S op 5).
 * Протокол op 7: 1 OPEN (id, путь раскладки, JSON данных), 2 CLOSE (id), 3 DATA (id, JSON); op 5: 1 CLICK (id,
 * action, кнопка мыши), 2 CLOSED (id).
 */
public final class HoUiModule extends OdysseyModule implements HoUi {

    static final int OP_UI = 7, OP_UI_C2S = 5, OP_HUD = 8;

    private ClientModsApi client;
    private int publishedTo;
    private volatile Set<String> menus = Set.of(), layers = Set.of();
    private final Hud hud = new Hud();
    private final Map<UUID, String> open = new ConcurrentHashMap<>();
    private final Map<String, ActionHandler> actions = new ConcurrentHashMap<>();
    private final Map<String, Consumer<Player>> closes = new ConcurrentHashMap<>();
    private DemoShop demo;
    private DemoHud demoHud;

    @Override
    protected void onEnable() {
        client = services().get(ClientModsApi.class).orElseThrow();
        client.onCore(OP_UI_C2S, this::onPacket);
        logger().info("окна: {}", reload());
        services().register(HoUi.class, this);
        services().register(HoHud.class, hud);
        events().addListener(PlayerDisconnectEvent.class, e -> {
            open.remove(e.getPlayer().getUuid());
            if (demoHud != null) demoHud.forget(e.getPlayer().getUuid());
        });
        registerCommand(new HoUiCommand());
        demo = new DemoShop(this, services(), logger());
        demoHud = new DemoHud(hud, demo, scheduler());
        demo.listener = (p, msg) -> {
            demoHud.coins(p);
            if (msg != null && !msg.isEmpty()) demoHud.toast(p, msg);
        };
        scheduler().repeat(() -> {                                     // clientmods перезагружен — отдать заново
            ClientModsApi now = services().get(ClientModsApi.class).orElse(null);
            if (now != null && System.identityHashCode(now) != publishedTo) {
                client = now;
                client.onCore(OP_UI_C2S, this::onPacket);
                publish();
            }
        }, 100, 100);
    }

    @Override
    protected void onDisable() {
        services().unregister(HoUi.class);
        services().unregister(HoHud.class);
        for (Player p : net.minestom.server.MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            hud.hide(p, null);
            hud.hideVanilla(p, java.util.List.of());
        }
        for (var e : open.entrySet())
            net.minestom.server.MinecraftServer.getConnectionManager().getOnlinePlayers().stream()
                    .filter(p -> p.getUuid().equals(e.getKey())).forEach(p -> close(p, null));
        try {
            client.offCore(OP_UI_C2S);
            client.clearFiles(id());
            client.publishFiles();
        } catch (Throwable ignored) { }
    }

    synchronized String reload() {
        return publish();
    }

    private String publish() {
        ClientModsApi api = client;
        api.clearFiles(id());
        Set<String> found = new TreeSet<>();
        int files = 0;
        try {
            Path menusDir = dataFolder().resolve("menus"), assets = dataFolder().resolve("assets");
            Files.createDirectories(menusDir);
            Files.createDirectories(assets);
            copyDefault("menus/demo_shop.json");
            copyDefault("assets/demo/coin.png");
            copyDefault("hud/demo_hud.json");
            copyDefault("hud/demo_toast.json");
            Path hudDir = dataFolder().resolve("hud");
            Files.createDirectories(hudDir);
            Set<String> hudFound = new TreeSet<>();
            try (Stream<Path> s = Files.list(hudDir)) {
                for (Path f : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".json"))::iterator) {
                    String name = f.getFileName().toString();
                    String layer = name.substring(0, name.length() - 5);
                    byte[] b = Files.readAllBytes(f);
                    try {
                        com.google.gson.JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
                    } catch (Exception e) {
                        logger().warn("слой HUD {}: ошибка в JSON — {}", layer, e.getMessage());
                        continue;
                    }
                    api.putFile(id(), "ui/hud/" + layer + ".json", b);
                    hudFound.add(layer);
                    files++;
                }
            }
            layers = hudFound;
            try (Stream<Path> s = Files.list(menusDir)) {
                for (Path f : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".json"))::iterator) {
                    String name = f.getFileName().toString();
                    String menu = name.substring(0, name.length() - 5);
                    byte[] b = Files.readAllBytes(f);
                    try {
                        com.google.gson.JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
                    } catch (Exception e) {
                        logger().warn("окно {}: ошибка в JSON — {}", menu, e.getMessage());
                        continue;
                    }
                    api.putFile(id(), "ui/menus/" + menu + ".json", b);
                    found.add(menu);
                    files++;
                }
            }
            try (Stream<Path> s = Files.walk(assets)) {
                for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    api.putFile(id(), "ui/" + assets.relativize(f).toString().replace(java.io.File.separatorChar, '/'), Files.readAllBytes(f));
                    files++;
                }
            }
        } catch (IOException e) {
            logger().error("файлы окон не прочитаны: {}", e.toString());
        }
        menus = found;
        api.publishFiles();
        publishedTo = System.identityHashCode(api);
        return found.size() + " окон " + found + ", слоёв HUD " + layers + ", файлов " + files;
    }

    /** Пример из jar, если его ещё нет на диске (свои правки не затираются). */
    private void copyDefault(String rel) throws IOException {
        Path to = dataFolder().resolve(rel);
        if (Files.exists(to)) return;
        try (var in = getClass().getClassLoader().getResourceAsStream("defaults/" + rel)) {
            if (in == null) return;
            Files.createDirectories(to.getParent());
            Files.copy(in, to);
        }
    }

    // ───────────────────────── протокол ─────────────────────────

    private interface W { void write(DataOutputStream out) throws IOException; }

    private static byte[] bytes(W w) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) {
            w.write(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return b.toByteArray();
    }

    private static void json(DataOutputStream out, JsonObject o) throws IOException {
        byte[] b = o == null ? new byte[0] : new Gson().toJson(o).getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private void onPacket(Player p, byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int sub = in.readUnsignedByte();
            String menu = in.readUTF();
            if (sub == 1) {
                String action = in.readUTF();
                if (!menu.equals(open.get(p.getUuid()))) return;               // нажатие не в открытом у него окне
                ActionHandler h = actions.get(menu);
                if (h != null) h.onAction(p, action);
            } else if (sub == 2) {
                if (open.remove(p.getUuid(), menu)) {
                    Consumer<Player> h = closes.get(menu);
                    if (h != null) h.accept(p);
                }
            }
        } catch (Exception e) {
            logger().warn("{}: пакет окна не разобран: {}", p.getUsername(), e.toString());
        }
    }

    // ───────────────────────── HoUi ─────────────────────────

    @Override public boolean open(Player p, String menu, JsonObject data) {
        if (!client.hasMod(p) || !menus.contains(menu)) return false;
        open.put(p.getUuid(), menu);
        client.sendCore(p, OP_UI, bytes(out -> {
            out.writeByte(1);
            out.writeUTF(menu);
            out.writeUTF("ui/menus/" + menu + ".json");
            json(out, data);
        }));
        return true;
    }

    @Override public void update(Player p, String menu, JsonObject data) {
        if (!menu.equals(open.get(p.getUuid()))) return;
        client.sendCore(p, OP_UI, bytes(out -> {
            out.writeByte(3);
            out.writeUTF(menu);
            json(out, data);
        }));
    }

    @Override public void close(Player p, String menu) {
        String cur = open.get(p.getUuid());
        if (cur == null || (menu != null && !menu.equals(cur))) return;
        open.remove(p.getUuid());
        client.sendCore(p, OP_UI, bytes(out -> {
            out.writeByte(2);
            out.writeUTF(cur);
        }));
    }

    @Override public String openMenu(Player p) { return open.get(p.getUuid()); }

    @Override public void onAction(String menu, ActionHandler h) { actions.put(menu, h); }

    @Override public void onClose(String menu, Consumer<Player> h) { closes.put(menu, h); }

    @Override public void off(String menu) { actions.remove(menu); closes.remove(menu); }

    @Override public boolean exists(String menu) { return menus.contains(menu); }

    // ───────────────────────── HoHud ─────────────────────────

    /** HUD — отдельный сервис (op 8 ядра): 1 SHOW, 2 DATA, 3 HIDE, 4 VANILLA. */
    private final class Hud implements HoHud {

        @Override public boolean show(Player p, String layer, JsonObject data) {
            if (!client.hasMod(p) || !layers.contains(layer)) return false;
            client.sendCore(p, OP_HUD, bytes(out -> {
                out.writeByte(1);
                out.writeUTF(layer);
                out.writeUTF("ui/hud/" + layer + ".json");
                json(out, data);
            }));
            return true;
        }

        @Override public void update(Player p, String layer, JsonObject data) {
            client.sendCore(p, OP_HUD, bytes(out -> {
                out.writeByte(2);
                out.writeUTF(layer);
                json(out, data);
            }));
        }

        @Override public void hide(Player p, String layer) {
            client.sendCore(p, OP_HUD, bytes(out -> {
                out.writeByte(3);
                out.writeUTF(layer == null ? "" : layer);
            }));
        }

        @Override public void hideVanilla(Player p, java.util.Collection<String> parts) {
            client.sendCore(p, OP_HUD, bytes(out -> {
                out.writeByte(4);
                out.writeInt(parts.size());
                for (String s : parts) out.writeUTF(s);
            }));
        }

        @Override public boolean exists(String layer) { return layers.contains(layer); }
    }

    // ───────────────────────── команда ─────────────────────────

    private final class HoUiCommand extends Command {
        HoUiCommand() {
            super("houi");
            setCondition((s, c) -> !(s instanceof Player p) || server().isOp(p));
            setDefaultExecutor((s, c) -> s.sendMessage(Component.text("Окна: " + menus + ".  /houi open <окно> · /houi reload · /houi shop — тестовый магазин · /houi hud — тестовый HUD", NamedTextColor.AQUA)));
            addSyntax((s, c) -> s.sendMessage(Component.text("Перечитано и отдано моду: " + reload(), NamedTextColor.GREEN)), ArgumentType.Literal("reload"));
            addSyntax((s, c) -> { if (s instanceof Player p) demo.open(p); }, ArgumentType.Literal("shop"));
            addSyntax((s, c) -> {
                if (!(s instanceof Player p)) return;
                s.sendMessage(Component.text(demoHud.toggle(p) ? "Тестовый HUD включён" : "Тестовый HUD выключен", NamedTextColor.GREEN));
            }, ArgumentType.Literal("hud"));
            var menuArg = ArgumentType.Word("окно");
            menuArg.setSuggestionCallback((s, c, sug) -> { for (String m : menus) sug.addEntry(new SuggestionEntry(m)); });
            addSyntax((s, c) -> {
                if (!(s instanceof Player p)) return;
                if (!open(p, c.get(menuArg), new JsonObject())) s.sendMessage(Component.text("Не открыть: нет мода или окна", NamedTextColor.RED));
            }, ArgumentType.Literal("open"), menuArg);
        }
    }
}
