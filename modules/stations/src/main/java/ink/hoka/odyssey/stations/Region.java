package ink.hoka.odyssey.stations;

import java.util.List;
import java.util.Map;

/**
 * Регион станции (неизменяемый: правка — новый объект). Несколько коробок — станция сложной формы.
 *
 * @param id      латиница/цифры/«_»/«-», уникален на сервере
 * @param map     карта (maps/&lt;карта&gt;)
 * @param title   название для игроков («Станция Альфа»)
 * @param owner   владелец (UUID строкой) или null
 * @param gravity гравитация внутри
 * @param boxes   коробки (хотя бы одна)
 * @param created порядковый номер создания: при равном объёме главнее созданный позже
 * @param flags   на будущее (права, доки): ключ → строка; сейчас никем не читается
 */
public record Region(String id, String map, String title, String owner, Grav gravity, List<Box> boxes, long created,
                     Map<String, String> flags) {

    public Region {
        boxes = List.copyOf(boxes);
        flags = Map.copyOf(flags);
        if (boxes.isEmpty()) throw new IllegalArgumentException("регион " + id + " без коробок");
    }

    /** Подходит ли строка как id региона. */
    public static boolean validId(String id) { return id != null && id.matches("[a-z0-9_-]{1,32}"); }

    /** Объём — сумма объёмов коробок (перекрытие своих коробок не вычитается: для приоритета этого достаточно). */
    public long volume() {
        long v = 0;
        for (Box b : boxes) v += b.volume();
        return v;
    }

    public boolean contains(double x, double y, double z) {
        for (Box b : boxes) if (b.contains(x, y, z)) return true;
        return false;
    }

    /** Кто главнее при пересечении: меньший объём, при равном — созданный позже. &lt; 0 — this главнее. */
    public int priorityCompare(Region o) {
        int c = Long.compare(volume(), o.volume());
        return c != 0 ? c : Long.compare(o.created, created);
    }

    public Region withTitle(String t) { return new Region(id, map, t, owner, gravity, boxes, created, flags); }

    public Region withGravity(Grav g) { return new Region(id, map, title, owner, g, boxes, created, flags); }

    public Region withBoxes(List<Box> b) { return new Region(id, map, title, owner, gravity, b, created, flags); }

    public Region withOwner(String o) { return new Region(id, map, title, o, gravity, boxes, created, flags); }
}
