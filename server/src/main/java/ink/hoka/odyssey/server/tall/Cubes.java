package ink.hoka.odyssey.server.tall;

import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Кубы для ядра и модулей (CUBIC-CHUNKS.md §4.10). Шаг 6 — поверх столбцов; шаг 10 — по-настоящему: на карте с
 * ленивой загрузкой (CubeStore) куб загружен = столбец в памяти и куб READY, loadCubes грузит столбцы и кубы рамки,
 * закрепление сразу грузит рамку и не даёт выгрузить её столбцы (Maps.reapColumns). На остальных картах (.htw,
 * store: cubes без ленивой загрузки) — как раньше: куб загружен = загружен столбец.
 * OdysseyServerApi отдаёт модулям эти же методы. Рамки — блоки, границы включительно, в любом порядке.
 */
public final class Cubes {

    private Cubes() {}

    /** Закреплённая рамка в кубах (включительно). */
    public record Pin(Instance instance, int cx0, int cy0, int cz0, int cx1, int cy1, int cz1) {
        public boolean contains(Instance inst, int cx, int cy, int cz) {
            return inst == instance && cx >= cx0 && cx <= cx1 && cy >= cy0 && cy <= cy1 && cz >= cz0 && cz <= cz1;
        }
        public boolean containsColumn(Instance inst, int cx, int cz) {
            return inst == instance && cx >= cx0 && cx <= cx1 && cz >= cz0 && cz <= cz1;
        }
    }

    private static final Map<Object, List<Pin>> PINS = new ConcurrentHashMap<>();

    /** Приоритет загрузки по запросу модулей (после куба игрока и соседей, раньше дальних кубов окна). */
    static final int API_PRIO = 2;

    private static int lo(Point a, Point b, int axis) { return Math.min(c(a, axis), c(b, axis)) >> 4; }
    private static int hi(Point a, Point b, int axis) { return Math.max(c(a, axis), c(b, axis)) >> 4; }
    private static int c(Point p, int axis) { return axis == 0 ? p.blockX() : axis == 1 ? p.blockY() : p.blockZ(); }

    public static boolean isCubeLoaded(Instance inst, int x, int y, int z) {
        return CubeStore.isReady(inst, x, y, z);
    }

    public static boolean areCubesLoaded(Instance inst, Point min, Point max) {
        int x0 = lo(min, max, 0), x1 = hi(min, max, 0), z0 = lo(min, max, 2), z1 = hi(min, max, 2);
        int ylo = lo(min, max, 1), yhi = hi(min, max, 1);
        for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) {
            Chunk ch = inst.getChunk(cx, cz);
            if (ch == null) return false;
            int y0 = ylo - ch.getMinSection(), y1 = yhi - ch.getMinSection(), n = ch.getMaxSection() - ch.getMinSection();
            if (ch instanceof TallColumn tc)
                for (int i = Math.max(0, y0); i <= Math.min(n - 1, y1); i++) if (!tc.ready(i)) return false;
        }
        return true;
    }

    /** Загрузить столбцы и кубы рамки. Будущее завершается в потоке загрузки — в тике не ждать (см. ensureCubes). */
    public static CompletableFuture<Void> loadCubes(Instance inst, Point min, Point max) {
        int x0 = lo(min, max, 0), x1 = hi(min, max, 0), z0 = lo(min, max, 2), z1 = hi(min, max, 2);
        int y0 = lo(min, max, 1), y1 = hi(min, max, 1);
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) loads.add(CubeStore.load(inst, cx, cz, y0, y1, API_PRIO));
        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
    }

    /** Для тика: всё загружено — true; иначе запустить загрузку (без ожидания) и false. */
    public static boolean ensureCubes(Instance inst, Point min, Point max) {
        if (areCubesLoaded(inst, min, max)) return true;
        loadCubes(inst, min, max);
        return false;
    }

    /** Закрепить рамку за владельцем: сразу грузится (без ожидания), её кубы и столбцы не выгружаются (шаг 11). */
    public static void pin(Instance inst, Point min, Point max, Object owner) {
        if (inst == null || owner == null) return;
        Pin p = new Pin(inst, lo(min, max, 0), lo(min, max, 1), lo(min, max, 2), hi(min, max, 0), hi(min, max, 1), hi(min, max, 2));
        PINS.computeIfAbsent(owner, k -> new CopyOnWriteArrayList<>()).add(p);
        loadCubes(inst, min, max);
    }

    public static void unpin(Object owner) {
        if (owner != null) PINS.remove(owner);
    }

    /** Закреплён ли куб хоть кем-то (для будущей выгрузки кубов и /hocubes). */
    public static boolean isPinned(Instance inst, int cx, int cy, int cz) {
        for (List<Pin> l : PINS.values()) for (Pin p : l) if (p.contains(inst, cx, cy, cz)) return true;
        return false;
    }

    /** Есть ли в столбце закреплённые кубы (Maps.reapColumns его не выгружает). */
    public static boolean isColumnPinned(Instance inst, int cx, int cz) {
        for (List<Pin> l : PINS.values()) for (Pin p : l) if (p.containsColumn(inst, cx, cz)) return true;
        return false;
    }

    /** Закреплённые кубы столбца (cx, cz) — в keep (индексы от нижнего куба; шаг 11: их не выгружать). */
    static void pinned(Instance inst, int cx, int cz, java.util.BitSet keep) {
        TallWorld.Dims dd = TallWorld.dims(inst);
        int min = dd.minSection(), n = dd.cubes();
        for (List<Pin> l : PINS.values()) for (Pin p : l) {
            if (!p.containsColumn(inst, cx, cz)) continue;
            int a = Math.max(0, p.cy0() - min), b = Math.min(n - 1, p.cy1() - min);
            if (a <= b) keep.set(a, b + 1);
        }
    }

    /** Кто закрепил куб (для /hocubes dump). */
    static List<String> pinOwners(Instance inst, int cx, int cy, int cz) {
        List<String> r = new ArrayList<>();
        for (var e : PINS.entrySet()) for (Pin p : e.getValue()) if (p.contains(inst, cx, cy, cz)) { r.add(String.valueOf(e.getKey())); break; }
        return r;
    }

    /** Сколько рамок закреплено всего. */
    static int frames() { int n = 0; for (List<Pin> l : PINS.values()) n += l.size(); return n; }

    /** Сколько владельцев держат закрепления. */
    public static int owners() { return PINS.size(); }

    /** Карта выгружена — её закрепления больше не нужны (не держать ссылку на инстанс). */
    public static void forget(Instance inst) {
        PINS.values().forEach(l -> l.removeIf(p -> p.instance() == inst));
        PINS.values().removeIf(List::isEmpty);
    }
}
