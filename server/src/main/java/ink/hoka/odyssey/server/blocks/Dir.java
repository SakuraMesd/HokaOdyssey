package ink.hoka.odyssey.server.blocks;

import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.block.BlockFace;

/** Направления как в ванили (значения свойства facing). */
enum Dir {
    DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

    final int x, y, z;

    Dir(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }

    String key() { return name().toLowerCase(); }

    boolean horizontal() { return y == 0; }

    char axis() { return x != 0 ? 'x' : y != 0 ? 'y' : 'z'; }

    Dir opposite() {
        return switch (this) { case DOWN -> UP; case UP -> DOWN; case NORTH -> SOUTH; case SOUTH -> NORTH; case WEST -> EAST; case EAST -> WEST; };
    }

    /** По часовой стрелке (вид сверху): north → east → south → west. */
    Dir cw() {
        return switch (this) { case NORTH -> EAST; case EAST -> SOUTH; case SOUTH -> WEST; case WEST -> NORTH; default -> this; };
    }

    Dir ccw() {
        return switch (this) { case NORTH -> WEST; case WEST -> SOUTH; case SOUTH -> EAST; case EAST -> NORTH; default -> this; };
    }

    Point rel(Point p) { return p.add(x, y, z); }

    static Dir of(String s) {
        return s == null ? null : switch (s) {
            case "down" -> DOWN; case "up" -> UP; case "north" -> NORTH; case "south" -> SOUTH; case "west" -> WEST; case "east" -> EAST;
            default -> null;
        };
    }

    static Dir of(BlockFace f) {
        return switch (f) {
            case BOTTOM -> DOWN; case TOP -> UP; case NORTH -> NORTH; case SOUTH -> SOUTH; case WEST -> WEST; case EAST -> EAST;
        };
    }

    /** Куда игрок смотрит по горизонтали (yaw: 0 — юг, 90 — запад, 180 — север, −90 — восток). */
    static Dir look(Pos p) {
        int i = (int) Math.floor(p.yaw() / 90.0 + 0.5) & 3;
        return switch (i) { case 0 -> SOUTH; case 1 -> WEST; case 2 -> NORTH; default -> EAST; };
    }

    /** Ближайшее направление взгляда с учётом вертикали (как getNearestLookingDirection). */
    static Dir look3d(Pos p) {
        double pitch = Math.toRadians(p.pitch()), yaw = Math.toRadians(p.yaw());
        double vx = -Math.sin(yaw) * Math.cos(pitch), vy = -Math.sin(pitch), vz = Math.cos(yaw) * Math.cos(pitch);
        double ax = Math.abs(vx), ay = Math.abs(vy), az = Math.abs(vz);
        if (ay >= ax && ay >= az) return vy > 0 ? UP : DOWN;
        if (ax >= az) return vx > 0 ? EAST : WEST;
        return vz > 0 ? SOUTH : NORTH;
    }

    static final Dir[] HORIZONTAL = {NORTH, EAST, SOUTH, WEST};
}
