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
    /** Прочность (−1 — полная: ещё не задавалась) и урон по зонам ({@link Zones}). */
    double hp = -1;
    final double[] zoneDmg = new double[Zones.COUNT];

    // ── не сохраняется: бой и варп ──
    /** Тик модуля, когда орудие снова готово; какой ствол следующий. */
    transient long gunReady;
    transient int nextGun;
    /** Варп: 0 — нет, 1 — зарядка (до warpAt), 2 — ждём загрузку точки прыжка; перезарядка до warpReady. */
    transient int warpPhase;
    transient long warpAt, warpReady, warpWaitUntil;
    transient double[] warpTarget;
    /** Откуда и куда (нос) прыгаем — запомнено при R: точка ищется у загруженной цели, даже если пилот довернул. */
    transient double[] warpFrom, warpDir;
    /** Набежавший расход топлива в полёте (доли штуки). */
    transient double burn;
    /** Меню корабля (пока открыто кем-то) и мастерская. */
    transient VesselMenu menu;
    transient Workshop.Menu shop;
    /** Тик, когда удар о блоки снова считается (не чаще), и последнее попадание. */
    transient long impactReady;

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

    /** Прочность сейчас (не задана — полная). */
    double hp(double max) { return hp < 0 ? max : Math.min(hp, max); }

    /** Урон amount по зоне zone. Вернуть сколько прочности осталось. */
    double damage(double amount, int zone, double max) {
        if (!(amount > 0)) return hp(max);
        double before = hp(max);
        double took = Math.min(before, amount);
        hp = before - amount <= 1e-9 ? 0 : before - amount;
        if (zone >= 0 && zone < Zones.COUNT) zoneDmg[zone] += took;
        return hp;
    }

    /** Ремонт: +amount прочности (не выше max); урон зон уменьшается в той же доле. Вернуть, сколько прибавилось. */
    double repair(double amount, double max) {
        double before = hp(max), lost = max - before;
        if (!(amount > 0) || lost <= 1e-9) return 0;
        double add = Math.min(amount, lost);
        hp = before + add >= max - 1e-9 ? -1 : before + add;
        double k = add >= lost - 1e-9 ? 0 : 1 - add / lost;
        for (int i = 0; i < zoneDmg.length; i++) zoneDmg[i] *= k;
        return add;
    }

    /** Задать прочность (команда): урон зон — в той же доле, что недостаёт. */
    void setHp(double value, double max) {
        double v = Math.max(0, Math.min(max, value));
        double lost = max - hp(max), want = max - v;
        hp = v >= max - 1e-9 ? -1 : v;
        if (want <= 1e-9) { java.util.Arrays.fill(zoneDmg, 0); return; }
        if (lost <= 1e-9) { java.util.Arrays.fill(zoneDmg, want / Zones.COUNT); return; }
        for (int i = 0; i < zoneDmg.length; i++) zoneDmg[i] *= want / lost;
    }
}
