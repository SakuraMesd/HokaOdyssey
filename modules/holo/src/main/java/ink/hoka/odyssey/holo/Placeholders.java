package ink.hoka.odyssey.holo;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Плейсхолдеры: имя → функция «игрок → строка» и период (тики). Значение считается на сервере для каждого игрока,
 * своё по частоте плейсхолдера; моду уходит только изменившееся ({@link #due}). Обобщён по типу игрока — тесты
 * обходятся без Minestom.
 *
 * @param <P> игрок
 * @param <K> ключ игрока (UUID)
 */
public final class Placeholders<P, K> {

    record Def<P>(Function<P, String> fn, int period, String owner) {}

    private final Map<String, Def<P>> defs = new ConcurrentHashMap<>();
    /** Последнее отправленное игроку: ключ → (имя → значение). */
    private final Map<K, Map<String, String>> sent = new ConcurrentHashMap<>();
    private final Function<P, K> key;

    public Placeholders(Function<P, K> key) { this.key = key; }

    public void register(String name, int periodTicks, String owner, Function<P, String> fn) {
        if (!name.matches("[a-zA-Z0-9_.:\\-]{1,48}")) throw new IllegalArgumentException("плохое имя плейсхолдера «" + name + "»");
        defs.put(name, new Def<>(fn, Math.max(1, periodTicks), owner));
    }

    public void unregister(String name) { defs.remove(name); }

    /** Снять все плейсхолдеры владельца (модуль выключился). */
    public void unregisterOwner(String owner) { defs.values().removeIf(d -> owner.equals(d.owner())); }

    public boolean has(String name) { return defs.containsKey(name); }

    public Set<String> names() { return new java.util.TreeSet<>(defs.keySet()); }

    public int period(String name) {
        Def<P> d = defs.get(name);
        return d == null ? 0 : d.period();
    }

    /**
     * Изменившиеся значения для игрока на тике {@code tick}: считаются только нужные ему плейсхолдеры, у которых
     * подошёл период ({@code force} — все сразу: новая голограмма рядом). Пусто — слать нечего.
     */
    public Map<String, String> due(P player, Set<String> wanted, long tick, boolean force) {
        if (wanted.isEmpty()) return Map.of();
        Map<String, String> last = sent.computeIfAbsent(key.apply(player), k -> new HashMap<>());
        Map<String, String> out = null;
        for (String name : wanted) {
            Def<P> d = defs.get(name);
            if (d == null) continue;
            if (!force && last.containsKey(name) && tick % d.period() != 0) continue;
            String v;
            try { v = d.fn().apply(player); }
            catch (RuntimeException e) { v = "?"; }
            if (v == null) v = "";
            if (v.length() > 256) v = v.substring(0, 256);
            if (v.equals(last.get(name))) continue;
            last.put(name, v);
            if (out == null) out = new LinkedHashMap<>();
            out.put(name, v);
        }
        return out == null ? Map.of() : out;
    }

    /** Значение сейчас (для действий: подставить в команду). */
    public String now(P player, String name) {
        Def<P> d = defs.get(name);
        if (d == null) return null;
        try { return d.fn().apply(player); } catch (RuntimeException e) { return "?"; }
    }

    /** Подставить {имя} в строку (неизвестные — как есть). */
    public String fill(P player, String s) {
        if (s == null || s.indexOf('{') < 0) return s;
        var m = Hologram.PLACEHOLDER.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String v = now(player, m.group(1));
            m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** Игрок вышел / сменил карту — забыть отправленное (при возвращении придут все значения). */
    public void forget(K k) { sent.remove(k); }

    public void clear() { sent.clear(); }
}
