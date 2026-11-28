package ink.hoka.odyssey.blocks;

import net.minestom.server.instance.block.Block;

/**
 * Многоклеточный блок (поле {@code size: [X, Y, Z]}): какие клетки занимает, как поворачивается, проверка места,
 * постановка и снятие всех частей. Главная клетка (часть 0) — с наименьшими X, Y, Z до поворота; в неё ставит игрок.
 * Поворот — как у мода (CustomBlocks.rotation): вокруг середины главной клетки, rot = 0/90/180/270, при rot = 0
 * части идут в +X и +Z. Без мира Minestom — через {@link World}, чтобы проверять тестами.
 */
final class Multi {

    private Multi() {}

    /** Доступ к миру. */
    interface World {
        Block get(int x, int y, int z);
        void set(int x, int y, int z, Block b);
        boolean loaded(int x, int y, int z);
    }

    static int parts(int[] size) { return size[0] * size[1] * size[2]; }

    /** Номер части по клетке до поворота: X быстрее всего, потом Z, потом Y. */
    static int part(int[] size, int dx, int dy, int dz) { return (dy * size[2] + dz) * size[0] + dx; }

    /** Клетка части до поворота: {dx, dy, dz}. */
    static int[] local(int[] size, int part) {
        int dx = part % size[0], rest = part / size[0];
        return new int[]{dx, rest / size[2], rest % size[2]};
    }

    /** Сдвиг части от главной клетки в мире при повороте rot. */
    static int[] offset(int[] size, int part, int rot) {
        int[] l = local(size, part);
        int dx = l[0], dz = l[2];
        return switch (Math.floorMod(rot, 360)) {
            case 90 -> new int[]{-dz, l[1], dx};
            case 180 -> new int[]{-dx, l[1], -dz};
            case 270 -> new int[]{dz, l[1], -dx};
            default -> new int[]{dx, l[1], dz};
        };
    }

    /** Свободна ли клетка под часть: воздух или заменяемое (трава, вода…). */
    static boolean free(Block b) {
        return b == null || b.isAir() || b.registry().isReplaceable();
    }

    /**
     * Можно ли поставить в главную клетку (x, y, z) с поворотом rot: все клетки свободны и под нижними — опора.
     * null — можно, иначе причина для игрока.
     */
    static String check(World w, int[] size, int x, int y, int z, int rot) {
        for (int p = 0; p < parts(size); p++) {
            int[] o = offset(size, p, rot);
            int cx = x + o[0], cy = y + o[1], cz = z + o[2];
            if (!w.loaded(cx, cy, cz)) return "Мир здесь ещё не загружен.";
            if (!free(w.get(cx, cy, cz))) return "Не хватает места: нужно " + size[0] + "×" + size[2] + " свободных клеток.";
            if (o[1] == 0) {
                if (!w.loaded(cx, cy - 1, cz)) return "Мир здесь ещё не загружен.";
                Block under = w.get(cx, cy - 1, cz);
                if (under == null || !under.isSolid()) return "Нужна ровная опора под всеми клетками.";
            }
        }
        return null;
    }

    /** Поставить части (blocks[part] — носитель части при этом повороте). Главную ставит вызывающий, если skipMain. */
    static void place(World w, Block[] blocks, int[] size, int x, int y, int z, int rot, boolean skipMain) {
        for (int p = skipMain ? 1 : 0; p < parts(size); p++) {
            int[] o = offset(size, p, rot);
            w.set(x + o[0], y + o[1], z + o[2], blocks[p]);
        }
    }

    /**
     * Убрать все части блока, к которому относится часть part в клетке (x, y, z). Убираются только клетки, где стоит
     * именно своя часть (чужое не трогаем). Возвращает, сколько клеток убрано.
     */
    static int remove(World w, Block[] blocks, int[] size, int x, int y, int z, int part, int rot) {
        int[] o0 = offset(size, part, rot);
        int mx = x - o0[0], my = y - o0[1], mz = z - o0[2];
        int n = 0;
        for (int p = 0; p < parts(size); p++) {
            int[] o = offset(size, p, rot);
            int cx = mx + o[0], cy = my + o[1], cz = mz + o[2];
            if (!w.loaded(cx, cy, cz)) continue;
            Block b = w.get(cx, cy, cz);
            if (b != null && blocks[p] != null && b.stateId() == blocks[p].stateId()) {
                w.set(cx, cy, cz, Block.AIR);
                n++;
            }
        }
        return n;
    }

    /** Главная клетка для части part в (x, y, z). */
    static int[] main(int[] size, int x, int y, int z, int part, int rot) {
        int[] o = offset(size, part, rot);
        return new int[]{x - o[0], y - o[1], z - o[2]};
    }

    /** Поворот по курсу игрока: «лицо» модели (−Z) смотрит на игрока. */
    static int rotOf(float yaw) {
        return Math.floorMod(Math.round(yaw / 90f) * 90, 360);
    }
}
