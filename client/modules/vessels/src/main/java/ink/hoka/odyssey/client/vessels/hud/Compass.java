package ink.hoka.odyssey.client.vessels.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Лента компаса сверху (как в War Thunder): риски через 5°, длинные через 10°, подписи через 30° (стороны света —
 * буквами), метка курса посередине. Здесь — только расчёт меток (без экрана, проверяется тестом).
 */
public final class Compass {

    private Compass() {}

    /** Метка ленты: off — сдвиг от курса в градусах (−span/2..span/2), size 0 — мелкая (5°), 1 — 10°, 2 — 30° с подписью. */
    public record Mark(int deg, float off, int size, String label) {}

    /** Курс (0 — север, 90 — восток) из курса игры (yaw: 0 — юг, 90 — запад). */
    public static float heading(float yaw) {
        float h = (yaw - 180) % 360;
        return h < 0 ? h + 360 : h;
    }

    /** Подпись курса: С, В, Ю, З или число градусов (30, 60, …). */
    public static String label(int deg) {
        deg = Math.floorMod(deg, 360);
        return switch (deg) {
            case 0 -> "С";
            case 90 -> "В";
            case 180 -> "Ю";
            case 270 -> "З";
            default -> Integer.toString(deg);
        };
    }

    /** Метки ленты при курсе heading (градусы, любые) и ширине обзора span (градусы). */
    public static List<Mark> marks(float heading, float span) {
        List<Mark> out = new ArrayList<>();
        float half = span / 2;
        int from = (int) Math.ceil((heading - half) / 5) * 5, to = (int) Math.floor((heading + half) / 5) * 5;
        for (int d = from; d <= to; d += 5) {
            float off = d - heading;
            if (off < -half || off > half) continue;
            int deg = Math.floorMod(d, 360);
            int size = deg % 30 == 0 ? 2 : deg % 10 == 0 ? 1 : 0;
            out.add(new Mark(deg, off, size, size == 2 ? label(deg) : null));
        }
        return out;
    }

    /** Курс в рамке: три цифры («007», «359»). */
    public static String readout(float heading) {
        int h = Math.floorMod(Math.round(heading), 360);
        return String.format("%03d", h);
    }
}
