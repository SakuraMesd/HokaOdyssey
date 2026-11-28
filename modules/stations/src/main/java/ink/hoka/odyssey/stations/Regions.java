package ink.hoka.odyssey.stations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Набор регионов и правки над ним (без Minestom — проверяется тестами). Правки — только из тика сервера (одним потоком);
 * читать {@link #index()} можно откуда угодно: индекс неизменяемый, ссылка volatile.
 * Ошибка правки — {@link Refused} с текстом для игрока.
 */
public final class Regions {

    /** Правку нельзя сделать — текст для игрока. */
    public static final class Refused extends RuntimeException {
        public Refused(String msg) { super(msg, null, false, false); }
    }

    private final Map<String, Region> byId = new LinkedHashMap<>();
    private long seq;
    private volatile RegionIndex index = RegionIndex.EMPTY;
    /** Растёт при каждой правке — кэши (поиск гравитации по карте) понимают, что индекс сменился. */
    private volatile int version;

    public RegionIndex index() { return index; }

    public int version() { return version; }

    public long seq() { return seq; }

    /** Заменить всё (загрузка из файла). */
    public void replaceAll(List<Region> regions, long seq) {
        byId.clear();
        for (Region r : regions) byId.put(r.id(), r);
        this.seq = seq;
        rebuild();
    }

    public List<Region> all() { return new ArrayList<>(byId.values()); }

    public Region get(String id) { return byId.get(id); }

    public Region require(String id) {
        Region r = byId.get(id);
        if (r == null) throw new Refused("Нет региона «" + id + "»");
        return r;
    }

    public Region create(String id, String map, String title, Box box, String owner) {
        if (!Region.validId(id)) throw new Refused("id — латиница в нижнем регистре, цифры, _ и -, до 32 знаков");
        if (byId.containsKey(id)) throw new Refused("Регион «" + id + "» уже есть");
        if (box == null) throw new Refused("Нет выделения: указка (/hostation wand) или /hostation pos1, pos2");
        Region r = new Region(id, map, title == null || title.isBlank() ? id : title, owner, Grav.NORMAL, List.of(box), ++seq, Map.of());
        put(r);
        return r;
    }

    public Region addBox(String id, String map, Box box) {
        Region r = require(id);
        if (box == null) throw new Refused("Нет выделения: указка (/hostation wand) или /hostation pos1, pos2");
        if (!r.map().equals(map)) throw new Refused("Регион «" + id + "» на карте " + r.map() + ", а выделение — на " + map);
        List<Box> b = new ArrayList<>(r.boxes());
        b.add(box);
        return put(r.withBoxes(b));
    }

    public Region remove(String id) {
        Region r = require(id);
        byId.remove(id);
        rebuild();
        return r;
    }

    public Region rename(String id, String title) {
        if (title == null || title.isBlank()) throw new Refused("Пустое название");
        return put(require(id).withTitle(title.strip()));
    }

    public Region gravity(String id, Grav g) { return put(require(id).withGravity(g)); }

    /** Расширить все коробки региона. */
    public Region expand(String id, int n, Box.Dir dir, int worldMinY, int worldMaxY) {
        Region r = require(id);
        List<Box> b = new ArrayList<>();
        for (Box x : r.boxes()) b.add(x.expand(n, dir, worldMinY, worldMaxY));
        return put(r.withBoxes(b));
    }

    private Region put(Region r) {
        byId.put(r.id(), r);
        rebuild();
        return r;
    }

    private void rebuild() {
        index = new RegionIndex(byId.values());
        version++;
    }
}
