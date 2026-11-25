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
import net.minestom.server.entity.Entity;
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
    /** Модель корабля: масштаб сущности в полёте (корабль рисует клиентский модуль ships — в полный размер). */
    record ModelInfo(float scale) {}
    private static final ModelInfo FULL = new ModelInfo(1f);
    /** Связь с клиентским модулем ships (рисует корабль из блоков чертежа). */
    final ShipsClient client = new ShipsClient(this);
    private final VacuumBridge vacuum = new VacuumBridge(this);
    /** Клики по кораблю-модели: двери, сундуки, стройка. */
    final ShipInteract interact = new ShipInteract(this);
    final YardStreamer yardStream = new YardStreamer(this);
    final DeckEntities deck = new DeckEntities(this);
    /** Автовыравнивание (только на «планетах»). */
    final ShipLeveler leveler = new ShipLeveler(this);

    /** Мост к модулю «Невесомость» (если он есть). */
    VacuumBridge vacuum() { return vacuum; }

    /** Модель корабля: её рисует мод (клиентский модуль ships), сервер двигает «якорь». */
    ModelInfo model(String template) { return FULL; }

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
            if (f == null) {                                                    // F у места пилота своего корабля-модели — за управление
                Ship s = ships.get(e.getPlayer().getUuid().toString());
                if (s != null && nearModel(e.getPlayer(), s, config().getDouble("flight.seat-reach", 2.5))) {
                    e.setCancelled(true);
                    takeOff(e.getPlayer(), s);
                }
                return;
            }
            e.setCancelled(true);
            f.park();                                                           // F — остаться моделью «как есть»
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
            insideBefore.remove(e.getPlayer().getUuid());
        });
        registerCommand(new HoShipCommand(this));
        scheduler().repeat(this::tickFlights, 1, 1);
        scheduler().repeat(() -> {
            try { leveler.tick(); } catch (Throwable e) { logger().error("автовыравнивание: {}", e.toString()); }
        }, 1, 1);
        client.enable();
        interact.enable();
        yardStream.enable();
        deck.enable();
        for (Ship s : ships.values()) if (s.state == Ship.State.MODEL) spawnModel(s);
        migrateToYard();                                                        // старые корабли — на настоящие блоки верфи
        Shipyard.pinAll(this);                                                  // плоты — закрепить сразу (кубы по запросу, ядро 0.4)
        scheduler().repeat(() -> Shipyard.pinAll(this), 100, 100);             // и в мирах, загруженных позже
        logger().info("чертежей: {}, кораблей игроков: {}", templates.size(), ships.size());
    }

    @Override
    protected void onDisable() {
        interact.disable();
        leveler.disable();
        for (Ship s : ships.values()) if (s.flight != null) s.flight.emergency();
        for (Entity a : parked.values()) a.remove();                            // корабли-модели остаются в ships.json
        parked.clear();
        for (Ship s : ships.values()) Shipyard.unpin(this, s);                 // закрепления плотов — снять (модуль выключен)
        saveShips();
        client.disable();
    }

    // ───────────────────────────── корабли-модели (стоянка «как есть») ─────────────────────────────

    /** Якоря стоящих моделью кораблей (по владельцу). */
    final Map<String, Entity> parked = new ConcurrentHashMap<>();

    /** Игроки внутри кораблей (гравитация корабля — у мода): прежний режим невесомости, чтобы вернуть при выходе. */
    private final Map<java.util.UUID, String> insideBefore = new ConcurrentHashMap<>();

    /** Мод: игрок внутри корабля / вышел. Внутри вакуум его не трогает (тянет к полу сам корабль). */
    void inside(Player p, boolean in) {
        if (in) {
            if (insideBefore.containsKey(p.getUuid()) || flightOf(p) != null) return;
            String before = vacuum.get(p);
            if (before == null) return;                                         // модуля невесомости нет
            insideBefore.put(p.getUuid(), before);
            vacuum.set(p, "NORMAL");
        } else {
            String before = insideBefore.remove(p.getUuid());
            if (before != null && flightOf(p) == null) vacuum.set(p, before);
        }
    }

    /** Пилот взлетает изнутри корабля: прежний режим (до «внутри») — полёту, чтобы вернуть его после посадки. */
    String takeInsideBefore(Player p) { return insideBefore.remove(p.getUuid()); }

    /** Полёт закончился стоянкой «как есть»: якорь остаётся, его видят все (и пилот). */
    void park(Ship s, Entity anchor) {
        if (anchor == null) { spawnModel(s); saveShips(); return; }
        anchor.updateViewableRule(v -> true);
        parked.put(s.owner, anchor);
        client.attachModel(s, anchor);
        saveShips();
    }

    /** Взлёт со стоянки: якорь отдаётся полёту (тот же — корабль не пропадает из-под ног стоящих на нём). */
    Entity takeParked(Ship s) {
        return parked.remove(s.owner);
    }

    /** Корабль-модель уходит со стоянки (призыв, «убрать»): якорь убрать, игрокам — «больше нет». */
    void unpark(Ship s) {
        Entity a = parked.remove(s.owner);
        if (a == null) return;
        client.detachModel(a);
        a.remove();
    }

    /** Поставить якорь корабля-модели (загрузка модуля). Карта не загружена — корабль подождёт. */
    void spawnModel(Ship s) {
        if (s.mparts == null || s.mblocks == null || s.mparts.size() != s.mblocks.size()) return;
        Instance inst = instanceOf(s);
        if (inst == null) { logger().warn("корабль {}: карты «{}» нет — модель не поставлена", s.ownerName, s.map); return; }
        Entity a = new Entity(net.minestom.server.entity.EntityType.ITEM_DISPLAY);
        a.setNoGravity(true);
        a.editEntityMeta(net.minestom.server.entity.metadata.display.ItemDisplayMeta.class, m -> {
            m.setItemStack(net.minestom.server.item.ItemStack.AIR);
            m.setViewRange(8f);
            m.setPosRotInterpolationDuration(3);
            m.setLeftRotation(Rot.quat(s.myaw, s.mpitch, s.mroll, 0));
        });
        a.setInstance(inst, new net.minestom.server.coordinate.Pos(s.mx, s.my, s.mz, 0f, 0f)).join();
        parked.put(s.owner, a);
        client.attachModel(s, a);
    }

    /** Корабль-модель рядом с игроком (точка пилота не дальше dist). */
    boolean nearModel(Player p, Ship s, double dist) {
        return s.state == Ship.State.MODEL && instanceOf(s) == p.getInstance()
                && p.getPosition().distanceSquared(new net.minestom.server.coordinate.Vec(s.mx, s.my, s.mz)) <= dist * dist;
    }

    /** Новый/изменённый чертёж: игрокам с модом — новые блоки корабля (если он сейчас летит). */
    void modelChanged(ShipTemplate t) { }

    /** Чертёж удалён. */
    void modelRemoved(String name) { }

    /** «Сбруя» гаста-двигателя: ассета нет — ничего не рисует (до этапа 2, где гаст уходит). */
    static final String SEAT_ASSET = "hoka_ships:none";
    /** «Шлем»-прицел: был на шейдере ресурспака; reticle.helmet в конфиге по умолчанию выключен. */
    static final String AIM_ASSET = "hoka_ships:aim";

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
                if (s.state == Ship.State.FLYING)                                    // сервер упал в полёте: из верфи — висит, где летел
                    s.state = s.yard != null && s.mparts != null ? Ship.State.MODEL : Ship.State.STOWED;
                ships.put(s.owner, s);
            }
        } catch (Exception e) {
            shipsBroken = true;
            logger().error("ships.json не прочитан ({}) — файл не будет перезаписан; исправьте его и перезагрузите модуль", e.toString());
            try { Files.copy(f, f.resolveSibling("ships.json.broken"), java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { }
        }
    }

    /** Сохранить без задержки тика: JSON — сейчас, запись на диск — в фоне (в полёте каждые 5 с). */
    void saveShipsAsync() {
        if (shipsBroken) return;
        List<Ship> all = new ArrayList<>(ships.values());
        all.addAll(unresolved);
        String json = GSON.toJson(all);
        Thread.ofVirtual().start(() -> writeShips(json));
    }

    private synchronized void writeShips(String json) {
        try {
            Path f = dataFolder().resolve("ships.json"), tmp = dataFolder().resolve("ships.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger().error("ships.json не записан: {}", e.toString());
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
        if (s.yard == null) { p.sendMessage(Component.text("Корабль переносится в верфь — повторите через секунду", NamedTextColor.YELLOW)); if (s.state == Ship.State.PARKED) toYard(s); return; }
        if (s.yard != null && s.state == Ship.State.PARKED) { p.sendMessage(Component.text("Корабль в верфи — призовите его заново (/hoship summon)", NamedTextColor.RED)); return; }
        if (s.yard != null && s.state == Ship.State.MODEL && !syncFromYard(s)) { p.sendMessage(Component.text("Верфь загружается — повторите через секунду", NamedTextColor.YELLOW)); return; }
        if (s.state != Ship.State.PARKED && s.state != Ship.State.MODEL) { p.sendMessage(Component.text("Корабль сейчас не стоит на месте", NamedTextColor.RED)); return; }
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
    /**
     * Корабль, стоящий блоками в мире (старый вид или только что призванный по чертежу), — в верфь: блоки мира (с
     * вещами сундуков) → клетки модели и плот, из мира убрать, корабль стоит моделью на том же месте.
     */
    void toYard(Ship s) {
        Instance inst = instanceOf(s);
        if (inst == null || s.state != Ship.State.PARKED || s.t == null) return;
        ShipTemplate t = s.t;
        Vec seat = s.seatWorld();
        List<int[]> parts = new ArrayList<>();
        List<String> states = new ArrayList<>();
        List<Block> full = new ArrayList<>();
        List<Vec> taken = new ArrayList<>();
        for (int y = 0; y < t.sy; y++) for (int z = 0; z < t.sz; z++) for (int x = 0; x < t.sx; x++) {
            if (t.block(x, y, z).isAir() && !t.inZone(x, y, z)) continue;
            Vec w = s.world(x, y, z);
            Block b = inst.getBlock(w);
            if (b.isAir()) continue;
            parts.add(new int[]{w.blockX() - seat.blockX(), w.blockY() - seat.blockY(), w.blockZ() - seat.blockZ()});
            states.add(b.state());
            full.add(b);
            taken.add(w);
        }
        for (Vec w : taken) inst.setBlock(w, Block.AIR, false);
        for (Vec w : taken) inst.setBlock(w, Block.AIR);                        // второй проход — обновить соседей снаружи
        float turn = (float) ShipTemplate.yawOf(Rot.dir(t.forwardDir(), s.rot));
        s.mparts = parts;
        s.mblocks = states;
        s.mturn = turn;
        s.myaw = turn; s.mpitch = 0; s.mroll = 0;
        s.mseatH = (float) config().getDouble("flight.seat-height", 0.5);
        s.mx = seat.x() + 0.5; s.my = seat.y() + s.mseatH; s.mz = seat.z() + 0.5;
        s.state = Ship.State.MODEL;
        s.yard = null;
        spawnModel(s);
        saveShips();
        Shipyard.build(this, s, inst, full).thenAccept(n -> scheduler().run(() -> {
            saveShips();
            Entity a = parked.get(s.owner);
            if (a != null) client.attachModel(s, a);                            // моду — где плот
            logger().info("корабль {} ({}) — в верфь: плот {}, блоков {}", s.ownerName, s.template, s.yard[0], n);
        })).exceptionally(e -> { logger().warn("верфь: не перенести корабль {}: {}", s.ownerName, e.toString()); return null; });
    }

    /** Старые корабли (без верфи) — в верфь при включении модуля. */
    private void migrateToYard() {
        for (Ship s : ships.values()) {
            if (s.yard != null) continue;
            Instance inst = instanceOf(s);
            if (inst == null) continue;
            if (s.state == Ship.State.MODEL && s.mparts != null && s.mblocks != null) {   // стоит моделью — плот из клеток модели
                Shipyard.build(this, s, inst).thenAccept(n -> scheduler().run(() -> {
                    saveShips();
                    Entity a = parked.get(s.owner);
                    if (a != null) client.attachModel(s, a);
                    logger().info("корабль {} ({}) — в верфь: плот {}, блоков {}", s.ownerName, s.template, s.yard[0], n);
                })).exceptionally(e -> { logger().warn("верфь: не перенести корабль {}: {}", s.ownerName, e.toString()); return null; });
            } else if (s.state == Ship.State.PARKED && s.t != null) {          // стоит блоками — дождаться чанков и перенести
                int[] sz = s.size();
                server().loadCubes(inst, new Vec(s.ax, s.ay, s.az), new Vec(s.ax + sz[0], s.ay + sz[1], s.az + sz[2])).thenRun(() -> scheduler().run(() -> toYard(s)));
            }
        }
    }

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
        if (old != null && old.yard != null && !t.name.equals(old.yardTemplate)) { Shipyard.unpin(this, old); old.dropYard(); }   // другой корабль — плот не его
        if (old != null && old.yard != null && summonYard(p, old)) return;
        Instance inst = p.getInstance();
        String map = server().mapName(inst).orElse(null);
        if (map == null) { p.sendMessage(Component.text("Здесь корабль не призвать", NamedTextColor.RED)); return; }
        if (!summoning.add(uid)) { p.sendMessage(Component.text("Место для корабля уже ищется — подождите", NamedTextColor.RED)); return; }
        Pos at = p.getPosition();
        int rot = Math.floorMod(Math.round(at.yaw() / 90f), 4);
        int radius = config().getInt("summon.radius", 40), margin = config().getInt("summon.margin", 1);
        int[] s2 = Rot.size(t.sx, t.sz, rot);
        int reach = radius + Math.max(s2[0], s2[1]) + margin + 1;
        p.sendActionBar(Component.text("Ищу свободное место для корабля…", NamedTextColor.AQUA));
        server().loadCubes(inst, at.sub(reach, reach, reach), at.add(reach, reach, reach)).exceptionally(ex -> { summoning.remove(uid); return null; }).thenRun(() -> scheduler().async(() -> {
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
                if (old != null && old.state == Ship.State.MODEL) { unpark(old); old.state = Ship.State.PARKED; }
                s.owner = uid; s.ownerName = p.getUsername();
                s.template = t.name; s.t = t; s.map = map;
                s.ax = spot[0]; s.ay = spot[1]; s.az = spot[2]; s.rot = rot;
                place(inst, s);
                ships.put(uid, s);
                toYard(s);                                                      // сразу на настоящие блоки верфи
                p.sendMessage(Component.text("Корабль «" + t.name + "» здесь. F у места пилота — за управление.", NamedTextColor.GREEN));
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
        if (s.state == Ship.State.MODEL) unpark(s);
        else clear(instanceOf(s), s);
        ships.remove(s.owner);
        Shipyard.unpin(this, s);
        saveShips();
        p.sendMessage(Component.text("Корабль убран", NamedTextColor.YELLOW));
        return true;
    }

    /**
     * Корабль из верфи: модель (mparts/mblocks, mturn) собирается ЦЕЛИКОМ из плота — он единственная правда (там
     * настоящие блоки: стройка, двери, сундуки). Ориентация — плота (yardTurn). Плот не загружен — модель не трогаем.
     */
    boolean syncFromYard(Ship s) {
        Instance inst = instanceOf(s);
        if (inst == null || s.yard == null) return false;
        if (s.yardBox == null) {                                                // старый плот: найти коробку по блокам
            s.yardTurn = s.mturn;
            s.yardBox = scanBox(inst, s, 40);
            if (s.yardBox == null) return false;
        }
        int[] b = s.yardBox;
        if (!server().ensureCubes(inst, Shipyard.boxMin(s), Shipyard.boxMax(s))) return false;   // без ожидания: грузится — позже
        java.util.List<int[]> parts = new java.util.ArrayList<>();
        java.util.List<String> blocks = new java.util.ArrayList<>();
        for (int dy = b[1]; dy <= b[4]; dy++) for (int dz = b[2]; dz <= b[5]; dz++) for (int dx = b[0]; dx <= b[3]; dx++) {
            Block bl = inst.getBlock(s.yard[1] + dx, s.yard[2] + dy, s.yard[3] + dz);
            if (bl.isAir()) continue;
            parts.add(new int[]{dx, dy, dz});
            blocks.add(bl.state());
        }
        if (parts.isEmpty()) return false;
        s.mparts = parts;
        s.mblocks = blocks;
        s.mturn = s.yardTurn;
        return true;
    }

    /** Коробка непустых блоков плота вокруг клетки пилота (радиус r) или null. */
    private int[] scanBox(Instance inst, Ship s, int r) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        Vec c = new Vec(s.yard[1], s.yard[2], s.yard[3]);
        if (!server().ensureCubes(inst, c.sub(r, r, r), c.add(r, r, r))) return null;   // без ожидания: грузится — позже
        for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            if (inst.getBlock(s.yard[1] + dx, s.yard[2] + dy, s.yard[3] + dz).isAir()) continue;
            b[0] = Math.min(b[0], dx); b[1] = Math.min(b[1], dy); b[2] = Math.min(b[2], dz);
            b[3] = Math.max(b[3], dx); b[4] = Math.max(b[4], dy); b[5] = Math.max(b[5], dz);
        }
        if (b[0] > b[3]) return null;
        int m = Shipyard.BOX_MARGIN;
        return new int[]{b[0] - m, b[1] - m, b[2] - m, b[3] + m, b[4] + m, b[5] + m};
    }

    /**
     * Призыв корабля, который уже в верфи: блоками в мир его не ставим (копия разошлась бы с плотом) — моделью
     * перед игроком, на высоте его глаз, носом туда, куда он смотрит. Блоки — из плота.
     */
    /**
     * Перенести плот корабля в верфь другой карты: блоки (с вещами сундуков — NBT блока) — в новый плот, старый
     * очистить. Чужие блоки в новом плоте (у карты есть рельеф там, где верфь) — убрать. Плот — только коробка корабля.
     */
    CompletableFuture<Boolean> moveYard(Ship s, Instance to, String toMap) {
        Instance from = instanceOf(s);
        if (from == null || s.yard == null || s.yardBox == null) return CompletableFuture.completedFuture(false);
        int k = Shipyard.allocate(this, toMap);
        int[] st = Shipyard.seatOf(k), ny = {k, st[0], st[1], st[2]}, oy = s.yard, b = s.yardBox;
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        CompletableFuture.allOf(
                server().loadCubes(from, new Vec(oy[1] + b[0], oy[2] + b[1], oy[3] + b[2]), new Vec(oy[1] + b[3], oy[2] + b[4], oy[3] + b[5])),
                server().loadCubes(to, new Vec(ny[1] + b[0], ny[2] + b[1], ny[3] + b[2]), new Vec(ny[1] + b[3], ny[2] + b[4], ny[3] + b[5]))
        ).whenComplete((v, ex) -> scheduler().run(() -> {
            if (ex != null) { done.complete(false); return; }
            try {
                int n = 0;
                for (int dy = b[1]; dy <= b[4]; dy++) for (int dz = b[2]; dz <= b[5]; dz++) for (int dx = b[0]; dx <= b[3]; dx++) {
                    Block src = from.getBlock(oy[1] + dx, oy[2] + dy, oy[3] + dz);
                    int tx = ny[1] + dx, ty = ny[2] + dy, tz = ny[3] + dz;
                    if (src.isAir()) {
                        if (!to.getBlock(tx, ty, tz).isAir()) to.setBlock(tx, ty, tz, Block.AIR, false);   // рельеф карты в верфи
                        continue;
                    }
                    to.setBlock(tx, ty, tz, src, false);
                    from.setBlock(oy[1] + dx, oy[2] + dy, oy[3] + dz, Block.AIR, false);
                    n++;
                }
                s.yard = ny;
                s.map = toMap;
                s.oldYards = null;                                              // брошенные плоты были на старой карте
                Shipyard.pin(this, to, s);                                      // закрепление — на новый плот
                logger().info("корабль {} перенесён с карты в верфь «{}»: плот {}, блоков {}", s.ownerName, toMap, k, n);
                done.complete(true);
            } catch (Throwable t) {
                logger().error("перенос корабля {} на карту {}: {}", s.ownerName, toMap, t.toString());
                done.complete(false);
            }
        }));
        return done;
    }

    boolean summonYard(Player p, Ship s) {
        if (s.yard == null) return false;
        Instance inst = p.getInstance();
        String map = server().mapName(inst).orElse(null);
        if (map == null) { p.sendMessage(Component.text("Здесь корабль не призвать", NamedTextColor.RED)); return true; }
        if (!map.equals(s.map)) {                                               // другая карта — корабль переезжает в её верфь
            if (!summoning.add(s.owner)) { p.sendMessage(Component.text("Корабль уже переносится — подождите", NamedTextColor.RED)); return true; }
            if (s.state == Ship.State.PARKED && instanceOf(s) != null) clear(instanceOf(s), s);
            if (s.state == Ship.State.MODEL) unpark(s);
            p.sendActionBar(Component.text("Переношу корабль на эту карту…", NamedTextColor.AQUA));
            moveYard(s, inst, map).thenAccept(ok -> scheduler().run(() -> {
                summoning.remove(s.owner);
                if (!ok) { p.sendMessage(Component.text("Не удалось перенести корабль с карты «" + s.map + "»", NamedTextColor.RED)); return; }
                saveShips();
                if (p.isOnline()) summonYard(p, s);
            }));
            return true;
        }
        if (!syncFromYard(s)) { p.sendMessage(Component.text("Верфь загружается — повторите через секунду", NamedTextColor.YELLOW)); return true; }
        if (s.state == Ship.State.PARKED) clear(instanceOf(s), s);
        if (s.state == Ship.State.MODEL) unpark(s);
        Pos at = p.getPosition();
        double yaw = Math.toRadians(at.yaw());
        double dist = config().getDouble("yard.summon-distance", 12);
        s.mx = at.x() - Math.sin(yaw) * dist; s.my = at.y() + 2; s.mz = at.z() + Math.cos(yaw) * dist;
        s.myaw = at.yaw(); s.mpitch = 0; s.mroll = 0;
        s.mseatH = (float) config().getDouble("flight.seat-height", 0.5);
        s.state = Ship.State.MODEL;
        park(s, null);
        p.sendMessage(Component.text("Корабль «" + s.template + "» здесь (модель из верфи). F у места пилота — за управление.", NamedTextColor.GREEN));
        return true;
    }
}
