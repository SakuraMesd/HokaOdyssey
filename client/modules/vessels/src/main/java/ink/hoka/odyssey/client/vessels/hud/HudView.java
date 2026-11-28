package ink.hoka.odyssey.client.vessels.hud;

/**
 * Что показать в HUD пилота в этом кадре (собирает Pilot; в тестах — превью). Координаты — пиксели интерфейса.
 *
 * @param rungs    лесенка тангажа: по 5 чисел на ступень — угол (°, + вверх), x, y середины на экране, dx, dy —
 *                 направление ступени «вправо» на экране (единичное)
 * @param throttle ход: 1 вперёд, 0 нет, −1 назад
 * @param gunReady 0..1 — доля перезарядки орудия (1 — готово)
 * @param warp     состояние варпа: {@link #WARP_READY} …
 * @param hit      0..1 — яркость маркера попадания (0 — нет)
 * @param hitKind  что за попадание: 1 — по цели, 2 — убийство, 3 — по кораблю, 4 — корабль разрушен
 * @param zones    целость зон корпуса 0…1 (нос, корпус, левый борт, правый борт, двигатели), triZone — зона
 *                 каждого треугольника модели (для силуэта); hurt — 0..1 красный край экрана (по нам попали)
 * @param pvp      корабль в PvP-зоне — янтарная метка «PvP-ЗОНА» у компаса (vessels-5)
 * @param broken   разбитые зоны с эффектом: {@link #GUN} — орудие, {@link #WARP} — варп-привод, борта, двигатели
 */
public record HudView(
        float noseX, float noseY, boolean noseOn, float aimX, float aimY, boolean aimOn,
        float heading, float[] rungs,
        double speed, double cruise, double boostMax, int throttle, boolean boost,
        double alt, double vspeed,
        boolean fuelKnown, int fuel, int capacity, int warpCost, boolean fuelEmpty,
        float gunReady, float gunLeftSec,
        int warp, float warpProgress, int warpLeftSec,
        String msg, boolean msgWarn, float msgAlpha,
        float hintsAlpha, float hit, int hitKind,
        String kindName, float[] tris,
        boolean hullKnown, double hp, double hpMax, float[] zones, int[] triZone, float hurt,
        boolean pvp, int broken) {

    /** Биты broken (как у сервера). */
    public static final int GUN = 1, WARP = 2, LEFT = 4, RIGHT = 8, ENGINE = 16;

    public static final int WARP_READY = 0, WARP_CHARGING = 1, WARP_AIMING = 2, WARP_RELOAD = 3, WARP_NO_FUEL = 4;

    /** Состояние варпа по сводке сервера (зарядка — по своему таймеру мода). */
    public static int warpState(float charge, int phase, long reloadLeft, boolean fuelKnown, int fuel, int cost) {
        if (charge >= 0) return WARP_CHARGING;
        if (phase == 2) return WARP_AIMING;
        if (reloadLeft > 0) return WARP_RELOAD;
        if (fuelKnown && fuel < cost) return WARP_NO_FUEL;
        return WARP_READY;
    }
}
