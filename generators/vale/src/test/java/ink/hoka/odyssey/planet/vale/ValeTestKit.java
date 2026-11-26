package ink.hoka.odyssey.planet.vale;

import ink.hoka.odyssey.gen.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Тестовое окружение vale без Minestom: номера состояний — по порядку первого запроса, объекты — из demo-objects/. */
final class ValeTestKit {

    private ValeTestKit() {}

    static final long SEED = 20261126L;
    static final GenBounds BOUNDS = new GenBounds(ValeProvider.MIN_Y, ValeProvider.MAX_Y, 25000);

    /** Номера: строка состояния → номер (0 — воздух). Один на все генераторы теста (номера сходятся). */
    static final class Ids implements GenIds {
        private final Map<String, Integer> st = new ConcurrentHashMap<>();
        private final Map<String, Integer> bi = new ConcurrentHashMap<>();
        Ids() { st.put("minecraft:air", 0); }
        @Override public synchronized int state(String canonical) {
            String k = GenIds.normalize(canonical);
            if (k.equals("minecraft:cave_air") || k.equals("minecraft:void_air")) k = "minecraft:air";
            return st.computeIfAbsent(k, x -> st.size());
        }
        @Override public int defaultState(String blockId) { return state(blockId); }
        @Override public synchronized int biome(String key) { return bi.computeIfAbsent(key, x -> bi.size()); }
    }

    static final Ids IDS = new Ids();
    private static GenObjects objects;

    /** Объекты demo-objects/&lt;категория&gt;/*.hobj (+ .json метаданные) — как objects/ карты hoka23. */
    static synchronized GenObjects objects() throws IOException {
        if (objects != null) return objects;
        String dir = System.getProperty("hoka.test.demo-objects-dir");
        List<GenObject> list = new ArrayList<>();
        if (dir != null && Files.isDirectory(Path.of(dir))) {
            try (Stream<Path> s = Files.walk(Path.of(dir), 2)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".hobj")).sorted().toList()) {
                    String cat = p.getParent().getFileName().toString();
                    String name = p.getFileName().toString().replace(".hobj", "");
                    Path mj = p.resolveSibling(name + ".json");
                    Map<String, Object> meta = Files.exists(mj) ? GenJson.parseObject(Files.readString(mj, StandardCharsets.UTF_8)) : Map.of();
                    list.add(GenObjectCodec.readFile(cat + "/" + name, Files.readAllBytes(p), meta));
                }
            }
        }
        objects = GenObjects.of(list);
        return objects;
    }

    /** Новый генератор (холодные кэши), как у карты hoka23. */
    static PlanetGenerator gen() throws IOException {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("sea_level", 300);
        opts.put("tree_density", 1.0);
        return new ValeProvider().create(GenContext.of("hoka23", SEED, BOUNDS, opts, IDS, false, null, objects()));
    }
}
