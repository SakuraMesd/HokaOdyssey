package ink.hoka.odyssey.api;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * YAML-конфиг. Путь через точку: {@code config.getInt("ship.max-speed", 20)}.
 * Значения по умолчанию в коде — модуль работает и без файла. Изменения — {@link #set} + {@link #save}.
 */
public final class ModuleConfig {

    private final Path file;
    private Map<String, Object> root = new LinkedHashMap<>();

    public ModuleConfig(Path file) {
        this.file = file;
        reload();
    }

    /** Перечитать файл с диска (если файла нет — пустой конфиг). */
    @SuppressWarnings("unchecked")
    public void reload() {
        root = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(r);
            if (o instanceof Map<?, ?> m) root = (Map<String, Object>) m;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("не прочитать " + file + ": " + e.getMessage(), e);
        }
    }

    public void save() {
        DumperOptions o = new DumperOptions();
        o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        o.setAllowUnicode(true);
        o.setIndent(2);
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                new Yaml(new Representer(o), o).dump(root, w);
            }
        } catch (IOException e) {
            throw new IllegalStateException("не записать " + file + ": " + e.getMessage(), e);
        }
    }

    public Path file() { return file; }

    public boolean contains(String path) { return get(path) != null; }

    public Object get(String path) {
        Object cur = root;
        for (String k : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(k);
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    public void set(String path, Object value) {
        String[] ks = path.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < ks.length - 1; i++) {
            Object n = cur.get(ks[i]);
            if (!(n instanceof Map)) { n = new LinkedHashMap<String, Object>(); cur.put(ks[i], n); }
            cur = (Map<String, Object>) n;
        }
        if (value == null) cur.remove(ks[ks.length - 1]); else cur.put(ks[ks.length - 1], value);
    }

    public String getString(String path, String def) {
        Object o = get(path);
        return o == null ? def : String.valueOf(o);
    }

    public int getInt(String path, int def) {
        return get(path) instanceof Number n ? n.intValue() : def;
    }

    public long getLong(String path, long def) {
        return get(path) instanceof Number n ? n.longValue() : def;
    }

    public double getDouble(String path, double def) {
        return get(path) instanceof Number n ? n.doubleValue() : def;
    }

    public boolean getBoolean(String path, boolean def) {
        return get(path) instanceof Boolean b ? b : def;
    }

    public List<String> getStringList(String path) {
        if (!(get(path) instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>(l.size());
        for (Object x : l) out.add(String.valueOf(x));
        return out;
    }

    /** Ключи первого уровня внутри раздела ("" — корень). */
    public Set<String> keys(String path) {
        Object o = path.isEmpty() ? root : get(path);
        return o instanceof Map<?, ?> m ? new LinkedHashSet<>((Set<String>) (Set<?>) m.keySet()) : Set.of();
    }
}
