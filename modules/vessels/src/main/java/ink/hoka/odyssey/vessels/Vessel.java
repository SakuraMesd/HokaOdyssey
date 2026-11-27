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
    /** Владелец (кто поставил /hovessel spawn или назначен /hovessel owner): uuid и ник; null — ничей (только OP). */
    String owner, ownerName;
    /** Хранилище, улучшения, топливо. */
    final Cargo cargo;

    // ── не сохраняется: бой и варп ──
    /** Тик модуля, когда орудие снова готово; какой ствол следующий. */
    transient long gunReady;
    transient int nextGun;
    /** Варп: 0 — нет, 1 — зарядка (до warpAt), 2 — ждём загрузку точки прыжка; перезарядка до warpReady. */
    transient int warpPhase;
    transient long warpAt, warpReady, warpWaitUntil;
    transient double[] warpTarget;
    /** Набежавший расход топлива в полёте (доли штуки). */
    transient double burn;
    /** Меню корабля (пока открыто кем-то). */
    transient VesselMenu menu;

    /** Носитель: невидимый item display — его положение и поворот (с интерполяцией) мод берёт для отрисовки. */
    transient Entity carrier;
    /** Пилот (null — свободен). */
    transient Piloting pilot;

    Vessel(int id, String kind, String map, double x, double y, double z, Quat rot) {
        this(id, kind, map, x, y, z, rot, new Cargo());
    }

    Vessel(int id, String kind, String map, double x, double y, double z, Quat rot, Cargo cargo) {
        this.cargo = cargo;
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
