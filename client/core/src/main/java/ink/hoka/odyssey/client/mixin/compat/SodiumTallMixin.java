package ink.hoka.odyssey.client.mixin.compat;

import ink.hoka.odyssey.client.ContentRange;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.tree.RemovableMultiForest;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium в высоком мире (≫64 секций по высоте): отрисовочные секции — только в окне по высоте вокруг камеры, а не
 * на всю высоту столбца (иначе >50% кадра в RenderRegion). Сверх того (план оптимизации, шаги 2 и 4):
 * <ul>
 *   <li>окно подрезано по слоям с геометрией (ContentRange): от камеры до lo-1..hi+1, не дальше «Дальности по
 *       высоте»; пока кубов нет — полное;</li>
 *   <li>гистерезис: окно сдвигается, когда камера ушла от центра на 4+ секции (было 2); радиус окна на столько же
 *       больше — дальность по высоте не теряется;</li>
 *   <li>пришли кубы выше/ниже — окно только расширяется (сужение — при сдвиге), граф пересчитывается;</li>
 *   <li>камера в чистом небе (выше hi+1 / ниже lo-1) — обход по дереву непустых секций (TreeSectionCollector)
 *       вместо графа видимости (А2), а секция, которой назначена перестройка, кладётся в дерево (А3) — иначе
 *       куб, пришедший в бывшую пустую секцию, на этом пути никогда бы не собрался;</li>
 *   <li>сущности в небе, где обход не ходит (и в режиме дерева), видимы — иначе культинг сущностей Sodium их прячет;</li>
 *   <li>0.54: непустых кубов нет вовсе (ContentRange.empty) — окно 3 секции у камеры и не сдвигается, обход по
 *       дереву; первый непустой куб — окно сразу встаёт по камере с подрезкой.</li>
 *   <li>0.62 «горы целиком»: сервер шлёт и полосу поверхности дальних столбцов (до ±«Горы целиком» кубов) — окно
 *       тянется за слоями с геометрией до этого предела (общее на все столбцы: обход Sodium идёт к вершине по воздуху
 *       над долинами). Пустое небо выше самой высокой загруженной вершины в окно не входит (подрезка ContentRange).</li>
 *   <li>0.63.2: окно снова только ±V вокруг камеры. Кубы полосы поверхности вне окна — отдельные секции Sodium лишь там,
 *       где куб непустой (ContentRange), без секций пустого неба над долинами; рисуются проходом по своему дереву
 *       (SodiumBand) после обхода графа. Было: окно до вершин во всех столбцах — ~550 тыс. секций в обходе каждый кадр.</li>
 * </ul>
 * Обычные миры не трогаем. Миксин необязательный (@Pseudo, require 0).
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class SodiumTallMixin {

    /** Гистерезис сдвига окна (секции). */
    @Unique private static final int HOKA_HYST = 4;

    @Shadow(remap = false) @Final private ClientLevel level;

    @Shadow(remap = false) @Final private RemovableMultiForest renderableSectionTree;

    @Shadow(remap = false) public abstract void onSectionAdded(int x, int y, int z);

    @Shadow(remap = false) public abstract void onSectionRemoved(int x, int y, int z);

    @Shadow(remap = false) public abstract void markGraphDirty();

    @Shadow(remap = false) @Final private it.unimi.dsi.fastutil.longs.Long2ReferenceMap<RenderSection> sectionByPosition;

    @Shadow(remap = false)
    private boolean upgradePendingUpdate(RenderSection section, int type) { throw new AssertionError(); }

    /** ChunkUpdateTypes.INITIAL_BUILD. */
    @Unique private static final int HOKA_INITIAL_BUILD = 8;

    /** Столбцы, которые Sodium считает загруженными. */
    @Unique private final LongOpenHashSet hoka$columns = new LongOpenHashSet();
    /** Текущее окно по высоте (секции), его центр и радиус; lo > hi — ещё не задано. */
    @Unique private int hoka$lo = 1, hoka$hi = 0, hoka$center, hoka$r;
    /** Версия ContentRange, под которую пересчитан граф / окно. */
    @Unique private int hoka$graphVer = Integer.MIN_VALUE, hoka$winVer = Integer.MIN_VALUE;
    /** Последний пересчёт шёл по дереву (камера в чистом небе). */
    @Unique private boolean hoka$tree;
    /** Результат hoka$target. */
    @Unique private int hoka$tLo, hoka$tHi;
    /** 0.54: окно задано под пустой мир (ContentRange.empty) — 3 секции вокруг камеры. */
    @Unique private boolean hoka$empty;

    @Unique
    private boolean hoka$tall() { return level != null && level.getSectionsCount() > 64; }

    @Unique
    private static int hoka$radius() {
        return ink.hoka.odyssey.client.VerticalView.get() + HOKA_HYST;
    }

    /** Ключ настроек окна: сменились — окно заново. */
    @Unique
    private static int hoka$key() {
        return hoka$radius();
    }

    /**
     * Окно вокруг центра c: ±радиус, подрезанное по слоям с геометрией (камера c±(HYST-1) всегда внутри). 0.63.2: полоса
     * поверхности сюда больше не входит — она отдельными секциями (hoka$extra), окно к вершинам не тянется.
     */
    @Unique
    private void hoka$target(int c) {
        int r = hoka$radius();
        int a = c - r, b = c + r;
        if (ContentRange.empty(level)) { a = c - 1; b = c + 1; }      // 0.54: кубов нет — рисовать нечего
        else if (ContentRange.cut) {                                   // до слоёв с геометрией, не дальше ±r
            a = Math.max(c - r, Math.min(c - HOKA_HYST, ContentRange.lo - 1));
            b = Math.min(c + r, Math.max(c + HOKA_HYST, ContentRange.hi + 1));
        }
        hoka$tLo = Math.max(level.getMinSectionY(), a);
        hoka$tHi = Math.min(level.getMaxSectionY(), b);
    }

    @Unique
    private void hoka$setWindow(int cy) {
        hoka$r = hoka$key();
        hoka$empty = ContentRange.empty(level);
        hoka$center = cy;
        hoka$target(cy);
        hoka$lo = hoka$tLo;
        hoka$hi = hoka$tHi;
        hoka$publish();
    }

    @Unique
    private void hoka$publish() {
        ContentRange.winLo = hoka$lo;
        ContentRange.winHi = hoka$hi;
    }

    @Unique
    private void hoka$ensureWindow() {
        if (hoka$lo <= hoka$hi) return;
        var p = Minecraft.getInstance().player;
        hoka$winVer = ContentRange.version;
        hoka$setWindow(p != null ? SectionPos.blockToSectionCoord(p.getBlockY()) : 0);
    }

    @Inject(method = "onChunkAdded(II)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void hoka$chunkAdded(int x, int z, CallbackInfo ci) {
        if (!hoka$tall()) return;
        hoka$ensureWindow();
        hoka$columns.add(ChunkPos.asLong(x, z));
        for (int y = hoka$lo; y <= hoka$hi; y++) onSectionAdded(x, y, z);
        var ys = ContentRange.ys(x, z);                                // 0.63.2: непустые кубы вне окна — свои секции
        if (ys != null)
            for (int y : ys.toIntArray()) if (y < hoka$lo || y > hoka$hi) hoka$addExtra(x, y, z, true);
        ci.cancel();
    }

    @Inject(method = "onChunkRemoved(II)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void hoka$chunkRemoved(int x, int z, CallbackInfo ci) {
        if (!hoka$tall()) return;
        long col = ChunkPos.asLong(x, z);
        hoka$columns.remove(col);
        if (hoka$lo <= hoka$hi) for (int y = hoka$lo; y <= hoka$hi; y++) onSectionRemoved(x, y, z);
        var ex = hoka$extra.remove(col);
        if (ex != null)
            for (int y : ex.toIntArray()) { hoka$band().remove(x, y, z); onSectionRemoved(x, y, z); }
        ci.cancel();
    }

    // ───────────── 0.63.2: кубы полосы поверхности вне окна ─────────────

    @Shadow(remap = false) @Final private int renderDistance;

    /** Столбец → Y секций вне окна, заведённых под непустые кубы (полоса поверхности). */
    @Unique private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.IntOpenHashSet> hoka$extra =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    /** Дерево этих секций (обход по пирамиде взгляда, без графа). */
    @Unique private RemovableMultiForest hoka$bandTree;
    @Unique private final ink.hoka.odyssey.client.SodiumBand hoka$bandVisitor = new ink.hoka.odyssey.client.SodiumBand();

    @Unique
    private RemovableMultiForest hoka$band() {
        if (hoka$bandTree == null) hoka$bandTree = new RemovableMultiForest(renderDistance);
        return hoka$bandTree;
    }

    @Unique
    private boolean hoka$isExtra(int x, int y, int z) {
        var s = hoka$extra.get(ChunkPos.asLong(x, z));
        return s != null && s.contains(y);
    }

    /** Секция вне окна под непустой куб. create — завести секцию Sodium (false — она уже есть: ушла из окна). */
    @Unique
    private void hoka$addExtra(int x, int y, int z, boolean create) {
        if (!hoka$extra.computeIfAbsent(ChunkPos.asLong(x, z), k -> new it.unimi.dsi.fastutil.ints.IntOpenHashSet(4)).add(y)) return;
        if (create) onSectionAdded(x, y, z);
        hoka$band().add(x, y, z);
    }

    /** Убрать из учёта. delete — и секцию Sodium (false — она остаётся: вошла в окно). */
    @Unique
    private void hoka$dropExtra(int x, int y, int z, boolean delete) {
        long col = ChunkPos.asLong(x, z);
        var s = hoka$extra.get(col);
        if (s == null || !s.remove(y)) return;
        if (s.isEmpty()) hoka$extra.remove(col);
        hoka$band().remove(x, y, z);
        if (delete) onSectionRemoved(x, y, z);
    }

    /** Свести секцию вне окна с ContentRange: куб непустой — секция есть, пустой/забыт — нет. */
    @Unique
    private void hoka$syncExtra(int x, int y, int z) {
        if (y >= hoka$lo && y <= hoka$hi) return;                     // окно — своё
        if (!hoka$columns.contains(ChunkPos.asLong(x, z))) return;     // столбца у Sodium нет — заведётся в onChunkAdded
        if (ContentRange.has(x, y, z)) hoka$addExtra(x, y, z, true);
        else hoka$dropExtra(x, y, z, true);
    }

    /** Изменения непустоты кубов за кадр → секции вне окна. */
    @Unique
    private void hoka$drainDirty() {
        var d = ContentRange.DIRTY;
        if (hoka$lo > hoka$hi) { d.clear(); ContentRange.overflow = false; return; }   // окна нет — столбцов тоже
        if (ContentRange.overflow) {                                   // очередь переполнилась / включили — сверка всех столбцов
            ContentRange.overflow = false;
            d.clear();
            var it = hoka$columns.iterator();
            while (it.hasNext()) {
                long c = it.nextLong();
                int x = ChunkPos.getX(c), z = ChunkPos.getZ(c);
                var ex = hoka$extra.get(c);
                if (ex != null) for (int y : ex.toIntArray()) hoka$syncExtra(x, y, z);
                var ys = ContentRange.ys(x, z);
                if (ys != null) for (int y : ys.toIntArray()) hoka$syncExtra(x, y, z);
            }
            return;
        }
        if (d.isEmpty()) return;
        for (int i = 0, n = d.size(); i < n; i++) {
            long k = d.getLong(i);
            hoka$syncExtra(SectionPos.x(k), SectionPos.y(k), SectionPos.z(k));
        }
        d.clear();
    }

    /** Срез lo/hi/cut, под который последний раз пересчитан граф (0.49), и время пересчёта. */
    @Unique private boolean hoka$gCut;
    @Unique private int hoka$gLo = 1, hoka$gHi = 0;
    @Unique private long hoka$gAt;

    /**
     * Каждый кадр (до обновления графа): чужой мир — диапазон забыть; диапазон слоёв изменился — пересчитать граф.
     * 0.49: не на каждый рост версии. Включился/выключился срез — сразу; диапазон расширился (кубы выше hi / ниже lo —
     * обход должен туда пойти) — не чаще раза в 100 мс; только сузился (лишь лишние пустые слои в обходе) — раз в 1 с.
     * При прогрузке края lo/hi дёргаются постоянно — раньше граф пересчитывался каждый кадр даже без движения камеры.
     */
    @Inject(method = "prepareFrame", at = @At("HEAD"), remap = false)
    private void hoka$frame(Vector3dc camera, CallbackInfo ci) {
        ContentRange.bind(level);                                      // и в обычном мире: старый диапазон не годится
        if (!hoka$tall()) { ContentRange.track = false; return; }
        if (!ContentRange.track) { ContentRange.track = true; ContentRange.overflow = true; }   // включили — сверка всего
        hoka$drainDirty();
        int v = ContentRange.version;
        if (v == hoka$graphVer) return;
        boolean cut = ContentRange.cut;
        int lo = ContentRange.lo, hi = ContentRange.hi;
        if (cut == hoka$gCut && (!cut || lo == hoka$gLo && hi == hoka$gHi)) { hoka$graphVer = v; return; }
        long now = System.nanoTime();
        if (cut == hoka$gCut) {
            boolean grew = lo < hoka$gLo || hi > hoka$gHi;
            if (now - hoka$gAt < (grew ? 100_000_000L : 1_000_000_000L)) return;   // позже: версия не принята
        }
        hoka$graphVer = v;
        hoka$gCut = cut; hoka$gLo = lo; hoka$gHi = hi; hoka$gAt = now;
        markGraphDirty();
    }

    /** Пересчёт графа: камера ушла от центра окна на HYST+ секций / сменили дальность — сдвинуть окно; пришли кубы — расширить. */
    @Inject(method = "update", at = @At("HEAD"), remap = false)
    private void hoka$update(Camera camera, @Coerce Object viewport, @Coerce Object fog, boolean spectator, CallbackInfo ci) {
        if (!hoka$tall()) return;
        int cy = SectionPos.blockToSectionCoord(camera.position().y);
        boolean had = hoka$lo <= hoka$hi;
        boolean empty = ContentRange.empty(level);
        // 0.54: пустой мир — окно не двигаем (полёт по высоте не трогает секции); пустой ↔ непустой — сразу по центру камеры
        boolean shift = !had || empty != hoka$empty
                || !empty && (Math.abs(cy - hoka$center) >= HOKA_HYST || hoka$key() != hoka$r);
        boolean grow = had && !empty && hoka$winVer != ContentRange.version;
        if (!shift && !grow) return;
        hoka$winVer = ContentRange.version;
        int oldLo = hoka$lo, oldHi = hoka$hi;
        if (shift) hoka$setWindow(cy);
        else {                                                         // только расширение: сужение — при сдвиге
            hoka$target(hoka$center);
            hoka$lo = Math.min(oldLo, hoka$tLo);
            hoka$hi = Math.max(oldHi, hoka$tHi);
            hoka$publish();
        }
        if (!had) return;
        int lo = hoka$lo, hi = hoka$hi;
        if (lo == oldLo && hi == oldHi) return;
        var it = hoka$columns.iterator();
        while (it.hasNext()) {
            long c = it.nextLong();
            int x = ChunkPos.getX(c), z = ChunkPos.getZ(c);
            // только изменившиеся ряды: ушедшие — убрать (непустой куб остаётся секцией полосы), новые — добавить
            for (int y = oldLo, e = Math.min(oldHi, lo - 1); y <= e; y++) hoka$leave(x, y, z);
            for (int y = Math.max(oldLo, hi + 1); y <= oldHi; y++) hoka$leave(x, y, z);
            for (int y = lo, e = Math.min(hi, oldLo - 1); y <= e; y++) hoka$enter(x, y, z);
            for (int y = Math.max(lo, oldHi + 1); y <= hi; y++) hoka$enter(x, y, z);
        }
    }

    /** Ряд ушёл из окна: непустой куб — остаётся секцией вне окна (с сеткой), иначе секцию убрать. */
    @Unique
    private void hoka$leave(int x, int y, int z) {
        if (ContentRange.has(x, y, z)) hoka$addExtra(x, y, z, false);
        else onSectionRemoved(x, y, z);
    }

    /** Ряд вошёл в окно: была секцией вне окна — теперь обычная, иначе завести. */
    @Unique
    private void hoka$enter(int x, int y, int z) {
        if (hoka$isExtra(x, y, z)) hoka$dropExtra(x, y, z, false);
        else onSectionAdded(x, y, z);
    }

    /**
     * К1 (0.48): Sodium принимает пометку перестройки только у уже построенной секции (isBuilt). Пока первая сборка
     * секции в полёте (задача сняла копию данных и сбросила пометку, built ещё false), всё, что пришло за это время —
     * куб, свет op 11, грань соседа, — выбрасывалось, и секция навсегда оставалась со старой сеткой («полоса»/дыра
     * до случайной перестройки, например при сдвиге окна в полёте). Здесь такая секция получает повторную первую
     * сборку: после задачи в полёте будет ещё одна, уже с новыми данными. Тело ванили не отменяем (оно сбросит кэш
     * копий секции); если пометка уже стоит — join ничего не меняет.
     */
    @Inject(method = "scheduleRebuild", at = @At("HEAD"), remap = false)
    private void hoka$rebuildUnbuilt(int x, int y, int z, boolean important, CallbackInfo ci) {
        if (!hoka$tall()) return;
        RenderSection s = sectionByPosition.get(SectionPos.asLong(x, y, z));
        if (s != null && !s.isBuilt() && !s.isDisposed()) upgradePendingUpdate(s, HOKA_INITIAL_BUILD);
    }

    @Shadow(remap = false)
    private void submitSectionTasks(net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector collector,
                                    net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget budget,
                                    net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType type) { throw new AssertionError(); }

    /**
     * 0.50: порядок очередей задач в высоком мире — ZERO → ONE → INITIAL_BUILD → ALWAYS_DEFER (у Sodium первые сборки
     * идут ПОСЛЕ обычных перестроек, обе делят один бюджет). Секции без сетки (вернулись к пролетённому, сдвиг окна) —
     * это дыры, строятся раньше повторных сборок уже показанного. Раз за кадр, без CallbackInfoReturnable.
     */
    @Inject(method = "submitSectionTasks(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/estimation/UploadResourceBudget;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void hoka$taskOrder(net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector now,
                                net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector next,
                                net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector deferred,
                                net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget budget,
                                CallbackInfo ci) {
        if (!hoka$tall()) return;
        hoka$nearFirst();
        submitSectionTasks(now, budget, net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType.ZERO_FRAME_DEFER);
        submitSectionTasks(next, budget, net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType.ONE_FRAME_DEFER);
        submitSectionTasks(deferred, budget, net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType.INITIAL_BUILD);
        submitSectionTasks(deferred, budget, net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType.ALWAYS_DEFER);
        ci.cancel();
    }

    @Shadow(remap = false) private java.util.Map<net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType,
            java.util.ArrayDeque<RenderSection>> taskLists;

    @Shadow(remap = false) private Vector3dc cameraPosition;

    /** Сколько кубов вокруг камеры (по каждой оси) строится вне очереди. */
    @Unique private static final int HOKA_NEAR = 2;
    @Unique private final java.util.ArrayList<RenderSection> hoka$near = new java.util.ArrayList<>();

    /**
     * 0.63.2: кубы в ±2 вокруг камеры — первыми среди отложенных сборок: из INITIAL_BUILD и ALWAYS_DEFER (туда идёт и
     * перестройка пустой секции, куда пришёл куб) — в голову INITIAL_BUILD (она у нас раньше ALWAYS_DEFER). Тип задачи
     * берётся из пометки секции, очередь — только порядок. Под землёй вокруг игрока не висят чёрные дыры, пока
     * строятся 128 дальних секций.
     */
    @Unique
    private void hoka$nearFirst() {
        Vector3dc c = cameraPosition;
        if (c == null || taskLists == null) return;
        var init = taskLists.get(net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType.INITIAL_BUILD);
        var defer = taskLists.get(net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType.ALWAYS_DEFER);
        if (init == null || defer == null || init.isEmpty() && defer.isEmpty()) return;
        int cx = SectionPos.blockToSectionCoord(c.x()), cy = SectionPos.blockToSectionCoord(c.y()), cz = SectionPos.blockToSectionCoord(c.z());
        var near = hoka$near;
        near.clear();
        hoka$takeNear(init, near, cx, cy, cz);
        hoka$takeNear(defer, near, cx, cy, cz);
        for (int i = near.size() - 1; i >= 0; i--) init.addFirst(near.get(i));   // в голову, в прежнем порядке
        near.clear();
    }

    @Unique
    private static void hoka$takeNear(java.util.ArrayDeque<RenderSection> q, java.util.ArrayList<RenderSection> near, int cx, int cy, int cz) {
        if (q.isEmpty()) return;
        for (var it = q.iterator(); it.hasNext(); ) {
            RenderSection s = it.next();
            if (Math.abs(s.getChunkX() - cx) <= HOKA_NEAR && Math.abs(s.getChunkY() - cy) <= HOKA_NEAR
                    && Math.abs(s.getChunkZ() - cz) <= HOKA_NEAR) {
                it.remove();
                near.add(s);
            }
        }
    }

    /** А2: камера в чистом небе (выше hi+1 / ниже lo-1) — обход по дереву непустых секций вместо графа. */
    @Inject(method = "isOutOfGraph", at = @At("HEAD"), cancellable = true, remap = false)
    private void hoka$outOfGraph(SectionPos cam, CallbackInfoReturnable<Boolean> cir) {
        hoka$tree = false;
        if (!hoka$tall()) return;
        if (ContentRange.empty(level)) {                               // 0.54: дерево пусто, пришедший куб положит A3
            hoka$tree = true;
            cir.setReturnValue(true);
            return;
        }
        if (!ContentRange.cut) return;
        int y = cam.y();
        if (y > ContentRange.hi + 1 || y < ContentRange.lo - 1) {
            hoka$tree = true;
            cir.setReturnValue(true);
        }
    }

    /** А3: секции назначена перестройка — в дерево (пустая секция в дереве не лежит, путь по дереву её бы не собрал). */
    @Inject(method = "upgradePendingUpdate", at = @At("RETURN"), remap = false)
    private void hoka$pendingToTree(RenderSection section, int type, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !hoka$tall()) return;
        renderableSectionTree.add(section.getChunkX(), section.getChunkY(), section.getChunkZ());
    }

    /** Культинг сущностей: в небе вне слоёв с геометрией обход не ходит, по дереву — не отмечает; там сущности видимы. */
    @Inject(method = "isSectionVisible", at = @At("HEAD"), cancellable = true, remap = false)
    private void hoka$entityVisible(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (!hoka$tall()) return;
        if (ContentRange.empty(level)) { cir.setReturnValue(true); return; }   // 0.54: пустой мир — обхода нет
        if (!ContentRange.cut) return;
        // 0.63.2: вне окна секций пустого неба нет (полоса — только непустые кубы) — там сущности видимы
        if (hoka$tree || y > ContentRange.hi || y < ContentRange.lo
                || hoka$lo <= hoka$hi && (y > hoka$hi || y < hoka$lo)) cir.setReturnValue(true);
    }

    /**
     * 0.63.2: после обхода графа (окно ±V) — кубы полосы поверхности вне окна по своему дереву, по пирамиде взгляда.
     * Глубоко под землёй (неба нет и над камерой больше 2 кубов) — не рисуем: из пещеры гор не видно. Секции полосы,
     * уже отмеченные графом (прилегают к окну), второй раз не берутся (SodiumBand). Идут после графа — и в очередь
     * сборки тоже последними.
     */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "createTerrainRenderList", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/occlusion/OcclusionCuller;findVisible("
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/RenderSectionVisitor;"
                            + "Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;FZI)V"))
    private void hoka$bandPass(net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller culler,
                               net.caffeinemc.mods.sodium.client.render.chunk.lists.RenderSectionVisitor visitor,
                               net.caffeinemc.mods.sodium.client.render.viewport.Viewport viewport, float dist, boolean occlusion,
                               int frame, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        original.call(culler, visitor, viewport, dist, occlusion, frame);
        if (hoka$bandTree == null || hoka$extra.isEmpty() || !hoka$tall() || hoka$lo > hoka$hi) return;
        var t = viewport.getTransform();
        if (level.getBrightness(net.minecraft.world.level.LightLayer.SKY, new net.minecraft.core.BlockPos(t.intX, t.intY, t.intZ)) == 0
                && level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, t.intX, t.intZ) > t.intY + 32) return;
        hoka$bandTree.prepareForTraversal();
        var v = hoka$bandVisitor;
        v.begin(sectionByPosition, visitor, frame, hoka$lo, hoka$hi, t.intX, t.intZ, dist);
        try {
            hoka$bandTree.traverse(v, viewport, dist);
        } finally {
            v.end();
        }
    }
}
