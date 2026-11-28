package ink.hoka.odyssey.vessels;

import net.minestom.server.instance.Instance;

/**
 * Связь с PvP-зонами модуля survival (сервис PvpZones; softdepends). Только здесь упоминаются его классы — если survival
 * не стоит, класса нет: всё ловится и значит «survival нет».
 */
final class ZoneLink {

    private final VesselsModule mod;

    ZoneLink(VesselsModule mod) { this.mod = mod; }

    /** Сервис зон или null. */
    private Object svc() {
        try {
            return mod.services().get(ink.hoka.odyssey.survival.PvpZones.class).orElse(null);
        } catch (Throwable noModule) {
            return null;
        }
    }

    boolean present() { return svc() != null; }

    /** В PvP-зоне ли точка; survival нет — false. */
    boolean in(Instance inst, double x, double y, double z) {
        Object s = svc();
        if (s == null) return false;
        try { return ((ink.hoka.odyssey.survival.PvpZones) s).isPvp(inst, x, y, z); } catch (Throwable e) { return false; }
    }

    /** Имя зоны или null. */
    String zone(Instance inst, double x, double y, double z) {
        Object s = svc();
        if (s == null) return null;
        try { return ((ink.hoka.odyssey.survival.PvpZones) s).zoneAt(inst, x, y, z); } catch (Throwable e) { return null; }
    }

    /** Режим боя игроков survival (all/zones/off) или null — survival нет. */
    String playerMode() {
        Object s = svc();
        if (s == null) return null;
        try { return ((ink.hoka.odyssey.survival.PvpZones) s).playerMode(); } catch (Throwable e) { return null; }
    }

    PvpRule.Mode mode() { return PvpRule.mode(mod.config().getString("combat.mode", "zones")); }
}
