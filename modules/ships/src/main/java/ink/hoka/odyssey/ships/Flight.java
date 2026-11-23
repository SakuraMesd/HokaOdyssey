package ink.hoka.odyssey.ships;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.display.ItemDisplayMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Полёт корабля. Летит МОД пилота (клиентский модуль ships): считает физику, управление, камеру, столкновения и
 * присылает положение каждый тик. Сервер только проверяет (скорость, границы мира, не в блоках ли), при нарушении
 * возвращает корабль назад, показывает его остальным (невидимый «якорь» — item display, мод рисует на нём блоки)
 * и сажает корабль блоками. Положение — точка пилота: центр клетки сиденья, на seat-height над её низом.
 */
final class Flight {

    private record Part(int dx, int dy, int dz, Block block) {}

    private final ShipsModule mod;
    final Ship ship;
    final Player pilot;
    private final Instance instance;
    private final List<Part> parts = new ArrayList<>();
    /** Внешние клетки корабля (у которых есть пустой сосед) — для проверки «не в блоках ли». */
    private final List<Part> hull = new ArrayList<>();
    /** Курс носа при взлёте (кратен 90°): блоки мира в систему корабля поворачиваются на него. */
    private final double baseYaw;
    private final double seatH;
    private Entity anchor;
    // принятое положение
    private double x, y, z, yaw, pitch, roll;
    private long ticks, lastPose, strikes, rejected;
    private boolean finished;
    // как было у пилота
    private final GameMode gm;
    private final boolean allowFly, flying, invisible;
    private final float flySpeed;
    private String vacuumBefore;

    Flight(ShipsModule mod, Ship ship, Player pilot) {
        this.mod = mod;
        this.ship = ship;
        this.pilot = pilot;
        this.instance = pilot.getInstance();
        this.gm = pilot.getGameMode();
        this.allowFly = pilot.isAllowFlying();
        this.flying = pilot.isFlying();
        this.invisible = pilot.isInvisible();
        this.flySpeed = pilot.getFlyingSpeed();
        boolean model = ship.state == Ship.State.MODEL;
        this.baseYaw = model ? ship.mturn : ShipTemplate.yawOf(Rot.dir(ship.t.forwardDir(), ship.rot));
        this.seatH = model ? ship.mseatH : mod.config().getDouble("flight.seat-height", 0.5);
    }

    // ───────────────────────── взлёт ─────────────────────────

    /** Взлёт: блоки корабля убираются из мира, мод пилота получает их и ведёт корабль. */
    void start() {
        if (!mod.client.ready(pilot)) {
            pilot.sendMessage(Component.text("Для полёта нужен мод Hoka Odyssey (клиентский модуль ships не загружен)", NamedTextColor.RED));
            return;
        }
        Map<Long, Block> cells = new HashMap<>();
        Pos pivot;
        if (ship.state == Ship.State.MODEL) {
            // взлёт со стоянки-модели: блоки — из сохранённого, положение — где стоял
            for (int i = 0; i < ship.mparts.size(); i++) {
                int[] q = ship.mparts.get(i);
                Block b = Block.fromState(ship.mblocks.get(i));
                if (b == null || b.isAir()) continue;
                Part p = new Part(q[0], q[1], q[2], b);
                parts.add(p);
                cells.put(key(p.dx, p.dy, p.dz), b);
            }
            anchor = mod.takeParked(ship);                                      // тот же якорь
            x = ship.mx; y = ship.my; z = ship.mz;
            yaw = ship.myaw; pitch = ship.mpitch; roll = ship.mroll;
            pivot = new Pos(x, y, z, (float) yaw, 0f);
        } else {
            pivot = takeBlocks(cells);
        }
        hullOf(cells);
        pilot.teleport(pivot);
        launch(pivot);
    }

    /** Взлёт со стоянки блоками: блоки корабля → части, из мира убрать. Возвращает точку пилота. */
    private Pos takeBlocks(Map<Long, Block> cells) {
        Vec seat = ship.seatWorld();
        ShipTemplate t = ship.t;
        List<Vec> toClear = new ArrayList<>();
        for (int yy = 0; yy < t.sy; yy++) for (int zz = 0; zz < t.sz; zz++) for (int xx = 0; xx < t.sx; xx++) {
            if (t.block(xx, yy, zz).isAir() && !t.inZone(xx, yy, zz)) continue;
            Vec w = ship.world(xx, yy, zz);
            Block placed = instance.getBlock(w);
            if (placed.isAir()) continue;
            Part p = new Part(w.blockX() - seat.blockX(), w.blockY() - seat.blockY(), w.blockZ() - seat.blockZ(), placed);
            parts.add(p);
            cells.put(key(p.dx, p.dy, p.dz), placed);
            toClear.add(w);
        }
        for (Vec w : toClear) instance.setBlock(w, Block.AIR, false);
        for (Vec w : toClear) instance.setBlock(w, Block.AIR);                  // второй проход — обновить соседей снаружи
        x = seat.x() + 0.5;
        y = seat.y() + seatH;
        z = seat.z() + 0.5;
        yaw = baseYaw;
        pitch = 0;
        roll = 0;
        return new Pos(x, y, z, (float) yaw, 0f);
    }

    private void hullOf(Map<Long, Block> cells) {
        for (Part p : parts) {
            boolean outer = false;
            for (int[] d : DIRS) if (!cells.containsKey(key(p.dx + d[0], p.dy + d[1], p.dz + d[2]))) { outer = true; break; }
            if (outer) hull.add(p);
        }
    }

    /** Пилот — в полёт, якорь — остальным, моду — «ты летишь». */
    private void launch(Pos pivot) {
        pilot.setGameMode(GameMode.ADVENTURE);                                  // в полёте ломать/ставить нельзя
        pilot.setAllowFlying(true);
        pilot.setFlying(true);                                                  // не падать; двигает мод (скорость полёта 0)
        pilot.setFlyingSpeed(0f);
        pilot.setInvisible(true);
        vacuumBefore = mod.takeInsideBefore(pilot);                             // был внутри корабля — его прежний режим
        if (vacuumBefore == null) vacuumBefore = mod.vacuum().get(pilot);       // невесомость не толкает пилота
        if (vacuumBefore != null) mod.vacuum().set(pilot, "NORMAL");

        // «якорь» для остальных: пустой item display, мод рисует на нём блоки корабля; пилот видит свой корабль сам
        if (anchor == null) {
            anchor = new Entity(EntityType.ITEM_DISPLAY);
            anchor.setNoGravity(true);
            anchor.editEntityMeta(ItemDisplayMeta.class, m -> {
                m.setItemStack(ItemStack.AIR);
                m.setViewRange(8f);
                m.setPosRotInterpolationDuration(3);
                m.setLeftRotation(Rot.quat(yaw, pitch, roll, 0));
            });
            anchor.setInstance(instance, pivot.withView(0f, 0f)).join();
        }
        anchor.updateViewableRule(v -> v != pilot);

        ship.state = Ship.State.FLYING;
        ship.flight = this;
        lastPose = 0;
        mod.client.start(this);
        pilot.sendMessage(Component.text("Полёт: мышь — прицел, нос доводится к нему. W/S — ход, Ctrl+W — форсаж, A/D — вбок, "
                + "пробел/Shift — вверх/вниз. F5 — вид из кабины / сзади. F — посадка.", NamedTextColor.AQUA));
    }

    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private static long key(int x, int y, int z) { return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF); }

    // ───────────────────────── положение от мода ─────────────────────────

    /** Положение корабля от мода пилота: проверить и принять (или вернуть корабль назад). */
    void pose(double nx, double ny, double nz, double nyaw, double npitch, double nroll) {
        if (finished) return;
        if (!Double.isFinite(nx) || !Double.isFinite(ny) || !Double.isFinite(nz) || !Double.isFinite(nyaw)
                || !Double.isFinite(npitch) || !Double.isFinite(nroll)) { reject("плохие числа"); return; }
        var c = mod.config();
        long dt = Math.max(1, ticks - lastPose);
        double cap = c.getDouble("flight.max-speed", 50) / 20.0 * Math.max(1, c.getDouble("flight.boost", 1.4))
                * c.getDouble("check.speed-margin", 1.3);
        double allowed = cap * Math.min(dt, 40) + c.getDouble("check.slack", 1.5);
        double dx = nx - x, dy = ny - y, dz = nz - z;
        if (dx * dx + dy * dy + dz * dz > allowed * allowed) { reject("слишком быстро"); return; }
        int minY = instance.getCachedDimensionType().minY(), maxY = minY + instance.getCachedDimensionType().height();
        if (ny < minY - 8 || ny > maxY + c.getInt("check.above-world", 256)) { reject("за границей мира"); return; }
        if (insideBlocks(nx, ny, nz, nyaw, npitch, nroll)) { reject("в блоках"); return; }
        x = nx;
        y = ny;
        z = nz;
        yaw = nyaw;
        pitch = Math.clamp(npitch, -90, 90);
        roll = Rot.wrap(nroll);                                                 // крен любой — хоть вверх ногами
        lastPose = ticks;
        strikes = Math.max(0, strikes - 1);
        anchor.teleport(new Pos(x, y, z, 0f, 0f));
        float[] q = Rot.quat(yaw, pitch, roll, 0);
        int interp = c.getInt("flight.others-interp-ticks", 3);
        anchor.editEntityMeta(ItemDisplayMeta.class, m -> {
            m.setTransformationInterpolationStartDelta(0);
            m.setTransformationInterpolationDuration(interp);
            m.setLeftRotation(q);
        });
    }

    /** Корабль заметно в блоках (по внешним клеткам, с допуском) — с курсом, наклоном и креном, как у мода. */
    private boolean insideBlocks(double px, double py, double pz, double pyaw, double ppitch, double proll) {
        if (hull.isEmpty()) return false;
        int step = Math.max(1, hull.size() / Math.max(1, mod.config().getInt("check.samples", 64)));
        int hit = 0, limit = mod.config().getInt("check.hits", 4);
        float[] q = Rot.quat(pyaw, ppitch, proll, baseYaw);
        for (int i = 0; i < hull.size(); i += step) {
            Part p = hull.get(i);
            double[] h = Rot.rotate(q, p.dx, p.dy + 0.5 - seatH, p.dz);
            int bx = (int) Math.floor(px + h[0]), by = (int) Math.floor(py + h[1]), bz = (int) Math.floor(pz + h[2]);
            if (!instance.isChunkLoaded(bx >> 4, bz >> 4)) continue;
            if (solid(instance.getBlock(bx, by, bz)) && ++hit >= limit) return true;
        }
        return false;
    }

    private void reject(String why) {
        strikes++;
        rejected++;
        mod.client.correct(this, x, y, z, yaw, pitch, roll);
        if (mod.config().getBoolean("flight.debug", true)) mod.logger().info("полёт {}: положение отклонено ({}), подряд {}", pilot.getUsername(), why, strikes);
        if (strikes > mod.config().getInt("check.max-strikes", 100)) {
            pilot.sendMessage(Component.text("Связь с кораблём потеряна — аварийная посадка", NamedTextColor.RED));
            emergency();
        }
    }

    /** Каждый тик сервера: мод пропал — посадить корабль. */
    void tick() {
        if (finished) return;
        ticks++;
        if (ticks - lastPose > mod.config().getInt("check.timeout-ticks", 200)) {
            pilot.sendMessage(Component.text("Мод не отвечает — аварийная посадка", NamedTextColor.RED));
            emergency();
        }
        if (mod.config().getBoolean("flight.debug", true) && ticks % Math.max(20, mod.config().getInt("flight.debug-every", 200)) == 0)
            mod.logger().info("полёт {}: ({}, {}, {}) курс {} наклон {} крен {}, отклонено за период {}, пинг {} мс",
                    pilot.getUsername(), Math.round(x), Math.round(y), Math.round(z), Math.round(yaw), Math.round(pitch), Math.round(roll),
                    rejected, pilot.getLatency());
        if (ticks % Math.max(20, mod.config().getInt("flight.debug-every", 200)) == 0) rejected = 0;
    }

    // ───────────────────────── для мода ─────────────────────────

    Entity anchorAll() { return anchor; }
    Entity anchorPilot() { return null; }
    Player pilot() { return pilot; }
    double seatHeight() { return seatH; }
    double blockTurn() { return baseYaw; }
    double[] pose() { return new double[]{x, y, z, yaw, pitch, roll}; }

    /** Блоки корабля для мода: dx, dy, dz от клетки пилота (мир) и id состояния блока. */
    List<int[]> partsForClient() {
        List<int[]> out = new ArrayList<>(parts.size());
        for (Part p : parts) out.add(new int[]{p.dx, p.dy, p.dz, p.block.stateId()});
        return out;
    }

    // старые обработчики (вид, сырая мышь, журнал пакетов) — теперь всё это в моде
    void rawLook(float yawDeg) { }
    void setView(boolean cockpit) { }
    void packetOut(Object packet) { }

    // ───────────────────────── посадка ─────────────────────────

    private static boolean solid(Block b) { return !b.isAir() && !b.registry().isReplaceable(); }

    boolean land() { return land(false); }

    /**
     * Посадка: курс округляется до 90°, корабль ставится по сетке в текущем месте. Вся новая коробка корабля
     * должна быть свободна (воздух/трава) и не задевать другие корабли — иначе false (летим дальше).
     * force — аварийно: наклон носа не важен.
     */
    boolean land(boolean force) {
        if (finished) return true;
        double lim = mod.config().getDouble("flight.land-max-pitch", 30);
        if (!force && Math.abs(pitch) > lim) { warn("Выровняйте нос корабля (сейчас " + Math.round(-pitch) + "°, можно до ±" + Math.round(lim) + "°)"); return false; }
        if (!force && Math.abs(Rot.wrap(roll)) > lim) { warn("Выровняйте крен корабля (сейчас " + Math.round(Rot.wrap(roll)) + "°, можно до ±" + Math.round(lim) + "°)"); return false; }
        int q = Math.floorMod((int) Math.round((yaw - baseYaw) / 90.0), 4);
        int sx = (int) Math.floor(x), sy = (int) Math.floor(y - seatH + 0.01), sz = (int) Math.floor(z);
        int rot = (ship.rot + q) & 3;
        int[] sr = Rot.xz(ship.t.seat[0], ship.t.seat[2], ship.t.sx, ship.t.sz, rot);
        Ship probe = new Ship();
        probe.t = ship.t; probe.rot = rot; probe.state = Ship.State.PARKED;
        probe.ax = sx - sr[0]; probe.ay = sy - ship.t.seat[1]; probe.az = sz - sr[1];
        int[] size = probe.size();
        int minY = instance.getCachedDimensionType().minY(), maxY = minY + instance.getCachedDimensionType().height();
        if (probe.ay < minY || probe.ay + size[1] > maxY) { warn("Здесь не сесть: корабль выходит за высоту мира"); return false; }
        String map = mod.server().mapName(instance).orElse(null);
        for (Ship other : mod.ships.values()) {
            if (other == ship || other.state != Ship.State.PARKED || !java.util.Objects.equals(other.map, map)) continue;
            int[] os = other.size();
            if (probe.ax < other.ax + os[0] && other.ax < probe.ax + size[0] && probe.ay < other.ay + os[1] && other.ay < probe.ay + size[1]
                    && probe.az < other.az + os[2] && other.az < probe.az + size[2]) { warn("Здесь не сесть: рядом корабль " + other.ownerName); return false; }
        }
        List<Vec> pos = new ArrayList<>(parts.size());
        for (Part p : parts) {
            int[] r = turn(p.dx, p.dz, q);
            pos.add(new Vec(sx + r[0], sy + p.dy, sz + r[1]));
        }
        for (Vec w : pos) {
            if (!instance.isChunkLoaded(w.blockX() >> 4, w.blockZ() >> 4)) { warn("Здесь не сесть: мир ещё не загружен"); return false; }
            if (solid(instance.getBlock(w))) { warn("Здесь не сесть: мешают блоки (x " + w.blockX() + " y " + w.blockY() + " z " + w.blockZ() + ")"); return false; }
        }
        for (int i = 0; i < parts.size(); i++) instance.setBlock(pos.get(i), Rot.block(parts.get(i).block, q), false);
        for (Vec w : pos) instance.setBlock(w, instance.getBlock(w));             // обновить соседей
        ship.rot = rot; ship.ax = probe.ax; ship.ay = probe.ay; ship.az = probe.az;
        ship.state = Ship.State.PARKED;
        finish(new Pos(sx + 0.5, sy + seatH, sz + 0.5, (float) (baseYaw + q * 90), 0f));
        pilot.sendMessage(Component.text("Посадка.", NamedTextColor.GREEN));
        return true;
    }

    /**
     * Стоянка «как есть» (F): корабль остаётся моделью там, где висит, в том же повороте — блоков в мире нет, его
     * рисует мод, по нему можно ходить. Пилот встаёт на место пилота. Снова в полёт — /hoship fly рядом.
     */
    void park() {
        if (finished) return;
        ship.state = Ship.State.MODEL;
        ship.mx = x; ship.my = y; ship.mz = z;
        ship.myaw = (float) yaw; ship.mpitch = (float) pitch; ship.mroll = (float) roll;
        ship.mturn = (float) baseYaw;
        ship.mseatH = (float) seatH;
        ship.mparts = new ArrayList<>(parts.size());
        ship.mblocks = new ArrayList<>(parts.size());
        for (Part p : parts) { ship.mparts.add(new int[]{p.dx, p.dy, p.dz}); ship.mblocks.add(p.block.state()); }
        ship.map = mod.server().mapName(instance).orElse(ship.map);
        Entity keep = anchor;
        anchor = null;                                                          // якорь остаётся — им владеет модуль
        mod.client.stop(this);
        finish(new Pos(x, y - seatH + 1.02, z, (float) yaw, 0f));               // встать на сиденье
        mod.park(ship, keep);
        pilot.sendMessage(Component.text("Корабль остался здесь. Снова в полёт — /hoship fly рядом с ним.", NamedTextColor.GREEN));
    }

    /** Целочисленный поворот смещения (dx, dz) на q × 90° по часовой — как Rot.yaw, но без погрешностей. */
    static int[] turn(int dx, int dz, int q) {
        return switch (q & 3) {
            case 1 -> new int[]{-dz, dx};
            case 2 -> new int[]{-dx, -dz};
            case 3 -> new int[]{dz, -dx};
            default -> new int[]{dx, dz};
        };
    }

    private void warn(String t) { if (pilot.isOnline()) pilot.sendActionBar(Component.text(t, NamedTextColor.RED)); }

    /**
     * Пилот вышел / сменил карту / мод пропал / модуль выключается: сесть на месте (если пилот ещё в этом мире),
     * иначе убрать корабль (вернётся при призыве). Состояние пилота восстанавливается всегда.
     */
    void emergency() {
        if (finished) return;
        try {
            if (pilot.isOnline() && pilot.getInstance() == instance && land(true)) return;
        } catch (Throwable e) {
            mod.logger().error("аварийная посадка {}: {}", ship.ownerName, e.toString());
        }
        ship.state = Ship.State.STOWED;
        finish(new Pos(x, y, z, (float) yaw, 0f));
    }

    /** Убрать якорь, вернуть пилота. */
    private void finish(Pos at) {
        finished = true;
        if (anchor != null) mod.client.stop(this);
        if (anchor != null) { anchor.remove(); anchor = null; }
        if (pilot.isOnline()) {
            if (vacuumBefore != null) mod.vacuum().set(pilot, vacuumBefore);
            pilot.setInvisible(invisible);
            pilot.setFlyingSpeed(flySpeed);
            pilot.setGameMode(gm);
            pilot.setAllowFlying(allowFly || gm == GameMode.CREATIVE);
            pilot.setFlying(flying && pilot.isAllowFlying());
            if (pilot.getInstance() == instance) pilot.teleport(at);
        }
        ship.flight = null;
        mod.saveShips();
    }
}
