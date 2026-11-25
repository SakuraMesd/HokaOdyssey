package ink.hoka.odyssey.server.tall;

import net.minestom.server.network.packet.server.play.UpdateLightPacket;
import net.minestom.server.network.packet.server.play.data.LightData;

import java.util.BitSet;

/**
 * Общее состояние столбца высокой карты (шаг 9): высоты, «что менялось» для сохранения, свет и его рассылка.
 * Живёт и в TallChunk (.htw), и в CubeColumn (регионы кубов) — код хранения и CubeStreamer один на оба.
 */
final class ColumnState {

    /** Высоты колонок (skyTop/motion/surface) — для света, карт высот и /hocubes dump. */
    final HeightIndex heights = new HeightIndex();

    /** Кубы, менявшиеся с загрузки/последнего сохранения (индекс от нижнего) — регионы пишут только их. */
    private final BitSet dirtyCubes = new BitSet();
    /** Кубы, лежащие в регионе (CubeRegionLoader); null — не знаем (столбец не из региона): сохранять — все кубы. */
    volatile BitSet diskCubes;
    /** Менялся с загрузки/генерации (иначе на диск не пишем: сгенерируется таким же). */
    volatile boolean modified;
    /** Когда столбец последний раз кто-то видел (для выгрузки пустующих, Maps). */
    volatile long seenAt = System.currentTimeMillis();
    /** Свет столбца надо разослать заново — раз в тик, см. TallWorld.flushLight. */
    volatile boolean lightDirty;
    /** Свет, который видели игроки до правок (кубам рассылается только разница с ним), или null — не знаем. */
    private volatile LightData lightBefore;
    /** Последний посчитанный свет столбца (кубам — нарезка из него); сбрасывается при смене блока. */
    private volatile LightData lightCache;

    /** Шаг 13: кубы, в которых (как на диске) нет блоков с высотой вида k (CubeMark) — спуск по невыгруженному их пропускает. */
    private final BitSet[] clear = {new BitSet(), new BitSet(), new BitSet()};

    boolean clear(HeightIndex.Kind k, int i) { synchronized (clear) { return clear[k.ordinal()].get(i); } }

    /** Куб i: биты «нет вклада» из отметки (нет отметки — сброс: спуск по нему с запасом). */
    void setClear(int i, int mark) {
        synchronized (clear) {
            for (var k : HeightIndex.Kind.values()) clear[k.ordinal()].set(i, CubeMark.clear(mark, k));
        }
    }

    /** Биты из заголовка столбца (null — нет). */
    void loadClear(BitSet[] b) {
        synchronized (clear) {
            for (int k = 0; k < clear.length; k++) { clear[k].clear(); if (b != null) clear[k].or(b[k]); }
        }
    }

    /** Копия битов, только по кубам on (для заголовка). */
    BitSet[] clearFor(BitSet on) {
        synchronized (clear) {
            BitSet[] r = new BitSet[clear.length];
            for (int k = 0; k < clear.length; k++) { r[k] = (BitSet) clear[k].clone(); r[k].and(on); }
            return r;
        }
    }

    /**
     * Маски колонок неба кубов (CubeMark.skyCols) — как на диске: спуск skyTop по кубу не в памяти проходит колонки
     * без бита сразу (без маски — «верх куба» с запасом). Под замком {@link #clear}.
     */
    private final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<long[]> skyCols = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(0);

    /** Маска куба i (null — не знаем: убрать). */
    void setSkyCols(int i, long[] m) {
        synchronized (clear) { if (m == null || CubeMark.full(m)) skyCols.remove(i); else skyCols.put(i, m); }
    }

    /** В колонке c куба i есть блок с высотой SKY: 1 — да, 0 — нет, −1 — не знаем. */
    int skyCol(int i, int c) {
        synchronized (clear) {
            long[] m = skyCols.get(i);
            return m == null ? -1 : (int) ((m[c >> 6] >>> c) & 1);
        }
    }

    void loadSkyCols(java.util.Map<Integer, long[]> m) {
        synchronized (clear) { skyCols.clear(); if (m != null) skyCols.putAll(m); }
    }

    /** Тег масок по кубам on (для заголовка) или null. */
    String skyColsTag(BitSet on) { synchronized (clear) { return CubeMark.encodeCols(skyCols, on); } }

    void markDirty(int i) { synchronized (dirtyCubes) { dirtyCubes.set(i); } }

    /** Забрать набор изменённых кубов (очистить). */
    BitSet takeDirty() { synchronized (dirtyCubes) { var b = (BitSet) dirtyCubes.clone(); dirtyCubes.clear(); return b; } }

    /** Куб i менялся и ещё не сохранён. */
    boolean isDirty(int i) { synchronized (dirtyCubes) { return dirtyCubes.get(i); } }

    /** Сколько кубов столбца ждут сохранения. */
    int dirtyCount() { synchronized (dirtyCubes) { return dirtyCubes.cardinality(); } }

    /** Вернуть кубы в изменённые (сохранение не удалось). */
    void restoreDirty(BitSet b) { synchronized (dirtyCubes) { dirtyCubes.or(b); } }

    /** Блок/биом сменился в кубе i (−1 — вне мира): на диск; light — пересчитать и разослать свет. */
    void changed(TallColumn c, int i, boolean light) {
        modified = true;
        if (i >= 0) markDirty(i);
        if (!light) return;
        if (lightBefore == null) lightBefore = lightCache;             // свет до первой правки этого тика — сравнить при рассылке
        lightCache = null;
        if (!lightDirty) { lightDirty = true; TallWorld.lightDirty(c); }
    }

    /** Кубы загрузились (шаг 10): свет пересчитать и разослать разницу; на диск не надо. */
    void relight(TallColumn c) {
        // свет ни разу не считался с загрузки — значит, никому и не уходил (всё шлётся из кэша): слать разницу не с чем,
        // новый куб получит свой свет при отправке (CubeStreamer.drainReady). Иначе «до» = null — всё окно каждому.
        if (lightBefore == null && lightCache == null) return;
        if (lightBefore == null) lightBefore = lightCache;
        lightCache = null;
        if (!lightDirty) { lightDirty = true; TallWorld.lightDirty(c); }
    }

    LightData light(TallColumn c) {
        var l = lightCache;
        if (l == null) lightCache = l = ColumnLight.compute(c.getSections(), heights, c.getInstance().getCachedDimensionType().hasSkylight());
        return l;
    }

    /** Свет — пакетом всем, кто видит столбец (кубовая карта: каждому — разница в его окне). */
    void sendLight(TallColumn c) {
        lightDirty = false;
        if (TallWorld.isCubes(c.getInstance())) {
            var to = CubeStreamer.receivers(c);                         // и кольцо 33 (его нет в getViewers)
            if (to.isEmpty()) return;
            LightData before = lightBefore;
            lightBefore = null;
            for (var p : to) CubeStreamer.relight(p, c, before);
            return;
        }
        var viewers = c.getViewers();                                    // некубовая карта: столбцов мало, трекер — как было
        if (viewers.isEmpty()) return;
        lightBefore = null;
        var pkt = new UpdateLightPacket(c.getChunkX(), c.getChunkZ(), light(c));
        for (var p : viewers) p.sendPacket(pkt);
    }

    /** Столбец только что загружен/сгенерирован: как на диске, свет никому не слать. */
    void loaded() {
        lightCache = null;
        lightBefore = null;
        lightDirty = false;
        modified = false;
        takeDirty();
    }
}
