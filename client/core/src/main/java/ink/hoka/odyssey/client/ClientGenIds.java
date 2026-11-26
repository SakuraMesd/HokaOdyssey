package ink.hoka.odyssey.client;

import ink.hoka.odyssey.gen.GenIdTable;
import ink.hoka.odyssey.gen.GenIds;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 0.59: таблица номеров для генератора на клиенте. Номер состояния — Block.BLOCK_STATE_REGISTRY (как у Minestom:
 * ванильные состояния первыми, блоки модов — после), номер биома — реестр биомов соединения (сервер прислал его в
 * своём порядке). Строки — канонические (GenIds.canonical), поиск — общий GenIdTable. Строится один раз (в фоне),
 * дальше только чтение — из любых потоков.
 */
final class ClientGenIds implements GenIds {

    private final List<String> states, biomes;
    private final Holder<Biome>[] holders;
    private final GenIdTable table;

    @SuppressWarnings("unchecked")
    ClientGenIds(net.minecraft.core.RegistryAccess access) {
        int n = Block.BLOCK_STATE_REGISTRY.size();
        List<String> st = new ArrayList<>(n);
        Map<String, Integer> defaults = new HashMap<>(4096);
        for (int i = 0; i < n; i++) {
            BlockState s = Block.BLOCK_STATE_REGISTRY.byId(i);
            if (s == null) { st.add(null); continue; }
            st.add(canonical(s));
            if (s == s.getBlock().defaultBlockState()) defaults.putIfAbsent(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString(), i);
        }
        Registry<Biome> reg = access.lookupOrThrow(Registries.BIOME);
        int m = reg.size();
        List<String> bs = new ArrayList<>(m);
        holders = new Holder[m];
        for (int i = 0; i < m; i++) {
            var h = reg.get(i).orElse(null);
            holders[i] = h;
            bs.add(h == null ? "" : h.key().identifier().toString());
        }
        states = Collections.unmodifiableList(st);
        biomes = Collections.unmodifiableList(bs);
        table = new GenIdTable(st, bs, defaults);
    }

    static String canonical(BlockState s) {
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        if (s.getProperties().isEmpty()) return id;
        Map<String, String> props = new TreeMap<>();
        for (Property<?> p : s.getProperties()) props.put(p.getName(), value(s, p));
        return GenIds.canonical(id, props);
    }

    private static <T extends Comparable<T>> String value(BlockState s, Property<T> p) {
        return p.getName(s.getValue(p));
    }

    int statesCount() { return states.size(); }

    /** Первые count состояний (null — у клиента их меньше, чем у сервера, или дыра в номерах). */
    List<String> firstStates(int count) {
        if (count < 0 || count > states.size()) return null;
        List<String> r = states.subList(0, count);
        for (String s : r) if (s == null) return null;
        return r;
    }

    List<String> biomeNames() { return biomes; }

    Holder<Biome>[] biomeHolders() { return Arrays.copyOf(holders, holders.length); }

    @Override public int state(String canonical) { return table.state(canonical); }
    @Override public int defaultState(String blockId) { return table.defaultState(blockId); }
    @Override public int biome(String key) { return table.biome(key); }
    @Override public int air() { return 0; }
}
