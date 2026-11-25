package ink.hoka.odyssey.ships;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.display.ItemDisplayMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Автовыравнивание — только «на планетах» (карты auto-level.worlds и участки auto-level.regions), в космосе его нет.
 * Стоящий моделью корабль, на котором никого нет, медленно встаёт ровно (курс тот же, наклон и крен → 0), вокруг
 * места пилота; мешают блоки мира — останавливается. В полёте — по U у пилота (это в моде, сервер говорит, можно ли).
 */
final class ShipLeveler {

    private final ShipsModule mod;
    /** Внешние клетки стоящих кораблей (для проверки «не задевает ли мир»): владелец → клетки и сколько их было. */
    private final Map<String, List<int[]>> hulls = new HashMap<>();
    private final Map<String, Integer> hullSize = new HashMap<>();
    /** Корабли, которые выравнивались и ещё не сохранены. */
    private final Set<String> dirty = new HashSet<>();
    /** Упёрлись в блоки мира — не пробовать, пока корабль не сдвинут (иначе дрожит у препятствия). */
    private final Set<String> stuck = new HashSet<>();
    private long ticks;

    ShipLeveler(ShipsModule mod) { this.mod = mod; }

    /** Есть ли здесь автовыравнивание: карта из списка или участок из regions. */
    boolean allowed(String map, double x, double y, double z) {
        if (map == null) return false;
        for (String w : mod.config().getStringList("auto-level.worlds")) if (w.equalsIgnoreCase(map)) return true;
        Object r = mod.config().get("auto-level.regions");
        if (r instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                if (!map.equalsIgnoreCase(String.valueOf(m.get("map")))) continue;
                double[] a = vec(m.get("min")), b = vec(m.get("max"));
                if (a == null || b == null) continue;
                if (x >= Math.min(a[0], b[0]) && x <= Math.max(a[0], b[0]) && y >= Math.min(a[1], b[1]) && y <= Math.max(a[1], b[1])
                        && z >= Math.min(a[2], b[2]) && z <= Math.max(a[2], b[2])) return true;
            }
        }
        return false;
    }

    private static double[] vec(Object o) {
        if (!(o instanceof List<?> l) || l.size() < 3) return null;
        try {
            return new double[]{((Number) l.get(0)).doubleValue(), ((Number) l.get(1)).doubleValue(), ((Number) l.get(2)).doubleValue()};
        } catch (ClassCastException e) {
            return null;
        }
    }

    /** Каждый тик сервера (работает раз в 2 тика). */
    void tick() {
        if (++ticks % 2 != 0) return;
        double speed = mod.config().getDouble("auto-level.parked-speed", 5);
        for (Ship s : mod.ships.values()) {
            if (s.state != Ship.State.MODEL || s.flight != null || s.mparts == null) continue;
            Entity a = mod.parked.get(s.owner);
            Instance inst = mod.instanceOf(s);
            if (a == null || inst == null) continue;
            if (Math.abs(s.mpitch) < 0.05 && Math.abs(Rot.wrap(s.mroll)) < 0.05) {
                s.mpitch = 0; s.mroll = 0;
                if (dirty.contains(s.owner)) mod.logger().info("корабль {}: выровнен", s.ownerName);
                save(s);
                continue;
            }
            if (!allowed(s.map, s.mx, s.my, s.mz)) continue;
            if (onBoard(s, inst)) { state(s, "на борту игрок — ждёт"); continue; }
            float[] q = Rot.quat(s.myaw, s.mpitch, s.mroll, 0);
            float[] level = Rot.level(q);
            double angle = Rot.angle(q, level);
            double step = Math.toRadians(speed) * 0.1;                          // 2 тика = 0.1 с
            float[] nq = angle <= step ? level : Rot.slerp(q, level, step / angle);
            double[] e = Rot.euler(nq);
            if (hits(s, inst, e)) { stuck.add(s.owner); state(s, "упёрся в блоки мира — стоит как есть"); continue; }
            stuck.remove(s.owner);
            state(s, "выравнивается");
            s.myaw = (float) e[0];
            s.mpitch = (float) e[1];
            s.mroll = (float) e[2];
            float[] lr = Rot.quat(s.myaw, s.mpitch, s.mroll, 0);
            a.editEntityMeta(ItemDisplayMeta.class, m -> {
                m.setTransformationInterpolationStartDelta(0);
                m.setTransformationInterpolationDuration(2);
                m.setLeftRotation(lr);
            });
            dirty.add(s.owner);
        }
    }

    /** Что сейчас с выравниванием корабля — в лог при смене. */
    private final Map<String, String> states = new HashMap<>();

    private void state(Ship s, String st) {
        if (st.equals(states.put(s.owner, st))) return;
        mod.logger().info("автовыравнивание {}: {}", s.ownerName, st);
    }

    /** Выравнивание закончено — сохранить (не каждые 2 тика). */
    private void save(Ship s) {
        if (dirty.remove(s.owner)) mod.saveShips();
    }

    /** На корабле кто-то есть (в коробке его блоков, с запасом) — не трогаем. */
    private boolean onBoard(Ship s, Instance inst) {
        float[] q = Rot.quat(s.myaw, s.mpitch, s.mroll, s.mturn);
        float[] inv = {-q[0], -q[1], -q[2], q[3]};
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int[] p : s.mparts) for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], p[i]); hi[i] = Math.max(hi[i], p[i]); }
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (p.getInstance() != inst) continue;
            Pos pos = p.getPosition();
            double[] l = Rot.rotate(inv, pos.x() - s.mx, pos.y() - s.my, pos.z() - s.mz);
            double cy = l[1] + s.mseatH;                                        // в клетках по высоте
            if (l[0] > lo[0] - 1.5 && l[0] < hi[0] + 1.5 && cy > lo[1] - 1.5 && cy < hi[1] + 2.5 && l[2] > lo[2] - 1.5 && l[2] < hi[2] + 1.5) return true;
        }
        return false;
    }

    /** С таким поворотом корабль задевает блоки мира (по внешним клеткам). */
    private boolean hits(Ship s, Instance inst, double[] e) {
        List<int[]> hull = hull(s);
        float[] q = Rot.quat(e[0], e[1], e[2], s.mturn);
        for (int[] p : hull) {
            double[] r = Rot.rotate(q, p[0], p[1] - s.mseatH + 0.5, p[2]);
            int bx = (int) Math.floor(s.mx + r[0]), by = (int) Math.floor(s.my + r[1]), bz = (int) Math.floor(s.mz + r[2]);
            if (!mod.server().isCubeLoaded(inst, bx, by, bz)) return true;
            Block b = inst.getBlock(bx, by, bz);
            if (!b.isAir() && b.isSolid()) return true;
        }
        return false;
    }

    private List<int[]> hull(Ship s) {
        List<int[]> h = hulls.get(s.owner);
        if (h != null && hullSize.getOrDefault(s.owner, -1) == s.mparts.size()) return h;
        Set<Long> cells = new HashSet<>();
        for (int[] p : s.mparts) cells.add(key(p[0], p[1], p[2]));
        h = new ArrayList<>();
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] p : s.mparts) {
            for (int[] o : d) if (!cells.contains(key(p[0] + o[0], p[1] + o[1], p[2] + o[2]))) { h.add(p); break; }
        }
        hulls.put(s.owner, h);
        hullSize.put(s.owner, s.mparts.size());
        return h;
    }

    private static long key(int x, int y, int z) { return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF); }

    /** Выключение модуля: сохранить начатое. */
    void disable() {
        if (!dirty.isEmpty()) { dirty.clear(); mod.saveShips(); }
    }
}
