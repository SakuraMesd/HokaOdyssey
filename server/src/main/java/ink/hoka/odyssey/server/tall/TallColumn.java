package ink.hoka.odyssey.server.tall;

import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.data.LightData;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Столбец высокой карты (шаг 9): {@link TallChunk} (DynamicChunk, хранение .htw) или {@link CubeColumn} (свой Chunk,
 * хранение регионами кубов, map.yml {@code store: cubes}). Хранение, CubeStreamer, /hocubes работают через него.
 * Методы Chunk ниже реализует сам Chunk — здесь они только чтобы не приводить типы.
 */
public sealed interface TallColumn permits TallChunk, CubeColumn {

    // ---- из Chunk ----
    int getChunkX();
    int getChunkZ();
    Instance getInstance();
    List<Section> getSections();
    int getMinSection();
    Set<? extends Player> getViewers();
    boolean isLoaded();
    void lockReadLock();
    void unlockReadLock();
    void lockWriteLock();
    void unlockWriteLock();
    void setBlock(int x, int y, int z, Block block);

    default Chunk chunk() { return (Chunk) this; }

    // ---- своё ----
    ColumnState st();

    /** Своя секция по индексу (общая пустая заменяется настоящей) — перед прямой записью в палитры. */
    Section materialize(int index);

    /** Секция i — своя (не общая пустая). */
    boolean isReal(int i);

    /** Сколько своих объектов секций (для отладки). */
    int realSections();

    /** Положить готовую секцию (с диска/генератора) на место i. */
    void putSection(int i, Section s);

    /** Блоки с данными в полосе Y [y0, y1] (ключ — индекс в столбце, CoordConversion.chunkBlockIndex). */
    Map<Integer, Block> dataBlocks(int y0, int y1);

    /** Блоки с данными (сундуки, таблички…) — для сохранения. */
    default Map<Integer, Block> dataBlocks() { return dataBlocks(Integer.MIN_VALUE, Integer.MAX_VALUE); }

    /** Палитры залиты напрямую — пересчитать высоты; свет не слать (ещё никто не видит); «как на диске». */
    void afterLoad();

    default boolean isModified() { return st().modified; }

    /**
     * Куб i в памяти (READY, шаг 10). Не в памяти — его секция общая пустая, писать в него нельзя, игроку не слать.
     * TallChunk и CubeColumn без ленивой загрузки — всегда true.
     */
    default boolean ready(int i) { return true; }

    /** Данные куба i в памяти (READY или выгружается, шаг 11) — сохранение их пишет; остальные на диске как были. */
    default boolean inMemory(int i) { return ready(i); }

    /** Попросить загрузить куб i (prio — меньше раньше: 0–1 — куб игрока и соседи). Готов — ничего. */
    default void request(int i, int prio) {}

    default LightData light() { return st().light(this); }

    /** Кэш клиентов (0.51): хэш содержимого куба i (CubeHash). Под замком чтения столбца. CubeColumn кэширует. */
    default long cubeHash(int i) { return cubeHashFresh(i); }

    /** То же, всегда заново по содержимому (для ссылки op 13). */
    default long cubeHashFresh(int i) { return CubeHash.of(getSections().get(i)); }

    default void sendLight() { st().sendLight(this); }

    /** Высота колонки (x, z — любые, берутся младшие 4 бита) по HeightIndex: Y блока или HeightIndex.NONE. */
    default int height(HeightIndex.Kind kind, int x, int z) { return st().heights.get(kind, x, z); }

    /** Залить генератором все кубы, кроме skip (их возьмут с диска — CubeRegionLoader); afterLoad — за вызывающим. */
    default void fillGen(BitSet skip) {
        int chunkX = getChunkX(), chunkZ = getChunkZ(), min = TallWorld.MIN_Y >> 4;
        CubeGen g = ink.hoka.odyssey.server.tall.gen.CubeGens.of(getInstance());
        int n = getSections().size();
        for (int i = 0; i < n; i++) {
            if (skip != null && skip.get(i)) continue;
            int cy = i + min;
            if (!g.hasBlocks(chunkX, cy, chunkZ)) continue;
            CubeGen.Cube cube = CubeGen.generate(g, chunkX, cy, chunkZ);
            if (cube.empty()) continue;
            putSection(i, cube.section());
            for (var e : cube.specials().int2ObjectEntrySet()) {          // блоки с данными — обычным setBlock (Y ключа — в кубе)
                int k = e.getIntKey();
                lockWriteLock();
                try { setBlock(chunkX * 16 + (CoordConversion.chunkBlockIndexGetX(k) & 15), (cy << 4) + CoordConversion.chunkBlockIndexGetY(k),
                        chunkZ * 16 + (CoordConversion.chunkBlockIndexGetZ(k) & 15), e.getValue()); }
                finally { unlockWriteLock(); }
            }
        }
    }
}
