package ink.hoka.odyssey.gen;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * Куб в массивах: blocks[4096] (y&lt;&lt;8|z&lt;&lt;4|x), biomes[64] (y&lt;&lt;4|z&lt;&lt;2|x) — как у {@link GenHash#ids}. Для пула
 * переиспользуется ({@link #reset}). secrets = true (сервер): секреты и данные блок-сущностей копятся отдельно и НЕ
 * входят в blocks — видимая часть и её хэш те же, что у клиента.
 */
public final class GenArrayCube implements GenCube {

    public final int[] blocks = new int[4096];
    public final int[] biomes = new int[64];
    private final boolean secrets;
    private int cx, cy, cz;
    private long seed;
    /** Секреты: индекс → состояние (по порядку индекса). Только при secrets. */
    private final TreeMap<Integer, Integer> secretMap;
    private final TreeMap<Integer, String> dataMap;

    public GenArrayCube(boolean secrets) {
        this.secrets = secrets;
        this.secretMap = secrets ? new TreeMap<>() : null;
        this.dataMap = secrets ? new TreeMap<>() : null;
    }

    /** Новый куб (воздух, биомы 0). */
    public GenArrayCube reset(int cx, int cy, int cz, long seed) {
        this.cx = cx; this.cy = cy; this.cz = cz; this.seed = seed;
        Arrays.fill(blocks, 0);
        Arrays.fill(biomes, 0);
        if (secrets) { secretMap.clear(); dataMap.clear(); }
        return this;
    }

    public static int index(int lx, int ly, int lz) { return ly << 8 | lz << 4 | lx; }

    @Override public int cx() { return cx; }
    @Override public int cy() { return cy; }
    @Override public int cz() { return cz; }
    @Override public long seed() { return seed; }
    @Override public boolean secrets() { return secrets; }

    private static void check(int lx, int ly, int lz) {
        if ((lx | ly | lz) < 0 || lx > 15 || ly > 15 || lz > 15) throw new IndexOutOfBoundsException(lx + " " + ly + " " + lz);
    }

    @Override
    public void set(int lx, int ly, int lz, int state) {
        check(lx, ly, lz);
        blocks[index(lx, ly, lz)] = state;
    }

    @Override
    public void setAbs(int x, int y, int z, int state) {
        if (x >> 4 != cx || y >> 4 != cy || z >> 4 != cz) return;
        blocks[index(x & 15, y & 15, z & 15)] = state;
    }

    @Override
    public int get(int lx, int ly, int lz) {
        check(lx, ly, lz);
        return blocks[index(lx, ly, lz)];
    }

    @Override public void fill(int state) { Arrays.fill(blocks, state); }

    @Override
    public void fillY(int ly0, int ly1, int state) {
        int a = Math.max(0, ly0), e = Math.min(15, ly1);
        if (a <= e) Arrays.fill(blocks, a << 8, (e + 1) << 8, state);
    }

    @Override public void biome(int bx, int by, int bz, int biome) { biomes[(by & 3) << 4 | (bz & 3) << 2 | (bx & 3)] = biome; }

    @Override public void fillBiome(int biome) { Arrays.fill(biomes, biome); }

    @Override
    public void secret(int lx, int ly, int lz, int state) {
        if (!secrets) return;
        check(lx, ly, lz);
        secretMap.put(index(lx, ly, lz), state);
    }

    @Override
    public void data(int lx, int ly, int lz, String snbt) {
        if (!secrets) return;
        check(lx, ly, lz);
        dataMap.put(index(lx, ly, lz), snbt);
    }

    /** Хэш видимой части (без секретов). */
    public long visHash() { return GenHash.ids(blocks, biomes); }

    /** Секреты (индекс → состояние); пусто на клиенте. */
    public Map<Integer, Integer> secretList() { return secrets ? secretMap : Map.of(); }

    /** Данные блок-сущностей (индекс → SNBT); пусто на клиенте. */
    public Map<Integer, String> dataList() { return secrets ? dataMap : Map.of(); }

    /** В кубе нет ни одного блока (видимого и секретного). */
    public boolean isEmpty() {
        for (int v : blocks) if (v != 0) return false;
        return secrets ? secretMap.isEmpty() : true;
    }
}
