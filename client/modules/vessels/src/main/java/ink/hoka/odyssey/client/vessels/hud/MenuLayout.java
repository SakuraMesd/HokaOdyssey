package ink.hoka.odyssey.client.vessels.hud;

/**
 * Раскладка окна корабля (G) — пиксели интерфейса от левого верха окна. Окно сервера — сундук на 5 рядов (у игрока без
 * мода — обычный сундук): слоты окна 0–26 — груз, 27 — ярлык, 28–35 — улучшения, 36 — ярлык, 37–40 — топливо,
 * 41–44 — ярлык и сводка; 45–71 — инвентарь игрока, 72–80 — быстрая панель. Здесь слоты стоят по группам:
 * <pre>
 *  КОРАБЛЬ #3 · CORABLL                    владелец …
 *  ─────────────────────────────────────────────────
 *  ГРУЗ                         УЛУЧШЕНИЯ        ▮ 150
 *  ▢▢▢▢▢▢▢▢▢                    ▢▢▢▢             ▮ /256
 *  ▢▢▢▢▢▢▢▢▢                    ▢▢▢▢             ▮
 *  ▢▢▢▢▢▢▢▢▢                    ТОПЛИВО          ▮─ 64
 *                               ▢▢▢▢             ▮
 *  ─────────────────────────────────────────────────
 *  ИНВЕНТАРЬ                    СВОДКА
 *  ▢▢▢▢▢▢▢▢▢                    варп        64 топл.
 *  ▢▢▢▢▢▢▢▢▢                    …
 *  ▢▢▢▢▢▢▢▢▢
 *  ▢▢▢▢▢▢▢▢▢
 * </pre>
 * Ярлыки сундука (27, 36, 41–44) в этом окне не видны и не берутся — они далеко за окном.
 */
public final class MenuLayout {

    private MenuLayout() {}

    public static final int W = 304, H = 208, CELL = 18, PAD = 8;
    public static final int GRID_Y = 35, RIGHT_X = 182, FUEL_LABEL_Y = 75, FUEL_Y = 85, DIV_Y = 109, INV_LABEL_Y = 114, INV_Y = 124, HOT_Y = 182;
    /** Шкала бака: x, y, ширина, высота. */
    public static final int GAUGE_X = 264, GAUGE_Y = 35, GAUGE_W = 7, GAUGE_H = 68;
    public static final int STORAGE = 27, UP0 = 28, UPGRADES = 8, FUEL0 = 37, FUEL = 4, PLAYER0 = 45, HOTBAR0 = 72, SLOTS = 81;
    /** Где прячутся ненужные слоты. */
    public static final int AWAY = -10000;

    /** Что за слот окна (по номеру в меню). */
    public enum Kind { CARGO, UPGRADE, FUEL, INVENTORY, HOTBAR, HIDDEN }

    public static Kind kind(int i) {
        if (i >= 0 && i < STORAGE) return Kind.CARGO;
        if (i >= UP0 && i < UP0 + UPGRADES) return Kind.UPGRADE;
        if (i >= FUEL0 && i < FUEL0 + FUEL) return Kind.FUEL;
        if (i >= PLAYER0 && i < PLAYER0 + 27) return Kind.INVENTORY;
        if (i >= HOTBAR0 && i < HOTBAR0 + 9) return Kind.HOTBAR;
        return Kind.HIDDEN;
    }

    /** Левый верх клетки слота (18×18) в окне; спрятанный — {AWAY, AWAY}. */
    public static int[] cell(int i) {
        return switch (kind(i)) {
            case CARGO -> new int[]{PAD + (i % 9) * CELL, GRID_Y + (i / 9) * CELL};
            case UPGRADE -> new int[]{RIGHT_X + ((i - UP0) % 4) * CELL, GRID_Y + ((i - UP0) / 4) * CELL};
            case FUEL -> new int[]{RIGHT_X + (i - FUEL0) * CELL, FUEL_Y};
            case INVENTORY -> new int[]{PAD + ((i - PLAYER0) % 9) * CELL, INV_Y + ((i - PLAYER0) / 9) * CELL};
            case HOTBAR -> new int[]{PAD + (i - HOTBAR0) * CELL, HOT_Y};
            case HIDDEN -> new int[]{AWAY, AWAY};
        };
    }

    /** Левый верх предмета слота (как Slot.x/y игры) — на пиксель внутри клетки. */
    public static int[] slot(int i) {
        int[] c = cell(i);
        return kind(i) == Kind.HIDDEN ? c : new int[]{c[0] + 1, c[1] + 1};
    }

    /** Окно по центру экрана: левый верх {x, y} (как у игры: (экран − окно) / 2). */
    public static int[] origin(int screenW, int screenH) { return new int[]{(screenW - W) / 2, (screenH - H) / 2}; }
}
