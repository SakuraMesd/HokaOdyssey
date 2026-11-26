package ink.hoka.odyssey.gen;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code hoka-gen.json} в корне jar генератора: {"id":"noise-test","version":2,"provider":"пакет.Класс"}; необязательно
 * "api" — уровень API (по умолчанию 1, см. {@link GenApi}) и "objects": true — генератору нужны объекты (нет ключа
 * objects: в map.yml — берутся все категории).
 */
public record GenManifest(String id, int version, String provider, int api, boolean objects) {

    public static final String FILE = "hoka-gen.json";
    public static final Pattern ID = Pattern.compile("[a-z0-9_\\-]{1,32}");

    public GenManifest {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("hoka-gen.json: id a-z 0-9 _ - (до 32), а не «" + id + "»");
        if (version < 0) throw new IllegalArgumentException("hoka-gen.json: version < 0");
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("hoka-gen.json: нет provider");
        if (api < 1) throw new IllegalArgumentException("hoka-gen.json: api < 1");
    }

    /** Без api/objects (как до 0.60). */
    public GenManifest(String id, int version, String provider) { this(id, version, provider, 1, false); }

    public static GenManifest parse(byte[] json) {
        Map<String, Object> m = GenJson.parseObject(new String(json, StandardCharsets.UTF_8));
        Object v = m.get("version");
        if (!(v instanceof Number n)) throw new IllegalArgumentException("hoka-gen.json: version — число");
        Object a = m.get("api");
        if (a != null && !(a instanceof Number)) throw new IllegalArgumentException("hoka-gen.json: api — число");
        Object ob = m.get("objects");
        if (ob != null && !(ob instanceof Boolean)) throw new IllegalArgumentException("hoka-gen.json: objects — true/false");
        return new GenManifest(String.valueOf(m.get("id")), n.intValue(), String.valueOf(m.get("provider")),
                a == null ? 1 : ((Number) a).intValue(), Boolean.TRUE.equals(ob));
    }

    public String tag() { return id + "@" + version; }
}
