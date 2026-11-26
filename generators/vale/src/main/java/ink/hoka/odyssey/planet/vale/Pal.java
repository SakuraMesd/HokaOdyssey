package ink.hoka.odyssey.planet.vale;

import ink.hoka.odyssey.gen.GenIds;

import java.util.ArrayList;
import java.util.List;

/**
 * Состояния блоков генератора: строка → номер (GenIds) и флаги высот ({@link Flags}) по номеру. Заполняется только в
 * конструкторе генератора (до параллельной работы), дальше лишь читается.
 */
final class Pal {

    private final GenIds ids;
    private byte[] flags = new byte[4096];
    /** Опора (ванильные правила выживания): вид, стороны/семья, твёрдость — см. {@link #need}, {@link #side}, {@link #hold}. */
    private byte[] need = new byte[4096], side = new byte[4096], hold = new byte[4096];
    private final List<String> used = new ArrayList<>();

    Pal(GenIds ids) { this.ids = ids; }

    GenIds ids() { return ids; }

    /** Полное каноническое состояние "minecraft:vine[east=true,...]". */
    int s(String state) {
        int v = ids.state(state);
        note(v, state);
        return v;
    }

    /** Состояние по умолчанию блока. */
    int d(String id) {
        int v = ids.defaultState(id);
        note(v, id);
        return v;
    }

    /** Первое существующее из вариантов (новые блоки могут отсутствовать в старых реестрах). */
    int any(String... states) {
        RuntimeException last = null;
        for (String s : states) {
            try { return s.indexOf('[') >= 0 ? s(s) : d(s); } catch (RuntimeException e) { last = e; }
        }
        throw last;
    }

    /** Листва, которая не опадает. */
    int leaves(String wood) { return s("minecraft:" + wood + "_leaves[distance=1,persistent=true,waterlogged=false]"); }

    /** Бревно/древесина по оси. */
    int log(String id, char axis) { return s("minecraft:" + id + "[axis=" + axis + "]"); }

    void note(int v, String state) {
        if (v >= flags.length) {
            int n = Math.max(v + 1, flags.length * 2);
            flags = java.util.Arrays.copyOf(flags, n);
            need = java.util.Arrays.copyOf(need, n);
            side = java.util.Arrays.copyOf(side, n);
            hold = java.util.Arrays.copyOf(hold, n);
        }
        int f = Flags.of(state);
        flags[v] = (byte) (f | 0x40);                                         // 0x40 — «известно»
        int b = state.indexOf('[');
        String id = b < 0 ? state : state.substring(0, b);
        if (id.startsWith("minecraft:")) id = id.substring(10);
        // твёрдость: 1 — грань полная по коллизии (лианы, мох), 2 — «прочная» опора (листва — нет: у неё пустая опорная форма)
        if (v != 0 && f == Flags.FULL) hold[v] = (byte) (id.endsWith("_leaves") ? 1 : 3);
        if (hold[v] != 0 && (id.startsWith("jungle_") || id.startsWith("stripped_jungle_")) && (id.endsWith("_log") || id.endsWith("_wood")))
            hold[v] |= 4;                                                     // опора какао
        int nd = 0, sd = 0;
        switch (id) {
            case "cave_vines", "cave_vines_plant" -> { nd = N_HANG; sd = 1; }
            case "weeping_vines", "weeping_vines_plant" -> { nd = N_HANG; sd = 2; }
            case "spore_blossom", "hanging_roots" -> { nd = N_HANG; sd = 3; }
            case "pale_hanging_moss" -> { nd = N_MOSS; sd = 4; }
            case "twisting_vines", "twisting_vines_plant" -> { nd = N_UP; sd = 5; }
            case "mangrove_propagule" -> { if (state.contains("hanging=true")) nd = N_PROPAGULE; }
            case "vine", "resin_clump", "glow_lichen" -> { nd = id.equals("vine") ? N_VINE : N_FACE; sd = faces(state); }
            case "cocoa" -> {
                nd = N_COCOA;
                sd = state.contains("facing=east") ? 1 : state.contains("facing=west") ? 2 : state.contains("facing=south") ? 4 : 8;
            }
            default -> {}
        }
        need[v] = (byte) nd;
        side[v] = (byte) sd;
        used.add(state);
    }

    /** Чего требует блок: 0 — ничего; висит (опора сверху прочная или та же семья); мох (сверху любая полная, листва тоже); вверх
     *  (снизу прочная или та же семья; снизу вне объекта — рельеф); лиана (сбоку полная или сверху лиана с той же стороной);
     *  налёт на грани; какао (сбоку джунглевое бревно); пропагула (сверху мангровая листва). */
    static final int N_HANG = 1, N_MOSS = 2, N_UP = 3, N_VINE = 4, N_FACE = 5, N_COCOA = 6, N_PROPAGULE = 7;

    /** Стороны "east=true…" → биты 1 E, 2 W, 4 S, 8 N, 16 up, 32 down. */
    private static int faces(String state) {
        int m = 0;
        if (state.contains("east=true")) m |= 1;
        if (state.contains("west=true")) m |= 2;
        if (state.contains("south=true")) m |= 4;
        if (state.contains("north=true")) m |= 8;
        if (state.contains("up=true")) m |= 16;
        if (state.contains("down=true")) m |= 32;
        return m;
    }

    int need(int v) { return v > 0 && v < need.length ? need[v] : 0; }

    /** Стороны (лиана/налёт/какао) или семья (висячие). */
    int side(int v) { return v > 0 && v < side.length ? side[v] : 0; }

    /** Биты: 1 — полная грань (лиане/мху хватит), 2 — прочная опора (не листва), 4 — джунглевое бревно; 0 — не опора. */
    int hold(int v) { return v > 0 && v < hold.length ? hold[v] : 0; }

    /** Джунглевое бревно/древесина (опора какао). */
    boolean jungleLog(int v) { return (hold(v) & 4) != 0; }

    /** Флаги номера состояния (неизвестный — как полный блок). */
    int flags(int v) {
        if (v == 0) return 0;
        if (v < flags.length && flags[v] != 0) return flags[v] & 0x3F;
        return Flags.FULL;
    }

    List<String> used() { return used; }
}
