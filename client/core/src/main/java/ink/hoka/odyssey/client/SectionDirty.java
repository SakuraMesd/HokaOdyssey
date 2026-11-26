package ink.hoka.odyssey.client;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Пометки перестройки секций в высоком мире (план, шаг 5, первый пункт). Вместо «куб + 26 соседей» на каждый
 * пришедший куб и на каждый его свет — сам куб, а соседей только со стороны граней, где изменился крайний слой
 * (блоков, биомов или света). Пометки куба и его света за кадр сливаются в один набор и уходят в отрисовку разом
 * ({@link #flush}: после света в renderLevel и в начале кадра). Только главный поток.
 * <p>0.50 (шторм перестроек): пометки двух видов.</p>
 * <ul>
 *   <li><b>обязательные</b> — сам куб (смена блоков, в т.ч. в воздух; выгрузка) и соседи за гранями, которые
 *       ОТКРЫЛИСЬ (было не воздух → стало другое): без них дыра. Уходят в этом же кадре; сосед-воздух пропускается —
 *       у воздуха нет сетки (его собственная смена — всегда «сам куб»);</li>
 *   <li><b>мягкие</b> — соседи за гранями, которые только ЗАКРЫЛИСЬ (был воздух → стал блок: у соседа лишь лишняя
 *       невидимая грань) и соседи по свету. Копятся до {@link #SOFT_NS} и уходят пачкой (приход 8 соседей — одна
 *       перестройка, а не 8–24); рядом с камерой (≤2 секции) — сразу. Воздух, вне окна Sodium, незагруженный столбец —
 *       пропуск.</li>
 * </ul>
 * Диагональные соседи — по И граней (ребро лежит в обеих гранях): точность та же, лишних меньше.
 */
public final class SectionDirty {

    private SectionDirty() {}

    /** Биты граней: -X +X -Y +Y -Z +Z, сам куб; ALL — куб и все 26 соседей. */
    public static final int NX = 1, PX = 2, NY = 4, PY = 8, NZ = 16, PZ = 32, SELF = 64, ALL = 127;
    /** Код пометки: биты 0..6 — обязательные (как выше), биты 8..13 — мягкие грани (FACES << SOFT). */
    public static final int SOFT = 8, FACES = 63;
    /** Мягкие пометки копятся не дольше этого. */
    static final long SOFT_NS = 250_000_000L;

    /** Обязательные: сам куб. */
    private static final LongOpenHashSet SET = new LongOpenHashSet();
    /** Обязательные: соседи за открывшимися гранями (воздух — пропуск). */
    private static final LongOpenHashSet NEAR = new LongOpenHashSet();
    /** Мягкие. */
    private static final LongOpenHashSet SOFTS = new LongOpenHashSet();
    private static long softAt;
    /** Отладка (F3): сколько пометок пришло и сколько ушло в отрисовку после слияния; мягких ушло; пропущено (воздух/вне окна). */
    public static long marked, flushed, softFlushed, skipped;

    /** Пометить куб и соседей: code — обязательные биты 0..6 и мягкие грани << SOFT. */
    public static void mark(int x, int y, int z, int code) {
        int hard = code & ALL, soft = (code >> SOFT) & FACES;
        if (hard == 0 && soft == 0) return;
        marked++;
        if ((hard & SELF) != 0) SET.add(SectionPos.asLong(x, y, z));
        int hf = hard & FACES, any = hf | soft;
        if (any == 0) return;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx | dy | dz) == 0) continue;
                    // сосед читает только крайние блоки куба со своей стороны: у диагонального это ребро/угол, он
                    // лежит в КАЖДОЙ из граней по ненулевым смещениям — изменился, значит изменились все эти грани
                    int need = (dx < 0 ? NX : dx > 0 ? PX : 0) | (dy < 0 ? NY : dy > 0 ? PY : 0) | (dz < 0 ? NZ : dz > 0 ? PZ : 0);
                    if ((any & need) != need) continue;
                    long p = SectionPos.asLong(x + dx, y + dy, z + dz);
                    if ((hf & need) == need) NEAR.add(p);
                    else if (SOFTS.add(p) && camKnown && Math.abs(x + dx - camX) <= 2 && Math.abs(y + dy - camY) <= 2
                            && Math.abs(z + dz - camZ) <= 2) URGENT.add(p);   // у камеры — в ближайший сброс
                }
    }

    /**
     * 0.63.6: мягкие у камеры (≤2 секции) — список, а не перебор всего SOFTS на каждом сбросе (их тысячи, сброс —
     * дважды за кадр: 6–7% главного потока в полёте). Камера перешла в другую секцию — один полный перебор, как было.
     */
    private static final it.unimi.dsi.fastutil.longs.LongArrayList URGENT = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private static int camX, camY, camZ;
    private static boolean camKnown;

    /** Свет куба: сам куб — обязательно (если есть), соседи за гранями m — мягко. */
    public static void markLight(int x, int y, int z, int m) {
        mark(x, y, z, (m & SELF) | (m & FACES) << SOFT);
    }

    /** Отдать накопленное в отрисовку (Sodium / ванильный LevelRenderer). */
    public static void flush() {
        if (SET.isEmpty() && NEAR.isEmpty() && SOFTS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        var lr = mc.levelRenderer;
        ClientLevel level = mc.level;
        if (lr == null || level == null) { clear(); return; }
        if (!SET.isEmpty()) {
            for (LongIterator it = SET.iterator(); it.hasNext(); ) {
                long p = it.nextLong();
                lr.setSectionDirty(SectionPos.x(p), SectionPos.y(p), SectionPos.z(p));
                NEAR.remove(p);
                SOFTS.remove(p);
            }
            flushed += SET.size();
            SET.clear();
        }
        if (!NEAR.isEmpty()) {
            for (LongIterator it = NEAR.iterator(); it.hasNext(); ) {
                long p = it.nextLong();
                SOFTS.remove(p);
                if (air(level, p)) { skipped++; continue; }
                lr.setSectionDirty(SectionPos.x(p), SectionPos.y(p), SectionPos.z(p));
                flushed++;
            }
            NEAR.clear();
        }
        if (SOFTS.isEmpty()) { softAt = System.nanoTime(); URGENT.clear(); return; }
        long now = System.nanoTime();
        boolean due = now - softAt >= SOFT_NS;
        var cam = mc.gameRenderer.getMainCamera().position();
        int cx = SectionPos.blockToSectionCoord(cam.x), cy = SectionPos.blockToSectionCoord(cam.y), cz = SectionPos.blockToSectionCoord(cam.z);
        boolean moved = !camKnown || cx != camX || cy != camY || cz != camZ;
        camX = cx; camY = cy; camZ = cz; camKnown = true;
        boolean win = ContentRange.winLo <= ContentRange.winHi;
        if (due || moved) {                                            // полный перебор: срок вышел / камера в новой секции
            URGENT.clear();
            for (LongIterator it = SOFTS.iterator(); it.hasNext(); ) {
                long p = it.nextLong();
                int x = SectionPos.x(p), y = SectionPos.y(p), z = SectionPos.z(p);
                if (!due && (Math.abs(x - cx) > 2 || Math.abs(y - cy) > 2 || Math.abs(z - cz) > 2)) continue;
                it.remove();
                softOne(lr, level, win, p);
            }
        } else if (!URGENT.isEmpty()) {                                // только новые у камеры
            for (int i = 0, n = URGENT.size(); i < n; i++) {
                long p = URGENT.getLong(i);
                if (SOFTS.remove(p)) softOne(lr, level, win, p);
            }
            URGENT.clear();
        }
        if (due) softAt = now;
    }

    private static void softOne(net.minecraft.client.renderer.LevelRenderer lr, ClientLevel level, boolean win, long p) {
        int x = SectionPos.x(p), y = SectionPos.y(p), z = SectionPos.z(p);
        if (win && (y < ContentRange.winLo || y > ContentRange.winHi) || air(level, p)) { skipped++; return; }
        lr.setSectionDirty(x, y, z);
        flushed++;
        softFlushed++;
    }

    /** Секция — воздух (или столбца нет): сетки у неё нет и от соседей не появится. */
    private static boolean air(ClientLevel level, long p) {
        LevelChunk c = level.getChunkSource().getChunk(SectionPos.x(p), SectionPos.z(p), ChunkStatus.FULL, false);
        if (c == null) return true;
        int idx = level.getSectionIndexFromSectionY(SectionPos.y(p));
        LevelChunkSection[] s = c.getSections();
        return idx < 0 || idx >= s.length || s[idx].hasOnlyAir();
    }

    /** Секция куба — воздух (для пометок света: у воздуха нет сетки). */
    public static boolean isAir(ClientLevel level, int x, int y, int z) {
        return air(level, SectionPos.asLong(x, y, z));
    }

    public static void clear() { SET.clear(); NEAR.clear(); SOFTS.clear(); URGENT.clear(); camKnown = false; }

    public static int pendingSoft() { return SOFTS.size(); }

    /**
     * Какие грани куба изменились при замене секции a → b (блоки и биомы); 0 — ничего. Код как у {@link #mark}:
     * грань, где все изменившиеся крайние блоки были воздухом (грань у соседа только закрылась), — мягкая.
     */
    public static int blockFaces(LevelChunkSection a, LevelChunkSection b) {
        if (a == b) return 0;
        int m = 0;
        boolean aAir = a.hasOnlyAir();
        if (!(aAir && b.hasOnlyAir())) {
            if (aAir || b.hasOnlyAir()) {
                // всё из воздуха — все изменения «закрылись»; всё в воздух — все «открылись»
                int f = 0;
                for (int i = 0; i < 16 && f != FACES; i++)
                    for (int j = 0; j < 16; j++) {
                        if ((f & NX) == 0 && a.getBlockState(0, i, j) != b.getBlockState(0, i, j)) f |= NX;
                        if ((f & PX) == 0 && a.getBlockState(15, i, j) != b.getBlockState(15, i, j)) f |= PX;
                        if ((f & NY) == 0 && a.getBlockState(i, 0, j) != b.getBlockState(i, 0, j)) f |= NY;
                        if ((f & PY) == 0 && a.getBlockState(i, 15, j) != b.getBlockState(i, 15, j)) f |= PY;
                        if ((f & NZ) == 0 && a.getBlockState(i, j, 0) != b.getBlockState(i, j, 0)) f |= NZ;
                        if ((f & PZ) == 0 && a.getBlockState(i, j, 15) != b.getBlockState(i, j, 15)) f |= PZ;
                        if (f == FACES) break;
                    }
                m = aAir ? SELF | f << SOFT : SELF | f;
            } else {
                int hard = 0, soft = 0;
                for (int i = 0; i < 16 && hard != FACES; i++)
                    for (int j = 0; j < 16; j++) {
                        int c;
                        if ((hard & NX) == 0 && (c = cmp(a.getBlockState(0, i, j), b.getBlockState(0, i, j))) != 0) { if (c == 2) hard |= NX; else soft |= NX; }
                        if ((hard & PX) == 0 && (c = cmp(a.getBlockState(15, i, j), b.getBlockState(15, i, j))) != 0) { if (c == 2) hard |= PX; else soft |= PX; }
                        if ((hard & NY) == 0 && (c = cmp(a.getBlockState(i, 0, j), b.getBlockState(i, 0, j))) != 0) { if (c == 2) hard |= NY; else soft |= NY; }
                        if ((hard & PY) == 0 && (c = cmp(a.getBlockState(i, 15, j), b.getBlockState(i, 15, j))) != 0) { if (c == 2) hard |= PY; else soft |= PY; }
                        if ((hard & NZ) == 0 && (c = cmp(a.getBlockState(i, j, 0), b.getBlockState(i, j, 0))) != 0) { if (c == 2) hard |= NZ; else soft |= NZ; }
                        if ((hard & PZ) == 0 && (c = cmp(a.getBlockState(i, j, 15), b.getBlockState(i, j, 15))) != 0) { if (c == 2) hard |= PZ; else soft |= PZ; }
                        if (hard == FACES) break;
                    }
                m = SELF | hard | (soft & ~hard) << SOFT;
            }
        }
        // биомы: смешение цветов травы/воды у соседей читает биомы куба — изменились, помечаем всех (обязательно)
        if (a.getBiomes() != b.getBiomes()) {
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++)
                    for (int k = 0; k < 4; k++)
                        if (!a.getNoiseBiome(i, j, k).equals(b.getNoiseBiome(i, j, k)))
                            // 0.54: воздух → воздух (пустой куб неба пришёл/выгружен): у самого сетки нет — только соседи
                            // (воздух среди них flush пропустит); было ALL — перестройка пустой секции на каждый куб
                            return m == 0 && a.hasOnlyAir() && b.hasOnlyAir() ? FACES : ALL;
        }
        return m;
    }

    /** 0 — тот же блок, 1 — был воздух (грань у соседа только закрылась), 2 — иначе (могла открыться). */
    private static int cmp(BlockState a, BlockState b) {
        if (a == b) return 0;
        return a.isAir() ? 1 : 2;
    }
}
