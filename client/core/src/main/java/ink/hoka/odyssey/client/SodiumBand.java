package ink.hoka.odyssey.client;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.CoordinateSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.RenderSectionVisitor;
import net.minecraft.core.SectionPos;

/**
 * 0.63.2: проход по дереву кубов полосы поверхности (вне окна Sodium) после обхода графа — без графа видимости и без
 * секций пустого неба. Только отсечение по пирамиде взгляда (дерево Sodium). Секцию, уже отмеченную обходом графа в
 * этом кадре, не берём (иначе нарисовалась бы дважды). Используется только из SodiumTallMixin (Sodium стоит).
 */
public final class SodiumBand implements CoordinateSectionVisitor {

    private Long2ReferenceMap<RenderSection> sections;
    private RenderSectionVisitor out;
    private int frame, lo, hi, cx, cz, r2;
    public int visited;

    /** dist — дальность поиска Sodium (блоки): дальше по горизонтали (как цилиндр обхода графа) не берём. */
    public void begin(Long2ReferenceMap<RenderSection> sections, RenderSectionVisitor out, int frame, int lo, int hi,
                      int camX, int camZ, float dist) {
        this.sections = sections;
        this.out = out;
        this.frame = frame;
        this.lo = lo;
        this.hi = hi;
        cx = camX >> 4;
        cz = camZ >> 4;
        int r = (int) Math.ceil(dist / 16f) + 1;
        r2 = r * r;
        visited = 0;
    }

    public void end() { sections = null; out = null; }

    @Override
    public void visit(int x, int y, int z) {
        if (y >= lo && y <= hi) return;                                // окно — дело графа
        int dx = x - cx, dz = z - cz;
        if (dx * dx + dz * dz > r2) return;                            // за дальностью (туман)
        RenderSection s = sections.get(SectionPos.asLong(x, y, z));
        if (s == null || s.isDisposed() || s.getLastVisibleFrame() == frame) return;
        s.setLastVisibleFrame(frame);
        out.visit(s);
        visited++;
    }
}
