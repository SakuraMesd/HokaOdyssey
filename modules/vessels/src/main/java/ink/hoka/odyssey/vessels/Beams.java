package ink.hoka.odyssey.vessels;

import net.kyori.adventure.sound.Sound;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.tag.Tag;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * «Лучи» корабля над планетой (корабль висит на «подушке», planet.clearance блоков над землёй): выход — игрока
 * плавно опускает вниз, посадка издалека (до planet.beam-reach) — поднимает в кабину; при разрушении корабля экипаж
 * выбрасывается. Двигает игрока его же мод/клиент (скорость от сервера каждый тик — плавно, без рывков телепорта),
 * по лучу — частицы. Урона от падения нет: метка {@link #FALL_SAFE} (время, мс) — модуль survival не считает падение,
 * пока она не истекла.
 */
final class Beams {

    /** До какого времени (System.currentTimeMillis) у игрока нет урона от падения — читает модуль survival. */
    static final Tag<Long> FALL_SAFE = Tag.Long("hoka_fall_safe_until");

    private enum Mode { DOWN, UP, DRIFT }

    private static final class Beam {
        final Player player;
        final Vessel vessel;
        final Mode mode;
        final long until;
        long ticks;

        Beam(Player player, Vessel vessel, Mode mode, long until) {
            this.player = player;
            this.vessel = vessel;
            this.mode = mode;
            this.until = until;
        }
    }

    private final VesselsModule mod;
    private final Map<UUID, Beam> beams = new ConcurrentHashMap<>();

    Beams(VesselsModule mod) { this.mod = mod; }

    double downSpeed() { return Math.max(1, mod.config().getDouble("planet.beam-down-speed", 4)); }

    double upSpeed() { return Math.max(1, mod.config().getDouble("planet.beam-up-speed", 7)); }

    boolean active(Player p) { return beams.containsKey(p.getUuid()); }

    /** Нет урона от падения ещё ms миллисекунд (не укорачивает уже данное). */
    static void safe(Player p, long ms) {
        long until = System.currentTimeMillis() + ms;
        Long was = p.getTag(FALL_SAFE);
        if (was == null || was < until) p.setTag(FALL_SAFE, until);
    }

    /** Выход на планете: игрока — под корабль и вниз лучом. */
    void down(Player p, Vessel v, VesselKind k) {
        Instance inst = p.getInstance();
        if (inst == null || k == null) return;
        Pos at = below(inst, v, k, k.seat[0], k.seat[2]);
        if (at == null) at = p.getPosition();
        p.teleport(at.withView((float) v.rot.yawDeg(), 30f));
        start(p, v, Mode.DOWN, 40 * 20);
        mod.client.message(p, "Луч опускает вас на землю", false);
        inst.playSound(Sound.sound(SoundEvent.BLOCK_BEACON_ACTIVATE, Sound.Source.NEUTRAL, 0.7f, 1.6f), at.x(), at.y(), at.z());
    }

    /** Посадка издалека на планете: луч поднимает к кабине, там — посадка. */
    void up(Player p, Vessel v) {
        start(p, v, Mode.UP, 10 * 20);
        mod.client.message(p, "Луч поднимает вас в кабину…", false);
        Pos pp = p.getPosition();
        p.getInstance().playSound(Sound.sound(SoundEvent.BLOCK_BEACON_POWER_SELECT, Sound.Source.NEUTRAL, 0.8f, 1.4f), pp.x(), pp.y(), pp.z());
    }

    /** Корабль разрушен: на планете — под корабль и вниз лучом; в космосе — оттолкнуть от корабля. */
    void eject(Player p, Vessel v, VesselKind k) {
        Instance inst = p.getInstance();
        if (inst == null) return;
        safe(p, 6000);
        if (mod.isPlanet(inst) && k != null) {
            double[] l = VesselMath.toLocal(p.getPosition().x(), p.getPosition().y(), p.getPosition().z(), v.pos(), v.rot);
            Pos at = below(inst, v, k, l[0], l[2]);
            if (at != null) p.teleport(at.withView(p.getPosition().yaw(), p.getPosition().pitch()));
            start(p, v, Mode.DOWN, 40 * 20);
            return;
        }
        Pos pp = p.getPosition();
        double dx = pp.x() - v.x, dy = pp.y() - v.y, dz = pp.z() - v.z, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.5) { dx = 0; dy = 1; dz = 0; len = 1; }
        p.setVelocity(new Vec(dx / len * 10, dy / len * 10 + 3, dz / len * 10));
        start(p, v, Mode.DRIFT, 6 * 20);
    }

    /**
     * Место под корпусом в точке (lx, lz) системы корабля: на 1.5 блока ниже коробки, свободно 2 блока по высоте;
     * занято — null.
     */
    private Pos below(Instance inst, Vessel v, VesselKind k, double lx, double lz) {
        double[] w = VesselMath.toWorld(lx, -k.half[1] - 1.5, lz, v.pos(), v.rot);
        double lowest = v.y - k.radius() - 1.5;
        double y = Math.min(w[1], lowest + 1);
        int bx = (int) Math.floor(w[0]), by = (int) Math.floor(y), bz = (int) Math.floor(w[2]);
        if (!mod.server().isCubeLoaded(inst, bx, by, bz) || !mod.server().isCubeLoaded(inst, bx, by + 1, bz)) return null;
        if (solid(inst, bx, by, bz) || solid(inst, bx, by + 1, bz)) return null;
        return new Pos(w[0], y, w[2]);
    }

    private static boolean solid(Instance inst, int x, int y, int z) {
        Block b = inst.getBlock(x, y, z, Block.Getter.Condition.TYPE);
        return !b.isAir() && b.isSolid();
    }

    private void start(Player p, Vessel v, Mode m, long ticks) {
        beams.put(p.getUuid(), new Beam(p, v, m, mod.ticks() + ticks));
        safe(p, 3000);
    }

    /** Каждый тик. */
    void tick() {
        for (Beam b : beams.values()) {
            Player p = b.player;
            b.ticks++;
            if (!p.isOnline() || p.getInstance() == null || p.isDead()) { beams.remove(p.getUuid()); continue; }
            Instance inst = p.getInstance();
            Pos pp = p.getPosition();
            safe(p, 3000);
            switch (b.mode) {
                case DOWN -> {
                    if ((b.ticks > 3 && p.isOnGround()) || mod.ticks() > b.until) { finish(b); continue; }
                    p.setVelocity(new Vec(0, -downSpeed(), 0));
                    if (b.ticks % 2 == 0) column(inst, pp, b.vessel);
                }
                case UP -> {
                    Vessel v = b.vessel;
                    VesselKind k = mod.kinds().get(v.kind);
                    if (k == null || mod.vessel(v.id) != v || v.pilot != null || v.carrier == null || v.carrier.getInstance() != inst
                            || mod.pilotOf(p) != null) { finish(b); continue; }
                    double[] seat = mod.seatWorld(v, k);
                    double dx = seat[0] - pp.x(), dy = seat[1] - (pp.y() + p.getEyeHeight()), dz = seat[2] - pp.z();
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double box = VesselMath.distanceToBox(pp.x(), pp.y() + p.getEyeHeight(), pp.z(), v.pos(), v.rot, k.half);
                    if (dist < 2.5 || box < 0.8 || mod.ticks() > b.until) {
                        beams.remove(p.getUuid());
                        p.setVelocity(Vec.ZERO);
                        if (dist <= mod.beamReach() + k.radius() + 4) mod.boardNow(p, v);
                        continue;
                    }
                    double sp = Math.min(upSpeed(), dist * 4);
                    p.setVelocity(new Vec(dx / dist * sp, dy / dist * sp + 1.6, dz / dist * sp));   // +1.6 — против силы тяжести клиента
                    if (b.ticks % 2 == 0) column(inst, pp, v);
                }
                case DRIFT -> {
                    if (mod.ticks() > b.until) { finish(b); continue; }
                }
            }
        }
    }

    /** Частицы луча: от низа корабля до игрока. */
    private void column(Instance inst, Pos pp, Vessel v) {
        VesselKind k = mod.kinds().get(v.kind);
        double top = k != null ? v.y - k.half[1] : pp.y() + 4;
        double from = Math.min(top, pp.y() + 12);
        for (double y = pp.y(); y <= from; y += 1.2)
            inst.sendGroupedPacket(new ParticlePacket(Particle.END_ROD, pp.x(), y, pp.z(), 0.25f, 0.3f, 0.25f, 0.01f, 1));
        inst.sendGroupedPacket(new ParticlePacket(Particle.GLOW, pp.x(), pp.y() + 1, pp.z(), 0.4f, 0.6f, 0.4f, 0f, 3));
    }

    private void finish(Beam b) {
        beams.remove(b.player.getUuid());
        safe(b.player, 2000);                                                   // приземлился — ещё 2 с без урона
    }

    /** Модуль выключается: лучи — прочь (метка урона от падения истечёт сама). */
    void clear() {
        for (Beam b : beams.values()) safe(b.player, 5000);
        beams.clear();
    }
}
