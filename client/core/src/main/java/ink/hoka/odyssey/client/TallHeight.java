package ink.hoka.odyssey.client;

import ink.hoka.odyssey.client.mixin.HeightmapAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.function.Predicate;

/**
 * 0.56: карта высот MOTION_BLOCKING высокого мира — по ней ваниль решает, где идёт дождь/снег (WeatherEffectRenderer,
 * Level.precipitationAt). Сервер даёт её в скелете столбца, но кубы (op 2/13) кладутся мимо LevelChunk.setBlockState —
 * ваниль высоту от них не поднимала: на ленивой карте скелет уходит раньше, чем куб сгенерирован, и дождь шёл сквозь
 * летающий остров / крышу коробки, пришедшие кубами.
 * <ul>
 *   <li>Куб пришёл: колонки, где верх был в этом кубе или ниже, — верх по блокам куба (выше — поднять; верх был тут,
 *       а блоков не стало — спуск по известным кубам, см. {@link #below}).</li>
 *   <li>Куб ушёл из окна (op 4) — высоту НЕ трогаем: у сервера блоки остались (остров над окном держит сухо).</li>
 *   <li>Правки блоков — ванильный Heightmap.update (как было).</li>
 *   <li>Страховка для неба выше окна (о нём клиент блоков не знает): {@link #rainTop} — небо у верха дождя &lt; 15
 *       (свет от сервера) — над колонкой что-то есть, дождя в ней нет.</li>
 * </ul>
 * Только главный поток, только высокий мир на нашем сервере.
 */
public final class TallHeight {

    private TallHeight() {}

    private static final Predicate<BlockState> BLOCKS = Heightmap.Types.MOTION_BLOCKING.isOpaque();
    public static long raised, lowered, coveredCols;

    private static boolean tall(Level l) { return TallWorld.net && l != null && l.isClientSide() && l.getSectionsCount() > 64; }

    /** Куб sy столбца c сменился на sec (из CubeReceiver.put). gone — op 4: высоту не трогать. */
    static void cube(ClientLevel level, LevelChunk c, int sy, LevelChunkSection sec, boolean gone) {
        if (gone || !tall(level) || YardColumns.isYard(c.getPos().x)) return;
        Heightmap hm = c.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        int y0 = sy << 4;
        boolean any = !sec.hasOnlyAir() && sec.maybeHas(BLOCKS);
        // 0.63.6: сперва — есть ли колонки, которые куб меняет (обычно нет: куб ниже верха); верхи куба — одним
        // проходом по сетевой форме секции (SectionIds), а не 16 getBlockState на колонку (7% главного потока в полёте)
        int[] hs = HS;
        boolean need = false;
        for (int i = 0; i < 256; i++) {
            int h = hm.getFirstAvailable(i & 15, i >> 4);
            hs[i] = h;
            if (h <= y0 + 16 && (any || h > y0)) need = true;
        }
        if (!need) return;
        int[] tops = any ? SectionIds.tops(sec, BLOCKS) : null;
        for (int i = 0; i < 256; i++) {
            int h = hs[i];                                              // верхний блок + 1
            if (h > y0 + 16) continue;                                  // верх выше куба — куб его не меняет
            int x = i & 15, z = i >> 4;
            int top = !any ? -1 : tops != null ? tops[i] : topIn(sec, x, z, 15);
            int now;
            if (top >= 0) now = y0 + top + 1;
            else if (h > y0) now = below(level, c, sy, x, z);           // верх был в этом кубе, блоков не стало
            else continue;                                              // верх ниже, в кубе пусто — как было
            if (now == h) continue;
            ((HeightmapAccessor) hm).hoka$setHeight(x, z, now);
            if (now > h) raised++; else lowered++;
        }
    }

    /** Высоты колонок текущего куба (только главный поток). */
    private static final int[] HS = new int[256];

    public static long serverCols;

    /**
     * op 17 HEIGHTS (сервер 0.56+): MOTION_BLOCKING столбцов целиком от сервера — он знает и кубы вне окна (остров
     * выше окна, крыша коробки). По очереди CubeQueue, после скелета; последнее пришедшее — верное.
     */
    static void heights(ClientLevel level, int[] d) {
        if (!tall(level) || d == null) return;
        int lo = level.getMinY(), hi = level.getMaxY() + 1;
        for (int b = 0; b + 258 <= d.length; b += 258) {
            int cx = d[b], cz = d[b + 1];
            if (YardColumns.isYard(cx)) continue;                       // верфь — свой мир клиента
            if (!(level.getChunkSource().getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) instanceof LevelChunk c)) continue;
            Heightmap hm = c.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
            for (int i = 0; i < 256; i++) {
                int h = Math.max(lo, Math.min(hi, d[b + 2 + i]));
                int x = i & 15, z = i >> 4;
                if (hm.getFirstAvailable(x, z) != h) ((HeightmapAccessor) hm).hoka$setHeight(x, z, h);
            }
            serverCols++;
        }
    }

    /** Верхний блок колонки (x, z) куба не выше from; -1 — нет. */
    private static int topIn(LevelChunkSection s, int x, int z, int from) {
        for (int y = from; y >= 0; y--) if (BLOCKS.test(s.getBlockState(x, y, z))) return y;
        return -1;
    }

    /**
     * Новый верх колонки под кубом sy: вниз по кубам окна (общий пустой в окне — воздух, дальше); куб вне окна —
     * неизвестно, что там у сервера: верх — его верх (дождь кончится на краю окна, игроку его не видно).
     */
    private static int below(ClientLevel level, LevelChunk c, int sy, int x, int z) {
        LevelChunkSection[] secs = c.getSections();
        for (int s = sy - 1; s >= level.getMinSectionY(); s--) {
            int idx = level.getSectionIndexFromSectionY(s);
            if (idx < 0 || idx >= secs.length) break;
            LevelChunkSection sec = secs[idx];
            boolean air = sec instanceof SparseSections.Shared || sec.hasOnlyAir();
            if (CubeReceiver.unloadedY(level, s) && air) return (s + 1) << 4;
            if (air || !sec.maybeHas(BLOCKS)) continue;
            int t = topIn(sec, x, z, 15);
            if (t >= 0) return (s << 4) + t + 1;
        }
        return level.getMinY();
    }

    // ───────────── страховка дождя над окном ─────────────

    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    /**
     * Высота для осадков в колонке (x, z) при камере на camY и радиусе r; h — по карте высот. Если верх дождя
     * (camY + r, не выше верха окна) — воздух с небом &lt; 15 по свету сервера, то над колонкой есть укрытие, которого
     * клиент не знает (остров выше окна): вернуть camY + r + 1 — ваниль колонку пропустит. Иначе h.
     */
    public static int rainTop(Level level, int x, int z, int h, int camY, int r) {
        if (!(level instanceof ClientLevel cl) || !tall(cl) || ServerLight.level != cl || !CubeReceiver.active(cl)) return h;
        int top = camY + r;
        if (h > top) return h;
        int sy = Math.min(top, (CubeReceiver.windowTo() << 4) + 15);
        if (sy < h || sy < CubeReceiver.windowFrom() << 4) return h;
        POS.set(x, sy, z);
        if (!cl.getBlockState(POS).isAir()) return h;                   // внутри блока свет 0 — ни о чём не говорит
        if (cl.getBrightness(LightLayer.SKY, POS) >= 15) return h;
        coveredCols++;
        return top + 1;
    }

    public static String stats() {
        return "высоты: поднято " + raised + ", опущено " + lowered + ", от сервера (op 17) столбцов " + serverCols + ", колонок под укрытием " + coveredCols;
    }
}
