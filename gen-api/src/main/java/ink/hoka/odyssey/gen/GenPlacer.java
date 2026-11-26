package ink.hoka.odyssey.gen;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Вставка объектов в куб с поворотом/отражением. Создаётся один раз (в create генератора): палитры всех объектов заранее
 * переведены в номера {@link GenIds} для 8 вариантов (4 поворота × отражение); повёрнутого состояния нет в таблице —
 * берётся неповёрнутое. Дальше неизменяем — можно звать из многих потоков.
 */
public final class GenPlacer {

    private final Map<String, int[][]> states = new HashMap<>();

    private GenPlacer(GenIds ids, Collection<GenObject> objects) {
        for (GenObject o : objects) {
            int p = o.palette().size();
            int[][] v = new int[8][p];
            for (int k = 0; k < p; k++) {
                String s = o.palette().get(k);
                int base = resolve(ids, s);
                for (int var = 0; var < 8; var++) {
                    if (var == 0) { v[0][k] = base; continue; }
                    int st;
                    try { st = ids.state(GenStates.rotate(s, var & 3, var >= 4)); }
                    catch (RuntimeException e) { st = base; }
                    v[var][k] = st;
                }
            }
            states.put(o.id(), v);
        }
    }

    private static int resolve(GenIds ids, String s) {
        if (GenObjectCodec.AIR.equals(s)) return ids.air();
        try { return ids.state(s); }
        catch (RuntimeException e) {                                         // нет такого состояния — блок по умолчанию
            int b = s.indexOf('[');
            try { return ids.defaultState(b < 0 ? s : s.substring(0, b)); }
            catch (RuntimeException e2) { throw new IllegalArgumentException("объект: нет блока " + s); }
        }
    }

    /** Все объекты контекста генератора. */
    public static GenPlacer of(GenContext ctx) { return new GenPlacer(ctx.ids(), ctx.objects().all()); }

    /** Свой набор объектов (сервер: /hoobj paste). */
    public static GenPlacer of(GenIds ids, Collection<GenObject> objects) { return new GenPlacer(ids, objects); }

    /** Номер состояния блока i объекта o в варианте (rot, mir). */
    public int state(GenObject o, int i, int rot, boolean mir) {
        return states(o)[(rot & 3) + (mir ? 4 : 0)][o.paletteIndex(i)];
    }

    private int[][] states(GenObject o) {
        int[][] v = states.get(o.id());
        if (v == null) throw new IllegalArgumentException("объект " + o.id() + " не в этом GenPlacer");
        return v;
    }

    /**
     * Вставить в куб out часть объекта o с якорем в (ax, ay, az) (мировые): поворот rot×90° по часовой, mir — отражение
     * по X (сначала оно). replace=air — пишется только в воздух; воздух объекта keep-air пишется всегда. Блок-сущности —
     * out.data (только при out.secrets()), если блок записан. Ответ — сколько блоков записано.
     */
    public int paste(GenCube out, GenObject o, int ax, int ay, int az, int rot, boolean mir) {
        int x0 = out.x0(), y0 = out.y0(), z0 = out.z0();
        int r = o.reach();
        if (ax + r < x0 || ax - r > x0 + 15 || az + r < z0 || az - r > z0 + 15) return 0;
        if (ay + o.maxDy() < y0 || ay + o.minDy() > y0 + 15) return 0;
        int[] st = states(o)[(rot & 3) + (mir ? 4 : 0)];
        boolean all = o.meta().replaceAll(), keepAir = o.keepAir();
        boolean data = out.secrets() && o.hasData();
        int n = 0;
        // блоки идут по возрастанию y (индекс рамки (y·sz + z)·sx + x) — берём только слой куба: бинпоиск начала
        int c = o.count(), lo = 0, hi = c, yMin = y0 - ay, yMax = y0 + 15 - ay;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (o.dy(m) < yMin) lo = m + 1; else hi = m; }
        for (int i = lo; i < c && o.dy(i) <= yMax; i++) {
            int dx = o.dx(i), dz = o.dz(i);
            int lx = ax + GenObject.rx(dx, dz, rot, mir) - x0, ly = ay + o.dy(i) - y0, lz = az + GenObject.rz(dx, dz, rot, mir) - z0;
            if ((lx | ly | lz) < 0 || lx > 15 || ly > 15 || lz > 15) continue;
            int s = st[o.paletteIndex(i)];
            boolean airBlock = keepAir && s == 0;
            if (!all && !airBlock && out.get(lx, ly, lz) != 0) continue;
            out.set(lx, ly, lz, s);
            n++;
            if (data) {
                String snbt = o.data().get(i);
                if (snbt != null) out.data(lx, ly, lz, snbt);
            }
        }
        return n;
    }
}
