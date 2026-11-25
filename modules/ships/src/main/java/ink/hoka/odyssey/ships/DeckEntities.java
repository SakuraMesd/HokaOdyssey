package ink.hoka.odyssey.ships;

import ink.hoka.odyssey.api.DropPlacer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.entity.EntitySpawnEvent;
import net.minestom.server.event.item.PickupItemEvent;
import net.minestom.server.instance.EntityTracker;
import net.minestom.server.instance.Instance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Предметы на палубе (SHIPYARD.md §9.3). Предмет, оказавшийся на корабле, живёт в ВЕРФИ — на настоящих блоках
 * корабля, с физикой Minestom: падает к полу корабля, лежит на нём в полёте и под любым наклоном. Игроки видят его
 * на корабле: сервер показывает его тем, кому отправлен корабль (YardStreamer), мод рисует матрицей корабля.
 * <ul>
 *   <li>выброс (Q, сломанный сундук при полном инвентаре) над палубой → в верфь ({@link DropPlacer});</li>
 *   <li>предмет мира упал на корабль (бросили с земли) → в верфь;</li>
 *   <li>предмет верфи упал за борт → в мир, там же, куда смотрит корабль;</li>
 *   <li>подбор: игрок в мире, предмет в верфи — сравниваем в системе верфи.</li>
 * </ul>
 */
final class DeckEntities implements DropPlacer {

    private final ShipsModule mod;

    DeckEntities(ShipsModule mod) { this.mod = mod; }

    /** Поза корабля (стоит моделью / летит) и его плот: мир ↔ верфь. Как ShipInteract.cellWorld и мод (ShipGravity). */
    record Frame(Ship s, float[] q, Vec pivot, double seatH) {
        static Frame of(Ship s) {
            if (s.yard == null || s.yardBox == null) return null;
            if (s.flight != null && s.yard == null) {
                double[] p = s.flight.pose();
                return new Frame(s, Rot.quat(p[3], p[4], p[5], s.flight.blockTurn()), new Vec(p[0], p[1], p[2]), s.flight.seatHeight());
            }
            if (s.state == Ship.State.MODEL || s.state == Ship.State.FLYING) return new Frame(s, Rot.quat(s.myaw, s.mpitch, s.mroll, s.mturn), new Vec(s.mx, s.my, s.mz), s.mseatH);
            return null;
        }

        Vec origin() { return new Vec(s.yard[1] + 0.5, s.yard[2] + seatH, s.yard[3] + 0.5); }

        float[] inv() { return new float[]{-q[0], -q[1], -q[2], q[3]}; }

        Vec toYard(Point w) {
            double[] r = Rot.rotate(inv(), w.x() - pivot.x(), w.y() - pivot.y(), w.z() - pivot.z());
            return origin().add(r[0], r[1], r[2]);
        }

        Vec toWorld(Point y) {
            Vec o = origin();
            double[] r = Rot.rotate(q, y.x() - o.x(), y.y() - o.y(), y.z() - o.z());
            return pivot.add(r[0], r[1], r[2]);
        }

        Vec dirToYard(Vec d) { double[] r = Rot.rotate(inv(), d.x(), d.y(), d.z()); return new Vec(r[0], r[1], r[2]); }

        Vec dirToWorld(Vec d) { double[] r = Rot.rotate(q, d.x(), d.y(), d.z()); return new Vec(r[0], r[1], r[2]); }

        /** Точка верфи в коробке корабля (с запасом m клеток). */
        boolean inBox(Point y, double m) {
            int[] b = s.yardBox;
            double dx = y.x() - s.yard[1], dy = y.y() - s.yard[2], dz = y.z() - s.yard[3];
            return dx >= b[0] - m && dx <= b[3] + 1 + m && dy >= b[1] - m && dy <= b[4] + 1 + m && dz >= b[2] - m && dz <= b[5] + 1 + m;
        }

        /** Над палубой: в коробке корабля и под точкой (до 4 блоков вниз по «низу» корабля) есть его блок. */
        boolean onDeck(Instance inst, Point y) {
            if (!inBox(y, 0.5)) return false;
            int x = y.blockX(), z = y.blockZ(), by = y.blockY();
            if (!inst.isChunkLoaded(x >> 4, z >> 4)) return false;               // плот ещё не подгружен — не палуба
            for (int i = 0; i <= 4; i++) if (!inst.getBlock(x, by - i, z).isAir()) return true;
            return false;
        }

        /** Радиус корабля в мире (для быстрого отсева). */
        double radius() {
            int[] b = s.yardBox;
            double ex = Math.max(-b[0], b[3]) + 1, ey = Math.max(-b[1], b[4]) + 1, ez = Math.max(-b[2], b[5]) + 1;
            return Math.sqrt(ex * ex + ey * ey + ez * ez) + 2;
        }
    }

    /** Сколько живёт предмет на палубе (в мире — 5 минут, как у игры). */
    static final Duration DECK_LIFE = Duration.ofMinutes(15);

    void enable() {
        mod.services().register(DropPlacer.class, this);
        mod.scheduler().repeat(this::tick, 1, 1);
        mod.events().addListener(EntitySpawnEvent.class, e -> {
            Entity en = e.getEntity();
            if (en instanceof Player) return;
            if (Shipyard.isYard(en.getPosition().x())) { mod.scheduler().run(() -> mod.yardStream.showEntity(en)); return; }   // новый в верфи — зрителям корабля
            if (en instanceof ItemEntity it) mod.scheduler().run(() -> adopt(it));   // выброшен в мире (модуль items) — не на палубу ли
        });
    }

    /** Корабли с позой и плотом (на этот тик). */
    private List<Frame> frames() {
        List<Frame> frames = new ArrayList<>();
        for (Ship s : mod.ships.values()) {
            Frame f = Frame.of(s);
            if (f != null) frames.add(f);
        }
        return frames;
    }

    // ───────────────────────── выброс ─────────────────────────

    @Override
    public boolean place(Player p, ItemEntity item, Instance inst, Pos at, Vec v) {
        return place(item, inst, at, v, p);
    }

    @Override
    public boolean place(ItemEntity item, Instance inst, Pos at, Vec v) {
        return place(item, inst, at, v, null);
    }

    private boolean place(ItemEntity item, Instance inst, Pos at, Vec v, Player p) {
        if (inst == null) return false;
        for (Frame f : frames()) {
            if (mod.instanceOf(f.s) != inst || at.distanceSquared(f.pivot) > sq(f.radius())) continue;
            Vec y = f.toYard(at);
            if (p != null) {                                                     // глаза игрока — над ногами по «верху» корабля (в верфи это +Y)
                Vec feet = f.toYard(p.getPosition());
                if (f.onDeck(inst, feet)) y = feet.add(0, p.getEyeHeight() - 0.3, 0);
            }
            if (!f.onDeck(inst, y)) continue;
            Shipyard.ensureLoaded(mod, inst, f.s);
            Vec yv;
            if (p != null) {                                                     // как у игры: по взгляду + чуть вверх — вверх КОРАБЛЯ
                Vec look = f.dirToYard(p.getPosition().direction());
                var r = java.util.concurrent.ThreadLocalRandom.current();
                double a = r.nextDouble() * Math.PI * 2, s = 0.02 * r.nextDouble();
                yv = new Vec(look.x() * 0.3 + Math.cos(a) * s, look.y() * 0.3 + 0.1 + (r.nextDouble() - r.nextDouble()) * 0.1,
                        look.z() * 0.3 + Math.sin(a) * s).mul(20);
            } else yv = f.dirToYard(v);
            item.scheduleRemove(DECK_LIFE);
            item.setVelocity(yv);                                                // скорость — уже в пакете появления
            item.setInstance(inst, new Pos(y.x(), y.y(), y.z())).thenRun(() -> mod.yardStream.showEntity(item));   // зрителям — сразу
            return true;
        }
        return false;
    }

    private static double sq(double d) { return d * d; }

    /**
     * Предмет только что выбросил игрок (модуль items кладёт его в мир: глаза по вертикали МИРА, скорость по взгляду в
     * мире). Игрок стоит на палубе — переносим в верфь: из рук по «верху» корабля, бросок как у игры в системе корабля.
     * Иначе на корабле на боку предмет рождался в полу и проваливался сквозь блоки.
     */
    private void adopt(ItemEntity it) {
        if (it.isRemoved() || it.getInstance() == null || it.getAliveTicks() > 3) return;
        Instance inst = it.getInstance();
        Player best = null;
        double bd = 2.0 * 2.0;
        for (Player p : inst.getPlayers()) {
            double d = p.getPosition().add(0, p.getEyeHeight(), 0).distanceSquared(it.getPosition());
            if (d < bd) { bd = d; best = p; }
        }
        if (best == null) return;
        for (Frame f : frames()) {
            if (mod.instanceOf(f.s) != inst || best.getPosition().distanceSquared(f.pivot) > sq(f.radius())) continue;
            Vec feet = f.toYard(best.getPosition());
            if (!f.onDeck(inst, feet)) continue;
            Shipyard.ensureLoaded(mod, inst, f.s);
            Vec y = feet.add(0, best.getEyeHeight() - 0.3, 0);
            Vec look = f.dirToYard(best.getPosition().direction());
            var r = java.util.concurrent.ThreadLocalRandom.current();
            double a = r.nextDouble() * Math.PI * 2, s = 0.02 * r.nextDouble();
            Vec yv = new Vec(look.x() * 0.3 + Math.cos(a) * s, look.y() * 0.3 + 0.1 + (r.nextDouble() - r.nextDouble()) * 0.1,
                    look.z() * 0.3 + Math.sin(a) * s).mul(20);
            ItemEntity n = new ItemEntity(it.getItemStack());
            n.setPickupDelay(leftDelay(it));
            n.setMergeable(it.isMergeable());
            n.scheduleRemove(DECK_LIFE);
            n.setVelocity(yv);
            it.remove();
            n.setInstance(inst, new Pos(y.x(), y.y(), y.z())).thenRun(() -> mod.yardStream.showEntity(n));
            return;
        }
    }

    // ───────────────────────── каждый тик ─────────────────────────

    private int ticks;

    private void tick() {
        List<Frame> fs = frames();
        if (fs.isEmpty()) return;
        ticks++;
        for (Frame f : fs) {
            Instance inst = mod.instanceOf(f.s);
            if (inst == null) continue;
            fallOff(f, inst);
            if (ticks % 2 == 0) pickup(f, inst);
            if (ticks % 5 == 0) landOn(f, inst);
        }
    }

    /** Предмет верфи этого корабля ушёл за борт (вне коробки + 2) — в мир, туда, где он на самом деле. */
    private void fallOff(Frame f, Instance inst) {
        Vec c = new Vec(f.s.yard[1], f.s.yard[2], f.s.yard[3]);
        List<ItemEntity> out = new ArrayList<>();
        inst.getEntityTracker().nearbyEntities(c, f.radius() + 16, EntityTracker.Target.ITEMS, it -> {
            if (Math.floorDiv((int) Math.floor(it.getPosition().z()), Shipyard.SIZE) != f.s.yard[0]) return;   // чужой плот
            if (!f.inBox(it.getPosition(), 2)) out.add(it);
        });
        for (ItemEntity it : out) {
            if (it.isRemoved()) continue;
            Vec w = f.toWorld(it.getPosition()), wv = f.dirToWorld(it.getVelocity());
            ItemEntity n = new ItemEntity(it.getItemStack());
            n.setPickupDelay(leftDelay(it));
            n.scheduleRemove(Duration.ofMinutes(5));
            it.remove();
            Vec at = free(inst, w);                                              // упал у стоящего на земле корабля — не в грунт
            n.setInstance(inst, new Pos(at.x(), at.y(), at.z())).thenRun(() -> n.setVelocity(wv));
        }
    }

    /** Сколько ещё нельзя подобрать (у нового предмета отсчёт начнётся заново). */
    private static Duration leftDelay(ItemEntity it) {
        return Duration.ofMillis(Math.max(0, it.getPickupDelay() - it.getAliveTicks() * 50));
    }

    /** Точка в мире, а если там блок (грунт под кораблём) — первая свободная выше (до 8 блоков). */
    private static Vec free(Instance inst, Vec w) {
        if (!inst.isChunkLoaded(w)) return w;
        for (int i = 0; i <= 8; i++) {
            Vec p = w.add(0, i, 0);
            if (!inst.getBlock(p).isSolid()) return i == 0 ? w : new Vec(w.x(), Math.floor(p.y()) + 0.05, w.z());
        }
        return w;
    }

    /** Предмет мира над палубой (бросили с земли, упал сверху) — в верфь. */
    private void landOn(Frame f, Instance inst) {
        List<ItemEntity> in = new ArrayList<>();
        inst.getEntityTracker().nearbyEntities(f.pivot, f.radius(), EntityTracker.Target.ITEMS, it -> {
            if (it.getAliveTicks() > 5 && !Shipyard.isYard(it.getPosition().x()) && f.onDeck(inst, f.toYard(it.getPosition()))) in.add(it);
        });
        for (ItemEntity it : in) {
            if (it.isRemoved()) continue;
            Vec y = f.toYard(it.getPosition()), yv = f.dirToYard(it.getVelocity());
            for (int i = 0; i < 3 && inst.getBlock(y).isSolid(); i++) y = new Vec(y.x(), Math.floor(y.y()) + 1.05, y.z());   // не в блок пола
            ItemEntity n = new ItemEntity(it.getItemStack());
            n.setPickupDelay(leftDelay(it));
            n.scheduleRemove(DECK_LIFE);
            it.remove();
            Shipyard.ensureLoaded(mod, inst, f.s);
            n.setInstance(inst, new Pos(y.x(), y.y(), y.z())).thenRun(() -> n.setVelocity(yv));
        }
    }

    /** Подбор: коробка игрока (в системе верфи он стоит прямо), раздутая как у игры, против предметов верфи. */
    private void pickup(Frame f, Instance inst) {
        double r2 = sq(f.radius() + 2);
        for (Player p : inst.getPlayers()) {
            if (p.isDead() || p.getGameMode() == net.minestom.server.entity.GameMode.SPECTATOR) continue;
            if (p.getPosition().distanceSquared(f.pivot) > r2) continue;
            Vec y = f.toYard(p.getPosition());
            if (!f.inBox(y, 3)) continue;
            List<ItemEntity> got = new ArrayList<>();
            inst.getEntityTracker().nearbyEntities(y, 2.5, EntityTracker.Target.ITEMS, it -> {
                if (!it.isPickable() || it.isRemoved()) return;
                var q = it.getPosition();
                if (Math.abs(q.x() - y.x()) <= 1.3 + 0.125 && Math.abs(q.z() - y.z()) <= 1.3 + 0.125
                        && q.y() + 0.25 >= y.y() - 0.5 && q.y() <= y.y() + 1.8 + 0.5) got.add(it);
            });
            for (ItemEntity it : got) {
                PickupItemEvent ev = new PickupItemEvent(p, it);
                EventDispatcher.callCancellable(ev, () -> {
                    // как у игры: «подобран» — мод рисует, как предмет летит в игрока (с палубы, не из верфи), и играет звук
                    var pk = new net.minestom.server.network.packet.server.play.CollectItemPacket(it.getEntityId(), p.getEntityId(), it.getItemStack().amount());
                    java.util.Set<Player> to = new java.util.HashSet<>(it.getViewers());
                    to.add(p);
                    for (Player v : to) v.sendPacket(pk);
                    it.remove();
                });
            }
        }
    }
}
