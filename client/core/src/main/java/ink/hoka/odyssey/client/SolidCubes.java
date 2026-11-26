package ink.hoka.odyssey.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 0.61: сплошные кубы (толща камня) в режиме «свет на клиенте». Куб, где в палитре только полные непрозрачные
 * несветящиеся блоки, ванильному движку света не нужен: внутри свет 0, грани соседи освещают своим светом, а данные
 * света кладёт LocalLight. Такому кубу НЕ ставим бит «есть данные» (updateSectionStatus): ни слоёв 2 КБ × 2 на куб и
 * соседей, ни обхода 27 состояний на вставку. Палитра со старыми записями (воздух после правки) — не сплошной
 * (осторожно). Правка, вносящая непрозрачное в сплошной куб, переводит его в обычный ({@link #beforeEdit}).
 * Только главный поток.
 */
public final class SolidCubes {

    private SolidCubes() {}

    public static long skipped, promoted;

    /** Режим, в котором сплошные кубы обходят ванильный свет. */
    static boolean on(Level l, int sectionX) {
        return l instanceof ClientLevel cl && LocalLight.on(cl) && cl.getSectionsCount() > 64 && !YardColumns.isYard(sectionX);
    }

    /** Полный непрозрачный блок без свечения. */
    static boolean opaque(BlockState s) {
        if (s == null || s.isAir() || s.getLightEmission() != 0) return false;
        int inf = LightInfo.info(s);
        return (inf & 15) >= 15 && s.isSolidRender() && s.canOcclude();
    }

    /** Куб сплошной (по палитре; прямая палитра maybeHas даёт true — не сплошной). */
    static boolean solid(LevelChunkSection s) {
        return s != null && !s.hasOnlyAir() && !s.maybeHas(st -> !opaque(st));
    }

    /** «Непустой» для ванильного движка света: не воздух и не сплошной (в режиме). */
    public static boolean lit(Level l, int sectionX, LevelChunkSection s) {
        if (s.hasOnlyAir()) return false;
        return !on(l, sectionX) || !solid(s);
    }

    /** LevelChunk.setBlockState (HEAD): правка в сплошной куб ставит не непрозрачное — вернуть кубу ванильный свет. */
    public static void beforeEdit(Level l, int x, int y, int z, LevelChunkSection s, BlockState now) {
        if (s == null || opaque(now) || !on(l, SectionPos.blockToSectionCoord(x)) || !solid(s)) return;
        if (!Minecraft.getInstance().isSameThread()) return;
        promoted++;
        ((ClientLevel) l).getChunkSource().getLightEngine().updateSectionStatus(
                SectionPos.of(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y), SectionPos.blockToSectionCoord(z)), false);
    }

    public static String stats() { return "сплошных без ванильного света " + skipped + " (возвращено правкой " + promoted + ")"; }
}
