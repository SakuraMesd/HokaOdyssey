package ink.hoka.odyssey.server.tall;

import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.entity.EntityTickEvent;
import net.minestom.server.event.entity.EntitySpawnEvent;
import net.minestom.server.event.instance.RemoveEntityFromInstanceEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.tag.Tag;

/**
 * Сущности на ленивой кубовой карте (CUBIC-CHUNKS.md §4.8, этап 4 шаг 10). Куб не в памяти для физики Minestom —
 * воздух, поэтому движущуюся сущность у такого куба замораживаем: скорость 0, noGravity, тег с прежним noGravity.
 * Когда кубы под ней и впереди загрузятся — возвращаем как было. Игроков не трогаем (их держит окно клиента).
 * <p>Появление в незагруженном кубе НЕ отменяем (AddEntityToInstanceEvent): Entity.setInstance при отмене
 * возвращает null, а вызывающие (якоря кораблей — join, выпавшие предметы, палуба — thenRun) упали бы на нём; да и
 * позиции появления в этом событии ещё нет. Вместо отмены — заморозка сразу после появления (EntitySpawnEvent) и
 * запрос загрузки куба.</p>
 */
public final class CubeEntities {

    private CubeEntities() {}

    /** Сущность заморожена нами; значение — её noGravity до заморозки. */
    static final Tag<Boolean> FROZEN = Tag.Boolean("hoka:cube_frozen");
    /** Приоритет загрузки куба под сущностью (как у модулей). */
    private static final int PRIO = Cubes.API_PRIO;
    /** Отладка: сейчас заморожено / заморожено всего / появилось в незагруженном кубе. */
    static volatile long frozenTotal, spawnedUnloaded;
    private static final java.util.Set<Entity> FROZEN_NOW = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void register() {
        var ev = MinecraftServer.getGlobalEventHandler();
        // позиция появления известна только после setInstance (в AddEntityToInstanceEvent — ещё старая)
        ev.addListener(EntitySpawnEvent.class, e -> {
            Entity en = e.getEntity();
            Instance inst = e.getSpawnInstance();
            if (en instanceof Player || !CubeStore.lazy(inst)) return;
            if (blocked(inst, en.getPosition(), en.getVelocity(), !en.hasNoGravity())) { spawnedUnloaded++; freeze(en); }
        });
        ev.addListener(RemoveEntityFromInstanceEvent.class, e -> FROZEN_NOW.remove(e.getEntity()));
        ev.addListener(EntityTickEvent.class, e -> {
            Entity en = e.getEntity();
            if (en instanceof Player) return;
            Instance inst = en.getInstance();
            if (!CubeStore.lazy(inst)) {
                if (en.hasTag(FROZEN)) unfreeze(en);                       // ушла на обычную карту
                return;
            }
            Pos at = en.getPosition();
            if (en.hasTag(FROZEN)) {
                Boolean g = en.getTag(FROZEN);
                if (!blocked(inst, at, Vec.ZERO, g != null && !g)) unfreeze(en);
                return;
            }
            if (!en.hasVelocity() && en.hasNoGravity()) return;            // стоит и не падает — не мешаем
            if (blocked(inst, at, en.getVelocity(), !en.hasNoGravity())) freeze(en);
        });
    }

    /**
     * Сущности нельзя стоять/двигаться: куб под ней не в памяти, или (с гравитацией) куб на блок ниже, или куб на 2 тика
     * вперёд по скорости. Загрузку таких кубов запрашивает.
     */
    private static boolean blocked(Instance inst, Pos at, Vec vel, boolean gravity) {
        double k = 2.0 / ServerFlag.SERVER_TICKS_PER_SECOND;
        boolean b = unloaded(inst, at.x(), at.y(), at.z());
        if (gravity) b |= unloaded(inst, at.x(), at.y() - 1, at.z());
        if (!vel.isZero()) b |= unloaded(inst, at.x() + vel.x() * k, at.y() + vel.y() * k, at.z() + vel.z() * k);
        return b;
    }

    private static boolean unloaded(Instance inst, double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        if (!(inst.getChunk(bx >> 4, bz >> 4) instanceof TallColumn tc)) return false;   // столбца нет — Minestom туда не двигает
        int i = (by >> 4) - CubeRegionLoader.MIN_SECTION;
        if (tc.ready(i)) return false;
        tc.request(i, PRIO);
        return true;
    }

    private static void freeze(Entity en) {
        if (en.hasTag(FROZEN)) return;
        en.setTag(FROZEN, en.hasNoGravity());
        en.setNoGravity(true);
        en.setVelocity(Vec.ZERO);
        FROZEN_NOW.add(en);
        frozenTotal++;
    }

    private static void unfreeze(Entity en) {
        Boolean g = en.getTag(FROZEN);
        en.removeTag(FROZEN);
        FROZEN_NOW.remove(en);
        en.setNoGravity(g != null && g);
    }

    static String stats() {
        FROZEN_NOW.removeIf(Entity::isRemoved);
        return "сущностей заморожено сейчас " + FROZEN_NOW.size() + ", всего " + frozenTotal + ", появилось в незагруженном кубе " + spawnedUnloaded;
    }
}
