package ink.hoka.odyssey.server.tall;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Перевод высокой карты .htw → регионы кубов и сверка (CUBIC-CHUNKS.md §4.9, шаг 7). Карта при этом выгружена:
 * столбцы читаются во временный мир (не зарегистрирован, игроков нет).
 */
public final class CubeConvert {

    private CubeConvert() {}

    private static final Pattern HTW = Pattern.compile("(-?\\d+)\\.(-?\\d+)\\.htw");

    /** Итог: столбцов, записано кубов, не прочитано .htw, расхождений (и первые из них). */
    public record Result(int columns, int cubes, int broken, int mismatches, List<String> first) {}

    /** Столбцы (x, z) по файлам .htw папки. */
    static List<int[]> columns(Path htwDir) throws IOException {
        List<int[]> out = new ArrayList<>();
        if (!Files.isDirectory(htwDir)) return out;
        try (Stream<Path> s = Files.list(htwDir)) {
            for (Path p : s.toList()) {
                Matcher m = HTW.matcher(p.getFileName().toString());
                if (m.matches()) out.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
            }
        }
        return out;
    }

    private static Instance tempInstance(RegistryKey<DimensionType> key) {
        return new net.minestom.server.instance.InstanceContainer(java.util.UUID.randomUUID(), key);
    }

    /** .htw из htwDir → регионы в mapDir (cols/, cubes/). progress — раз в 500 столбцов. */
    public static Result convert(Path mapDir, Path htwDir, RegistryKey<DimensionType> key, Consumer<String> progress) throws IOException {
        Instance inst = tempInstance(key);
        TallLoader src = new TallLoader(htwDir);
        CubeRegionLoader dst = new CubeRegionLoader(mapDir, null);
        List<int[]> cols = columns(htwDir);
        int done = 0, cubes = 0, broken = 0;
        List<String> errs = new ArrayList<>();
        try {
            for (int[] xz : cols) {
                Chunk ch = src.loadChunk(inst, xz[0], xz[1]);
                if (!(ch instanceof TallColumn c)) { broken++; continue; }  // битый — TallLoader переименовал в .broken
                c.st().diskCubes = null;
                c.st().modified = true;
                try { cubes += dst.save(c); }
                catch (IOException | RuntimeException e) { errs.add(xz[0] + " " + xz[1] + ": " + e); }
                if (++done % 500 == 0) progress.accept("переведено столбцов " + done + " из " + cols.size());
            }
        } finally {
            dst.close();
        }
        return new Result(done, cubes, broken, errs.size(), errs.subList(0, Math.min(5, errs.size())));
    }

    /** Сверить блоки, биомы и блоки с данными: каждый столбец .htw против того, что дают регионы (+ генератор). */
    /**
     * Сжать регионы выгруженной карты: заголовки столбцов без кубов на диске (как у генератора) убрать, файлы
     * переписать без пустых секторов, пустые файлы удалить. Возвращает {было байт, стало байт, убрано заголовков}.
     */
    private static boolean hasFiles(Path d) {
        if (!java.nio.file.Files.isDirectory(d)) return false;
        try (var st = java.nio.file.Files.list(d)) { return st.findAny().isPresent(); }
        catch (IOException e) { return true; }
    }

    public static long[] compact(Path mapDir, Consumer<String> progress) throws IOException {
        long before = 0, after = 0, dropped = 0;
        // есть .htw (tall/): пустой заголовок нужен — без него ленивая миграция вернёт из .htw старые блоки столбца
        boolean htw = hasFiles(mapDir.resolve("tall"));
        for (String sub : new String[]{"cols", "cubes"}) {
            Path d = mapDir.resolve(sub);
            if (!java.nio.file.Files.isDirectory(d)) continue;
            java.util.List<Path> files;
            try (var st = java.nio.file.Files.list(d)) { files = st.filter(p -> p.toString().endsWith(".hcl") || p.toString().endsWith(".hcr")).toList(); }
            for (Path f : files) {
                before += java.nio.file.Files.size(f);
                Path tmp = f.resolveSibling(f.getFileName() + ".compact");
                java.nio.file.Files.deleteIfExists(tmp);
                int kept = 0;
                try (var src = ink.hoka.odyssey.server.tall.region.RegionFile.open(f, sub.equals("cols") ? 1024 : 4096, false)) {
                    if (src == null) continue;
                    try (var dst = ink.hoka.odyssey.server.tall.region.RegionFile.open(tmp, src.entries(), true)) {
                        for (int i = 0; i < src.entries(); i++) {
                            if (!src.has(i)) continue;
                            byte[] b = src.read(i);
                            if (sub.equals("cols")) {
                                var h = ink.hoka.odyssey.server.tall.region.ColumnHeader.decode(b);
                                if (h.onDisk.isEmpty() && !htw) { dropped++; continue; }
                            }
                            dst.write(i, b);
                            kept++;
                        }
                        dst.flush();
                    }
                }
                if (kept == 0) { java.nio.file.Files.deleteIfExists(tmp); java.nio.file.Files.delete(f); continue; }
                java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                after += java.nio.file.Files.size(f);
            }
            progress.accept(sub + ": готово");
        }
        return new long[]{before, after, dropped};
    }

    public static Result verify(Path mapDir, Path htwDir, RegistryKey<DimensionType> key, Consumer<String> progress) throws IOException {
        Instance inst = tempInstance(key);
        TallLoader src = new TallLoader(htwDir);
        CubeRegionLoader dst = new CubeRegionLoader(mapDir, null);
        List<int[]> cols = columns(htwDir);
        int done = 0, broken = 0, bad = 0;
        List<String> first = new ArrayList<>();
        try {
            for (int[] xz : cols) {
                int x = xz[0], z = xz[1];
                Chunk a = src.loadChunk(inst, x, z);
                if (!(a instanceof TallColumn ta)) { broken++; continue; }
                String diff;
                if (!dst.hasColumn(x, z)) {                               // нет записи — столбец как у генератора (так и хранится)
                    TallChunk g = TallChunk.generated(inst, x, z);
                    diff = compare(ta, g);
                    if (diff != null) diff = "нет в регионах, а от генератора отличается: " + diff;
                } else {
                    Chunk b = dst.loadChunk(inst, x, z);
                    diff = b instanceof TallColumn tb ? compare(ta, tb) : "регион не прочитан";
                }
                if (diff != null) { bad++; if (first.size() < 5) first.add(x + " " + z + ": " + diff); }
                if (++done % 500 == 0) progress.accept("сверено столбцов " + done + " из " + cols.size());
            }
        } finally {
            dst.close();
        }
        return new Result(done, 0, broken, bad, first);
    }

    /** Первое расхождение двух столбцов или null — одинаковые. */
    static String compare(TallColumn a, TallColumn b) {
        List<Section> sa = a.getSections(), sb = b.getSections();
        for (int i = 0; i < sa.size(); i++) {
            var pa = sa.get(i).blockPalette();
            var pb = sb.get(i).blockPalette();
            int cy = i + ((net.minestom.server.instance.Chunk) a).getMinSection();
            if (pa.count() == 0 && pb.count() == 0) {
                if (!CubeRegionLoader.samePalette(sa.get(i).biomePalette(), sb.get(i).biomePalette(), 4)) return "биомы куба Y " + cy;
                continue;
            }
            if (!CubeRegionLoader.samePalette(pa, pb, 16)) return "блоки куба Y " + cy + " (" + pa.count() + " / " + pb.count() + ")";
            if (!CubeRegionLoader.samePalette(sa.get(i).biomePalette(), sb.get(i).biomePalette(), 4)) return "биомы куба Y " + cy;
        }
        Map<Integer, Block> da = a.dataBlocks(), db = b.dataBlocks();
        if (!CubeRegionLoader.sameData(da, db)) return "блоки с данными (" + da.size() + " / " + db.size() + ")";
        return null;
    }

    /** Тип измерения карты для временного мира (только высокие карты; null — не высокая). */
    public static RegistryKey<DimensionType> key(String map) {
        return TallWorld.key(map);
    }
}
