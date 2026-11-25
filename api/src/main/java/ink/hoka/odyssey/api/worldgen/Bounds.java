package ink.hoka.odyssey.api.worldgen;

/**
 * Где у планеты вообще есть блоки: minY…maxY (включительно); size &gt; 0 — квадрат ±size/2 по X/Z вокруг 0,0,
 * снаружи пусто. Кубы вне границ ядро считает пустыми и генератор не зовёт.
 */
public record Bounds(int minY, int maxY, int size) {
    public Bounds {
        if (minY > maxY) throw new IllegalArgumentException("minY > maxY");
        if (size < 0) throw new IllegalArgumentException("size < 0");
    }

    public Bounds(int minY, int maxY) { this(minY, maxY, 0); }

    /** Куб (cx, cy, cz) хоть частично внутри границ. */
    public boolean cubeInside(int cx, int cy, int cz) {
        if ((cy << 4) + 15 < minY || (cy << 4) > maxY) return false;
        if (size <= 0) return true;
        int h = size / 2;
        return (cx << 4) + 15 >= -h && (cx << 4) < h && (cz << 4) + 15 >= -h && (cz << 4) < h;
    }

    /** Колонка (x, z) внутри квадрата size. */
    public boolean columnInside(int x, int z) {
        if (size <= 0) return true;
        int h = size / 2;
        return x >= -h && x < h && z >= -h && z < h;
    }
}
