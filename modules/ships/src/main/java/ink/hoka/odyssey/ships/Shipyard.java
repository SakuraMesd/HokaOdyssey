package ink.hoka.odyssey.ships;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Верфь (SHIPYARD.md): блоки корабля — НАСТОЯЩИЕ блоки мира в далёкой полосе той же карты. Там они живут как
 * обычные (двери, сундуки, свет, ломание, сохранение), а в мире корабль показывает клиент: рисует участок верфи
 * сдвинутым и повёрнутым туда, где корабль летит. Этап 2: участки («плоты») и перенос блоков корабля в плот.
 * <p>Полоса — внутри границы мира (±500 000), чтобы не трогать границу и доставку кубов: X от {@link #X0}, плоты
 * 256×256 подряд по Z (k — номер плота), блоки корабля — с отступом {@link #MARGIN} от края плота, низ на {@link #BASE_Y}.
 */
final class Shipyard {

    private Shipyard() {}

    static final int X0 = 400_000, SIZE = 256, MARGIN = 16, BASE_Y = 64;
    /** Запас коробки корабля в плоте под достройку (клеток с каждой стороны). */
    static final int BOX_MARGIN = 4;

    /** Начало плота k (мин. угол). Плоты идут по Z в обе стороны: 0, 1, −1, 2, −2… */
    static int plotMinX() { return X0; }
    static int plotMinZ(int k) { return k * SIZE; }

    /** Полоса верфи? (для сторожей: игрокам туда нельзя). */
    static boolean isYard(double x) { return x >= X0 - 64; }

    /** Свободный номер плота на карте map. */
    static int allocate(ShipsModule mod, String map) {
        Set<Integer> used = new HashSet<>();
        for (Ship s : mod.ships.values()) {
            if (s.yard != null && map.equals(s.map)) used.add(s.yard[0]);
            if (s.oldYards != null && map.equals(s.map)) used.addAll(s.oldYards);   // брошенные плоты — там старые блоки
        }
        for (int i = 0; ; i++) {
            int k = (i % 2 == 0) ? i / 2 : -(i / 2 + 1);
            if (!used.contains(k)) return k;
        }
    }

    /** Клетка пилота в плоте k: середина плота по горизонтали, высота BASE_Y + 64. */
    static int[] seatOf(int k) { return new int[]{plotMinX() + SIZE / 2, BASE_Y + 64, plotMinZ(k) + SIZE / 2}; }

    /** Блок плота для клетки корабля (dx,dy,dz от клетки пилота). */
    static net.minestom.server.coordinate.BlockVec cell(Ship s, int dx, int dy, int dz) {
        return new net.minestom.server.coordinate.BlockVec(s.yard[1] + dx, s.yard[2] + dy, s.yard[3] + dz);
    }

    /**
     * Перенести блоки корабля-модели (стоит моделью: mparts/mblocks) в его плот. Дальше клетки корабля читаются и
     * пишутся в плоте (ShipInteract), полёт берёт блоки из плота (Flight.start). Возвращает число блоков.
     */
    static CompletableFuture<Integer> build(ShipsModule mod, Ship s, Instance inst) { return build(mod, s, inst, null); }

    /** То же, блоки — готовые (по порядку mparts; с NBT блока — вещи сундуков, таблички). null — из mblocks. */
    static CompletableFuture<Integer> build(ShipsModule mod, Ship s, Instance inst, List<Block> full) {
        if (s.yard == null) {
            int k = allocate(mod, s.map);
            int[] st = seatOf(k);
            s.yard = new int[]{k, st[0], st[1], st[2]};
        }
        int minDx = 0, maxDx = 0, minDz = 0, maxDz = 0, minDy = 0, maxDy = 0;
        for (int[] q : s.mparts) {
            minDx = Math.min(minDx, q[0]); maxDx = Math.max(maxDx, q[0]); minDz = Math.min(minDz, q[2]); maxDz = Math.max(maxDz, q[2]);
            minDy = Math.min(minDy, q[1]); maxDy = Math.max(maxDy, q[1]);
        }
        int m = BOX_MARGIN;
        s.yardTurn = s.mturn;
        s.yardTemplate = s.template;
        s.yardBox = new int[]{minDx - m, minDy - m, minDz - m, maxDx + m, maxDy + m, maxDz + m};
        pin(mod, inst, s);                                                      // плот не выгружать (пока только запись)
        return mod.server().loadCubes(inst, boxMin(s), boxMax(s)).thenApply(v -> {
            int n = 0;
            for (int i = 0; i < s.mparts.size(); i++) {
                int[] q = s.mparts.get(i);
                Block b = full != null ? full.get(i) : Block.fromState(s.mblocks.get(i));
                if (b == null || b.isAir()) continue;
                inst.setBlock(cell(s, q[0], q[1], q[2]), b);
                n++;
            }
            return n;
        });
    }

    /** Загрузить кубы плота корабля, если выгружены (иначе блоки верфи читаются как воздух). */
    static void ensureLoaded(ShipsModule mod, Instance inst, Ship s) {
        if (s.yard == null || s.mparts == null) return;
        if (!s.pinned || s.pinnedIn != inst) pin(mod, inst, s);                 // плот из ships.json — закрепить при первом деле
        Set<net.minestom.server.coordinate.Vec> cubes = new HashSet<>();        // кубы клеток (по 1 блоку-представителю)
        for (int[] q : s.mparts)
            cubes.add(new net.minestom.server.coordinate.Vec((s.yard[1] + q[0]) & ~15, (s.yard[2] + q[1]) & ~15, (s.yard[3] + q[2]) & ~15));
        for (var c : cubes)                                                     // без ожидания: не вешать тик сервера
            if (!mod.server().isCubeLoaded(inst, c)) mod.server().loadCubes(inst, c, c);
    }

    /** Углы коробки плота (yardBox) в блоках мира; коробки ещё нет — клетки модели с запасом. */
    static net.minestom.server.coordinate.Vec boxMin(Ship s) {
        int[] b = box(s);
        return new net.minestom.server.coordinate.Vec(s.yard[1] + b[0], s.yard[2] + b[1], s.yard[3] + b[2]);
    }

    static net.minestom.server.coordinate.Vec boxMax(Ship s) {
        int[] b = box(s);
        return new net.minestom.server.coordinate.Vec(s.yard[1] + b[3], s.yard[2] + b[4], s.yard[3] + b[5]);
    }

    private static int[] box(Ship s) {
        if (s.yardBox != null) return s.yardBox;
        int m = BOX_MARGIN, r = 40;                                             // как scanBox: старый плот без коробки
        if (s.mparts == null || s.mparts.isEmpty()) return new int[]{-r, -r, -r, r, r, r};
        int[] b = {0, 0, 0, 0, 0, 0};
        for (int[] q : s.mparts) for (int i = 0; i < 3; i++) { b[i] = Math.min(b[i], q[i]); b[i + 3] = Math.max(b[i + 3], q[i]); }
        return new int[]{b[0] - m, b[1] - m, b[2] - m, b[3] + m, b[4] + m, b[5] + m};
    }

    /** Ключ закрепления кубов плота (один на корабль: новый плот заменяет старый). */
    static String pinKey(Ship s) { return "ships:yard:" + s.owner; }

    /**
     * Закрепить кубы плота (CUBIC-CHUNKS.md §4.10): ядро сразу грузит их и не выгружает столбцы, пока закрепление
     * не снято (ядро 0.4 шаг 10; старое ядро только записывает).
     */
    static void pin(ShipsModule mod, Instance inst, Ship s) {
        if (s.yard == null || inst == null) return;
        mod.server().unpin(pinKey(s));
        mod.server().pinCubes(inst, boxMin(s), boxMax(s), pinKey(s));
        s.pinned = true;
        s.pinnedIn = inst;
    }

    /** Плот брошен / переехал / корабль убран — снять закрепление. */
    static void unpin(ShipsModule mod, Ship s) {
        mod.server().unpin(pinKey(s));
        s.pinned = false;
        s.pinnedIn = null;
    }

    /**
     * Плот закреплён, пока есть корабль: всем кораблям с плотом на загруженных картах — закрепить (новый мир карты
     * после перезагрузки — заново). Карты не грузит. Зовётся при включении модуля и раз в 5 с.
     */
    static void pinAll(ShipsModule mod) {
        java.util.Map<String, Instance> loaded = new java.util.HashMap<>();
        for (Instance i : net.minestom.server.MinecraftServer.getInstanceManager().getInstances())
            mod.server().mapName(i).ifPresent(n -> loaded.put(n, i));
        for (Ship s : mod.ships.values()) {
            if (s.yard == null || s.map == null) continue;
            Instance inst = loaded.get(s.map);
            if (inst != null && (!s.pinned || s.pinnedIn != inst)) pin(mod, inst, s);
        }
    }

    /** Где в плоте стоит пилот (над его клеткой). */
    static Pos seat(Ship s) {
        return new Pos(s.yard[1] + 0.5, s.yard[2] + 1, s.yard[3] + 0.5, s.mturn, 0);
    }
}
