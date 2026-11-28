package ink.hoka.odyssey.stations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Неизменяемый индекс регионов по картам — для частых запросов «какой регион в точке» (гравитация зовёт на каждого
 * игрока/предмет каждый тик). Коробки карты лежат плоскими массивами в порядке приоритета регионов (меньший объём
 * раньше, при равном — созданный позже): первая коробка, в которую попали, — ответ. Без аллокаций в запросе.
 * Меняются регионы — строится новый индекс (их десятки, это дёшево).
 */
public final class RegionIndex {

    /** Коробки одной карты. */
    public static final class MapIndex {
        private final int n;
        private final double[] b;           // по 6 на коробку: minX, minY, minZ, maxX+1, maxY+1, maxZ+1
        private final Region[] owner;       // регион коробки
        private final List<Region> regions; // регионы карты по приоритету
        private final double bx0, by0, bz0, bx1, by1, bz1;   // общие границы карты (быстрый отказ)

        MapIndex(List<Region> sorted) {
            regions = List.copyOf(sorted);
            int count = 0;
            for (Region r : sorted) count += r.boxes().size();
            n = count;
            b = new double[n * 6];
            owner = new Region[n];
            double x0 = Double.POSITIVE_INFINITY, y0 = x0, z0 = x0, x1 = Double.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
            int i = 0;
            for (Region r : sorted) {
                for (Box bx : r.boxes()) {
                    int k = i * 6;
                    b[k] = bx.minX(); b[k + 1] = bx.minY(); b[k + 2] = bx.minZ();
                    b[k + 3] = bx.maxX() + 1.0; b[k + 4] = bx.maxY() + 1.0; b[k + 5] = bx.maxZ() + 1.0;
                    x0 = Math.min(x0, b[k]); y0 = Math.min(y0, b[k + 1]); z0 = Math.min(z0, b[k + 2]);
                    x1 = Math.max(x1, b[k + 3]); y1 = Math.max(y1, b[k + 4]); z1 = Math.max(z1, b[k + 5]);
                    owner[i++] = r;
                }
            }
            bx0 = x0; by0 = y0; bz0 = z0; bx1 = x1; by1 = y1; bz1 = z1;
        }

        /** Главный регион в точке или null. */
        public Region at(double x, double y, double z) {
            if (x < bx0 || x >= bx1 || y < by0 || y >= by1 || z < bz0 || z >= bz1) return null;
            double[] a = b;
            for (int i = 0, k = 0; i < n; i++, k += 6) {
                if (x >= a[k] && x < a[k + 3] && y >= a[k + 1] && y < a[k + 4] && z >= a[k + 2] && z < a[k + 5]) return owner[i];
            }
            return null;
        }

        /** Регионы карты по приоритету (главные — раньше). */
        public List<Region> regions() { return regions; }
    }

    private static final MapIndex EMPTY_MAP = new MapIndex(List.of());
    public static final RegionIndex EMPTY = new RegionIndex(List.of());

    private final Map<String, MapIndex> maps;
    private final Map<String, Region> byId;

    public RegionIndex(Collection<Region> all) {
        Map<String, List<Region>> per = new HashMap<>();
        Map<String, Region> ids = new LinkedHashMap<>();
        for (Region r : all) {
            per.computeIfAbsent(r.map(), k -> new ArrayList<>()).add(r);
            ids.put(r.id(), r);
        }
        Map<String, MapIndex> m = new HashMap<>();
        for (var e : per.entrySet()) {
            List<Region> l = e.getValue();
            l.sort(Region::priorityCompare);
            m.put(e.getKey(), new MapIndex(l));
        }
        maps = Map.copyOf(m);
        byId = Collections.unmodifiableMap(ids);
    }

    /** Коробки карты (пустой индекс, если регионов нет). */
    public MapIndex map(String map) {
        MapIndex mi = map == null ? null : maps.get(map);
        return mi == null ? EMPTY_MAP : mi;
    }

    public Region at(String map, double x, double y, double z) { return map(map).at(x, y, z); }

    public Region byId(String id) { return byId.get(id); }

    /** Все регионы (в порядке создания). */
    public Collection<Region> all() { return byId.values(); }
}
