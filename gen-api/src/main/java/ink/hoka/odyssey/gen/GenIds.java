package ink.hoka.odyssey.gen;

import java.util.Map;
import java.util.TreeMap;

/**
 * Номера состояний блоков и биомов. Каждая сторона строит свою таблицу ({@link GenIdTable}): сервер — из Minestom,
 * клиент — из реестров MC; номера совпадают (сверка — дайджесты в op 20).
 */
public interface GenIds {

    /** Номер состояния: "minecraft:oak_log[axis=y]" (порядок свойств любой; без "minecraft:" можно). Нет — исключение. */
    int state(String canonical);

    /** Состояние по умолчанию блока "minecraft:stone". Нет — исключение. */
    int defaultState(String blockId);

    /** Номер биома "minecraft:plains". Нет — исключение. */
    int biome(String key);

    /** Номер воздуха (0). */
    default int air() { return 0; }

    /** Каноническая строка состояния: id блока, затем [k=v,…] со свойствами по алфавиту (без свойств — только id). */
    static String canonical(String blockId, Map<String, String> props) {
        String id = blockId.indexOf(':') < 0 ? "minecraft:" + blockId : blockId;
        if (props == null || props.isEmpty()) return id;
        StringBuilder sb = new StringBuilder(id).append('[');
        boolean first = true;
        for (var e : new TreeMap<>(props).entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    /** Привести "oak_log[axis=y]" и т.п. к канонической строке. */
    static String normalize(String state) {
        String s = state.trim();
        int b = s.indexOf('[');
        if (b < 0) return canonical(s, null);
        if (!s.endsWith("]")) throw new IllegalArgumentException("состояние: " + state);
        Map<String, String> props = new TreeMap<>();
        String body = s.substring(b + 1, s.length() - 1).trim();
        if (!body.isEmpty()) for (String kv : body.split(",")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("состояние: " + state);
            props.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
        }
        return canonical(s.substring(0, b).trim(), props);
    }
}
