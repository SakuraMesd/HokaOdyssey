package ink.hoka.odyssey.gen;

import java.util.ArrayList;
import java.util.List;

/**
 * Детерминированная расстановка объектов категории по «якорям в ячейках»: плоскость X/Z разбита на ячейки cell×cell, в
 * каждой (от сида, соли и номера ячейки) — не больше одного объекта. Куб пересчитывает ячейки в радиусе охвата объектов
 * и вставляет свою часть каждого задевающего его объекта (GenPlacer). Размещение зависит только от сида, соли, ячейки и
 * поверхности — результат одинаков при любом порядке кубов, на сервере и на клиенте.
 * <pre>
 * FeatureGrid trees = FeatureGrid.of(ctx, placer, "trees").cell(12).density(0.35).margin(2).build();
 * ... в generate: trees.place(out, surface);
 * </pre>
 * Нет объектов категории — пустой экземпляр, все методы ничего не делают.
 */
public final class FeatureGrid {

    /** Объект, поставленный в ячейку: якорь (x, ay, z) — мировые, поворот, отражение. */
    public record Placement(GenObject object, int x, int ay, int z, int rot, boolean mirror, int gx, int gz) {}

    private final GenPlacer placer;
    private final List<GenObject> objects;
    private final double[] cum;
    private final double total;
    private final int cell, margin, span;
    private final double density;
    private final long seed;
    /** Наибольший охват по X/Z; диапазон Y объекта относительно поверхности: [surf + lo, surf + hi]. */
    private final int reach, lo, hi;
    private final boolean hasData;
    private final String category;

    private FeatureGrid(Builder b) {
        this.placer = b.placer;
        this.category = b.category;
        this.objects = b.ctx.objects().category(b.category);                // уже по id
        this.cell = b.cell;
        this.margin = Math.max(0, Math.min(b.margin, (b.cell - 1) / 2));
        this.span = Math.max(1, cell - 2 * margin);
        this.density = b.density;
        long salt = GenHash.strings(List.of("feature", b.category, b.salt == null ? "" : b.salt));
        this.seed = b.ctx.seed() ^ salt;
        cum = new double[objects.size()];
        double t = 0;
        int r = 0, l = Integer.MAX_VALUE, h = Integer.MIN_VALUE;
        boolean d = false;
        for (int i = 0; i < objects.size(); i++) {
            GenObject o = objects.get(i);
            t += o.meta().weight();
            cum[i] = t;
            r = Math.max(r, o.reach());
            int base = 1 - o.meta().sink();
            l = Math.min(l, base + o.minDy());
            h = Math.max(h, base + o.maxDy());
            d |= o.hasData();
        }
        total = t;
        reach = r;
        lo = objects.isEmpty() ? 0 : l;
        hi = objects.isEmpty() ? 0 : h;
        hasData = d;
    }

    public static Builder of(GenContext ctx, GenPlacer placer, String category) { return new Builder(ctx, placer, category); }

    public static final class Builder {
        private final GenContext ctx;
        private final GenPlacer placer;
        private final String category;
        private int cell = 12, margin = 2;
        private double density = 0.35;
        private String salt = "";

        Builder(GenContext ctx, GenPlacer placer, String category) {
            this.ctx = ctx;
            this.placer = placer;
            this.category = category;
        }

        /** Размер ячейки (2…256). */
        public Builder cell(int c) {
            if (c < 2 || c > 256) throw new IllegalArgumentException("cell 2..256");
            this.cell = c;
            return this;
        }

        /** Доля ячеек с объектом (0…1). */
        public Builder density(double d) {
            if (!(d >= 0) || d > 1) throw new IllegalArgumentException("density 0..1");
            this.density = d;
            return this;
        }

        /** Отступ якоря от края ячейки. */
        public Builder margin(int m) { this.margin = Math.max(0, m); return this; }

        /** Своя соль (две сетки одной категории — разные места). */
        public Builder salt(String s) { this.salt = s; return this; }

        public FeatureGrid build() { return new FeatureGrid(this); }
    }

    public String category() { return category; }
    public boolean isEmpty() { return objects.isEmpty() || density <= 0; }
    /** В категории есть объекты с блок-сущностями (сундуки) — сервер шлёт их клиенту op 22. */
    public boolean hasData() { return hasData && !isEmpty(); }
    public int reach() { return reach; }

    /**
     * Куб может задеть объект этой сетки при поверхности в [surfMin, surfMax] (консервативно; false — точно нет).
     */
    public boolean mayTouch(int cx, int cy, int cz, int surfMin, int surfMax) {
        if (isEmpty()) return false;
        int y0 = cy << 4;
        return (long) surfMax + hi >= y0 && (long) surfMin + lo <= y0 + 15;
    }

    /** Ячейка (gx, gz): объект или null. Порядок случайных чисел фиксирован (даже для пустой ячейки). */
    public Placement at(int gx, int gz, GenSurface s) {
        if (isEmpty()) return null;
        GenRandom r = new GenRandom(GenRandom.hash(seed, gx, 0, gz));
        double p = r.nextDouble();
        int jx = r.nextInt(span), jz = r.nextInt(span);
        double w = r.nextDouble();
        int rot = r.nextInt(4), mir = r.nextInt(2);
        if (p >= density) return null;
        int x = gx * cell + margin + jx, z = gz * cell + margin + jz;
        double t = w * total;
        int k = 0, kh = cum.length - 1;                                     // первый k с t < cum[k] (бинпоиск: тысячи объектов)
        while (k < kh) { int m = (k + kh) >>> 1; if (t >= cum[m]) k = m + 1; else kh = m; }
        GenObject o = objects.get(k);
        GenObjectMeta m = o.meta();
        int y = s.height(x, z);
        if (y == GenSurface.NONE || y == PlanetGenerator.UNKNOWN) return null;
        if (!m.on().isEmpty()) {
            String top = s.block(x, z);
            if (top != null && !m.on().contains(top)) return null;
        }
        if (!m.underwater() && s.water(x, z)) return null;
        if (!m.biomes().isEmpty()) {
            String bio = s.biome(x, z);
            if (bio != null && !m.biomes().contains(bio)) return null;
        }
        return new Placement(o, x, y + 1 - m.sink(), z, m.rotate() ? rot : 0, m.mirror() && mir == 1, gx, gz);
    }

    /** Вставить в куб все объекты сетки, задевающие его (ячейки по gz↑, gx↑). Ответ — сколько объектов задели куб. */
    public int place(GenCube out, GenSurface s) {
        if (isEmpty()) return 0;
        int x0 = out.x0(), z0 = out.z0(), y0 = out.y0();
        int gx0 = Math.floorDiv(x0 - reach - (cell - 1), cell), gx1 = Math.floorDiv(x0 + 15 + reach, cell);
        int gz0 = Math.floorDiv(z0 - reach - (cell - 1), cell), gz1 = Math.floorDiv(z0 + 15 + reach, cell);
        int n = 0;
        for (int gz = gz0; gz <= gz1; gz++) for (int gx = gx0; gx <= gx1; gx++) {
            Placement p = at(gx, gz, s);
            if (p == null) continue;
            GenObject o = p.object();
            if (p.ay() + o.maxDy() < y0 || p.ay() + o.minDy() > y0 + 15) continue;
            if (placer.paste(out, o, p.x(), p.ay(), p.z(), p.rot(), p.mirror()) > 0) n++;
        }
        return n;
    }

    /** То же с предотсевом по диапазону поверхности (mayTouch). */
    public int place(GenCube out, GenSurface s, int surfMin, int surfMax) {
        if (!mayTouch(out.cx(), out.cy(), out.cz(), surfMin, surfMax)) return 0;
        return place(out, s);
    }

    /** Все объекты с якорем в [minX…maxX]×[minZ…maxZ] (отладка, тесты, /hoobj). */
    public List<Placement> placements(int minX, int minZ, int maxX, int maxZ, GenSurface s) {
        List<Placement> r = new ArrayList<>();
        if (isEmpty()) return r;
        for (int gz = Math.floorDiv(minZ, cell); gz <= Math.floorDiv(maxZ, cell); gz++)
            for (int gx = Math.floorDiv(minX, cell); gx <= Math.floorDiv(maxX, cell); gx++) {
                Placement p = at(gx, gz, s);
                if (p != null && p.x() >= minX && p.x() <= maxX && p.z() >= minZ && p.z() <= maxZ) r.add(p);
            }
        return r;
    }
}
