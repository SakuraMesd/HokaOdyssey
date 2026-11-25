package ink.hoka.odyssey.client;

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Слои по высоте, где есть геометрия (план оптимизации, шаг 0). Для каждого слоя Y — сколько непустых кубов в
 * загруженных столбцах; lo..hi — от нижнего до верхнего непустого слоя. Пока ни одного непустого куба нет
 * (ещё не пришли) — диапазона нет ({@link #cut} = false), Sodium работает как раньше, полным окном.
 * <p>Пополняется: CubeReceiver.put (куб пришёл / стал пустым / выгружен), пакет столбца, первый блок в пустой
 * секции (копия при записи). Столбец забыт — его кубы убираются. Опустевшая от ломания секция остаётся в счёте —
 * это лишь чуть шире диапазон, не дыра. Только высокий мир (> 64 секций), только главный поток.</p>
 * <p>Используют миксины Sodium: SodiumOcclusionMixin (обход не уходит выше hi+1 / ниже lo-1), SodiumTallMixin
 * (подрезка окна, обход по дереву в чистом небе).</p>
 */
public final class ContentRange {

    private ContentRange() {}

    /** Диапазон есть (в мире есть хоть один непустой куб) — подрезать можно. Читается в горячем цикле Sodium. */
    public static boolean cut;
    /** Нижний и верхний непустой слой (секции); смысл — только при cut. */
    public static int lo = 1, hi = 0;
    /** Растёт при каждом изменении lo/hi/cut — Sodium по нему пересчитывает граф и окно. */
    public static int version;
    /** Окно секций Sodium (для F3), lo > hi — нет. */
    public static int winLo = 1, winHi = 0;

    private static Level level;
    private static int base;
    private static int[] count = new int[0];
    private static int total;
    /** Столбец → непустые Y. */
    private static final Long2ObjectOpenHashMap<IntOpenHashSet> COLS = new Long2ObjectOpenHashMap<>();

    /** Можно ли считать этот мир: наш сервер, клиентский мир, высокий. */
    private static boolean accept(Level l) {
        return TallWorld.net && l != null && l.isClientSide() && l.getSectionsCount() > 64;
    }

    /** Привязать к миру: другой мир — всё сначала. */
    private static void ensure(Level l) {
        if (l == level) return;
        clear();
        level = l;
        base = l.getMinSectionY();
        count = new int[l.getSectionsCount()];
    }

    private static void clear() {
        COLS.clear();
        count = new int[0];
        total = 0;
        level = null;
        if (cut) version++;
        cut = false;
        lo = 1; hi = 0;
    }

    /** Мир сменился / вышли. */
    public static void reset() {
        clear();
        winLo = 1; winHi = 0;
        version++;
    }

    /** Каждый кадр из Sodium: его мир не наш — забыть старое (диапазон чужого мира не годится). */
    public static void bind(Level l) {
        if (l != level && level != null) reset();
    }

    public static void add(Level l, int x, int y, int z) {
        if (!accept(l) || YardColumns.isYard(x)) return;
        ensure(l);
        int i = y - base;
        if (i < 0 || i >= count.length) return;
        if (!COLS.computeIfAbsent(ChunkPos.asLong(x, z), k -> new IntOpenHashSet(4)).add(y)) return;
        total++;
        if (count[i]++ != 0) return;
        if (!cut) { cut = true; lo = hi = y; version++; }
        else if (y < lo) { lo = y; version++; }
        else if (y > hi) { hi = y; version++; }
    }

    public static void remove(Level l, int x, int y, int z) {
        if (l != level || level == null) return;
        var s = COLS.get(ChunkPos.asLong(x, z));
        if (s == null || !s.remove(y)) return;
        if (s.isEmpty()) COLS.remove(ChunkPos.asLong(x, z));
        drop(y);
    }

    /** Секция стала такой: непустая — в счёт, пустая — из счёта. */
    public static void set(Level l, int x, int y, int z, LevelChunkSection s) {
        if (s.hasOnlyAir()) remove(l, x, y, z); else add(l, x, y, z);
    }

    private static void drop(int y) {
        total--;
        int i = y - base;
        if (--count[i] != 0) return;
        if (total == 0) { cut = false; lo = 1; hi = 0; version++; return; }
        if (y == lo) { while (lo < hi && count[lo - base] == 0) lo++; version++; }
        if (y == hi) { while (hi > lo && count[hi - base] == 0) hi--; version++; }
    }

    /** Столбец забыт — его кубы из счёта. */
    public static void forgetColumn(Level l, int x, int z) {
        if (l != level || level == null) return;
        var s = COLS.remove(ChunkPos.asLong(x, z));
        if (s == null) return;
        for (IntIterator it = s.iterator(); it.hasNext(); ) drop(it.nextInt());
    }

    /** Пакет столбца: всё прежнее забыть, непустые (не общие) секции — в счёт. */
    public static void column(Level l, int x, int z, LevelChunkSection[] secs) {
        if (!accept(l) || YardColumns.isYard(x)) return;
        ensure(l);
        forgetColumn(l, x, z);
        for (int i = 0; i < secs.length; i++) {
            LevelChunkSection s = secs[i];
            if (s instanceof SparseSections.Shared || s.hasOnlyAir()) continue;
            add(l, x, l.getSectionYFromSectionIndex(i), z);
        }
    }

    /** Строка F3; null — не высокий мир. */
    public static String debugLine() {
        if (!TallWorld.net) return null;
        Level l = Minecraft.getInstance().level;
        if (l == null || l.getSectionsCount() <= 64) return null;
        return "Hoka кубы: content " + (cut && level == l ? lo + ".." + hi : "—")
                + ", окно Sodium " + (winLo <= winHi ? winLo + ".." + winHi : "—")
                + ", кубов загружено " + (level == l ? total : 0)
                + ", пометок " + SectionDirty.marked + "/" + SectionDirty.flushed + " (мягких " + SectionDirty.softFlushed
                + ", ждут " + SectionDirty.pendingSoft() + ", пропуск " + SectionDirty.skipped + ", свет без перестр. " + ServerLight.sameLight + ")"
                + ", " + CubeQueue.stats() + ", кадр ~" + (FrameWatch.avgFrameNs() / 1000) + " мкс";
    }

    /**
     * Строка F3 о кубе под ногами (0.48): есть ли столбец, пуст ли куб у клиента, построен ли он у Sodium, сколько
     * кубов отброшено. Отличает «нет данных» (сервер не прислал) от «нет сетки» (клиент не перестроил). null — не высокий мир.
     */
    public static String feetLine() {
        if (!TallWorld.net) return null;
        Minecraft mc = Minecraft.getInstance();
        Level l = mc.level;
        if (l == null || mc.player == null || l.getSectionsCount() <= 64) return null;
        var bp = mc.player.blockPosition().below();
        int x = bp.getX() >> 4, y = bp.getY() >> 4, z = bp.getZ() >> 4;
        var c = l.getChunkSource().getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        String sec;
        if (c == null) sec = "столбца нет";
        else {
            int idx = l.getSectionIndexFromSectionY(y);
            sec = idx < 0 || idx >= c.getSections().length ? "вне мира" : c.getSections()[idx].hasOnlyAir() ? "куб пуст" : "куб с блоками";
        }
        Boolean b = SodiumProbe.built(x, y, z);
        return "Hoka под ногами " + x + " " + y + " " + z + ": " + sec
                + (b == null ? "" : b ? ", Sodium: построен" : ", Sodium: не построен")
                + (CubeReceiver.unloadedY(l, y) ? ", вне окна сервера" : "")
                + ", отброшено кубов " + CubeReceiver.dropped;
    }
}
