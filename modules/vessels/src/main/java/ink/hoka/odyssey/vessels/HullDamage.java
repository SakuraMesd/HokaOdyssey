package ink.hoka.odyssey.vessels;

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.sound.SoundEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Прочность корабля (hull.hp вида, текущая — в vessels.json): урон от болтов чужих кораблей и от удара о блоки на
 * скорости, повреждения по зонам ({@link Zones}), ремонт (ремкомплект, команда), разрушение при 0 — взрыв (частицы,
 * звук, блоки целы), пилот и стоящие выбрасываются (без урона от падения первые секунды), груз выпадает предметами,
 * корабль удаляется из мира и vessels.json.
 */
final class HullDamage {

    private final VesselsModule mod;

    HullDamage(VesselsModule mod) { this.mod = mod; }

    double maxHp(Vessel v) {
        VesselKind k = mod.kinds().get(v.kind);
        return k != null ? k.hp : 200;
    }

    double repairKit() { return Math.max(1, mod.config().getDouble("hull.repair-kit", 50)); }

    /** Целость зон (0…1) — для HUD и меню. */
    double[] zoneHealth(Vessel v) {
        VesselKind k = mod.kinds().get(v.kind);
        double[] out = new double[Zones.COUNT];
        for (int i = 0; i < out.length; i++) out[i] = k == null ? 1 : k.zoneHealth(i, v.zoneDmg[i]);
        return out;
    }

    // ───────────────────────── урон ─────────────────────────

    /**
     * Урон кораблю: amount, точка попадания (мир; null — зона by) или зона явно. by — кто стрелял (маркер «разрушен»).
     * Вернуть true — корабль разрушен.
     */
    boolean damage(Vessel v, double amount, double[] at, int zone, Player by, String cause) {
        VesselKind k = mod.kinds().get(v.kind);
        if (k == null || !(amount > 0) || mod.vessel(v.id) != v) return false;
        if (zone < 0 && at != null) {
            double[] l = VesselMath.toLocal(at[0], at[1], at[2], v.pos(), v.rot);
            zone = k.zoneNear(l[0], l[1], l[2]);
        }
        int wasBroken = mod.effects(v).broken();
        double left = v.damage(amount, zone, k.hp);
        mod.markDirty();
        Instance inst = v.carrier != null ? v.carrier.getInstance() : mod.instanceOf(v);
        if (inst != null && at != null) sparks(inst, at, amount);
        mod.client.hull(v, true, zone);
        mod.client.state(v);                                                    // ход мог измениться (двигатели)
        if (v.menu != null) v.menu.info();
        if (left <= 0) {
            destroy(v, by, cause);
            return true;
        }
        HullEffects.Effects ef = mod.effects(v);
        int fresh = ef.broken() & ~wasBroken;                                   // что разбилось этим ударом
        if ((fresh & HullEffects.WARP) != 0 && v.warpPhase != WarpDrive.IDLE) mod.warp.cancel(v, "Варп-привод повреждён");
        if (fresh != 0 && v.pilot != null) {
            for (int b = 0; b < Zones.COUNT; b++) {
                String m = (fresh & (1 << b)) != 0 ? HullEffects.message(1 << b, ef) : null;
                if (m != null) { mod.client.message(v.pilot.player, m, true); break; }   // строка HUD одна — самое свежее
            }
        } else if (v.pilot != null && left <= k.hp * 0.25 && left + amount > k.hp * 0.25)
            mod.client.message(v.pilot.player, "Корпус: осталось " + Math.round(left) + " из " + Math.round(k.hp), true);
        return false;
    }

    /** Искры и звук попадания по корпусу — всем рядом (и без мода). */
    private static void sparks(Instance inst, double[] at, double amount) {
        int n = (int) Math.min(30, 8 + amount * 1.5);
        inst.sendGroupedPacket(new ParticlePacket(Particle.ELECTRIC_SPARK, at[0], at[1], at[2], 0.35f, 0.35f, 0.35f, 0.4f, n));
        inst.sendGroupedPacket(new ParticlePacket(Particle.SMOKE, at[0], at[1], at[2], 0.2f, 0.2f, 0.2f, 0.02f, 4));
        inst.playSound(Sound.sound(SoundEvent.ENTITY_IRON_GOLEM_DAMAGE, Sound.Source.NEUTRAL, 1f, 1.3f), at[0], at[1], at[2]);
    }

    // ───────────────────────── удар о блоки ─────────────────────────

    /**
     * C2S IMPACT: мод пилота врезался в блоки на скорости speed (бл/с) по направлению dir (мир). Сервер верит не
     * больше, чем видел сам (скорость по последним позам + запас), и только если у корпуса по ходу правда есть блоки.
     */
    void impact(Player p, double speed, double dx, double dy, double dz) {
        Piloting pl = mod.pilotOf(p);
        if (pl == null || !mod.config().getBoolean("hull.impact.enabled", true)) return;
        Vessel v = pl.vessel;
        VesselKind k = mod.kinds().get(v.kind);
        Instance inst = v.carrier != null ? v.carrier.getInstance() : null;
        if (k == null || inst == null || !Double.isFinite(speed) || speed <= 0) return;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(len) || len < 1e-6) return;
        double[] d = {dx / len, dy / len, dz / len};
        long now = mod.ticks();
        if (now < v.impactReady) return;
        double seen = Math.max(pl.peakSpeed, Math.sqrt(pl.vx * pl.vx + pl.vy * pl.vy + pl.vz * pl.vz) * 20);
        double sp = Math.min(speed, seen * 1.25 + 3);
        double min = mod.config().getDouble("hull.impact.min-speed", 12);
        if (sp < min) return;
        double[] hit = touching(inst, k, v, d);
        if (hit == null) return;                                                // по ходу пусто — не верим
        v.impactReady = now + Math.max(1, Math.round(mod.config().getDouble("hull.impact.cooldown", 0.5) * 20));
        double dmg = (sp - min) * mod.config().getDouble("hull.impact.damage-per-speed", 2);
        double[] ld = v.rot.conjugate().rotate(d[0], d[1], d[2]);
        int zone = k.zoneToward(ld[0], ld[1], ld[2]);
        inst.sendGroupedPacket(new ParticlePacket(Particle.BLOCK.withBlock(Block.STONE), hit[0], hit[1], hit[2], 0.6f, 0.4f, 0.6f, 0.1f, 24));
        inst.playSound(Sound.sound(SoundEvent.ENTITY_GENERIC_SMALL_FALL, Sound.Source.NEUTRAL, 1.2f, 0.6f), hit[0], hit[1], hit[2]);
        if (mod.config().getBoolean("debug", false)) mod.logger().info("корабль {}: удар о блоки {} бл/с → урон {}", v.id, Math.round(sp), Math.round(dmg));
        if (dmg > 0) damage(v, dmg, hit, zone, null, "удар о блоки");
    }

    /** Есть ли твёрдый блок у корпуса по ходу (клетки корпуса, сдвинутые на 1.5 блока по d); точка удара или null. */
    private double[] touching(Instance inst, VesselKind k, Vessel v, double[] d) {
        double[] ld = v.rot.conjugate().rotate(d[0], d[1], d[2]);
        for (int[] c : k.hull) {
            double lx = c[0] + 0.5, ly = c[1] + 0.5, lz = c[2] + 0.5;
            if (lx * ld[0] + ly * ld[1] + lz * ld[2] < 0) continue;            // только передняя по ходу половина
            for (double s : new double[]{0.8, 1.6}) {
                double[] w = VesselMath.toWorld(lx + ld[0] * s, ly + ld[1] * s, lz + ld[2] * s, v.pos(), v.rot);
                int bx = (int) Math.floor(w[0]), by = (int) Math.floor(w[1]), bz = (int) Math.floor(w[2]);
                if (!mod.server().isCubeLoaded(inst, bx, by, bz)) continue;
                Block b = inst.getBlock(bx, by, bz, Block.Getter.Condition.TYPE);
                if (!b.isAir() && b.isSolid()) return w;
            }
        }
        return null;
    }

    // ───────────────────────── ремонт ─────────────────────────

    /** Починить на amount. Вернуть сколько прибавилось (0 — корпус цел). */
    double repair(Vessel v, double amount) {
        double add = v.repair(amount, maxHp(v));
        if (add <= 0) return 0;
        mod.markDirty();
        mod.save();
        mod.client.hull(v, false, -1);
        mod.client.state(v);
        if (v.menu != null) v.menu.info();
        Instance inst = v.carrier != null ? v.carrier.getInstance() : null;
        if (inst != null) {
            inst.sendGroupedPacket(new ParticlePacket(Particle.HAPPY_VILLAGER, v.x, v.y, v.z, 2f, 1f, 2f, 0f, 30));
            inst.playSound(Sound.sound(SoundEvent.BLOCK_ANVIL_USE, Sound.Source.NEUTRAL, 0.8f, 1.3f), v.x, v.y, v.z);
        }
        return add;
    }

    /**
     * Ремкомплектом игрока: из руки (hand = true) или из инвентаря, иначе из груза корабля. Сообщение — игроку.
     * Вернуть true — починили.
     */
    boolean repairWithKit(Player p, Vessel v, boolean hand) {
        double max = maxHp(v);
        if (v.hp(max) >= max - 1e-9) { mod.client.message(p, "Корпус цел: " + Math.round(max) + " / " + Math.round(max), false); return false; }
        boolean took = false;
        if (hand) {
            ItemStack h = p.getItemInMainHand();
            if (VesselItems.isRepair(h)) { p.setItemInMainHand(h.amount() <= 1 ? ItemStack.AIR : h.withAmount(h.amount() - 1)); took = true; }
        }
        if (!took) {
            var inv = p.getInventory();
            for (int i = 0; i < inv.getSize() && !took; i++) {
                ItemStack it = inv.getItemStack(i);
                if (VesselItems.isRepair(it)) { inv.setItemStack(i, it.amount() <= 1 ? ItemStack.AIR : it.withAmount(it.amount() - 1)); took = true; }
            }
        }
        if (!took) {
            ItemStack[] st = v.cargo.storage;
            for (int i = 0; i < st.length && !took; i++) {
                if (VesselItems.isRepair(st[i])) { st[i] = st[i].amount() <= 1 ? ItemStack.AIR : st[i].withAmount(st[i].amount() - 1); took = true; }
            }
            if (took) mod.cargoChanged(v);
        }
        if (!took) { mod.client.message(p, "Нужен ремкомплект (в инвентаре или в грузе корабля)", true); return false; }
        double add = repair(v, repairKit());
        mod.client.message(p, "Ремонт: +" + Math.round(add) + " — прочность " + Math.round(v.hp(max)) + " / " + Math.round(max), false);
        return true;
    }

    // ───────────────────────── разрушение ─────────────────────────

    /** Прочность 0: взрыв, всех с корабля — наружу (без урона от падения), груз — предметами на месте, корабль — прочь. */
    void destroy(Vessel v, Player by, String cause) {
        Instance inst = v.carrier != null ? v.carrier.getInstance() : mod.instanceOf(v);
        VesselKind k = mod.kinds().get(v.kind);
        List<Player> crew = new ArrayList<>(mod.riders(v));
        Player pilot = v.pilot != null ? v.pilot.player : null;
        if (pilot != null) {
            crew.add(0, pilot);
            mod.exit(pilot, false);
        }
        if (inst != null) {
            double r = k != null ? k.radius() : 4;
            inst.sendGroupedPacket(new ParticlePacket(Particle.EXPLOSION_EMITTER, v.x, v.y, v.z, (float) r * 0.3f, 0.5f, (float) r * 0.3f, 0f, 3));
            inst.sendGroupedPacket(new ParticlePacket(Particle.EXPLOSION, v.x, v.y, v.z, (float) r * 0.5f, 1f, (float) r * 0.5f, 0f, 16));
            inst.sendGroupedPacket(new ParticlePacket(Particle.FLAME, v.x, v.y, v.z, (float) r * 0.4f, 1f, (float) r * 0.4f, 0.15f, 80));
            inst.sendGroupedPacket(new ParticlePacket(Particle.LARGE_SMOKE, v.x, v.y, v.z, (float) r * 0.4f, 1f, (float) r * 0.4f, 0.05f, 60));
            inst.playSound(Sound.sound(SoundEvent.ENTITY_GENERIC_EXPLODE, Sound.Source.HOSTILE, 4f, 0.7f), v.x, v.y, v.z);
            inst.playSound(Sound.sound(SoundEvent.ENTITY_WITHER_BREAK_BLOCK, Sound.Source.HOSTILE, 1.5f, 0.6f), v.x, v.y, v.z);
        }
        // груз — предметами на месте, разлетаются
        List<ItemStack> items = v.cargo.all();
        v.cargo.clear();
        for (ItemStack it : items) scatter(v, inst, it);
        if (!items.isEmpty()) mod.logger().info("корабль {} разрушен: выпало предметов {}", v.id, items.size());
        // экипаж — наружу
        for (Player p : crew) {
            if (!p.isOnline() || inst == null || p.getInstance() != inst) continue;
            mod.beams.eject(p, v, k);
            p.sendMessage(Component.text("Корабль #" + v.id + " разрушен" + (cause != null ? " (" + cause + ")" : ""), NamedTextColor.RED));
        }
        if (by != null) mod.client.hit(by, 4);
        Player owner = v.owner != null ? net.minestom.server.MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(java.util.UUID.fromString(v.owner)) : null;
        if (owner != null && !crew.contains(owner))
            owner.sendMessage(Component.text("Ваш корабль #" + v.id + " разрушен" + (cause != null ? " (" + cause + ")" : ""), NamedTextColor.RED));
        mod.logger().info("корабль {} ({}) разрушен{}{} на {} {} {} {}", v.id, v.kind, cause != null ? ": " + cause : "",
                by != null ? " — стрелял " + by.getUsername() : "", v.map, Math.round(v.x), Math.round(v.y), Math.round(v.z));
        mod.remove(v);
    }

    /** Предмет — в мир у корабля, с разлётом; мира/столбца нет — в лог (не пропадает молча). */
    private void scatter(Vessel v, Instance inst, ItemStack it) {
        if (inst == null || !mod.server().isCubeLoaded(inst, (int) Math.floor(v.x), (int) Math.floor(v.y), (int) Math.floor(v.z))) {
            mod.logger().warn("корабль {}: предмет некуда выронить (мира/куба нет) — {}", v.id, Cargo.item(it));
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        ItemEntity e = new ItemEntity(it);
        e.setPickupDelay(Duration.ofMillis(1000));
        e.setVelocity(new Vec((r.nextDouble() - 0.5) * 8, 2 + r.nextDouble() * 4, (r.nextDouble() - 0.5) * 8));
        e.setInstance(inst, new Pos(v.x + (r.nextDouble() - 0.5) * 2, v.y, v.z + (r.nextDouble() - 0.5) * 2));
    }
}
