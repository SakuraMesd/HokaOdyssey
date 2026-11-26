package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.gen.GenIdTable;
import ink.hoka.odyssey.gen.GenIds;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Таблица номеров сервера для переносимых генераторов: состояния — Block.fromStateId(0…statesCount−1) (те же номера,
 * что уходят в палитры op 2 и что клиент берёт из Block.BLOCK_STATE_REGISTRY), биомы — реестр Minestom по номерам
 * (клиент строит реестр в том же порядке). Пересобирается, если реестр биомов вырос.
 */
public final class MinestomGenIds {

    private MinestomGenIds() {}

    private static volatile GenIdTable table;
    private static volatile int biomesAt = -1;

    public static GenIdTable get() {
        GenIdTable t = table;
        int nb = MinecraftServer.getBiomeRegistry().size();
        if (t != null && biomesAt == nb) return t;
        synchronized (MinestomGenIds.class) {
            if (table != null && biomesAt == nb) return table;
            int n = Block.statesCount();
            List<String> states = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Block b = Block.fromStateId(i);
                states.add(b == null ? null : canonical(b));
            }
            Map<String, Integer> defaults = new HashMap<>();
            for (Block b : Block.values()) defaults.put(b.key().asString(), b.stateId());
            var reg = MinecraftServer.getBiomeRegistry();
            List<String> biomes = new ArrayList<>(nb);
            for (int i = 0; i < nb; i++) { var k = reg.getKey(i); biomes.add(k == null ? null : k.key().asString()); }
            table = new GenIdTable(states, biomes, defaults);
            biomesAt = nb;
            return table;
        }
    }

    /** Каноническая строка состояния Minestom (как у клиента по BuiltInRegistries.BLOCK и свойствам). */
    public static String canonical(Block b) { return GenIds.canonical(b.key().asString(), b.properties()); }
}
