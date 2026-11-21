package ink.hoka.odyssey.ships;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.display.BlockDisplayMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Полёт корабля.
 *
 * <p><b>Почему без задержки управления.</b> Пилот летит собственным полётом игрока (как в креативе: W/A/S/D,
 * пробел — вверх, Shift — вниз). Это движение считает клиент пилота сам, не дожидаясь сервера. Модель корабля
 * (по block_display на блок) — <i>пассажиры</i> пилота: клиент каждого игрока сам держит их приклеенными к пилоту,
 * поэтому модель не отстаёт ни у пилота, ни у зрителей (у зрителей вся связка просто идёт с их пингом).
 * Сервер только плавно поворачивает модель за взглядом пилота (с инерцией — запаздывание поворота незаметно),
 * проверяет столкновения с рельефом и сажает корабль.
 */
final class Flight {

    /** Блок модели: смещение угла блока от точки пилота (в мировой ориентации на момент взлёта) и состояние. */
    private record Part(Vec o, Block block, Entity display) {}

    private record Rider(Player p, Vec offset, float yaw) {}

    final Ship ship;
    final Player pilot;
    private final ShipsModule mod;
    private final Instance instance;
    private final List<Part> parts = new ArrayList<>();
    private final List<Rider> crew = new ArrayList<>();
    private final double baseYaw;            // взгляд пилота при взлёте (поворот модели 0)
    private double shipYaw;                  // текущий курс модели
    private double shownYaw = Double.NaN;    // последний отправленный клиентам
    private Pos lastSafe;
    private long ticks;
    // как было у пилота до полёта
    private final GameMode gm;
    private final boolean allowFly, flying;
    private final float flySpeed;

    Flight(ShipsModule mod, Ship ship, Player pilot) {
        this.mod = mod;
        this.ship = ship;
        this.pilot = pilot;
        this.instance = pilot.getInstance();
        this.gm = pilot.getGameMode();
        this.allowFly = pilot.isAllowFlying();
        this.flying = pilot.isFlying();
        this.flySpeed = pilot.getFlyingSpeed();
        this.baseYaw = pilot.getPosition().yaw();
        this.shipYaw = baseYaw;
    }

    private double seatH() { return mod.config().getDouble("flight.seat-height", 0.5); }
    private double riderY() { return mod.config().getDouble("flight.rider-y", 1.8); }

    /** Взлёт: блоки → модель, пилот в полёт, экипаж — пассажирами. */
    void start() {
        Vec seat = ship.seatWorld();
        Pos pivot = new Pos(seat.x() + 0.5, seat.y() + seatH(), seat.z() + 0.5, (float) baseYaw, pilot.getPosition().pitch());
        // экипаж: игроки на борту (кроме пилота)
        for (Player p : instance.getPlayers()) {
            if (p == pilot || !ship.around(p.getPosition()) || p.getVehicle() != null) continue;
            crew.add(new Rider(p, p.getPosition().asVec().sub(pivot), p.getPosition().yaw()));
        }
        // блоки → части модели (мировая ориентация на момент взлёта), блоки убираем
        ShipTemplate t = ship.t;
        List<Vec> toClear = new ArrayList<>();
        for (int y = 0; y < t.sy; y++) for (int z = 0; z < t.sz; z++) for (int x = 0; x < t.sx; x++) {
            if (t.block(x, y, z).isAir()) continue;
            Vec w = ship.world(x, y, z);
            Block placed = instance.getBlock(w);
            if (placed.isAir()) continue;                                       // игрок разобрал — в модели нет
            parts.add(new Part(w.sub(pivot.x(), pivot.y(), pivot.z()), placed, null));
            toClear.add(w);
        }
        for (Vec w : toClear) instance.setBlock(w, Block.AIR, false);
        for (Vec w : toClear) instance.setBlock(w, Block.AIR);                  // второй проход — обновить соседей снаружи

        pilot.teleport(pivot);
        pilot.setGameMode(GameMode.ADVENTURE);                                  // в полёте ломать/ставить нельзя
        pilot.setAllowFlying(true);
        pilot.setFlying(true);
        pilot.setFlyingSpeed((float) mod.config().getDouble("flight.speed", 0.12));
        pilot.setInvisible(true);
        lastSafe = pivot;

        // модель
        List<Part> spawned = new ArrayList<>(parts.size());
        for (Part p : parts) {
            Entity e = new Entity(EntityType.BLOCK_DISPLAY);
            e.setNoGravity(true);
            e.editEntityMeta(BlockDisplayMeta.class, m -> {
                m.setBlockState(p.block);
                m.setViewRange(4f);
                m.setTranslation(p.o.sub(0, riderY(), 0));
            });
            e.setInstance(instance, pivot).thenRun(() -> pilot.addPassenger(e));
            spawned.add(new Part(p.o, p.block, e));
        }
        parts.clear();
        parts.addAll(spawned);
        for (Rider r : crew) {
            r.p.teleport(pivot).thenRun(() -> pilot.addPassenger(r.p));
            r.p.sendMessage(Component.text("Вы на борту — корабль взлетел. Высадка — при посадке.", NamedTextColor.AQUA));
        }
        ship.state = Ship.State.FLYING;
        ship.flight = this;
        pilot.sendMessage(Component.text("Полёт: W/A/S/D, пробел — вверх, Shift — вниз. F — посадка.", NamedTextColor.GREEN));
    }

    /** Каждый тик. */
    void tick() {
        ticks++;
        if (!pilot.isOnline() || pilot.getInstance() != instance) { emergency(); return; }
        if (!pilot.isFlying()) pilot.setFlying(true);                           // двойной пробел не выключает полёт
        // курс модели — за взглядом пилота, с ограничением скорости поворота
        double maxStep = mod.config().getDouble("flight.turn-speed", 90) / 20.0;
        double diff = Rot.wrap(pilot.getPosition().yaw() - shipYaw);
        shipYaw += Math.max(-maxStep, Math.min(maxStep, diff));
        int every = Math.max(1, mod.config().getInt("flight.model-update-ticks", 2));
        if (ticks % every == 0 && (Double.isNaN(shownYaw) || Math.abs(Rot.wrap(shipYaw - shownYaw)) > 0.3)) {
            shownYaw = shipYaw;
            updateModel(every + 1);
        }
        int col = mod.config().getInt("flight.collision-ticks", 4);
        if (col > 0 && ticks % col == 0) {
            if (collides(pilot.getPosition(), shipYaw - baseYaw)) {
                pilot.teleport(lastSafe.withView(pilot.getPosition().yaw(), pilot.getPosition().pitch()));
                pilot.sendActionBar(Component.text("Столкновение!", NamedTextColor.RED));
            } else {
                lastSafe = pilot.getPosition();
            }
        }
        if (ticks % 10 == 0)
            pilot.sendActionBar(Component.text("«" + ship.template + "»  высота " + pilot.getPosition().blockY() + "   F — посадка", NamedTextColor.AQUA));
    }

    private void updateModel(int interp) {
        double rel = shipYaw - baseYaw;
        float[] q = Rot.quat(rel);
        double ry = riderY();
        for (Part p : parts) {
            double[] r = Rot.yaw(p.o.x(), p.o.z(), rel);
            Vec t = new Vec(r[0], p.o.y() - ry, r[1]);
            p.display.editEntityMeta(BlockDisplayMeta.class, m -> {
                m.setTransformationInterpolationStartDelta(0);
                m.setTransformationInterpolationDuration(interp);
                m.setTranslation(t);
                m.setLeftRotation(q);
            });
        }
    }

    /** Пересекается ли модель (при курсе rel) с непроходимыми блоками мира. */
    private boolean collides(Pos pilotPos, double rel) {
        for (Part p : parts) {
            double[] r = Rot.yaw(p.o.x() + 0.5, p.o.z() + 0.5, rel);
            Block b = instance.getBlock(pilotPos.x() + r[0], pilotPos.y() + p.o.y() + 0.5, pilotPos.z() + r[1]);
            if (!b.isAir() && !b.registry().isReplaceable()) return true;
        }
        return false;
    }

    /**
     * Посадка: курс округляется до 90°, корабль ставится по сетке в текущем месте. Если место занято — false
     * (корабль летит дальше).
     */
    boolean land() {
        Pos pp = pilot.getPosition();
        int q = Math.floorMod((int) Math.round((shipYaw - baseYaw) / 90.0), 4);
        double sh = seatH();
        Vec pivot = new Vec(Math.floor(pp.x()) + 0.5, Math.floor(pp.y() - sh + 0.01) + sh, Math.floor(pp.z()) + 0.5);
        List<Vec> pos = new ArrayList<>(parts.size());
        for (Part p : parts) {
            double[] r = Rot.yaw(p.o.x() + 0.5, p.o.z() + 0.5, q * 90.0);
            Vec w = new Vec(Math.floor(pivot.x() + r[0]), Math.floor(pivot.y() + p.o.y() + 0.5), Math.floor(pivot.z() + r[1]));
            Block b = instance.getBlock(w);
            if (!b.isAir() && !b.registry().isReplaceable()) {
                pilot.sendActionBar(Component.text("Здесь не сесть: мешают блоки (x " + w.blockX() + " y " + w.blockY() + " z " + w.blockZ() + ")", NamedTextColor.RED));
                return false;
            }
            pos.add(w);
        }
        // новое положение корабля: поворот и угол по месту пилота
        ship.rot = (ship.rot + q) & 3;
        Vec seatW = new Vec(Math.floor(pivot.x()), Math.floor(pivot.y() - sh + 0.01), Math.floor(pivot.z()));
        int[] sr = Rot.xz(ship.t.seat[0], ship.t.seat[2], ship.t.sx, ship.t.sz, ship.rot);
        ship.ax = seatW.blockX() - sr[0];
        ship.ay = seatW.blockY() - ship.t.seat[1];
        ship.az = seatW.blockZ() - sr[1];
        for (int i = 0; i < parts.size(); i++) instance.setBlock(pos.get(i), Rot.block(parts.get(i).block, q), false);
        for (int i = 0; i < parts.size(); i++) instance.setBlock(pos.get(i), instance.getBlock(pos.get(i)));   // обновить соседей
        finish(new Pos(pivot.x(), pivot.y(), pivot.z(), (float) (baseYaw + q * 90), pp.pitch()), q);
        ship.state = Ship.State.PARKED;
        pilot.sendMessage(Component.text("Посадка.", NamedTextColor.GREEN));
        return true;
    }

    /** Пилот вышел/сменил мир/модуль выключается: сесть где есть, иначе убрать корабль (вернётся при призыве). */
    void emergency() {
        if (land()) return;
        finish(lastSafe, 0);
        ship.state = Ship.State.STOWED;
    }

    /** Убрать модель, вернуть пилота и экипаж. */
    private void finish(Pos pivot, int q) {
        for (Part p : parts) { pilot.removePassenger(p.display); p.display.remove(); }
        parts.clear();
        for (Rider r : crew) {
            pilot.removePassenger(r.p);
            if (!r.p.isOnline()) continue;
            double[] o = Rot.yaw(r.offset.x(), r.offset.z(), q * 90.0);
            r.p.teleport(new Pos(pivot.x() + o[0], pivot.y() + r.offset.y(), pivot.z() + o[1], r.yaw + q * 90f, 0));
        }
        crew.clear();
        if (pilot.isOnline()) {
            pilot.setInvisible(false);
            pilot.setFlyingSpeed(flySpeed);
            pilot.setGameMode(gm);
            pilot.setAllowFlying(allowFly || gm == GameMode.CREATIVE);
            pilot.setFlying(flying && pilot.isAllowFlying());
            pilot.teleport(pivot);
        }
        ship.flight = null;
        mod.saveShips();
    }
}
