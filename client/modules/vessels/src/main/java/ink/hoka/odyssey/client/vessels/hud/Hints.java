package ink.hoka.odyssey.client.vessels.hud;

/**
 * Подсказки клавиш внизу: видны 10 с после посадки, потом за 0.8 с гаснут; пока зажата H — видны (и ещё 1.5 с после).
 * Время — миллисекунды (часы любые, лишь бы одни).
 */
public final class Hints {

    public static final long SHOW_MS = 10_000, HOLD_MS = 1_500, FADE_MS = 800;
    public static final String[][] KEYS = {{"F", "выйти"}, {"G", "меню"}, {"ЛКМ", "огонь"}, {"R", "варп"},
            {"Ctrl+W", "форсаж"}, {"F5", "вид"}, {"H", "подсказки"}};

    private long until = Long.MIN_VALUE;

    /** Сел в корабль. */
    public void board(long now) { until = now + SHOW_MS; }

    /** Каждый тик/кадр: зажата ли H. */
    public void hold(boolean down, long now) { if (down) until = Math.max(until, now + HOLD_MS); }

    /** Прозрачность подсказок сейчас (1 — видны, 0 — погасли). */
    public float alpha(long now) {
        if (until == Long.MIN_VALUE) return 0;
        if (now <= until) return 1;
        return Math.max(0, 1 - (now - until) / (float) FADE_MS);
    }
}
