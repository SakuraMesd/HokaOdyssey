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
 *   <li>сущности в небе, где обход не ходит (и в режиме дерева), видимы — иначе культинг сущностей Sodium их прячет.</li>
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

    @Unique
    private boolean hoka$tall() { return level != null && level.getSectionsCount() > 64; }

    @Unique
    private static int hoka$radius() {
        return ink.hoka.odyssey.client.VerticalView.get() + HOKA_HYST;
    }

    /** Окно вокруг центра c: ±радиус, подрезанное по слоям с геометрией (камера c±(HYST-1) всегда внутри). */
    @Unique
    private void hoka$target(int c) {
        int r = hoka$radius();
        int a = c - r, b = c + r;
        if (ContentRange.cut) {
            a = Math.max(a, Math.min(c - HOKA_HYST, ContentRange.lo - 1));
            b = Math.min(b, Math.max(c + HOKA_HYST, ContentRange.hi + 1));
        }
        hoka$tLo = Math.max(level.getMinSectionY(), a);
        hoka$tHi = Math.min(level.getMaxSectionY(), b);
    }

    @Unique
    private void hoka$setWindow(int cy) {
        hoka$r = hoka$radius();
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
        ci.cancel();
    }

    @Inject(method = "onChunkRemoved(II)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void hoka$chunkRemoved(int x, int z, CallbackInfo ci) {
        if (!hoka$tall()) return;
        hoka$columns.remove(ChunkPos.asLong(x, z));
        if (hoka$lo <= hoka$hi) for (int y = hoka$lo; y <= hoka$hi; y++) onSectionRemoved(x, y, z);
        ci.cancel();
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
        if (!hoka$tall()) return;
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
        boolean shift = !had || Math.abs(cy - hoka$center) >= HOKA_HYST || hoka$radius() != hoka$r;
        boolean grow = had && hoka$winVer != ContentRange.version;
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
            // только изменившиеся ряды: ушедшие — убрать, новые — добавить
            for (int y = oldLo, e = Math.min(oldHi, lo - 1); y <= e; y++) onSectionRemoved(x, y, z);
            for (int y = Math.max(oldLo, hi + 1); y <= oldHi; y++) onSectionRemoved(x, y, z);
            for (int y = lo, e = Math.min(hi, oldLo - 1); y <= e; y++) onSectionAdded(x, y, z);
            for (int y = Math.max(lo, oldHi + 1); y <= hi; y++) onSectionAdded(x, y, z);
        }
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
        submitSectionTasks(now, budget, net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType.ZERO_FRAME_DEFER);
        submitSectionTasks(next, budget, net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType.ONE_FRAME_DEFER);
        submitSectionTasks(deferred, budget, net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType.INITIAL_BUILD);
        submitSectionTasks(deferred, budget, net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType.ALWAYS_DEFER);
        ci.cancel();
    }

    /** А2: камера в чистом небе (выше hi+1 / ниже lo-1) — обход по дереву непустых секций вместо графа. */
    @Inject(method = "isOutOfGraph", at = @At("HEAD"), cancellable = true, remap = false)
    private void hoka$outOfGraph(SectionPos cam, CallbackInfoReturnable<Boolean> cir) {
        hoka$tree = false;
        if (!ContentRange.cut || !hoka$tall()) return;
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
        if (!ContentRange.cut || !hoka$tall()) return;
        if (hoka$tree || y > ContentRange.hi || y < ContentRange.lo) cir.setReturnValue(true);
    }
}
