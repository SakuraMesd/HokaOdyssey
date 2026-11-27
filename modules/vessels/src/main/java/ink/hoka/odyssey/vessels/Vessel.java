package ink.hoka.odyssey.vessels;

import net.minestom.server.entity.Entity;

/**
 * Корабль в мире: вид, карта, положение (середина модели) и поворот. Сохраняется в vessels.json; носитель и пилот —
 * только пока модуль включён.
 */
final class Vessel {

    final int id;
    final String kind;
    String map;
    double x, y, z;
    Quat rot;

    /** Носитель: невидимый item display — его положение и поворот (с интерполяцией) мод берёт для отрисовки. */
    transient Entity carrier;
    /** Пилот (null — свободен). */
    transient Piloting pilot;

    Vessel(int id, String kind, String map, double x, double y, double z, Quat rot) {
        this.id = id;
        this.kind = kind;
        this.map = map;
        this.x = x;
        this.y = y;
        this.z = z;
        this.rot = rot;
    }

    double[] pos() { return new double[]{x, y, z}; }
}
