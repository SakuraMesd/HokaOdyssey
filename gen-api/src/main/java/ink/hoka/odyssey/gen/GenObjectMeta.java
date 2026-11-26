package ink.hoka.odyssey.gen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Метаданные объекта (файл objects/&lt;кат&gt;/&lt;имя&gt;.json рядом с .hobj): вес, где ставить, вкапывание и т.п.
 * Неизменяемые. Известные ключи — {@link #KEYS}; свои — только с префиксом «x.».
 */
public final class GenObjectMeta {

    /** Известные ключи и их смысл (для /hoobj set и подсказок). */
    public static final Map<String, String> KEYS;
    static {
        Map<String, String> k = new TreeMap<>();
        k.put("weight", "вес при выборе в категории (число > 0, по умолчанию 1)");
        k.put("on", "на каком верхнем блоке ставить: grass_block,dirt или any");
        k.put("sink", "вкопать на N блоков (0..32)");
        k.put("underwater", "можно под водой (true/false)");
        k.put("biomes", "биомы через запятую или any");
        k.put("rotate", "поворачивать случайно (true/false)");
        k.put("mirror", "отражать случайно (true/false)");
        k.put("replace", "air — ставить только в воздух, all — затирать всё");
        KEYS = Collections.unmodifiableMap(k);
    }

    private final Map<String, Object> raw;
    private final double weight;
    private final Set<String> on, biomes;
    private final int sink;
    private final boolean underwater, rotate, mirror, replaceAll;

    private GenObjectMeta(Map<String, Object> raw, boolean keepAir) {
        TreeMap<String, Object> r = new TreeMap<>(raw == null ? Map.of() : raw);
        this.raw = Collections.unmodifiableMap(r);
        double w = num(r.get("weight"), 1);
        weight = w > 0 && w < 1e9 ? w : 1;
        on = ids(r.get("on"));
        biomes = ids(r.get("biomes"));
        int s = (int) num(r.get("sink"), 0);
        sink = Math.max(0, Math.min(32, s));
        underwater = bool(r.get("underwater"), false);
        rotate = bool(r.get("rotate"), true);
        mirror = bool(r.get("mirror"), true);
        Object rep = r.get("replace");
        replaceAll = rep == null ? keepAir : "all".equals(String.valueOf(rep));
    }

    public static GenObjectMeta of(Map<String, Object> raw, boolean keepAir) { return new GenObjectMeta(raw, keepAir); }

    public double weight() { return weight; }
    /** Пусто — любой верхний блок. */
    public Set<String> on() { return on; }
    public int sink() { return sink; }
    public boolean underwater() { return underwater; }
    /** Пусто — любые биомы. */
    public Set<String> biomes() { return biomes; }
    public boolean rotate() { return rotate; }
    public boolean mirror() { return mirror; }
    /** true — затирать всё; false — писать только в воздух. */
    public boolean replaceAll() { return replaceAll; }
    /** Весь файл (ключи по алфавиту). */
    public Map<String, Object> raw() { return raw; }

    public String string(String k, String def) { Object v = raw.get(k); return v == null ? def : v.toString(); }
    public double num(String k, double def) { return num(raw.get(k), def); }
    public List<String> list(String k, List<String> def) {
        Object v = raw.get(k);
        if (v == null) return def;
        List<String> r = new ArrayList<>();
        if (v instanceof Iterable<?> it) for (Object o : it) r.add(String.valueOf(o));
        else for (String s : v.toString().split(",")) if (!s.isBlank()) r.add(s.trim());
        return r;
    }

    private static double num(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        return def;
    }

    private static boolean bool(Object v, boolean def) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) return true;
            if ("false".equalsIgnoreCase(s.trim())) return false;
        }
        return def;
    }

    /** Список id (строка через запятую или массив) → с "minecraft:"; "any"/пусто — пустое множество (любой). */
    private static Set<String> ids(Object v) {
        Set<String> r = new TreeSet<>();
        if (v == null) return Collections.unmodifiableSet(r);
        List<String> parts = new ArrayList<>();
        if (v instanceof Iterable<?> it) for (Object o : it) parts.add(String.valueOf(o));
        else parts.addAll(List.of(v.toString().split(",")));
        for (String p : parts) {
            String s = p.trim();
            if (s.isEmpty() || "any".equals(s) || "*".equals(s)) continue;
            r.add(s.indexOf(':') < 0 ? "minecraft:" + s : s);
        }
        return Collections.unmodifiableSet(r);
    }

    /**
     * Значение ключа из строки команды → объект для JSON (с проверкой типа). Неизвестный ключ без «x.» — исключение
     * с понятным текстом.
     */
    public static Object parseValue(String key, String value) {
        String v = value.trim();
        switch (key) {
            case "weight" -> {
                double d;
                try { d = Double.parseDouble(v); } catch (NumberFormatException e) { throw new IllegalArgumentException("weight — число > 0"); }
                if (!(d > 0) || d >= 1e9) throw new IllegalArgumentException("weight — число > 0");
                return d;
            }
            case "sink" -> {
                int i;
                try { i = Integer.parseInt(v); } catch (NumberFormatException e) { throw new IllegalArgumentException("sink — целое 0..32"); }
                if (i < 0 || i > 32) throw new IllegalArgumentException("sink — целое 0..32");
                return i;
            }
            case "underwater", "rotate", "mirror" -> {
                if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) throw new IllegalArgumentException(key + " — true или false");
                return Boolean.parseBoolean(v.toLowerCase());
            }
            case "replace" -> {
                if (!"air".equals(v) && !"all".equals(v)) throw new IllegalArgumentException("replace — air или all");
                return v;
            }
            case "on", "biomes" -> {
                List<String> l = new ArrayList<>();
                for (String s : v.split(",")) {
                    String t = s.trim();
                    if (t.isEmpty() || "any".equals(t)) continue;
                    if (!t.matches("[a-z0-9_.\\-]+(:[a-z0-9_./\\-]+)?")) throw new IllegalArgumentException(key + ": плохой id «" + t + "»");
                    l.add(t.indexOf(':') < 0 ? "minecraft:" + t : t);
                }
                return l;
            }
            default -> {
                if (!key.startsWith("x.") || key.length() < 3 || key.length() > 64)
                    throw new IllegalArgumentException("неизвестный ключ «" + key + "» — есть " + String.join(", ", KEYS.keySet()) + " и свои x.*");
                return v;
            }
        }
    }
}
