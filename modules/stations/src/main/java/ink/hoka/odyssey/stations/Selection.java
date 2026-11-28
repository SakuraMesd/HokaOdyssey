package ink.hoka.odyssey.stations;

/**
 * Выделение админа: две точки на карте (указкой или /hostation pos1|pos2). Неизменяемое.
 * Точки на разных картах — выделения нет (берётся карта последней поставленной точки, другая сбрасывается).
 */
public record Selection(String map, int[] p1, int[] p2) {

    public static final Selection NONE = new Selection(null, null, null);

    /** Поставить точку (1 или 2) на карте map. */
    public Selection with(int which, String onMap, int x, int y, int z) {
        int[] p = {x, y, z};
        boolean same = onMap.equals(map);
        int[] a = which == 1 ? p : (same ? p1 : null);
        int[] b = which == 2 ? p : (same ? p2 : null);
        return new Selection(onMap, a, b);
    }

    public boolean complete() { return map != null && p1 != null && p2 != null; }

    /** Коробка выделения (только если обе точки). */
    public Box box() { return complete() ? Box.of(p1[0], p1[1], p1[2], p2[0], p2[1], p2[2]) : null; }

    /** Выделение по готовой коробке (после expand выделения). */
    public static Selection of(String map, Box b) {
        return new Selection(map, new int[]{b.minX(), b.minY(), b.minZ()}, new int[]{b.maxX(), b.maxY(), b.maxZ()});
    }
}
