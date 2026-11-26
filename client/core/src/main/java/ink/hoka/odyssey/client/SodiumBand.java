package ink.hoka.odyssey.client;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.CoordinateSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.RenderSectionVisitor;
import net.minecraft.core.SectionPos;

/**
 * 0.63.2: проход по дереву кубов полосы поверхности (вне окна Sodium) после обхода графа — без графа видимости и без
 * секций пустого неба. Только отсечение по пирамиде взгляда (дерево Sodium). Секцию, уже нарисованную обходом графа в
 * этом кадре, не берём (иначе нарисовалась бы дважды). Используется только из SodiumTallMixin (Sodium стоит).
 * <p>0.63.5: «нарисована графом» для рядов вне окна — по {@link Graph#drawn}, а не по getLastVisibleFrame. Sodium
 * ставит кадр секции, когда кладёт её в очередь обхода (visitNode), а дальность (|dy| &lt; дальности поиска) проверяет
 * уже при разборе очереди. Ряд полосы сразу над окном (winHi+1) граф клал в очередь из верхнего ряда окна и отбрасывал
 * по высоте (наш выход за вертикаль — только для рядов окна), а полоса пропускала его как «уже отмеченный графом» —
 * никто не рисовал: щель в 1 куб по склону горы на высоте winHi+1 (и так же winLo-1).</p>
 */
public final class SodiumBand implements CoordinateSectionVisitor {

    /**
     * Обёртка посетителя обхода графа: секции рядов вне окна, которые граф действительно отдал на отрисовку (прошли
     * дальность и пирамиду), — в {@link #drawn}. Таких мало (только кубы полосы у края окна), рядов окна не копим.
     */
    public static final class Graph implements RenderSectionVisitor {
        private RenderSectionVisitor out;
        private int lo, hi;
        public final ReferenceOpenHashSet<RenderSection> drawn = new ReferenceOpenHashSet<>();

        public Graph begin(RenderSectionVisitor out, int lo, int hi) {
            this.out = out;
            this.lo = lo;
            this.hi = hi;
            drawn.clear();
            return this;
        }

        public void end() { out = null; }

        @Override
        public void visit(RenderSection s) {
            int y = s.getChunkY();
            if (y < lo || y > hi) drawn.add(s);
            out.visit(s);
        }
    }

    public final Graph graph = new Graph();
    /** Отладка: секции, нарисованные полосой в последнем кадре (только при открытом F3). */
    public final ReferenceOpenHashSet<RenderSection> drawnBand = new ReferenceOpenHashSet<>();
    /** Кадр последнего прохода. */
    public int lastFrame = Integer.MIN_VALUE;

    private Long2ReferenceMap<RenderSection> sections;
    private RenderSectionVisitor out;
    private int frame, lo, hi, cx, cz, r2;
    private boolean diag;
    public int visited;

    /** dist — дальность поиска Sodium (блоки): дальше по горизонтали (как цилиндр обхода графа) не берём. */
    public void begin(Long2ReferenceMap<RenderSection> sections, RenderSectionVisitor out, int frame, int lo, int hi,
                      int camX, int camZ, float dist) {
        this.sections = sections;
        this.out = out;
        this.frame = frame;
        lastFrame = frame;
        this.lo = lo;
        this.hi = hi;
        cx = camX >> 4;
        cz = camZ >> 4;
        int r = (int) Math.ceil(dist / 16f) + 1;
        r2 = r * r;
        visited = 0;
        diag = System.currentTimeMillis() < SodiumProbe.diagUntil;
        if (!drawnBand.isEmpty()) drawnBand.clear();
    }

    public void end() { sections = null; out = null; }

    @Override
    public void visit(int x, int y, int z) {
        // 0.63.4: без «окно — дело графа»: в дереве только секции вне окна СВОЕГО столбца, а столбец при сдвиге окна
        // догоняет его порциями (SodiumTallMixin.hoka$migrate) — его секция полосы уже может быть в рядах нового окна.
        int dx = x - cx, dz = z - cz;
        if (dx * dx + dz * dz > r2) return;                            // за дальностью (туман)
        RenderSection s = sections.get(SectionPos.asLong(x, y, z));
        if (s == null || s.isDisposed()) return;
        // ряды окна: граф проходит их без предела по высоте (SodiumOcclusionMixin) — отмечен = нарисован;
        // вне окна: отмечен ≠ нарисован (0.63.5) — смотрим, отдал ли граф её на отрисовку
        if (y >= lo && y <= hi ? s.getLastVisibleFrame() == frame : graph.drawn.contains(s)) return;
        s.setLastVisibleFrame(frame);
        out.visit(s);
        visited++;
        if (diag) drawnBand.add(s);
    }
}
