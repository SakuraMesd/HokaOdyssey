package ink.hoka.odyssey.gen;

/**
 * Куб 16×16×16, который заполняет генератор. Локальные координаты 0…15; {@code *Abs} — мировые, запись мимо куба
 * отбрасывается (деревья на стыке кубов: каждый куб рисует всё дерево, остаётся своя часть). Сначала — воздух (0),
 * биомы 0. Один буфер — один поток.
 */
public interface GenCube {
    int cx();
    int cy();
    int cz();
    long seed();

    default int x0() { return cx() << 4; }
    default int y0() { return cy() << 4; }
    default int z0() { return cz() << 4; }

    /** true только на сервере: секреты пишутся (на клиенте secret/data ничего не делают). */
    boolean secrets();

    void set(int lx, int ly, int lz, int state);

    /** Мировые координаты; вне куба — ничего. */
    void setAbs(int x, int y, int z, int state);

    int get(int lx, int ly, int lz);

    void fill(int state);

    /** Слои ly0…ly1 включительно (обрезается по 0…15). */
    void fillY(int ly0, int ly1, int state);

    /** Биом ячейки 4×4×4 (0…3). */
    void biome(int bx, int by, int bz, int biome);

    void fillBiome(int biome);

    /** Секрет (руда, тайник): сервер — поверх видимой части; клиент — ничего. */
    void secret(int lx, int ly, int lz, int state);

    /** Данные блок-сущности (SNBT, лут и т.п.) — только сервер. */
    void data(int lx, int ly, int lz, String snbt);
}
