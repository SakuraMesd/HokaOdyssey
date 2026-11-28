package ink.hoka.odyssey.stations;

/**
 * Коробка региона в блоках, включительно с обеих сторон: блок (x, y, z) внутри, если min ≤ x ≤ max (и т.д.).
 * Точка (дробные координаты) внутри, если min ≤ x &lt; max + 1 — то есть по граням блоков.
 */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Box {
        if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("min > max: " + minX + "," + minY + "," + minZ + " … " + maxX + "," + maxY + "," + maxZ);
    }

    /** По двум углам в любом порядке. */
    public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x < maxX + 1.0 && y >= minY && y < maxY + 1.0 && z >= minZ && z < maxZ + 1.0;
    }

    public long sizeX() { return (long) maxX - minX + 1; }

    public long sizeY() { return (long) maxY - minY + 1; }

    public long sizeZ() { return (long) maxZ - minZ + 1; }

    /** Объём в блоках. */
    public long volume() { return sizeX() * sizeY() * sizeZ(); }

    /** Центр (по граням блоков). */
    public double centerX() { return (minX + maxX + 1.0) / 2; }

    public double centerY() { return (minY + maxY + 1.0) / 2; }

    public double centerZ() { return (minZ + maxZ + 1.0) / 2; }

    /** Куда расширять: вверх, вниз, во все шесть сторон, по горизонтали (четыре стороны). */
    public enum Dir { UP, DOWN, ALL, SIDES }

    /**
     * Расширить на n блоков (n &lt; 0 — сжать; меньше блока по оси не сожмётся — останется один слой).
     * Высота обрезается по minY…maxY карты (Integer.MIN_VALUE/MAX_VALUE — без обрезки).
     */
    public Box expand(int n, Dir dir, int worldMinY, int worldMaxY) {
        long x0 = minX, y0 = minY, z0 = minZ, x1 = maxX, y1 = maxY, z1 = maxZ;
        switch (dir) {
            case UP -> y1 += n;
            case DOWN -> y0 -= n;
            case ALL -> { x0 -= n; y0 -= n; z0 -= n; x1 += n; y1 += n; z1 += n; }
            case SIDES -> { x0 -= n; z0 -= n; x1 += n; z1 += n; }
        }
        y0 = Math.max(y0, worldMinY);
        y1 = Math.min(y1, worldMaxY);
        if (x0 > x1) { long c = (minX + (long) maxX) / 2; x0 = x1 = c; }
        if (y0 > y1) { long c = Math.clamp((minY + (long) maxY) / 2, worldMinY, worldMaxY); y0 = y1 = c; }
        if (z0 > z1) { long c = (minZ + (long) maxZ) / 2; z0 = z1 = c; }
        return new Box(clampInt(x0), clampInt(y0), clampInt(z0), clampInt(x1), clampInt(y1), clampInt(z1));
    }

    private static int clampInt(long v) { return (int) Math.clamp(v, Integer.MIN_VALUE, Integer.MAX_VALUE); }

    /** «x,y,z … x,y,z (A×B×C)» — для сообщений. */
    public String describe() {
        return minX + "," + minY + "," + minZ + " … " + maxX + "," + maxY + "," + maxZ + " (" + sizeX() + "×" + sizeY() + "×" + sizeZ() + ")";
    }
}
