package ink.hoka.odyssey.vessels;

import java.util.Map;

/**
 * Эффекты разбитых зон корпуса (vessels-5), раздел hull.effects конфига. Зона «разбита», когда её целость ≤ threshold
 * (по умолчанию 0 — совсем). Каждое можно выключить (enabled: false):
 * <ul>
 *   <li>двигатели — ход × engine.speed (0.7; старое hull.engine-broken-speed — то же);</li>
 *   <li>нос — орудие: перезарядка × nose.reload (1.5), разброс болтов + nose.spread градусов (1.5);</li>
 *   <li>левый / правый борт — поворот (курс, тангаж, крен) × sides.turn (0.7) за каждый разбитый борт;</li>
 *   <li>корпус — варп недоступен («Варп-привод повреждён»).</li>
 * </ul>
 * Со множителями улучшений складывается умножением ({@link #combine}). Ремонт уменьшает урон зон — эффекты уходят сами.
 */
final class HullEffects {

    /** Биты «что сломано» (номер бита — номер зоны {@link Zones}). */
    static final int GUN = 1 << Zones.NOSE, LEFT = 1 << Zones.LEFT, RIGHT = 1 << Zones.RIGHT, WARP = 1 << Zones.BODY, ENGINE = 1 << Zones.ENGINE;

    record Config(double threshold, boolean engine, double engineSpeed, boolean nose, double noseReload, double noseSpread,
                  boolean sides, double sideTurn, boolean body) {
        static final Config DEFAULT = new Config(0, true, 0.7, true, 1.5, 1.5, true, 0.7, true);

        /** Из раздела hull: конфига (effects и старое engine-broken-speed). */
        static Config of(Object hull) {
            if (!(hull instanceof Map<?, ?> h)) return DEFAULT;
            Map<?, ?> e = h.get("effects") instanceof Map<?, ?> m ? m : Map.of();
            double legacy = VesselKind.num(h, "engine-broken-speed", 0.7);
            Map<?, ?> en = sub(e, "engine"), no = sub(e, "nose"), si = sub(e, "sides"), bo = sub(e, "body");
            double es = VesselKind.num(en, "speed", legacy);
            return new Config(Math.clamp(VesselKind.num(e, "threshold", 0), 0, 1),
                    on(en, true) && es < 1, Math.clamp(es, 0.05, 1),
                    on(no, true), Math.max(1, VesselKind.num(no, "reload", 1.5)), Math.clamp(VesselKind.num(no, "spread", 1.5), 0, 30),
                    on(si, true), Math.clamp(VesselKind.num(si, "turn", 0.7), 0.05, 1),
                    on(bo, true));
        }

        private static Map<?, ?> sub(Map<?, ?> m, String k) { return m.get(k) instanceof Map<?, ?> s ? s : Map.of(); }

        private static boolean on(Map<?, ?> m, boolean def) { return m.get("enabled") instanceof Boolean b ? b : def; }
    }

    /**
     * Итог: speed — ход и форсаж, gunReload — задержка выстрела, spread — разброс болтов (°), turn — скорость поворота,
     * warpBroken — варп нельзя, broken — биты разбитых зон с включённым эффектом.
     */
    record Effects(double speed, double gunReload, double spread, double turn, boolean warpBroken, int broken) {
        static final Effects NONE = new Effects(1, 1, 0, 1, false, 0);
    }

    private HullEffects() {}

    /** Зона разбита: есть у вида (доля клеток > 0) и целость ≤ порога. */
    static boolean broken(double health, double share, double threshold) { return share > 0 && health <= threshold + 1e-9; }

    /** Эффекты по целости зон (health[5]) и долям зон у вида (share[5]). */
    static Effects of(double[] health, double[] share, Config c) {
        int mask = 0;
        for (int i = 0; i < Zones.COUNT; i++) if (broken(health[i], share[i], c.threshold())) mask |= 1 << i;
        int b = 0;
        double speed = 1, reload = 1, spread = 0, turn = 1;
        boolean warp = false;
        if (c.engine() && (mask & ENGINE) != 0) { speed = c.engineSpeed(); b |= ENGINE; }
        if (c.nose() && (mask & GUN) != 0) { reload = c.noseReload(); spread = c.noseSpread(); b |= GUN; }
        if (c.sides() && (mask & LEFT) != 0) { turn *= c.sideTurn(); b |= LEFT; }
        if (c.sides() && (mask & RIGHT) != 0) { turn *= c.sideTurn(); b |= RIGHT; }
        if (c.body() && (mask & WARP) != 0) { warp = true; b |= WARP; }
        return b == 0 ? Effects.NONE : new Effects(speed, reload, spread, turn, warp, b);
    }

    /** Улучшения × эффекты зон: ход и перезарядка орудия перемножаются, остальное — от улучшений. */
    static Upgrades.Stats combine(Upgrades.Stats up, Effects e) {
        if (e.broken() == 0) return up;
        return new Upgrades.Stats(up.speed() * e.speed(), up.warpCost(), up.gunReload() * e.gunReload(), up.gunDamage(), up.warpReload());
    }

    /** Сообщение пилоту, когда зона только что разбилась (что стало хуже). */
    static String message(int bit, Effects e) {
        return switch (bit) {
            case ENGINE -> "Двигатели разбиты — ход " + Math.round(e.speed() * 100) + "%";
            case GUN -> "Нос разбит — орудие медленнее, разброс больше";
            case LEFT -> "Левый борт разбит — поворот " + Math.round(e.turn() * 100) + "%";
            case RIGHT -> "Правый борт разбит — поворот " + Math.round(e.turn() * 100) + "%";
            case WARP -> "Корпус разбит — варп-привод повреждён";
            default -> null;
        };
    }
}
