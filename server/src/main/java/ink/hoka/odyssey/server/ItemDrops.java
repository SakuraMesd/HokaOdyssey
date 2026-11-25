package ink.hoka.odyssey.server;

import ink.hoka.odyssey.api.DropPlacer;
import ink.hoka.odyssey.api.Services;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.item.ItemDropEvent;
import net.minestom.server.event.item.PickupItemEvent;
import net.minestom.server.instance.Instance;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Выброс и подбор предметов как в ванили (Minestom сам их не делает: Q просто убирал вещь из инвентаря, подбор —
 * удалял предмет, ничего не положив). Куда класть — спрашиваем {@link DropPlacer} (корабли: палуба → верфь).
 */
final class ItemDrops {

    private ItemDrops() {}

    static void register(GlobalEventHandler events, Supplier<Services> services) {
        events.addListener(ItemDropEvent.class, e -> {
            Player p = e.getPlayer();
            if (p.getInstance() == null || e.getItemStack().isAir()) return;
            Pos at = p.getPosition().add(0, p.getEyeHeight() - 0.3, 0);
            Vec look = p.getPosition().direction();
            ThreadLocalRandom r = ThreadLocalRandom.current();
            double a = r.nextDouble() * Math.PI * 2, s = 0.02 * r.nextDouble();
            // как у игры (за тик): взгляд × 0.3, чуть вверх, разброс; у Minestom скорость — блоки в секунду
            Vec v = new Vec(look.x() * 0.3 + Math.cos(a) * s, look.y() * 0.3 + 0.1 + (r.nextDouble() - r.nextDouble()) * 0.1,
                    look.z() * 0.3 + Math.sin(a) * s).mul(20);
            ItemEntity item = new ItemEntity(e.getItemStack());
            item.setPickupDelay(Duration.ofSeconds(2));
            spawn(p, item, p.getInstance(), at, v, services);
        });
        events.addListener(PickupItemEvent.class, e -> {
            if (!(e.getLivingEntity() instanceof Player p)) return;
            if (!p.getInventory().addItemStack(e.getItemStack())) e.setCancelled(true);   // некуда — лежит дальше
        });
    }

    /** Положить предмет: сервису (палуба корабля) или в мир. */
    static void spawn(Player dropper, ItemEntity item, Instance inst, Pos at, Vec v, Supplier<Services> services) {
        DropPlacer placer = null;
        try { placer = services.get().get(DropPlacer.class).orElse(null); } catch (Throwable ignored) { }
        if (placer != null) {
            try { if (dropper != null ? placer.place(dropper, item, inst, at, v) : placer.place(item, inst, at, v)) return; }
            catch (Throwable t) { org.slf4j.LoggerFactory.getLogger("odyssey").warn("DropPlacer: {}", t.toString()); }
        }
        item.scheduleRemove(Duration.ofMinutes(5));
        item.setInstance(inst, at).thenRun(() -> item.setVelocity(v));
    }
}
