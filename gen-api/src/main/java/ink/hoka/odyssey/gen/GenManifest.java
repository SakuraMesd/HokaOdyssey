package ink.hoka.odyssey.gen;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/** {@code hoka-gen.json} в корне jar генератора: {"id":"noise-test","version":2,"provider":"пакет.Класс"}. */
public record GenManifest(String id, int version, String provider) {

    public static final String FILE = "hoka-gen.json";
    public static final Pattern ID = Pattern.compile("[a-z0-9_\\-]{1,32}");

    public GenManifest {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("hoka-gen.json: id a-z 0-9 _ - (до 32), а не «" + id + "»");
        if (version < 0) throw new IllegalArgumentException("hoka-gen.json: version < 0");
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("hoka-gen.json: нет provider");
    }

    public static GenManifest parse(byte[] json) {
        Map<String, Object> m = GenJson.parseObject(new String(json, StandardCharsets.UTF_8));
        Object v = m.get("version");
        if (!(v instanceof Number n)) throw new IllegalArgumentException("hoka-gen.json: version — число");
        return new GenManifest(String.valueOf(m.get("id")), n.intValue(), String.valueOf(m.get("provider")));
    }

    public String tag() { return id + "@" + version; }
}
