package ink.hoka.odyssey.client.vessels.hud;

/**
 * Раскладка окна корабля (G) — пиксели интерфейса от левого верха окна. Окно сервера — сундук на 5 рядов (у игрока без
 * мода — обычный сундук): слоты окна 0–26 — груз, 27 — ярлык, 28–35 — улучшения, 36 — ярлык, 37–40 — топливо,
 * 41 — кнопка «Мастерская», 42 — «Корпус / ремонт», 43–44 — сводка; 45–71 — инвентарь игрока, 72–80 — быстрая
 * панель. Здесь слоты стоят по группам:
 * <pre>
 *  КОРАБЛЬ #3 · CORABLL           [▣] МАСТЕРСКАЯ  [▣] РЕМОНТ
 *  владелец …
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
 * Ярлыки сундука (27, 36, 43–44) в этом окне не видны и не берутся — они далеко за окном; кнопки 41, 42 — в
 * заголовке (vessels-4).
 */
public final class MenuLayout {

    private MenuLayout() {}

    public static final int W = 304, H = 212, CELL = 18, PAD = 8, HEAD_Y = 24;
    public static final int GRID_Y = 39, RIGHT_X = 182, FUEL_LABEL_Y = 79, FUEL_Y = 89, DIV_Y = 113, INV_LABEL_Y = 118, INV_Y = 128, HOT_Y = 186;
    /** Шкала бака: x, y, ширина, высота. */
    public static final int GAUGE_X = 264, GAUGE_Y = 39, GAUGE_W = 7, GAUGE_H = 68;
    public static final int STORAGE = 27, UP0 = 28, UPGRADES = 8, FUEL0 = 37, FUEL = 4, PLAYER0 = 45, HOTBAR0 = 72, SLOTS = 81;
    /** Кнопки (слоты сервера с предметом-значком): мастерская и ремонт — в заголовке справа; ширина кнопки с подписью. */
    public static final int WORKSHOP = 41, REPAIR = 42, WORKSHOP_X = 172, REPAIR_X = 248, BUTTON_Y = 3, WORKSHOP_W = 70, REPAIR_W = 48;
    /** Где прячутся ненужные слоты. */
    public static final int AWAY = -10000;

    /** Что за слот окна (по номеру в меню). */
    public enum Kind { CARGO, UPGRADE, FUEL, INVENTORY, HOTBAR, BUTTON, HIDDEN }

    public static Kind kind(int i) {
        if (i >= 0 && i < STORAGE) return Kind.CARGO;
        if (i >= UP0 && i < UP0 + UPGRADES) return Kind.UPGRADE;
        if (i >= FUEL0 && i < FUEL0 + FUEL) return Kind.FUEL;
        if (i >= PLAYER0 && i < PLAYER0 + 27) return Kind.INVENTORY;
        if (i >= HOTBAR0 && i < HOTBAR0 + 9) return Kind.HOTBAR;
        if (i == WORKSHOP || i == REPAIR) return Kind.BUTTON;
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
            case BUTTON -> new int[]{i == WORKSHOP ? WORKSHOP_X : REPAIR_X, BUTTON_Y};
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
