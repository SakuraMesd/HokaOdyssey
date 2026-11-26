package ink.hoka.odyssey.gen;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Таблица номеров: канонические строки состояний по порядку номеров (0…N−1), ключи биомов по порядку номеров и
 * состояние по умолчанию каждого блока. Строят сервер (MinestomGenIds) и клиент (ClientGenIds) — каждый из своих реестров.
 * Дайджесты (op 20): {@link GenHash#strings} строк по порядку номеров.
 */
public final class GenIdTable implements GenIds {

    private final Map<String, Integer> states, defaults, biomes;
    private final int statesCount;
    private final long statesDigest, biomesDigest;

    public GenIdTable(List<String> statesById, List<String> biomesById, Map<String, Integer> defaultStates) {
        states = new HashMap<>(statesById.size() * 2);
        for (int i = 0; i < statesById.size(); i++) if (statesById.get(i) != null) states.putIfAbsent(statesById.get(i), i);
        biomes = new HashMap<>();
        for (int i = 0; i < biomesById.size(); i++) biomes.putIfAbsent(biomesById.get(i), i);
        defaults = new HashMap<>(defaultStates);
        statesCount = statesById.size();
        statesDigest = GenHash.strings(statesById);
        biomesDigest = GenHash.strings(biomesById);
    }

    public int statesCount() { return statesCount; }
    public long statesDigest() { return statesDigest; }
    public long biomesDigest() { return biomesDigest; }

    @Override
    public int state(String canonical) {
        Integer v = states.get(GenIds.normalize(canonical));
        if (v == null) throw new IllegalArgumentException("нет состояния " + canonical);
        return v;
    }

    @Override
    public int defaultState(String blockId) {
        Integer v = defaults.get(blockId.indexOf(':') < 0 ? "minecraft:" + blockId : blockId);
        if (v == null) throw new IllegalArgumentException("нет блока " + blockId);
        return v;
    }

    @Override
    public int biome(String key) {
        Integer v = biomes.get(key.indexOf(':') < 0 ? "minecraft:" + key : key);
        if (v == null) throw new IllegalArgumentException("нет биома " + key);
        return v;
    }
}
