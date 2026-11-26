package ink.hoka.odyssey.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 0.63.5, F3 «Hoka под прицелом»: по лучу взгляда до 2000 блоков — первая клетка, где у клиента есть блок или где по
 * высотам сервера (op 17/18 → MOTION_BLOCKING) должна быть земля. Её секция: есть ли у клиента (блоки / пусто / нет
 * столбца), окно сервера или полоса, что о ней знает Sodium (окно столбца / полоса, построена, нарисована ли в кадре).
 * Наводишь прицел на щель — видно, кто её не нарисовал. Считается только при открытом F3.
 */
public final class AimProbe {

    private AimProbe() {}

    private static final double MAX = 2000, STEP = 0.5;
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    /** Счёт луча раз в 100 мс (F3 рисуется каждый кадр). */
    private static long at;
    private static String cached;

    public static String line() {
        if (!TallWorld.net) return null;
        Minecraft mc = Minecraft.getInstance();
        Level l = mc.level;
        if (l == null || mc.player == null || l.getSectionsCount() <= 64) return null;
        SodiumProbe.diagUntil = System.currentTimeMillis() + 1000;
        long now = System.nanoTime();
        if (cached != null && now - at < 100_000_000L) return cached;
        at = now;
        return cached = compute(mc, l);
    }

    private static String compute(Minecraft mc, Level l) {
        Vec3 eye = mc.player.getEyePosition(1f), d = mc.player.getViewVector(1f);
        int lbx = Integer.MIN_VALUE, lby = 0, lbz = 0, minY = l.getMinY(), maxY = l.getMaxY();
        LevelChunk c = null;
        long ck = Long.MIN_VALUE;
        int noCol = 0;
        for (double t = 0; t <= MAX; t += STEP) {
            int bx = (int) Math.floor(eye.x + d.x * t), by = (int) Math.floor(eye.y + d.y * t), bz = (int) Math.floor(eye.z + d.z * t);
            if (bx == lbx && by == lby && bz == lbz) continue;
            lbx = bx; lby = by; lbz = bz;
            if (by < minY || by > maxY) break;
            long k = net.minecraft.world.level.ChunkPos.asLong(bx >> 4, bz >> 4);
            if (k != ck) {
                ck = k;
                c = l.getChunkSource().getChunk(bx >> 4, bz >> 4, ChunkStatus.FULL, false) instanceof LevelChunk lc ? lc : null;
            }
            if (c == null) { noCol++; continue; }
            boolean block = !c.getBlockState(POS.set(bx, by, bz)).isAir();
            boolean ground = by < c.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15) + 1;
            if (block || ground)
                return describe(l, c, bx, by, bz, block, (int) Math.round(t));
        }
        return "Hoka под прицелом: до " + (int) MAX + " блоков ни блока, ни земли по высотам" + (noCol > 0 ? " (луч шёл и по незагруженным столбцам)" : "");
    }

    private static String describe(Level l, LevelChunk c, int bx, int by, int bz, boolean block, int dist) {
        int x = bx >> 4, y = by >> 4, z = bz >> 4;
        StringBuilder b = new StringBuilder("Hoka под прицелом: секция ").append(x).append(' ').append(y).append(' ').append(z)
                .append(" (").append(dist).append(" бл., ").append(block ? "блок" : "земля по высотам сервера").append(")");
        int idx = l.getSectionIndexFromSectionY(y);
        String cl;
        if (idx < 0 || idx >= c.getSections().length) cl = "вне мира";
        else if (!c.getSections()[idx].hasOnlyAir()) cl = "есть";
        else if (CubeCache.waiting(x, y, z)) cl = "пусто, ждёт кэш";
        else cl = "пусто — не пришло?";
        b.append(" — у клиента: ").append(cl);
        b.append(ContentRange.has(x, y, z) ? " (в счёте content)" : " (не в счёте content)");
        if (CubeReceiver.active(l)) {
            int sf = CubeReceiver.windowFrom(), st = CubeReceiver.windowTo();
            b.append("; сервер: окно ").append(sf).append("..").append(st)
                    .append(y >= sf && y <= st ? " — в окне" : y == sf - 1 || y == st + 1 ? " — кольцо ±1 (блоки шлёт)" : " — только полоса поверхности");
        }
        b.append("; окно Sodium ").append(ContentRange.winLo <= ContentRange.winHi ? ContentRange.winLo + ".." + ContentRange.winHi : "—");
        SodiumProbe.Probe p = SodiumProbe.probe;
        String s = p == null ? null : SodiumProbe.describe(p, x, y, z);
        b.append("; ").append(s != null ? s : "Sodium: нет данных");
        return b.toString();
    }
}
