package ink.hoka.odyssey.ships;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import ink.hoka.odyssey.api.OdysseyModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.player.*;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Корабли. Админ строит корабль из блоков и сохраняет чертёж (/hoship …), игрок призывает свой корабль
 * (/hoship summon) и летает на нём (ПКМ по месту пилота, F — посадка). Один корабль на игрока.
 * Чертежи: module/ships/templates/*.json, корабли игроков: module/ships/ships.json.
 */
public final class ShipsModule extends OdysseyModule {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    final Map<String, ShipTemplate> templates = new ConcurrentHashMap<>();
    /** Корабли по UUID владельца. */
    final Map<String, Ship> ships = new ConcurrentHashMap<>();
    /** Корабли с пропавшим чертежом — не трогаем, но сохраняем как были. */
    private final List<Ship> unresolved = new ArrayList<>();
    /** ships.json не прочитался — не перезаписываем, чтобы не потерять корабли. */
    private boolean shipsBroken;
    /** Модель корабля в паке: во сколько раз уменьшена (масштаб сущности в полёте). */
    record ModelInfo(float scale) {}
    /** Готовые модели (есть в паке) по имени чертежа. */
    private final Map<String, ModelInfo> models = new ConcurrentHashMap<>();
    private ink.hoka.odyssey.respack.ResourcePackApi pack;
    private final VacuumBridge vacuum = new VacuumBridge(this);

    /** Мост к модулю «Невесомость» (если он есть). */
    VacuumBridge vacuum() { return vacuum; }

    /** Модель корабля, если она уже в паке (иначе полёт — на блоках). */
    ModelInfo model(String template) { return models.get(template); }

    /** Игроки, у которых сейчас идёт поиск места для призыва. */
    private final Set<String> summoning = ConcurrentHashMap.newKeySet();

    @Override
    protected void onEnable() {
        loadTemplates();
        loadShips();
        events().addListener(PlayerBlockInteractEvent.class, this::onInteract);
        events().addListener(PlayerBlockBreakEvent.class, e -> guard(e.getPlayer(), e.getInstance(), e.getBlockPosition(), () -> e.setCancelled(true)));
        events().addListener(PlayerBlockPlaceEvent.class, e -> guard(e.getPlayer(), e.getInstance(), e.getBlockPosition(), () -> e.setCancelled(true)));
        events().addListener(PlayerSwapItemEvent.class, e -> {
            Flight f = flightOf(e.getPlayer());
            if (f == null) return;
            e.setCancelled(true);
            f.land();
        });
        events().addListener(net.minestom.server.event.player.PlayerPacketEvent.class, e -> {  // сырой курс взгляда пилота
            if (e.getPacket() instanceof net.minestom.server.network.packet.client.play.ClientPlayerRotationPacket r) {
                Flight f = flightOf(e.getPlayer());
                if (f != null) f.rawLook(r.yaw());
            }
        });
        events().addListener(net.minestom.server.event.player.PlayerChangeHeldSlotEvent.class, e -> {  // 1 — кабина, 2 — сверху
            Flight f = flightOf(e.getPlayer());
            if (f != null && e.getNewSlot() <= 1) f.setView(e.getNewSlot() == 1);
        });
        events().addListener(PlayerStopFlyingEvent.class, e -> {
            if (flightOf(e.getPlayer()) != null) e.getPlayer().setFlying(true);
        });
        events().addListener(PlayerPacketOutEvent.class, e -> {               // журнал полёта: что сервер шлёт пилоту
            Flight f = flightOf(e.getPlayer());
            if (f != null) f.packetOut(e.getPacket());
        });
        events().addListener(PlayerDisconnectEvent.class, e -> {
            Flight f = flightOf(e.getPlayer());
            if (f != null) f.emergency();
        });
        registerCommand(new HoShipCommand(this));
        scheduler().repeat(this::tickFlights, 1, 1);
        pack = services().get(ink.hoka.odyssey.respack.ResourcePackApi.class).orElse(null);
        if (pack == null) logger().warn("нет модуля respack — корабли летают на отдельных блоках");
        else pack.vanillaReady().thenRun(() -> scheduler().async(() -> {
            putStaticAssets();
            for (ShipTemplate t : List.copyOf(templates.values())) buildModel(t);
            pack.rebuild();
        }));
        logger().info("чертежей: {}, кораблей игроков: {}", templates.size(), ships.size());
    }

    @Override
    protected void onDisable() {
        for (Ship s : ships.values()) if (s.flight != null) s.flight.emergency();
        saveShips();
        if (pack != null) pack.clear(id());
    }

    // ───────────────────────────── модели в пак ─────────────────────────────

    /** Ассет снаряжения без слоёв: «сбруя» невидимого гаста-двигателя ничего не рисует. */
    static final String SEAT_ASSET = "hoka_ships:none";

    /**
     * Постоянные файлы пака для полёта на гасте-двигателе: пустой ассет сбруи, прозрачные сердечки транспорта
     * (иначе вместо голода видно здоровье «скакуна») и своя подсказка вместо «Нажмите Shift, чтобы спешиться».
     */
    private void putStaticAssets() {
        var utf = java.nio.charset.StandardCharsets.UTF_8;
        pack.put(id(), "assets/hoka_ships/equipment/none.json", "{\"layers\":{}}".getBytes(utf));
        byte[] empty = transparentPng(9, 9);
        for (String h : new String[]{"vehicle_container", "vehicle_full", "vehicle_half"})
            pack.put(id(), "assets/minecraft/textures/gui/sprites/hud/heart/" + h + ".png", empty);
        String hint = config().getString("flight.mount-hint", "Корабль: мышь — прицел, W — вперёд по носу, F — посадка");
        var lj = new com.google.gson.JsonObject();
        lj.addProperty("mount.onboard", hint);
        String lang = lj.toString();
        pack.put(id(), "assets/minecraft/lang/ru_ru.json", lang.getBytes(utf));
        pack.put(id(), "assets/minecraft/lang/en_us.json", lang.getBytes(utf));
        putHud();
    }

    /**
     * Прицелы пилота на экране: шейдер текста rendertype_text (узнаёт глифы цвета #FE…, см. Flight.spawnReticle)
     * и шрифт hoka_ships:hud — кольцо носа U+E000, точка пунктира U+E001, прицел взгляда U+E002.
     */
    private void putHud() {
        var utf = java.nio.charset.StandardCharsets.UTF_8;
        for (String f : new String[]{"rendertype_text.vsh", "rendertype_text.fsh", "entity.vsh", "entity.fsh"}) {
            try (var in = ShipsModule.class.getResourceAsStream("/hud/" + f)) {
                if (in == null) { logger().error("нет ресурса hud/{}", f); continue; }
                pack.put(id(), "assets/minecraft/shaders/core/" + f, in.readAllBytes());
            } catch (java.io.IOException e) {
                logger().error("hud/{}: {}", f, e.toString());
            }
        }
        pack.put(id(), "assets/hoka_ships/textures/font/hud_nose.png", hudPng(0));
        pack.put(id(), "assets/hoka_ships/textures/font/hud_dot.png", hudPng(1));
        pack.put(id(), "assets/hoka_ships/textures/font/hud_aim.png", hudPng(2));
        pack.put(id(), "assets/hoka_ships/textures/font/hud_circle.png", hudCirclePng());
        String font = "{\"providers\":["
                + "{\"type\":\"bitmap\",\"file\":\"hoka_ships:font/hud_circle.png\",\"ascent\":8,\"height\":16,\"chars\":[\"\\uE003\"]},"
                + "{\"type\":\"bitmap\",\"file\":\"hoka_ships:font/hud_nose.png\",\"ascent\":8,\"height\":16,\"chars\":[\"\\uE000\"]},"
                + "{\"type\":\"bitmap\",\"file\":\"hoka_ships:font/hud_dot.png\",\"ascent\":8,\"height\":16,\"chars\":[\"\\uE001\"]},"
                + "{\"type\":\"bitmap\",\"file\":\"hoka_ships:font/hud_aim.png\",\"ascent\":8,\"height\":16,\"chars\":[\"\\uE002\"]}";
        // HUD: буквы сторон света (U+E010..E013 — С В Ю З), цифры (U+E020..E029), подписи (U+E030 скорость, U+E031 высота)
        String[] letters = {"С", "В", "Ю", "З"};
        for (int i = 0; i < 4; i++) {
            pack.put(id(), "assets/hoka_ships/textures/font/hud_l" + i + ".png", hudTextPng(letters[i], 64, 64, 46));
            font += ",{\"type\":\"bitmap\",\"file\":\"hoka_ships:font/hud_l" + i + ".png\",\"ascent\":8,\"height\":16,\"chars\":[\"\\uE01" + i + "\"]}";
        }
        for (int i = 0; i < 10; i++) {
            pack.put(id(), "assets/hoka_ships/textures/font/hud_d" + i + ".png", hudTextPng(String.valueOf(i), 44, 64, 54));
            font += ",{\"type\":\"bitmap\",\"file\":\"hoka_ships:font/hud_d" + i + ".png\",\"ascent\":8,\"height\":16,\"chars\":[\"\\uE02" + i + "\"]}";
        }
        String[] signs = {"−", "+"};                                             // U+E02A минус, U+E02B плюс
        for (int i = 0; i < 2; i++) {
            pack.put(id(), "assets/hoka_ships/textures/font/hud_s" + i + ".png", hudTextPng(signs[i], 44, 64, 54));
            font += ",{\"type\":\"bitmap\",\"file\":\"hoka_ships:font/hud_s" + i + ".png\",\"ascent\":8,\"height\":16,\"chars\":[\"\\uE02" + (char) ('A' + i) + "\"]}";
        }
        String[] labels = {"СКОРОСТЬ", "ВЫСОТА", "НАКЛОН"};
        for (int i = 0; i < 3; i++) {
            pack.put(id(), "assets/hoka_ships/textures/font/hud_t" + i + ".png", hudTextPng(labels[i], 256, 64, 40));
            font += ",{\"type\":\"bitmap\",\"file\":\"hoka_ships:font/hud_t" + i + ".png\",\"ascent\":8,\"height\":16,\"chars\":[\"\\uE03" + i + "\"]}";
        }
        font += "]}";
        pack.put(id(), "assets/hoka_ships/font/hud.json", font.getBytes(utf));
        // «шлем»-прицел пилота (режим камеры ghast): шейдер entity по меткам его граней рисует прицел мыши без пинга
        pack.put(id(), "assets/hoka_ships/equipment/aim.json", "{\"layers\":{\"humanoid\":[{\"texture\":\"hoka_ships:aim\"}]}}".getBytes(utf));
        pack.put(id(), "assets/hoka_ships/textures/entity/equipment/humanoid/aim.png", aimHelmetPng());
    }

    /** Ассет снаряжения «шлема»-прицела. */
    static final String AIM_ASSET = "hoka_ships:aim";

    /**
     * Текстура шлема 64×32 (раскладка головы ванильной брони): метки (254, 9, b, альфа 254) — b = 1 передняя грань
     * (8..16, 8..16), 2 — задняя (24..32, 8..16), 0 — прочие и внешний слой; картинка прицела 16×16 в (32, 16),
     * белый пиксель для пунктира в (48, 16).
     */
    static byte[] aimHelmetPng() {
        var img = new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.util.function.IntUnaryOperator mark = b -> (254 << 24) | (254 << 16) | (9 << 8) | b;
        for (int y = 0; y < 16; y++) for (int x = 0; x < 64; x++) {
            boolean head = x < 32;
            int b = 0;
            if (head && y >= 8 && x >= 8 && x < 16) b = 1;
            else if (head && y >= 8 && x >= 24) b = 2;
            img.setRGB(x, y, mark.applyAsInt(b));
        }
        int w = 0xFFFFFFFF;                                                   // прицел: крестик с пустым центром и точкой
        for (int i = 0; i < 5; i++) {
            img.setRGB(32 + 7, 16 + i, w); img.setRGB(32 + 8, 16 + i, w);
            img.setRGB(32 + 7, 16 + 11 + i, w); img.setRGB(32 + 8, 16 + 11 + i, w);
            img.setRGB(32 + i, 16 + 7, w); img.setRGB(32 + i, 16 + 8, w);
            img.setRGB(32 + 11 + i, 16 + 7, w); img.setRGB(32 + 11 + i, 16 + 8, w);
        }
        img.setRGB(32 + 7, 16 + 7, w); img.setRGB(32 + 8, 16 + 8, w); img.setRGB(32 + 7, 16 + 8, w); img.setRGB(32 + 8, 16 + 7, w);
        img.setRGB(48, 16, w);
        try {
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Круг ограничения прицела 256×256 (шейдер ставит в центр экрана; обод — радиус 100 из 128): серый полупрозрачный
     * обод, шкала-риски по верхней дуге, буквы W/A/S/D по сторонам. Прозрачность ≥ 26 — иначе шейдер текста отбросит.
     */
    /** Надпись для HUD: белая, по центру картинки w×h, кегль size (шейдер красит серым и задаёт размер на экране). */
    static byte[] hudTextPng(String s, int w, int h, int size) {
        var img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        var font = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, size);
        if (s.length() > 2) {                                                   // длинную подпись — с разрядкой
            font = font.deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.12));
        }
        g.setFont(font);
        var fm = g.getFontMetrics();
        int sw = fm.stringWidth(s);
        if (sw > w - 4) {                                                       // не влезает — уменьшить
            g.setFont(font.deriveFont((float) size * (w - 4) / sw));
            fm = g.getFontMetrics();
            sw = fm.stringWidth(s);
        }
        g.setColor(java.awt.Color.WHITE);
        var lm = fm.getLineMetrics(s, g);
        g.drawString(s, (w - sw) / 2f, (h + lm.getAscent() - lm.getDescent()) / 2f);
        g.dispose();
        try {
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static byte[] hudCirclePng() {
        int n = 256, c = 128, r = 100;
        var img = new java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);
        g.setColor(new java.awt.Color(200, 210, 215, 110));
        g.setStroke(new java.awt.BasicStroke(2.5f));
        g.drawOval(c - r, c - r, 2 * r, 2 * r);
        g.setColor(new java.awt.Color(215, 225, 230, 150));
        for (int a = -60; a <= 60; a += 10) {                                   // шкала по верхней дуге
            boolean major = a % 30 == 0;
            g.setStroke(new java.awt.BasicStroke(major ? 2.5f : 1.8f));
            double rad = Math.toRadians(a - 90), cs = Math.cos(rad), sn = Math.sin(rad);
            double r1 = r - 3, r2 = r - (major ? 14 : 8);
            g.draw(new java.awt.geom.Line2D.Double(c + cs * r1, c + sn * r1, c + cs * r2, c + sn * r2));
        }
        g.setStroke(new java.awt.BasicStroke(2f));                             // короткие риски слева, справа, снизу
        for (int a : new int[]{0, 90, 180}) {
            double rad = Math.toRadians(a), cs = Math.cos(rad), sn = Math.sin(rad);
            g.draw(new java.awt.geom.Line2D.Double(c + cs * (r - 3), c + sn * (r - 3), c + cs * (r - 10), c + sn * (r - 10)));
        }
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 15));
        var fm = g.getFontMetrics();
        g.setColor(new java.awt.Color(220, 230, 235, 170));
        String[] lab = {"W", "D", "S", "A"};                                    // верх, право, низ, лево
        double[][] at = {{c, c - r - 14}, {c + r + 14, c}, {c, c + r + 14}, {c - r - 14, c}};
        for (int i = 0; i < 4; i++) {
            int w = fm.stringWidth(lab[i]);
            g.drawString(lab[i], (float) (at[i][0] - w / 2.0), (float) (at[i][1] + fm.getAscent() / 2.0 - 2));
        }
        g.dispose();
        try {
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Картинки прицелов 64×64, белые (цвет задаёт шейдер): 0 — кольцо носа с рисками, 1 — точка, 2 — прицел взгляда. */
    static byte[] hudPng(int kind) {
        int n = 64;
        var img = new java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(java.awt.Color.WHITE);
        switch (kind) {
            case 0 -> {                                                         // кольцо + 4 риски наружу
                g.setStroke(new java.awt.BasicStroke(4f));
                g.drawOval(14, 14, 36, 36);
                g.fillRect(30, 0, 4, 12); g.fillRect(30, 52, 4, 12);
                g.fillRect(0, 30, 12, 4); g.fillRect(52, 30, 12, 4);
            }
            case 1 -> g.fillOval(8, 8, 48, 48);
            default -> {                                                        // крестик с пустым центром + точка
                g.fillRect(29, 4, 6, 20); g.fillRect(29, 40, 6, 20);
                g.fillRect(4, 29, 20, 6); g.fillRect(40, 29, 20, 6);
                g.fillOval(28, 28, 8, 8);
            }
        }
        g.dispose();
        try {
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static byte[] transparentPng(int w, int h) {
        try {
            var img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Собрать модель чертежа и положить в пак (без пересборки пака). Вызывать не в тике — читает ванильные файлы. */
    private void buildModel(ShipTemplate t) {
        try {
            ShipModel m = ShipModel.build(t, config().getDouble("flight.seat-height", 0.5),
                    path -> pack.vanilla(path).orElse(null));
            pack.put(id(), ShipModel.modelPath(t.name), m.modelJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            pack.put(id(), ShipModel.itemPath(t.name), m.itemJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            models.put(t.name, new ModelInfo(m.scale));
            logger().info("модель корабля «{}»: {} элементов, уменьшена в {} раз", t.name, m.elements, m.scale);
        } catch (Throwable e) {
            models.remove(t.name);
            logger().error("модель корабля «{}» не собрана: {}", t.name, e.toString());
        }
    }

    /** Новый/изменённый чертёж: модель в пак и разослать игрокам. */
    void modelChanged(ShipTemplate t) {
        if (pack == null) return;
        models.remove(t.name);
        pack.vanillaReady().thenRun(() -> scheduler().async(() -> { buildModel(t); pack.rebuild(); }));
    }

    /** Чертёж удалён — убрать модель из пака. */
    void modelRemoved(String name) {
        models.remove(name);
        if (pack == null) return;
        pack.remove(id(), ShipModel.modelPath(name));
        pack.remove(id(), ShipModel.itemPath(name));
        pack.rebuild();
    }

    // ───────────────────────────── файлы ─────────────────────────────

    Path templatesDir() { return dataFolder().resolve("templates"); }

    private void loadTemplates() {
        templates.clear();
        Path dir = templatesDir();
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    ShipTemplate t = ShipTemplate.load(f);
                    templates.put(t.name, t);
                } catch (Exception e) {
                    logger().error("чертёж {} не прочитан: {}", f.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            logger().error("папка чертежей: {}", e.toString());
        }
    }

    private void loadShips() {
        Path f = dataFolder().resolve("ships.json");
        if (!Files.isRegularFile(f)) return;
        try {
            List<Ship> list = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<List<Ship>>() {}.getType());
            if (list == null) return;
            for (Ship s : list) {
                s.t = templates.get(s.template);
                if (s.t == null) { logger().warn("корабль {}: нет чертежа «{}» — отложен", s.ownerName, s.template); unresolved.add(s); continue; }
                if (s.state == Ship.State.FLYING) s.state = Ship.State.STOWED;       // сервер упал в полёте
                ships.put(s.owner, s);
            }
        } catch (Exception e) {
            shipsBroken = true;
            logger().error("ships.json не прочитан ({}) — файл не будет перезаписан; исправьте его и перезагрузите модуль", e.toString());
            try { Files.copy(f, f.resolveSibling("ships.json.broken"), java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { }
        }
    }

    synchronized void saveShips() {
        if (shipsBroken) return;
        try {
            List<Ship> all = new ArrayList<>(ships.values());
            all.addAll(unresolved);
            Path f = dataFolder().resolve("ships.json"), tmp = dataFolder().resolve("ships.json.tmp");
            Files.writeString(tmp, GSON.toJson(all), StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger().error("ships.json не записан: {}", e.toString());
        }
    }

    // ───────────────────────────── поиск ─────────────────────────────

    Instance instanceOf(Ship s) { return server().map(s.map).orElse(null); }

    /** Стоящий корабль, в коробке которого точка (в этом мире). */
    Ship shipAt(Instance inst, Point p) {
        String map = server().mapName(inst).orElse(null);
        if (map == null) return null;
        for (Ship s : ships.values()) if (s.state == Ship.State.PARKED && map.equals(s.map) && s.contains(p)) return s;
        return null;
    }

    Flight flightOf(Player p) {
        for (Ship s : ships.values()) if (s.flight != null && s.flight.pilot == p) return s.flight;
        return null;
    }

    boolean canControl(Player p, Ship s) { return s.owner.equals(p.getUuid().toString()) || server().isOp(p); }

    // ───────────────────────────── события ─────────────────────────────

    /** Тик полётов. Сбой одного полёта не останавливает остальные: его корабль аварийно садится/убирается. */
    private void tickFlights() {
        for (Ship s : ships.values()) {
            Flight f = s.flight;
            if (f == null) continue;
            try { f.tick(); }
            catch (Throwable e) {
                logger().error("полёт {}: {}", s.ownerName, e.toString());
                try { f.emergency(); } catch (Throwable e2) { logger().error("аварийная посадка {}: {}", s.ownerName, e2.toString()); }
            }
        }
    }

    /** ПКМ по месту пилота — взлёт. */
    private void onInteract(PlayerBlockInteractEvent e) {
        if (e.getHand() != PlayerHand.MAIN) return;
        Ship s = shipAt(e.getInstance(), e.getBlockPosition());
        if (s == null) return;
        int[] l = s.local(e.getBlockPosition());
        if (l == null || l[0] != s.t.seat[0] || l[1] != s.t.seat[1] || l[2] != s.t.seat[2]) return;
        e.setBlockingItemUse(true);
        Player p = e.getPlayer();
        if (!canControl(p, s)) { p.sendActionBar(Component.text("Это корабль игрока " + s.ownerName, NamedTextColor.RED)); return; }
        takeOff(p, s);
    }

    void takeOff(Player p, Ship s) {
        if (s.state != Ship.State.PARKED) { p.sendMessage(Component.text("Корабль сейчас не стоит на месте", NamedTextColor.RED)); return; }
        if (summoning.contains(s.owner)) { p.sendMessage(Component.text("Корабль переносится — подождите", NamedTextColor.RED)); return; }
        if (flightOf(p) != null) { p.sendMessage(Component.text("Вы уже управляете кораблём", NamedTextColor.RED)); return; }
        if (instanceOf(s) != p.getInstance()) { p.sendMessage(Component.text("Корабль на другой карте: " + s.map, NamedTextColor.RED)); return; }
        new Flight(this, s, p).start();
        saveShips();
    }

    /** Защита блоков стоящего корабля: ломать/ставить можно только в зонах строительства (OP — везде). */
    private void guard(Player p, Instance inst, Point pos, Runnable cancel) {
        Ship s = shipAt(inst, pos);
        if (s == null || server().isOp(p)) return;
        int[] l = s.local(pos);
        if (l != null && s.t.inZone(l[0], l[1], l[2]) && s.owner.equals(p.getUuid().toString())) return;
        cancel.run();
        p.sendActionBar(Component.text(s.t.inZone(l[0], l[1], l[2]) ? "Строить на корабле может только его владелец" : "Это часть корабля — строить можно только в зонах строительства", NamedTextColor.RED));
    }

    // ───────────────────────────── блоки корабля ─────────────────────────────

    /** Поставить корабль блоками (anchor, rot уже заданы). */
    void place(Instance inst, Ship s) {
        ShipTemplate t = s.t;
        for (int y = 0; y < t.sy; y++) for (int z = 0; z < t.sz; z++) for (int x = 0; x < t.sx; x++) {
            Block b = t.block(x, y, z);
            if (!b.isAir()) inst.setBlock(s.world(x, y, z), Rot.block(b, s.rot), false);
        }
        s.state = Ship.State.PARKED;
    }

    /** Убрать блоки стоящего корабля. */
    void clear(Instance inst, Ship s) {
        if (inst == null || s.state != Ship.State.PARKED) return;
        ShipTemplate t = s.t;
        for (int y = 0; y < t.sy; y++) for (int z = 0; z < t.sz; z++) for (int x = 0; x < t.sx; x++)
            if (!t.block(x, y, z).isAir()) inst.setBlock(s.world(x, y, z), Block.AIR, false);
    }

    // ───────────────────────────── призыв ─────────────────────────────

    /**
     * Призвать корабль игрока по чертежу рядом с ним, на свободное место (вся коробка корабля плюс зазор — воздух,
     * постройки не трогаем). Свой старый стоящий корабль переносится.
     */
    void summon(Player p, ShipTemplate t) {
        String uid = p.getUuid().toString();
        Ship old = ships.get(uid);
        if (old != null && old.state == Ship.State.FLYING) { p.sendMessage(Component.text("Ваш корабль в полёте — сначала посадка", NamedTextColor.RED)); return; }
        Instance inst = p.getInstance();
        String map = server().mapName(inst).orElse(null);
        if (map == null) { p.sendMessage(Component.text("Здесь корабль не призвать", NamedTextColor.RED)); return; }
        if (!summoning.add(uid)) { p.sendMessage(Component.text("Место для корабля уже ищется — подождите", NamedTextColor.RED)); return; }
        Pos at = p.getPosition();
        int rot = Math.floorMod(Math.round(at.yaw() / 90f), 4);
        int radius = config().getInt("summon.radius", 40), margin = config().getInt("summon.margin", 1);
        int[] s2 = Rot.size(t.sx, t.sz, rot);
        int reach = radius + Math.max(s2[0], s2[1]) + margin + 1;
        List<CompletableFuture<?>> chunks = new ArrayList<>();
        for (int cx = (at.blockX() - reach) >> 4; cx <= (at.blockX() + reach) >> 4; cx++)
            for (int cz = (at.blockZ() - reach) >> 4; cz <= (at.blockZ() + reach) >> 4; cz++) chunks.add(inst.loadChunk(cx, cz));
        p.sendActionBar(Component.text("Ищу свободное место для корабля…", NamedTextColor.AQUA));
        CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new)).exceptionally(ex -> { summoning.remove(uid); return null; }).thenRun(() -> scheduler().async(() -> {
            Ship probe = new Ship();
            probe.t = t; probe.rot = rot;
            int[] found;
            try { found = findSpot(inst, at, probe, radius, margin, old != null && old.state == Ship.State.PARKED && map.equals(old.map) ? old : null); }
            catch (Throwable ex) { logger().error("поиск места: {}", ex.toString()); found = null; }
            int[] spot = found;
            scheduler().run(() -> {
                summoning.remove(uid);
                if (!p.isOnline()) return;
                Ship cur = ships.get(uid);
                if (cur != old || (cur != null && (cur.state == Ship.State.FLYING || cur.flight != null))) {
                    p.sendMessage(Component.text("Пока искалось место, корабль сменил состояние — призовите снова", NamedTextColor.RED));
                    return;
                }
                // перепроверка на тике: за время поиска место могли занять
                if (spot != null) {
                    int[] sz2 = probe.size();
                    if (!free(inst, spot[0] - margin, spot[1], spot[2] - margin, spot[0] + sz2[0] + margin, spot[1] + sz2[1] + margin, spot[2] + sz2[2] + margin,
                            old != null && old.state == Ship.State.PARKED && map.equals(old.map) ? old : null)) {
                        p.sendMessage(Component.text("Место заняли — призовите снова", NamedTextColor.RED));
                        return;
                    }
                }
                if (spot == null) { p.sendMessage(Component.text("Рядом нет свободного места под корабль «" + t.name + "» — отойдите на открытое место", NamedTextColor.RED)); return; }
                Ship s = old != null ? old : new Ship();
                if (old != null && old.state == Ship.State.PARKED) clear(instanceOf(old), old);
                s.owner = uid; s.ownerName = p.getUsername();
                s.template = t.name; s.t = t; s.map = map;
                s.ax = spot[0]; s.ay = spot[1]; s.az = spot[2]; s.rot = rot;
                place(inst, s);
                ships.put(uid, s);
                saveShips();
                Vec seat = s.seatWorld();
                p.sendMessage(Component.text("Корабль «" + t.name + "» здесь: " + seat.blockX() + " " + seat.blockY() + " " + seat.blockZ()
                        + ". ПКМ по месту пилота — взлёт.", NamedTextColor.GREEN));
            });
        }));
    }

    /** Ближайшее к игроку место: спираль по XZ (шаг 2), высота от ног −2 до +24. null — нет. */
    private int[] findSpot(Instance inst, Pos at, Ship probe, int radius, int margin, Ship ignore) {
        int[] sz = probe.size();
        int w = sz[0], h = sz[1], d = sz[2];
        int minY = inst.getCachedDimensionType().minY(), maxY = minY + inst.getCachedDimensionType().height();
        for (int dist = 0; dist <= radius; dist += 2) {
            for (int dx = -dist; dx <= dist; dx += 2) for (int dz = -dist; dz <= dist; dz += 2) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != dist) continue;              // только кольцо
                for (int dy = 0; dy <= 26; dy++) {
                    int yy = dy <= 2 ? at.blockY() - dy : at.blockY() + dy - 2;
                    int ax = at.blockX() + dx - w / 2, az = at.blockZ() + dz - d / 2;
                    if (yy - 1 < minY || yy + h + margin >= maxY) continue;
                    // игрок не должен оказаться внутри корабля
                    if (at.x() >= ax - 1 && at.x() < ax + w + 1 && at.z() >= az - 1 && at.z() < az + d + 1 && at.y() >= yy - 2 && at.y() < yy + h + 1) continue;
                    if (free(inst, ax - margin, yy, az - margin, ax + w + margin, yy + h + margin, az + d + margin, ignore))
                        return new int[]{ax, yy, az};
                }
            }
        }
        return null;
    }

    /** Вся коробка [x1..x2) × [y1..y2) × [z1..z2) — воздух (или блоки своего старого корабля). */
    private boolean free(Instance inst, int x1, int y1, int z1, int x2, int y2, int z2, Ship ignore) {
        for (int y = y1; y < y2; y++) for (int x = x1; x < x2; x++) for (int z = z1; z < z2; z++) {
            Block b = inst.getBlock(x, y, z);
            if (b.isAir()) continue;
            if (ignore != null) {
                int[] l = ignore.local(new Vec(x, y, z));
                if (l != null && !ignore.t.block(l[0], l[1], l[2]).isAir()) continue;
            }
            return false;
        }
        return true;
    }

    /** Убрать свой корабль совсем. */
    boolean dismiss(Player p) {
        Ship s = ships.get(p.getUuid().toString());
        if (s == null) return false;
        if (s.state == Ship.State.FLYING) { p.sendMessage(Component.text("Корабль в полёте — сначала посадка", NamedTextColor.RED)); return true; }
        clear(instanceOf(s), s);
        ships.remove(s.owner);
        saveShips();
        p.sendMessage(Component.text("Корабль убран", NamedTextColor.YELLOW));
        return true;
    }
}
