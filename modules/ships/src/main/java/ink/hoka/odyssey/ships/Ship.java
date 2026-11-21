package ink.hoka.odyssey.ships;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;

import java.util.UUID;

/**
 * Корабль игрока. Состояния:
 * <ul>
 *   <li>PARKED — стоит блоками на карте map: угол anchor (минимальные x,y,z), поворот rot (0..3 × 90° по часовой);</li>
 *   <li>FLYING — летит моделью (см. {@link Flight}), блоков в мире нет;</li>
 *   <li>STOWED — убран (не нашлось места при аварийной посадке) — появится снова при призыве.</li>
 * </ul>
 * Сохраняется в module/ships/ships.json.
 */
final class Ship {

    enum State { PARKED, FLYING, STOWED }

    String owner;          // UUID владельца
    String ownerName;
    String template;
    String map;
    int ax, ay, az;        // anchor
    int rot;
    State state = State.PARKED;

    transient ShipTemplate t;
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
