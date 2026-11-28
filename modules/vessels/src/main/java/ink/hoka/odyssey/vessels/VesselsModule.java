package ink.hoka.odyssey.vessels;

import com.google.gson.JsonParser;
import ink.hoka.odyssey.api.OdysseyModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.display.ItemDisplayMeta;
import net.minestom.server.event.inventory.InventoryItemChangeEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerDeathEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.player.PlayerStopFlyingEvent;
import net.minestom.server.event.player.PlayerSwapItemEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Корабли-модели. Корабль — модель .bbmodel (файл в module/clientmods/files/, рисует клиентский модуль vessels) на
 * невидимом носителе. Админ ставит их командой /hovessel (/vessel), игрок садится ПКМ по кораблю и летит: позу
 * считает мод пилота (как у старых кораблей ships), сервер проверяет скорость и поворот и показывает корабль
 * остальным. F — выйти. Корабли — module/vessels/vessels.json.
 */
public final class VesselsModule extends OdysseyModule {

    private final Map<String, VesselKind> kinds = new LinkedHashMap<>();
    private final Map<Integer, Vessel> vessels = new ConcurrentHashMap<>();
    final VesselsClient client = new VesselsClient(this);
    final Combat combat = new Combat(this);
    final WarpDrive warp = new WarpDrive(this);
    final VesselItems items = new VesselItems(this::hoItems);
    final HullDamage hull = new HullDamage(this);
    final Beams beams = new Beams(this);
    /** Карта — планета (подушка, лучи) или космос: по имени карты (сбрасывается при reload). */
    private final Map<String, Boolean> planetMaps = new ConcurrentHashMap<>();
    private Upgrades.Config upgradeCfg = Upgrades.Config.DEFAULT;
    /** Рецепты мастерской (recipes:) и свои названия предметов (item-names:). */
    private List<Workshop.Recipe> recipes = List.of();
    private Map<String, String> itemNames = Map.of();
    private VesselStore store;
    private long ticks;
    private boolean dirty;
    private VesselMath.Limits limits = VesselMath.Limits.DEFAULT;

    @Override
    protected void onEnable() {
        loadKinds();
        store = new VesselStore(dataFolder().resolve("vessels.json"));
        for (Vessel v : store.load()) vessels.put(v.id, v);
        if (store.broken())
            logger().error("vessels.json не прочитан ({}) — файл не будет перезаписан (копия — vessels.json.broken); исправьте его и перезагрузите модуль", store.brokenWhy());
        for (Vessel v : vessels.values()) spawnCarrier(v);

        events().addListener(PlayerSwapItemEvent.class, e -> {                  // F — выйти
            if (pilotOf(e.getPlayer()) == null) return;
            e.setCancelled(true);
            exit(e.getPlayer(), true);
        });
        events().addListener(PlayerStopFlyingEvent.class, e -> {               // пилот не падает: двигает мод
            if (pilotOf(e.getPlayer()) != null) e.getPlayer().setFlying(true);
        });
        events().addListener(PlayerBlockBreakEvent.class, e -> { if (pilotOf(e.getPlayer()) != null) e.setCancelled(true); });
        events().addListener(PlayerBlockPlaceEvent.class, e -> { if (pilotOf(e.getPlayer()) != null) e.setCancelled(true); });
        events().addListener(PlayerBlockInteractEvent.class, e -> { if (pilotOf(e.getPlayer()) != null) e.setCancelled(true); });
        events().addListener(PlayerUseItemEvent.class, e -> { if (pilotOf(e.getPlayer()) != null) e.setCancelled(true); });
        events().addListener(InventoryPreClickEvent.class, e -> {               // меню корабля: что куда можно класть
            VesselMenu m = menuOf(e.getPlayer().getOpenInventory());
            if (m != null) m.onClick(e);
            Workshop.Menu w = shopOf(e.getPlayer().getOpenInventory());
            if (w != null) w.onClick(e);
        });
        events().addListener(PlayerBlockBreakEvent.class, this::fuelDrop);       // руды астероидов — топливные кристаллы
        events().addListener(InventoryItemChangeEvent.class, e -> {
            VesselMenu m = menuOf(e.getInventory());
            if (m != null) m.onChange(e);
        });
        events().addListener(PlayerDisconnectEvent.class, e -> exit(e.getPlayer(), false));
        events().addListener(PlayerDeathEvent.class, e -> exit(e.getPlayer(), false));
        events().addListener(PlayerSpawnEvent.class, e -> {                   // сменил мир — корабль остаётся, пилот снимается
            Piloting pl = pilotOf(e.getPlayer());
            if (pl != null && pl.vessel.carrier != null && e.getInstance() != pl.vessel.carrier.getInstance()) exit(e.getPlayer(), false);
            client.world(e.getPlayer(), e.getInstance());                      // планета или космос — моду (подушка, лучи)
        });
        registerCommand(new HoVesselCommand(this));
        scheduler().repeat(this::tick, 1, 1);
        client.enable();
        logger().info("видов кораблей: {}, кораблей: {}", kinds.size(), vessels.size());
    }

    @Override
    protected void onDisable() {
        beams.clear();
        for (Vessel v : vessels.values()) if (v.pilot != null) exit(v.pilot.player, true);
        for (Vessel v : vessels.values()) if (v.menu != null) { v.menu.closeAll(); v.menu = null; }
        for (Vessel v : vessels.values()) if (v.shop != null) { v.shop.closeAll(); v.shop = null; }
        client.disable();
        for (Vessel v : vessels.values()) {
            if (v.carrier != null) v.carrier.remove();
            v.carrier = null;
        }
        saveNow();
    }

    // ───────────────────────── виды ─────────────────────────

    /** Где лежат файлы для мода (module/clientmods/files) — модели берём оттуда же. */
    Path filesDir() { return dataFolder().getParent().resolve("clientmods").resolve("files"); }

    /** Перечитать виды из конфига и их модели. */
    void loadKinds() {
        kinds.clear();
        planetMaps.clear();
        List<String> bad = new ArrayList<>();
        recipes = Workshop.parse(config().get("recipes"), bad);
        for (String b : bad) logger().warn("рецепт мастерской {}", b);
        Map<String, String> names = new LinkedHashMap<>();
        if (config().get("item-names") instanceof Map<?, ?> nm) for (var e : nm.entrySet()) names.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        itemNames = Map.copyOf(names);
        upgradeCfg = Upgrades.Config.of(config().get("upgrades"));
        limits = new VesselMath.Limits(config().getDouble("check.speed-margin", 1.3), config().getDouble("check.slack", 1.5),
                config().getDouble("check.turn-margin", 1.5), config().getDouble("check.turn-slack", 10), config().getInt("check.max-dt-ticks", 40));
        if (!(config().get("kinds") instanceof Map<?, ?> all)) { logger().warn("в config.yml нет раздела kinds — кораблей нет"); return; }
        for (var e : all.entrySet()) {
            String id = e.getKey().toString();
            if (!(e.getValue() instanceof Map<?, ?> cfg)) continue;
            Object model = cfg.get("model");
            String path = model != null ? model.toString() : "vessels/" + id + ".bbmodel";
            Path f = filesDir().resolve(path).normalize();
            if (!f.startsWith(filesDir().normalize()) || !Files.isRegularFile(f)) {
                logger().error("вид {}: нет модели {} — положите её в module/clientmods/files/{}", id, f, path);
                continue;
            }
            try {
                ModelShape shape = ModelShape.parse(JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
                VesselKind k = new VesselKind(id, cfg, shape);
                kinds.put(id, k);
                logger().info("вид {}: {} треугольников, размер {}×{}×{} блоков, клеток корпуса {}", id, k.triangles,
                        r1(k.half[0] * 2), r1(k.half[1] * 2), r1(k.half[2] * 2), k.hull.size());
            } catch (Exception ex) {
                logger().error("вид {}: модель {} не разобрана: {}", id, path, ex.toString());
            }
        }
    }

    private static double r1(double v) { return Math.round(v * 10) / 10.0; }

    Map<String, VesselKind> kinds() { return kinds; }

    Collection<Vessel> vessels() { return vessels.values(); }

    Vessel vessel(int id) { return vessels.get(id); }

    double boardReach() { return config().getDouble("board-reach", 6); }

    // ───────────────────────── корабли ─────────────────────────

    Instance instanceOf(Vessel v) { return server().map(v.map).orElse(null); }

    /** Поставить корабль (команда spawn); owner — владелец (null — ничей). */
    Vessel create(String kind, Instance inst, double x, double y, double z, Quat rot, Player owner) {
        int id = 1;
        for (int k : vessels.keySet()) id = Math.max(id, k + 1);
        String map = server().mapName(inst).orElse(null);
        if (map == null) return null;
        Vessel v = new Vessel(id, kind, map, x, y, z, rot);
        if (owner != null) { v.owner = owner.getUuid().toString(); v.ownerName = owner.getUsername(); }
        vessels.put(id, v);
        spawnCarrier(v);
        save();
        return v;
    }

    /** Убрать корабль: груз выпадает рядом (мира/столбца нет — в лог), окно закрывается. */
    void remove(Vessel v) {
        if (v.pilot != null) exit(v.pilot.player, true);
        if (v.menu != null) { v.menu.closeAll(); v.menu = null; }
        if (v.shop != null) { v.shop.closeAll(); v.shop = null; }
        if (!v.cargo.isEmpty()) {
            for (ItemStack it : v.cargo.all()) dropNear(v, it);
            v.cargo.clear();
        }
        vessels.remove(v.id);
        client.detachToAll(v);
        if (v.carrier != null) v.carrier.remove();
        v.carrier = null;
        save();
    }

    /** Носитель корабля в его мире (мира нет — подождёт, вида нет — корабль сохраняется, но не виден). */
    private void spawnCarrier(Vessel v) {
        if (!kinds.containsKey(v.kind)) return;
        Instance inst = instanceOf(v);
        if (inst == null) return;
        Entity e = new Entity(EntityType.ITEM_DISPLAY);
        e.setNoGravity(true);
        int interp = config().getInt("others-interp-ticks", 3);
        e.editEntityMeta(ItemDisplayMeta.class, m -> {
            m.setItemStack(ItemStack.AIR);
            m.setViewRange((float) config().getDouble("view-range", 8));
            m.setPosRotInterpolationDuration(interp);
            m.setLeftRotation(v.rot.toFloats());
        });
        v.carrier = e;
        e.setInstance(inst, new Pos(v.x, v.y, v.z)).whenComplete((ok, err) -> scheduler().run(() -> {
            if (err != null) { logger().warn("корабль {}: носитель не поставлен: {}", v.id, err.toString()); return; }
            if (v.carrier == e && vessels.get(v.id) == v) client.attachToAll(v);
        }));
    }

    /** Раз в секунду: носитель пропал (столбец выгрузили вместе с ним) — поставить снова, когда столбец есть. */
    private void ensureCarriers() {
        for (Vessel v : vessels.values()) {
            if (v.carrier != null && !v.carrier.isRemoved()) continue;
            Instance inst = instanceOf(v);
            if (inst == null || !inst.isChunkLoaded((int) Math.floor(v.x) >> 4, (int) Math.floor(v.z) >> 4)) continue;
            spawnCarrier(v);
        }
    }

    // ───────────────────────── пилот ─────────────────────────

    Piloting pilotOf(Player p) {
        for (Vessel v : vessels.values()) if (v.pilot != null && v.pilot.player == p) return v.pilot;
        return null;
    }

    /** Глаза пилота в мире. */
    double[] seatWorld(Vessel v, VesselKind k) { return VesselMath.toWorld(k.seat[0], k.seat[1], k.seat[2], v.pos(), v.rot); }

    /**
     * Мод: ПКМ по кораблю — сесть. С ремкомплектом в руке (владелец, OP) — ремонт. На планете дальше board-reach, но
     * в пределах planet.beam-reach — луч поднимает в кабину.
     */
    void board(Player p, int id) {
        Vessel v = vessels.get(id);
        VesselKind k = v == null ? null : kinds.get(v.kind);
        if (v == null || k == null || v.carrier == null || v.carrier.isRemoved()) return;
        if (p.getInstance() != v.carrier.getInstance()) return;
        if (pilotOf(p) != null || beams.active(p)) return;
        Pos pp = p.getPosition();
        double d = VesselMath.distanceToBox(pp.x(), pp.y() + p.getEyeHeight(), pp.z(), v.pos(), v.rot, k.half);
        double slack = config().getDouble("board-slack", 1);
        boolean planet = isPlanet(p.getInstance());
        if (VesselItems.isRepair(p.getItemInMainHand())) {                      // ремкомплект в руке — чинить, не садиться
            if (d > Math.max(boardReach(), planet ? beamReach() : 0) + slack) { client.message(p, "Слишком далеко от корабля", true); return; }
            if (!canOpen(v, p.getUuid().toString(), server().isOp(p))) {
                client.message(p, "Это не ваш корабль" + (v.ownerName != null ? " (владелец — " + v.ownerName + ")" : ""), true);
                return;
            }
            hull.repairWithKit(p, v, true);
            return;
        }
        if (v.pilot != null) { p.sendActionBar(Component.text("Место пилота занято", NamedTextColor.RED)); return; }
        if (d > boardReach() + slack) {
            if (planet && d <= beamReach() + slack) { beams.up(p, v); return; }
            p.sendActionBar(Component.text("Слишком далеко от корабля", NamedTextColor.RED));
            return;
        }
        boardNow(p, v);
    }

    /** Посадить пилотом (проверки уже сделаны: место свободно, рядом). */
    void boardNow(Player p, Vessel v) {
        VesselKind k = kinds.get(v.kind);
        if (k == null || v.pilot != null || pilotOf(p) != null || v.carrier == null || p.getInstance() != v.carrier.getInstance()) return;
        Piloting pl = new Piloting(p, v, ticks);
        pl.checkMul = speedMul(v);
        v.pilot = pl;
        p.setAllowFlying(true);
        p.setFlying(true);                                                      // не падать; двигает мод (скорость полёта 0)
        p.setFlyingSpeed(0f);
        p.setInvisible(true);
        double[] eye = seatWorld(v, k);
        p.teleport(new Pos(eye[0], eye[1] - p.getEyeHeight(), eye[2], (float) v.rot.yawDeg(), 0f));
        client.start(v);
        client.state(v);
        client.hull(v, false, -1);
        p.sendMessage(Component.text("Полёт: мышь — прицел, нос доводится к нему. W/S — ход, Ctrl+W — форсаж, A/D — крен, "
                + "пробел/Shift — вверх/вниз. ЛКМ — огонь, R — варп, G — меню корабля. F5 — вид из кабины / сзади. F — выйти.", NamedTextColor.AQUA));
    }

    /** Снять пилота. teleport — поставить рядом с кораблём на безопасное место (если игрок ещё в его мире). */
    void exit(Player p, boolean teleport) {
        Piloting pl = pilotOf(p);
        if (pl == null) return;
        Vessel v = pl.vessel;
        warp.cancel(v, null);
        v.pilot = null;
        if (p.isOnline()) {
            pl.restore();
            client.end(p);
            Instance inst = v.carrier != null ? v.carrier.getInstance() : null;
            VesselKind k = kinds.get(v.kind);
            if (teleport && inst != null && p.getInstance() == inst && k != null) {
                if (isPlanet(inst)) beams.down(p, v, k);                       // планета: корабль на подушке — луч вниз
                else {
                    Pos at = exitSpot(v, k, inst);
                    p.teleport(at.withView((float) v.rot.yawDeg(), 0f));
                }
            }
        }
        save();
    }

    /**
     * Где встать после выхода: у бортов на высоте сиденья, сзади, спереди (в осях корабля), иначе над кораблём.
     * Место — свободные 2 блока не в коробке корабля; есть пол в пределах 3 блоков ниже — встать на него.
     */
    Pos exitSpot(Vessel v, VesselKind k, Instance inst) {
        double[] h = k.half;
        double y = k.seat[1] - 1.2;
        double[][] cand = {{h[0] + 1.2, y, k.seat[2]}, {-h[0] - 1.2, y, k.seat[2]}, {0, y, -h[2] - 1.5}, {0, y, h[2] + 1.5}};
        for (double[] c : cand) {
            double[] w = VesselMath.toWorld(c[0], c[1], c[2], v.pos(), v.rot);
            Pos p = standAt(inst, w[0], w[1], w[2]);
            if (p != null && VesselMath.distanceToBox(p.x(), p.y() + 0.9, p.z(), v.pos(), v.rot, k.half) > 0.6) return p;
        }
        double top = k.radius() + 0.5;                                            // над кораблём (по миру) — всегда вне коробки
        return new Pos(v.x, v.y + top, v.z);
    }

    /** Ноги в (x,y,z): свободно ли (2 блока); пол ниже — опустить на него (не глубже 3 блоков). null — занято/не загружено. */
    private Pos standAt(Instance inst, double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        if (!server().isCubeLoaded(inst, bx, by, bz) || !server().isCubeLoaded(inst, bx, by + 1, bz)) return null;
        if (solid(inst.getBlock(bx, by, bz)) || solid(inst.getBlock(bx, by + 1, bz))) return null;
        for (int d = 1; d <= 3; d++) {
            if (!server().isCubeLoaded(inst, bx, by - d, bz)) break;
            if (solid(inst.getBlock(bx, by - d, bz))) return new Pos(x, by - d + 1, z);
        }
        return new Pos(x, y, z);
    }

    private static boolean solid(Block b) { return !b.isAir() && b.isSolid(); }

    /** Поза от мода пилота: проверить и принять (или вернуть корабль назад). */
    void pose(Player p, double x, double y, double z, Quat q) {
        Piloting pl = pilotOf(p);
        if (pl == null) return;
        Vessel v = pl.vessel;
        VesselKind k = kinds.get(v.kind);
        Instance inst = v.carrier != null ? v.carrier.getInstance() : null;
        if (k == null || inst == null) return;
        double[] np = {x, y, z};
        double mul = speedMul(v);                                               // улучшения скорости; сняли — старый предел ещё 5 с
        if (mul >= pl.checkMul) { pl.checkMul = mul; pl.checkMulAt = ticks; }
        else if (ticks - pl.checkMulAt > 100) pl.checkMul = mul;
        String why = VesselMath.check(v.pos(), v.rot, np, q, ticks - pl.lastPose, k, limits, pl.checkMul);
        if (why == null) {
            int minY = inst.getCachedDimensionType().minY(), maxY = minY + inst.getCachedDimensionType().height();
            if (y < minY - 8 || y > maxY + config().getInt("check.above-world", 256)) why = "за границей мира";
            else if (insideBlocks(inst, k, np, q.normalized())) why = "в блоках";
        }
        if (why != null) {
            pl.strikes++;
            pl.rejected++;
            client.correct(v);
            if (config().getBoolean("debug", false)) logger().info("корабль {} ({}): поза отклонена ({}), подряд {}", v.id, p.getUsername(), why, pl.strikes);
            if (pl.strikes > config().getInt("check.max-strikes", 100)) {
                p.sendMessage(Component.text("Связь с кораблём потеряна — вы покинули место пилота", NamedTextColor.RED));
                exit(p, true);
            }
            return;
        }
        long dt = Math.max(1, ticks - pl.lastPose);
        double moved = Math.sqrt((x - v.x) * (x - v.x) + (y - v.y) * (y - v.y) + (z - v.z) * (z - v.z)) / dt * 20;
        pl.peakSpeed = Math.max(moved, pl.peakSpeed * 0.85);                     // для проверки удара о блоки
        pl.vx = (x - v.x) / dt;
        pl.vy = (y - v.y) / dt;
        pl.vz = (z - v.z) / dt;
        v.x = x; v.y = y; v.z = z;
        v.rot = q.normalized();
        pl.lastPose = ticks;
        pl.strikes = Math.max(0, pl.strikes - 1);
        dirty = true;
        if (v.carrier != null) {
            v.carrier.teleport(new Pos(x, y, z));
            float[] r = v.rot.toFloats();
            int interp = config().getInt("others-interp-ticks", 3);
            v.carrier.editEntityMeta(ItemDisplayMeta.class, m -> {
                m.setTransformationInterpolationStartDelta(0);
                m.setTransformationInterpolationDuration(interp);
                m.setLeftRotation(r);
            });
        }
    }

    /** Корабль заметно в блоках: клетки корпуса (выборка) в твёрдых блоках загруженного мира — не меньше check.hits. */
    private boolean insideBlocks(Instance inst, VesselKind k, double[] c, Quat q) {
        List<int[]> hull = k.hull;
        if (hull.isEmpty()) return false;
        int step = Math.max(1, hull.size() / Math.max(1, config().getInt("check.samples", 64)));
        int hit = 0, limit = config().getInt("check.hits", 4);
        for (int i = 0; i < hull.size(); i += step) {
            int[] h = hull.get(i);
            double[] w = VesselMath.toWorld(h[0] + 0.5, h[1] + 0.5, h[2] + 0.5, c, q);
            int bx = (int) Math.floor(w[0]), by = (int) Math.floor(w[1]), bz = (int) Math.floor(w[2]);
            if (!server().isCubeLoaded(inst, bx, by, bz)) continue;
            if (solid(inst.getBlock(bx, by, bz)) && ++hit >= limit) return true;
        }
        return false;
    }

    // ───────────────────────── тик ─────────────────────────

    private static final int[] AHEAD_TICKS = {10, 20, 40, 80};

    private void tick() {
        ticks++;
        if (ticks % 20 == 0) ensureCarriers();
        for (Vessel v : List.copyOf(vessels.values())) {
            Piloting pl = v.pilot;
            if (pl == null) continue;
            Player p = pl.player;
            if (!p.isOnline()) { exit(p, false); continue; }
            if (ticks - pl.lastPose > config().getInt("check.timeout-ticks", 200)) {
                p.sendMessage(Component.text("Мод не отвечает — вы покинули место пилота", NamedTextColor.RED));
                exit(p, true);
                continue;
            }
            if (ticks % 2 == 0) aheadLoad(v, pl);
            warp.tick(v);
            burn(v, pl);
        }
        beams.tick();
        if (dirty && ticks % 100 == 0) save();                                 // в полёте — раз в 5 с
    }

    /**
     * Столбцы по курсу корабля — заранее (как ядро для быстрого игрока, server/tall/AheadLoad): Minestom откидывает
     * назад, если влетел в незагруженный столбец. На 0.5…4 с вперёд, полосой в ширину корабля + 1 столбец.
     */
    private void aheadLoad(Vessel v, Piloting pl) {
        Instance inst = v.carrier != null ? v.carrier.getInstance() : null;
        VesselKind k = kinds.get(v.kind);
        if (inst == null || k == null) return;
        double sp = Math.sqrt(pl.vx * pl.vx + pl.vz * pl.vz);
        if (sp < 0.4 || sp > 200) return;                                     // медленно (обзор успевает) / телепорт
        int pcx = (int) Math.floor(v.x) >> 4, pcz = (int) Math.floor(v.z) >> 4;
        int w = 1 + (int) Math.ceil(k.radius() / 16);
        for (int t : AHEAD_TICKS) {
            int cx = (int) Math.floor((v.x + pl.vx * t) / 16), cz = (int) Math.floor((v.z + pl.vz * t) / 16);
            if (Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) > 28) break;   // не дальше обзора
            for (int dx = -w; dx <= w; dx++) for (int dz = -w; dz <= w; dz++) {
                int x = cx + dx, z = cz + dz;
                if (inst.getChunk(x, z) == null) inst.loadChunk(x, z);
            }
        }
    }

    // ───────────────────────── груз, топливо, меню ─────────────────────────

    /** Модуль hoitems (если есть): предметы с иконками. */
    private ink.hoka.odyssey.hoitems.HoItems hoItems() {
        try {
            return services().get(ink.hoka.odyssey.hoitems.HoItems.class).orElse(null);
        } catch (Throwable noModule) {                                          // hoitems не стоит — класса нет
            return null;
        }
    }

    /** Множители улучшений корабля. */
    Upgrades.Stats stats(Vessel v) { return Upgrades.stats(v.cargo.upgradeCounts(), upgradeCfg); }

    /** Сводка корабля: топливо, варп, орудие, скорость (меню и HUD пилота). */
    record Status(int fuel, int capacity, int warpCost, double burnPerMinute, double warpDistance, double warpChargeSec,
                  double warpReloadSec, double gunDamage, double gunReloadSec, double gunRange, double speedMul, boolean empty) {}

    /** Множитель скорости корабля (улучшения; пустой бак при расходе — аварийный ход). Быстро — для каждой позы. */
    double speedMul(Vessel v) {
        double m = stats(v).speed() * hull.engineMul(v);
        if (config().getDouble("fuel.burn-per-minute", 0) > 0 && v.cargo.fuelUnits() <= 0) m *= config().getDouble("fuel.empty-speed", 0.25);
        return m;
    }

    Status status(Vessel v) {
        Upgrades.Stats st = stats(v);
        int cap = Cargo.capacity(items.fuelStack());
        int fuel = v.cargo.fuelUnits();
        double bpm = Math.max(0, config().getDouble("fuel.burn-per-minute", 0));
        boolean empty = bpm > 0 && fuel <= 0;
        double speed = st.speed() * hull.engineMul(v) * (empty ? config().getDouble("fuel.empty-speed", 0.25) : 1);
        return new Status(fuel, cap, Cargo.warpCost(cap, warp.costFraction(), st.warpCost()), bpm, warp.distance(),
                warp.chargeTicks() / 20.0, warp.reloadTicks(st) / 20.0, combat.damage() * st.gunDamage(), combat.reloadTicks(st) / 20.0,
                combat.range(), speed, empty);
    }

    /** Груз поменялся (окно, варп, расход): сохранить, обновить окно и HUD пилота. */
    void cargoChanged(Vessel v) {
        dirty = true;
        if (v.menu != null) v.menu.refresh();
        if (v.shop != null) v.shop.refresh();
        client.state(v);
    }

    /** Расход топлива в полёте (fuel.burn-per-minute, 0 — не тратится). */
    private void burn(Vessel v, Piloting pl) {
        double bpm = config().getDouble("fuel.burn-per-minute", 0);
        if (bpm <= 0) return;
        double sp = Math.sqrt(pl.vx * pl.vx + pl.vy * pl.vy + pl.vz * pl.vz);
        if (sp < 0.05 || ticks - pl.lastPose > 20) return;                    // стоит / мод молчит
        v.burn += bpm / 1200;
        boolean used = false;
        while (v.burn >= 1) {
            if (!v.cargo.consumeFuel(1)) { v.burn = 0; break; }
            v.burn -= 1;
            used = true;
        }
        if (used) {
            cargoChanged(v);
            if (v.cargo.fuelUnits() == 0) client.message(pl.player, "Топливо кончилось — аварийный ход", true);
        }
    }

    /** Предмет — на землю у корабля; мира или столбца нет — в лог (не пропадает молча). */
    void dropNear(Vessel v, ItemStack it) {
        Instance inst = instanceOf(v);
        if (inst != null && inst.isChunkLoaded((int) Math.floor(v.x) >> 4, (int) Math.floor(v.z) >> 4)) {
            VesselKind k = kinds.get(v.kind);
            double up = k != null ? k.half[1] + 0.5 : 1;
            net.minestom.server.entity.ItemEntity e = new net.minestom.server.entity.ItemEntity(it);
            e.setPickupDelay(java.time.Duration.ofMillis(500));
            e.setInstance(inst, new Pos(v.x, v.y + up, v.z));
            return;
        }
        logger().warn("корабль {}: предмет некуда выронить (мира/столбца нет) — {}", v.id, Cargo.item(it));
    }

    /** Кто может открыть меню: владелец и OP. */
    static boolean canOpen(Vessel v, String uuid, boolean op) { return op || (v.owner != null && v.owner.equals(uuid)); }

    double menuReach() { return config().getDouble("menu-reach", 6); }

    /** Меню этого окна (или null — не наше). */
    VesselMenu menuOf(net.minestom.server.inventory.AbstractInventory inv) {
        if (inv == null) return null;
        for (Vessel v : vessels.values()) if (v.menu != null && v.menu.inv == inv) return v.menu;
        return null;
    }

    /** C2S MENU: открыть меню корабля (пилот — своего, иначе — на который смотрит, рядом). */
    void openMenu(Player p, int id) {
        Vessel v = vessels.get(id);
        VesselKind k = v == null ? null : kinds.get(v.kind);
        if (v == null || k == null || v.carrier == null || p.getInstance() != v.carrier.getInstance()) return;
        if (!canOpen(v, p.getUuid().toString(), server().isOp(p))) {
            client.message(p, "Это не ваш корабль" + (v.ownerName != null ? " (владелец — " + v.ownerName + ")" : ""), true);
            return;
        }
        boolean pilot = v.pilot != null && v.pilot.player == p;
        if (!pilot) {
            Pos pp = p.getPosition();
            double d = VesselMath.distanceToBox(pp.x(), pp.y() + p.getEyeHeight(), pp.z(), v.pos(), v.rot, k.half);
            double reach = isPlanet(p.getInstance()) ? Math.max(menuReach(), beamReach()) : menuReach();   // планета: корабль висит высоко
            if (d > reach + config().getDouble("board-slack", 1)) { client.message(p, "Слишком далеко от корабля", true); return; }
        }
        if (v.menu == null) v.menu = new VesselMenu(this, v);
        else v.menu.refresh();
        client.menu(p, v, v.menu);                                              // моду — до окна: по номеру окна он его узнает
        p.openInventory(v.menu.inv);
    }

    /** Кто стоит на корабле (ноги на корпусе, по мелким клеткам), кроме пилота. */
    List<Player> riders(Vessel v) {
        List<Player> out = new ArrayList<>();
        VesselKind k = kinds.get(v.kind);
        Instance inst = v.carrier != null ? v.carrier.getInstance() : null;
        if (k == null || inst == null) return out;
        double r = k.radius() + 2;
        for (Player p : inst.getPlayers()) {
            if (v.pilot != null && v.pilot.player == p) continue;
            Pos pp = p.getPosition();
            if (Math.abs(pp.x() - v.x) > r || Math.abs(pp.y() - v.y) > r || Math.abs(pp.z() - v.z) > r) continue;
            double[] l = VesselMath.toLocal(pp.x(), pp.y(), pp.z(), v.pos(), v.rot);
            if (k.standsOn(l[0], l[1], l[2], 0.6)) out.add(p);
        }
        return out;
    }

    long ticks() { return ticks; }

    void markDirty() { dirty = true; }

    /** Кнопка «Мастерская» в меню корабля: окно рецептов (тем, кто может открыть меню). */
    void openWorkshop(Player p, Vessel v) {
        if (vessels.get(v.id) != v || !canOpen(v, p.getUuid().toString(), server().isOp(p))) return;
        if (recipes.isEmpty()) { client.message(p, "В мастерской нет рецептов (config.yml, recipes:)", true); return; }
        if (v.shop == null) v.shop = new Workshop.Menu(this, v);
        else v.shop.refresh();
        if (v.shop.lastJson != null && client.isReady(p)) client.shop(p, v.shop.lastJson);   // моду — до окна
        p.openInventory(v.shop.inv);
    }

    List<Workshop.Recipe> recipes() { return recipes; }

    Map<String, String> itemNames() { return itemNames; }

    /** Предмет по ключу мастерской (vessel:…, item:…, minecraft:…); свой блок (hoka:…) не делается — null. */
    ItemStack make(String key, int count) {
        if (key == null) return null;
        int c = Math.max(1, count);
        if (key.startsWith("vessel:")) return items.of(key.substring(7), c);
        if (key.startsWith("item:")) {
            var h = hoItems();
            ItemStack it = h != null ? h.item(key.substring(5), c) : null;
            return it == null || it.isAir() ? null : it;
        }
        if (key.startsWith("hoka:")) return null;
        net.minestom.server.item.Material m = net.minestom.server.item.Material.fromKey(key.contains(":") ? key : "minecraft:" + key);
        return m == null ? null : ItemStack.of(m, Math.min(c, m.maxStackSize()));
    }

    /** Мастерская: сделать по рецепту (Shift — сколько хватит, до 8 раз). */
    void craft(Player p, Vessel v, Workshop.Recipe r, boolean many) {
        if (vessels.get(v.id) != v || !canOpen(v, p.getUuid().toString(), server().isOp(p))) return;
        int times = many ? Math.max(1, Workshop.maxTimes(v.cargo.storage, r, 8)) : 1;
        Workshop.Result res = Workshop.craft(v.cargo, r, times, k -> make(k, 1));
        String name = Workshop.name(r.result(), itemNames);
        switch (res) {
            case OK -> {
                client.message(p, "Мастерская: " + name + " ×" + r.count() * times + " — в грузе корабля", false);
                p.playSound(net.kyori.adventure.sound.Sound.sound(net.minestom.server.sound.SoundEvent.BLOCK_SMITHING_TABLE_USE,
                        net.kyori.adventure.sound.Sound.Source.BLOCK, 0.8f, 1.1f));
                cargoChanged(v);
            }
            case MISSING -> {
                StringBuilder sb = new StringBuilder();
                for (var e : r.needs().entrySet()) {
                    int h = Workshop.have(v.cargo.storage, e.getKey());
                    if (h < e.getValue()) sb.append(sb.isEmpty() ? "" : ", ").append(Workshop.name(e.getKey(), itemNames)).append(" ").append(h).append("/").append(e.getValue());
                }
                client.message(p, "Не хватает в грузе: " + sb, true);
            }
            case NO_ROOM -> client.message(p, "Нет места в грузе корабля для «" + name + "»", true);
            case BAD -> {
                client.message(p, "Рецепт «" + r.id() + "» не работает (см. лог)", true);
                logger().warn("рецепт {}: результат {} не сделать (vessel:…, item:…, minecraft:…)", r.id(), r.result());
            }
        }
    }

    /** Окно мастерской этого окна (или null). */
    Workshop.Menu shopOf(net.minestom.server.inventory.AbstractInventory inv) {
        if (inv == null) return null;
        for (Vessel v : vessels.values()) if (v.shop != null && v.shop.inv == inv) return v.shop;
        return null;
    }

    /**
     * Добыча руд астероидов (выживание): с шансом из fuel-drops выпадают «Топливные кристаллы». Ключ блока — свой
     * блок модуля blocks (hoka:id) или ванильный. Выпадают после ломания (другие модули могли его отменить).
     */
    private void fuelDrop(PlayerBlockBreakEvent e) {
        Player p = e.getPlayer();
        if (e.isCancelled() || p.getGameMode() == net.minestom.server.entity.GameMode.CREATIVE
                || p.getGameMode() == net.minestom.server.entity.GameMode.SPECTATOR) return;
        if (!(config().get("fuel-drops") instanceof Map<?, ?> drops) || drops.isEmpty()) return;
        String key = blockKey(e.getBlock());
        if (!(drops.get(key) instanceof Map<?, ?> d)) return;
        double chance = VesselKind.num(d, "chance", 0);
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        if (chance <= 0 || r.nextDouble() >= chance) return;
        int min = (int) Math.max(1, VesselKind.num(d, "min", 1)), max = (int) Math.max(min, VesselKind.num(d, "max", min));
        int n = min + r.nextInt(max - min + 1);
        Instance inst = p.getInstance();
        var pos = e.getBlockPosition();
        scheduler().run(() -> {
            if (inst == null || !inst.getBlock(pos).isAir()) return;              // ломание отменили
            net.minestom.server.entity.ItemEntity it = new net.minestom.server.entity.ItemEntity(items.crystal(n));
            it.setPickupDelay(java.time.Duration.ofMillis(300));
            it.setVelocity(new net.minestom.server.coordinate.Vec(r.nextDouble() - 0.5, 3, r.nextDouble() - 0.5));
            it.setInstance(inst, new Pos(pos.blockX() + 0.5, pos.blockY() + 0.4, pos.blockZ() + 0.5));
        });
    }

    /** Ключ блока для fuel-drops: свой блок (модуль blocks) — «hoka:<id>», иначе ванильный «minecraft:…». */
    private String blockKey(Block b) {
        try {
            var hb = services().get(ink.hoka.odyssey.blocks.HoBlocks.class).orElse(null);
            String id = hb == null ? null : hb.idOf(b);
            if (id != null) return "hoka:" + id;
        } catch (Throwable noModule) {                                          // blocks не стоит — класса нет
        }
        return b.key().asString();
    }

    /** Подушка над планетой: ближе стольких блоков корпус к блокам не подходит. */
    double clearance() { return Math.max(0, config().getDouble("planet.clearance", 10)); }

    /** Посадка лучом и меню с земли на планете — не дальше стольких блоков от коробки корабля. */
    double beamReach() { return config().getDouble("planet.beam-reach", 16); }

    /**
     * Планета ли карта (подушка над поверхностью, лучи): нет — космос. Космос — карты из space-maps (по умолчанию
     * [cosmos]) и карты с генератором space в map.yml; миры не из maps/ — тоже как космос (без подушки).
     */
    boolean isPlanet(Instance inst) {
        if (inst == null || !config().getBoolean("planet.enabled", true)) return false;
        String name = server().mapName(inst).orElse(null);
        if (name == null) return false;
        return planetMaps.computeIfAbsent(name, n -> {
            List<String> space = config().contains("space-maps") ? config().getStringList("space-maps") : List.of("cosmos");
            if (space.contains(n)) return false;
            String gen = server().mapConfig(n).map(c -> c.getString("generator", "")).orElse("");
            return !"space".equalsIgnoreCase(gen);
        });
    }

    // ───────────────────────── сохранение ─────────────────────────

    /** Сохранить в фоне (снимок — сейчас). */
    void save() {
        dirty = false;
        if (store == null || store.broken()) return;
        String json = VesselStore.json(new ArrayList<>(vessels.values()));
        scheduler().async(() -> write(json));
    }

    private void saveNow() {
        if (store == null || store.broken()) return;
        write(VesselStore.json(new ArrayList<>(vessels.values())));
    }

    private void write(String json) {
        try {
            store.write(json);
        } catch (Exception e) {
            logger().error("vessels.json не записан: {}", e.toString());
        }
    }

    boolean storeBroken() { return store != null && store.broken(); }
}
