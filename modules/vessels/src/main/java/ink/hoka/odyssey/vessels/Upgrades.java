package ink.hoka.odyssey.vessels;

import java.util.Map;

/**
 * Улучшения корабля (слоты улучшений меню): что дают и как складываются. Одинаковые складываются: прибавки — сумма
 * (2 «Двигателя+» — +20%), скидки — тоже сумма, но множитель не меньше min-factor (8 «Бак+» по −10% — не бесплатно).
 * Одно улучшение — один слот. Числа — раздел upgrades: конфига.
 */
final class Upgrades {

    /** Вид улучшения — он же хвост id предмета (vessel_engine …) и аргумент /hovessel upgrade. */
    enum Type {
        ENGINE("engine", "Двигатель+"), TANK("tank", "Бак+"), GUN("gun", "Орудие+"), WARP("warp", "Варп-катушка");

        final String id, title;

        Type(String id, String title) { this.id = id; this.title = title; }

        static Type of(String id) {
            for (Type t : values()) if (t.id.equals(id)) return t;
            return null;
        }
    }

    /** Сколько даёт одна штука (доли) и нижний предел множителей-скидок. */
    record Config(double speed, double warpCost, double gunReload, double gunDamage, double warpReload, double minFactor) {
        static final Config DEFAULT = new Config(0.10, 0.10, 0.10, 0.10, 0.15, 0.1);

        static Config of(Object section) {
            if (!(section instanceof Map<?, ?> m)) return DEFAULT;
            return new Config(VesselKind.num(sub(m, "engine"), "speed", 0.10), VesselKind.num(sub(m, "tank"), "warp-cost", 0.10),
                    VesselKind.num(sub(m, "gun"), "reload", 0.10), VesselKind.num(sub(m, "gun"), "damage", 0.10),
                    VesselKind.num(sub(m, "warp"), "warp-reload", 0.15), VesselKind.num(m, "min-factor", 0.1));
        }

        private static Map<?, ?> sub(Map<?, ?> m, String k) { return m.get(k) instanceof Map<?, ?> s ? s : Map.of(); }
    }

    /**
     * Итог — множители: speed — ход и форсаж; warpCost — цена варпа; gunReload — задержка между выстрелами;
     * gunDamage — урон; warpReload — перезарядка варпа.
     */
    record Stats(double speed, double warpCost, double gunReload, double gunDamage, double warpReload) {
        static final Stats NONE = new Stats(1, 1, 1, 1, 1);
    }

    private Upgrades() {}

    /** Множители по числу улучшений каждого вида (counts[Type.ordinal()]). */
    static Stats stats(int[] counts, Config c) {
        int e = counts[Type.ENGINE.ordinal()], t = counts[Type.TANK.ordinal()], g = counts[Type.GUN.ordinal()], w = counts[Type.WARP.ordinal()];
        return new Stats(1 + c.speed() * e, down(c.warpCost() * t, c), down(c.gunReload() * g, c), 1 + c.gunDamage() * g,
                down(c.warpReload() * w, c));
    }

    private static double down(double cut, Config c) { return Math.max(c.minFactor(), 1 - cut); }
}
