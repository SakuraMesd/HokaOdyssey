package ink.hoka.odyssey.ships;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;

import java.util.UUID;

/**
 * Корабль игрока. Состояния:
 * <ul>
 *   <li>PARKED — стоит блоками на карте map: угол anchor (минимальные x,y,z), поворот rot (0..3 × 90° по часовой);</li>
 *   <li>FLYING — летит моделью (см. {@link Flight}), блоков в мире нет;</li>
 *   <li>STOWED — убран (не нашлось места при аварийной посадке) — появится снова при призыве;</li>
 *   <li>MODEL — стоит моделью там, где пилот вышел (F), в том же повороте: блоков в мире нет, его рисует мод,
 *       по нему можно ходить. Положение и блоки — поля m*.</li>
 * </ul>
 * Сохраняется в module/ships/ships.json.
 */
final class Ship {

    enum State { PARKED, FLYING, STOWED, MODEL }

    String owner;          // UUID владельца
    String ownerName;
    String template;
    String map;
    int ax, ay, az;        // anchor
    int rot;
    State state = State.PARKED;
    // MODEL: точка пилота, курс/наклон/крен, курс взлёта (поворот блоков), высота сиденья; блоки — dx dy dz от клетки
    // пилота и их состояния (строкой — переживает смену номеров состояний)
    double mx, my, mz;
    float myaw, mpitch, mroll, mturn, mseatH;
    java.util.List<int[]> mparts;
    java.util.List<String> mblocks;
    /** Вещи сундуков/бочек корабля-модели: клетка «dx,dy,dz» → вещи по ячейкам (JSON, пусто — нет вещи). */
    java.util.Map<String, java.util.List<String>> mchests;

    /**
     * Верфь (SHIPYARD.md): номер плота и клетка пилота в нём {k, x, y, z}; null — корабль ещё не в верфи. Клетка
     * корабля (dx,dy,dz) от пилота (как mparts / Flight.parts) = блок плота (x+dx, y+dy, z+dz) — настоящий блок мира.
     */
    int[] yard;
    /** Ориентация блоков в плоте (курс взлёта, как mturn) и коробка клеток {minDx,minDy,minDz,maxDx,maxDy,maxDz}. */
    float yardTurn;
    int[] yardBox;
    /** Из какого чертежа построен плот (другой корабль — плот не его) и номера брошенных плотов (не занимать снова). */
    String yardTemplate;
    java.util.List<Integer> oldYards;

    /** Бросить плот (корабль сменился): блоки остаются, номер больше не выдаётся. */
    void dropYard() {
        if (yard == null) return;
        if (oldYards == null) oldYards = new java.util.ArrayList<>();
        oldYards.add(yard[0]);
        yard = null; yardBox = null; yardTemplate = null;
    }

    transient ShipTemplate t;
    /** Кубы плота закреплены в ядре (Shipyard.pin); pinnedIn — в каком мире (карту перезагрузили — закрепить заново). */
    transient boolean pinned;
    transient net.minestom.server.instance.Instance pinnedIn;
    transient Flight flight;

    UUID ownerId() { return UUID.fromString(owner); }

    int[] size() {
        int[] s = Rot.size(t.sx, t.sz, rot);
        return new int[]{s[0], t.sy, s[1]};
    }

    /** Локальная клетка чертежа → мировая позиция блока. */
    Vec world(int x, int y, int z) {
        int[] r = Rot.xz(x, z, t.sx, t.sz, rot);
        return new Vec(ax + r[0], ay + y, az + r[1]);
    }

    /** Мировая позиция → локальная клетка чертежа, или null, если вне корабля. */
    int[] local(Point p) {
        int u = p.blockX() - ax, y = p.blockY() - ay, v = p.blockZ() - az;
        int[] s = size();
        if (u < 0 || v < 0 || y < 0 || u >= s[0] || y >= s[1] || v >= s[2]) return null;
        return switch (rot & 3) {
            case 1 -> new int[]{v, y, t.sz - 1 - u};
            case 2 -> new int[]{t.sx - 1 - u, y, t.sz - 1 - v};
            case 3 -> new int[]{t.sx - 1 - v, y, u};
            default -> new int[]{u, y, v};
        };
    }

    boolean contains(Point p) { return state == State.PARKED && local(p) != null; }

    /** Стоит ли точка внутри коробки корабля (с запасом по высоте на рост игрока). */
    boolean around(Point p) {
        if (state != State.PARKED) return false;
        int[] s = size();
        return p.x() >= ax && p.x() < ax + s[0] && p.z() >= az && p.z() < az + s[2] && p.y() >= ay - 0.5 && p.y() < ay + s[1] + 1;
    }

    Vec seatWorld() { return world(t.seat[0], t.seat[1], t.seat[2]); }
}
