package ink.hoka.odyssey.client;

import ink.hoka.odyssey.client.mixin.LevelLightEngineAccessor;
import ink.hoka.odyssey.client.mixin.LightEngineAccessor;
import ink.hoka.odyssey.client.mixin.LightStorageAccessor;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;

/**
 * 0.49 (E, F): ванильные задачи света столбца в высоком мире — не по всей высоте (1876 секций), а только по слоям,
 * где у движка света есть состояние.
 * <ul>
 *   <li>Зеркало бита «есть данные» движков ({@code SectionState.hasData}): его ставит и снимает только
 *       {@code LevelLightEngine.updateSectionStatus} (оба движка сразу) — здесь для каждого столбца храним Y, где он
 *       стоит. Вызов «секция пустая» там, где бита нет, у движка ничего не меняет, — такие вызовы и пропускаем.</li>
 *   <li>enableChunkLight: все не общие секции — как у ванили; общие пустые ({@link SparseSections.Shared}) — только
 *       там, где бит стоит. Без ~3750 обращений к хеш-таблицам и 1876 объектов SectionPos на столбец.</li>
 *   <li>queueLightRemoval (Forget): из очередей данных убираем записи этого столбца (по самим очередям, они короткие),
 *       «пустая» — только по Y с битом.</li>
 *   <li>Пометка перестройки при включении света — только окно Sodium ∩ слои с геометрией.</li>
 * </ul>
 * Только главный поток, только высокий мир. Мир сменился — зеркало сначала (по движку).
 */
public final class TallLight {

    private TallLight() {}

    private static LevelLightEngine engine;
    /** Столбец → Y, где у движков стоит бит «есть данные». */
    private static final Long2ObjectOpenHashMap<IntOpenHashSet> DATA_Y = new Long2ObjectOpenHashMap<>();
    public static long fastEnable, fastForget;

    private static boolean tall(ClientLevel l) { return l != null && l.getSectionsCount() > 64; }

    /** 0.61: бит «есть данные» куба по зеркалу: 1/0, −1 — зеркало для мира не ведётся. Только главный поток. */
    static int bit(ClientLevel l, int x, int y, int z) {
        if (!tracked(l)) return -1;
        IntOpenHashSet s = DATA_Y.get(ChunkPos.asLong(x, z));
        return s != null && s.contains(y) ? 1 : 0;
    }

    /** Из LevelLightEngine.updateSectionStatus (HEAD): отследить бит. */
    public static void status(LevelLightEngine e, SectionPos p, boolean empty) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel l = mc.level;
        if (!tall(l) || l.getChunkSource().getLightEngine() != e || !mc.isSameThread()) return;
        if (e != engine) { DATA_Y.clear(); engine = e; }
        long k = ChunkPos.asLong(p.x(), p.z());
        if (empty) {
            IntOpenHashSet s = DATA_Y.get(k);
            if (s != null && s.remove(p.y()) && s.isEmpty()) DATA_Y.remove(k);
        } else {
            DATA_Y.computeIfAbsent(k, q -> new IntOpenHashSet(4)).add(p.y());
        }
    }

    /** Движок мира отслеживается с рождения (зеркалу можно верить). */
    private static boolean tracked(ClientLevel l) {
        return tall(l) && engine != null && l.getChunkSource().getLightEngine() == engine;
    }

    /**
     * enableChunkLight вместо ванили; false — делать как ваниль. dirty — пометка перестройки (окно, см. {@link #dirty}).
     */
    public static boolean enable(ClientLevel level, LevelChunk chunk, int cx, int cz) {
        if (!TallWorld.net || !tracked(level)) return false;
        LevelLightEngine e = level.getChunkSource().getLightEngine();
        LevelChunkSection[] secs = chunk.getSections();
        ChunkPos cp = chunk.getPos();
        for (int i = 0; i < secs.length; i++) {
            LevelChunkSection s = secs[i];
            if (s instanceof SparseSections.Shared) continue;
            e.updateSectionStatus(SectionPos.of(cp, level.getSectionYFromSectionIndex(i)), !SolidCubes.lit(level, cp.x, s));   // 0.61: сплошной — как пустой
        }
        IntOpenHashSet set = DATA_Y.get(cp.toLong());
        if (set != null) {
            for (int y : set.toIntArray()) {                              // updateSectionStatus меняет set
                int idx = level.getSectionIndexFromSectionY(y);
                if (idx >= 0 && idx < secs.length && secs[idx] instanceof SparseSections.Shared)
                    e.updateSectionStatus(SectionPos.of(cp, y), true);
            }
        }
        fastEnable++;
        // 0.50: только сам столбец (не 3×3). Скелет — воздух; соседи, уже собранные Sodium, этот столбец видели (Sodium
        // строит столбец, лишь когда загружены все 8 соседей), новый столбец Sodium соберёт сам при добавлении, кубы
        // помечают себя и грани соседей сами (SectionDirty). Свой столбец — на случай повторной присылки (старая сетка).
        // Без Sodium — как раньше, 3×3: ванильный отрисовщик соседей при добавлении столбца сам не пересобирает.
        // Повторно присланный столбец (скелет затёр кубы) открывает грани соседям — это помечает SparseLevelChunkMixin.
        int r = SODIUM ? 0 : 1;
        dirty(level, cx - r, level.getMinSectionY(), cz - r, cx + r, level.getMaxSectionY(), cz + r);
        return true;
    }

    private static final boolean SODIUM = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");

    /** Пометка перестройки столбца и соседей при включении света: окно камеры ∩ слои с геометрией. */
    public static void dirty(ClientLevel level, int x0, int y0, int z0, int x1, int y1, int z1) {
        if (y1 - y0 <= 64) { level.setSectionRangeDirty(x0, y0, z0, x1, y1, z1); return; }   // обычная высота — как у игры
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        int cy = SectionPos.blockToSectionCoord(cam.position().y);
        int r = Math.max(VerticalView.get(), VerticalView.surface()) + 2;   // 0.62: и полоса поверхности (режет ContentRange)
        int a = Math.max(y0, cy - r), b = Math.min(y1, cy + r);
        if (ContentRange.cut) {                                         // пустые слои воздуха перестраивать незачем
            a = Math.max(a, ContentRange.lo - 1);
            b = Math.min(b, ContentRange.hi + 1);
        }
        if (a <= b) level.setSectionRangeDirty(x0, a, z0, x1, b, z1);
    }

    /** Задача забывания света столбца вместо ванильной; false — как ваниль. Ставит её в очередь света сама. */
    public static boolean forget(ClientLevel level, ChunkPos cp) {
        if (!TallWorld.net || !tracked(level)) return false;
        level.queueLightUpdate(() -> {
            LevelLightEngine e = level.getLightEngine();
            e.setLightEnabled(cp, false);
            var acc = (LevelLightEngineAccessor) e;
            if (!dropQueued(acc.hoka$block(), cp) || !dropQueued(acc.hoka$sky(), cp)) {
                for (int i = e.getMinLightSection(); i < e.getMaxLightSection(); i++) {   // очередь длинная — как ваниль
                    SectionPos sp = SectionPos.of(cp, i);
                    e.queueSectionData(LightLayer.BLOCK, sp, null);
                    e.queueSectionData(LightLayer.SKY, sp, null);
                }
            }
            if (e == engine) {
                IntOpenHashSet set = DATA_Y.get(cp.toLong());
                if (set != null) for (int y : set.toIntArray()) e.updateSectionStatus(SectionPos.of(cp, y), true);
            } else {
                for (int i = level.getMinSectionY(); i <= level.getMaxSectionY(); i++) e.updateSectionStatus(SectionPos.of(cp, i), true);
            }
        });
        fastForget++;
        return true;
    }

    /** Убрать из очереди данных движка записи столбца; false — очередь слишком длинная, пусть пройдёт ваниль. */
    private static boolean dropQueued(LightEngine<?, ?> engine, ChunkPos cp) {
        if (engine == null) return true;
        var q = ((LightStorageAccessor) ((LightEngineAccessor) engine).hoka$storage()).hoka$queued();
        if (q.isEmpty()) return true;
        if (q.size() > 8192) return false;
        LongArrayList gone = null;
        for (LongIterator it = q.keySet().iterator(); it.hasNext(); ) {
            long p = it.nextLong();
            if (SectionPos.x(p) == cp.x && SectionPos.z(p) == cp.z) {
                if (gone == null) gone = new LongArrayList();
                gone.add(p);
            }
        }
        if (gone != null) for (int i = 0; i < gone.size(); i++) q.remove(gone.getLong(i));
        return true;
    }

    public static void reset() {
        DATA_Y.clear();
        engine = null;
    }

    public static String stats() {
        return "свет столбцов: быстро вкл " + fastEnable + ", забыто " + fastForget + ", столбцов с данными " + DATA_Y.size();
    }
}
