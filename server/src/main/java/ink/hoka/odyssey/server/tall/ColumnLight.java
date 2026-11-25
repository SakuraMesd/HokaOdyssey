package ink.hoka.odyssey.server.tall;

import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.network.packet.server.play.data.LightData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Свет столбца высокой карты (вынесен из TallChunk, шаг 9 — общий для TallChunk и CubeColumn):
 * <ul>
 *   <li>Небо — 15 выше skyTop колонки (HeightIndex, предикат как у игры: вода и листва ослабляют), дальше растекание
 *       внутри секции с ослаблением max(1, lightBlock) — как у игры.</li>
 *   <li>Пустые секции не шлём вовсе: клиент над самой верхней секцией со светом считает небо полным (15), а пустую
 *       секцию ниже — по нижнему ряду ближайшей секции со светом над ней.</li>
 *   <li>Свет блоков (светокамень и т.п.) — только внутри своей секции (без перехода в соседние).</li>
 * </ul>
 */
final class ColumnLight {

    private ColumnLight() {}

    private static final int MIN_SECTION = TallWorld.MIN_Y >> 4;

    /** Свет столбца по секциям и высотам; hasSky — у измерения есть солнце. */
    static LightData compute(List<Section> secs, HeightIndex heights, boolean hasSky) {
        BitSet sky = new BitSet(), block = new BitSet(), emptySky = new BitSet(), emptyBlock = new BitSet();
        List<byte[]> skyArr = new ArrayList<>(), blockArr = new ArrayList<>();
        int n = secs.size();
        int top = -1;
        for (int i = n - 1; i >= 0; i--) if (secs.get(i).blockPalette().count() > 0) { top = i; break; }
        if (top < 0) return new LightData(sky, block, emptySky, emptyBlock, skyArr, blockArr);   // пусто: всё небо
        // небо (шаг 4): источник 15 — всё выше skyTop колонки (HeightIndex, предикат как у игры); дальше растекание
        // с ослаблением max(1, lightBlock) за шаг — вода и листва гасят по 1, полный блок не пропускает.
        // inc — нижний ряд последнего посчитанного массива: клиент повторяет его в пустых секциях ниже (derivedSky),
        // от него же светится верхний ряд следующей непустой секции — без ступеньки на стыке.
        int[] inc = new int[256];
        java.util.Arrays.fill(inc, 15);
        List<byte[]> skyRev = new ArrayList<>();
        List<Integer> skyIdx = new ArrayList<>();
        for (int i = top; i >= 0 && hasSky; i--) {
            Palette p = secs.get(i).blockPalette();
            if (p.count() == 0) continue;
            int base = (i + MIN_SECTION) << 4;
            byte[] lvl = new byte[4096];
            int[] inf = new int[4096];
            ArrayDeque<Integer> q = new ArrayDeque<>();
            for (int y = 15; y >= 0; y--) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int c = z << 4 | x, idx = y << 8 | c;
                int id = p.get(x, y, z);
                inf[idx] = id == 0 ? 0 : HeightIndex.info(id);
                if (base + y > heights.skyTop(c)) { lvl[idx] = 15; q.add(idx); }
            }
            for (int c = 0; c < 256; c++) {                                 // сверху — то, что пришло из секции выше
                int idx = 15 << 8 | c, f = inf[idx];
                if (lvl[idx] != 0 || inc[c] <= 1 || HeightIndex.lightBlock(f) >= 15
                        || HeightIndex.full(f, net.minestom.server.instance.block.BlockFace.TOP)) continue;
                int l = inc[c] - Math.max(1, HeightIndex.lightBlock(f));
                if (l > 0) { lvl[idx] = (byte) l; q.add(idx); }
            }
            while (!q.isEmpty()) {
                int idx = q.poll(), l = lvl[idx];
                if (l <= 1) continue;
                int x = idx & 15, z = (idx >> 4) & 15, y = idx >> 8;
                for (var f : FACES) {
                    int nx = x + f.dx, ny = y + f.dy, nz = z + f.dz;
                    if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny < 0 || ny > 15) continue;
                    int n2 = ny << 8 | nz << 4 | nx, fb = inf[n2];
                    int lb = HeightIndex.lightBlock(fb);
                    if (lb >= 15 || HeightIndex.full(inf[idx], f.face) || HeightIndex.full(fb, f.face.getOppositeFace())) continue;
                    int nl = l - Math.max(1, lb);
                    if (nl <= lvl[n2]) continue;
                    lvl[n2] = (byte) nl;
                    q.add(n2);
                }
            }
            byte[] arr = new byte[2048];
            for (int k = 0; k < 4096; k++) if (lvl[k] > 0) set(arr, k & 15, k >> 8, (k >> 4) & 15, lvl[k]);
            for (int c = 0; c < 256; c++)                                   // низ с полной нижней гранью (верхняя плита) — вниз не светит
                inc[c] = HeightIndex.full(inf[c], net.minestom.server.instance.block.BlockFace.BOTTOM) ? 0 : lvl[c];
            skyIdx.add(i);
            skyRev.add(arr);
        }
        for (int k = skyIdx.size() - 1; k >= 0; k--) { sky.set(skyIdx.get(k) + 1); skyArr.add(skyRev.get(k)); }   // по возрастанию
        // свет блоков — внутри секции
        for (int i = 0; i <= top; i++) {
            Palette p = secs.get(i).blockPalette();
            if (p.count() == 0) continue;
            byte[] arr = blockLight(p);
            if (arr == null) continue;
            block.set(i + 1);
            blockArr.add(arr);
        }
        return new LightData(sky, block, emptySky, emptyBlock, skyArr, blockArr);
    }

    /** Шесть направлений растекания: сдвиг и грань, через которую уходит свет. */
    private record Dir(int dx, int dy, int dz, net.minestom.server.instance.block.BlockFace face) {}
    private static final Dir[] FACES = {
            new Dir(1, 0, 0, net.minestom.server.instance.block.BlockFace.EAST), new Dir(-1, 0, 0, net.minestom.server.instance.block.BlockFace.WEST),
            new Dir(0, 0, 1, net.minestom.server.instance.block.BlockFace.SOUTH), new Dir(0, 0, -1, net.minestom.server.instance.block.BlockFace.NORTH),
            new Dir(0, -1, 0, net.minestom.server.instance.block.BlockFace.BOTTOM), new Dir(0, 1, 0, net.minestom.server.instance.block.BlockFace.TOP)};

    /** Свет блоков в секции (заливка от источников внутри неё) или null — источников нет. */
    private static byte[] blockLight(Palette p) {
        byte[] lvl = new byte[4096];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int id = p.get(x, y, z);
            if (id == 0) continue;
            Block b = Block.fromStateId(id);
            int e = b == null ? 0 : b.registry().lightEmission();
            if (e > 0) { lvl[y << 8 | z << 4 | x] = (byte) e; q.add(new int[]{x, y, z}); }
        }
        if (q.isEmpty()) return null;
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            int l = lvl[c[1] << 8 | c[2] << 4 | c[0]];
            if (l <= 1) continue;
            for (int[] o : d) {
                int x = c[0] + o[0], y = c[1] + o[1], z = c[2] + o[2];
                if (x < 0 || y < 0 || z < 0 || x > 15 || y > 15 || z > 15) continue;
                int idx = y << 8 | z << 4 | x;
                if (lvl[idx] >= l - 1) continue;
                int id = p.get(x, y, z);
                if (id != 0) {
                    Block b = Block.fromStateId(id);
                    if (b != null && b.registry().occludes()) continue;
                }
                lvl[idx] = (byte) (l - 1);
                q.add(new int[]{x, y, z});
            }
        }
        byte[] arr = new byte[2048];
        for (int i = 0; i < 4096; i++) if (lvl[i] > 0) set(arr, i & 15, i >> 8, (i >> 4) & 15, lvl[i]);
        return arr;
    }

    private static void set(byte[] arr, int x, int y, int z, int v) {
        int i = y << 8 | z << 4 | x;
        int b = i >> 1;
        arr[b] = (i & 1) == 0 ? (byte) ((arr[b] & 0xF0) | (v & 0xF)) : (byte) ((arr[b] & 0x0F) | ((v & 0xF) << 4));
    }
}
