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
        client.enable();
        interact.enable();
        for (Ship s : ships.values()) if (s.state == Ship.State.MODEL) spawnModel(s);
        logger().info("чертежей: {}, кораблей игроков: {}", templates.size(), ships.size());
    }

    @Override
    protected void onDisable() {
        interact.disable();
        for (Ship s : ships.values()) if (s.flight != null) s.flight.emergency();
        for (Entity a : parked.values()) a.remove();                            // корабли-модели остаются в ships.json
        parked.clear();
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
                if (old != null && old.state == Ship.State.MODEL) { unpark(old); old.state = Ship.State.PARKED; }
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
        if (s.state == Ship.State.MODEL) unpark(s);
        else clear(instanceOf(s), s);
        ships.remove(s.owner);
        saveShips();
        p.sendMessage(Component.text("Корабль убран", NamedTextColor.YELLOW));
        return true;
    }
}
