package ink.hoka.odyssey.server.objects;

import net.minestom.server.MinecraftServer;
import net.minestom.server.color.Color;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Выделение /hoobj на игрока (в памяти: сбрасывается при выходе и смене карты) и рамки частицами — только этому
 * игроку: pos1 зелёный, pos2 красный, рёбра белые; предпросмотр (preview) — жёлтая рамка и голубой якорь.
 * Показ — каждые 10 тиков 60 с после изменения.
 */
public final class Selections {

    private Selections() {}

    /** Выделение: точки могут быть null. */
    public record Sel(Instance inst, Point pos1, Point pos2) {
        public boolean complete() { return pos1 != null && pos2 != null; }
        public int minX() { return Math.min(pos1.blockX(), pos2.blockX()); }
        public int minY() { return Math.min(pos1.blockY(), pos2.blockY()); }
        public int minZ() { return Math.min(pos1.blockZ(), pos2.blockZ()); }
        public int maxX() { return Math.max(pos1.blockX(), pos2.blockX()); }
        public int maxY() { return Math.max(pos1.blockY(), pos2.blockY()); }
        public int maxZ() { return Math.max(pos1.blockZ(), pos2.blockZ()); }
        public long volume() { return (long) (maxX() - minX() + 1) * (maxY() - minY() + 1) * (maxZ() - minZ() + 1); }
    }

    /** Рамка предпросмотра: блоки min…max включительно и якорь. */
    record Preview(Instance inst, int x0, int y0, int z0, int x1, int y1, int z1, int ax, int ay, int az) {}

    private static final Map<UUID, Sel> SEL = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SHOW_UNTIL = new ConcurrentHashMap<>();
    private static final Map<UUID, Preview> PREVIEW = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PREVIEW_UNTIL = new ConcurrentHashMap<>();
    static final long SHOW_MS = 60_000;

    private static final Particle P1 = Particle.DUST.withColor(new Color(40, 230, 60)).withScale(1.5f);
    private static final Particle P2 = Particle.DUST.withColor(new Color(240, 40, 40)).withScale(1.5f);
    private static final Particle EDGE = Particle.DUST.withColor(new Color(255, 255, 255)).withScale(0.8f);
    private static final Particle PREV = Particle.DUST.withColor(new Color(255, 210, 40)).withScale(0.8f);
    private static final Particle ANCHOR = Particle.DUST.withColor(new Color(40, 200, 255)).withScale(1.6f);

    public static void register(GlobalEventHandler events) {
        events.addListener(PlayerDisconnectEvent.class, e -> forget(e.getPlayer().getUuid()));
        events.addListener(PlayerSpawnEvent.class, e -> {
            Sel s = SEL.get(e.getPlayer().getUuid());
            if (s != null && s.inst() != e.getSpawnInstance()) forget(e.getPlayer().getUuid());
        });
        MinecraftServer.getSchedulerManager().buildTask(Selections::tick).repeat(TaskSchedule.tick(10)).schedule();
    }

    private static void forget(UUID u) {
        SEL.remove(u);
        SHOW_UNTIL.remove(u);
        PREVIEW.remove(u);
        PREVIEW_UNTIL.remove(u);
    }

    public static Sel get(Player p) { return SEL.get(p.getUuid()); }

    public static Sel set(Player p, int which, Point pos) {
        Point b = new Vec(pos.blockX(), pos.blockY(), pos.blockZ());
        Sel old = SEL.get(p.getUuid());
        Instance inst = p.getInstance();
        if (old == null || old.inst() != inst) old = new Sel(inst, null, null);
        Sel s = which == 1 ? new Sel(inst, b, old.pos2()) : new Sel(inst, old.pos1(), b);
        SEL.put(p.getUuid(), s);
        show(p);
        return s;
    }

    public static void clear(Player p) {
        SEL.remove(p.getUuid());
        SHOW_UNTIL.remove(p.getUuid());
    }

    public static void show(Player p) { SHOW_UNTIL.put(p.getUuid(), System.currentTimeMillis() + SHOW_MS); }

    static void preview(Player p, Preview pv) {
        PREVIEW.put(p.getUuid(), pv);
        PREVIEW_UNTIL.put(p.getUuid(), System.currentTimeMillis() + SHOW_MS);
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            UUID u = p.getUuid();
            Long until = SHOW_UNTIL.get(u);
            Sel s = SEL.get(u);
            if (until != null && until > now && s != null && s.inst() == p.getInstance()) drawSel(p, s);
            else if (until != null && until <= now) SHOW_UNTIL.remove(u);
            Long pu = PREVIEW_UNTIL.get(u);
            Preview pv = PREVIEW.get(u);
            if (pu != null && pu > now && pv != null && pv.inst() == p.getInstance()) {
                box(p, pv.x0(), pv.y0(), pv.z0(), pv.x1() + 1, pv.y1() + 1, pv.z1() + 1, PREV);
                dot(p, pv.ax() + 0.5, pv.ay() + 0.5, pv.az() + 0.5, ANCHOR);
                dot(p, pv.ax() + 0.5, pv.ay() + 0.1, pv.az() + 0.5, ANCHOR);
            } else if (pu != null && pu <= now) { PREVIEW.remove(u); PREVIEW_UNTIL.remove(u); }
        }
    }

    private static void drawSel(Player p, Sel s) {
        if (s.complete()) box(p, s.minX(), s.minY(), s.minZ(), s.maxX() + 1, s.maxY() + 1, s.maxZ() + 1, EDGE);
        if (s.pos1() != null) cube(p, s.pos1(), P1);
        if (s.pos2() != null) cube(p, s.pos2(), P2);
    }

    /** Углы блока точкой цвета. */
    private static void cube(Player p, Point b, Particle c) {
        box(p, b.blockX(), b.blockY(), b.blockZ(), b.blockX() + 1, b.blockY() + 1, b.blockZ() + 1, c);
    }

    /** 12 рёбер рамки (координаты граней), не больше ~50 точек на ребро. */
    private static void box(Player p, double x0, double y0, double z0, double x1, double y1, double z1, Particle c) {
        double len = Math.max(x1 - x0, Math.max(y1 - y0, z1 - z0));
        double step = Math.max(0.5, len / 50);
        double[] xs = {x0, x1}, ys = {y0, y1}, zs = {z0, z1};
        for (double y : ys) for (double z : zs) line(p, x0, y, z, x1, y, z, step, c);
        for (double x : xs) for (double z : zs) line(p, x, y0, z, x, y1, z, step, c);
        for (double x : xs) for (double y : ys) line(p, x, y, z0, x, y, z1, step, c);
    }

    private static void line(Player p, double ax, double ay, double az, double bx, double by, double bz, double step, Particle c) {
        double len = Math.max(Math.abs(bx - ax), Math.max(Math.abs(by - ay), Math.abs(bz - az)));
        int n = Math.max(1, (int) Math.ceil(len / step));
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            dot(p, ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, c);
        }
    }

    private static void dot(Player p, double x, double y, double z, Particle c) {
        p.sendPacket(new ParticlePacket(c, true, false, new Vec(x, y, z), Vec.ZERO, 0f, 1));
    }
}
