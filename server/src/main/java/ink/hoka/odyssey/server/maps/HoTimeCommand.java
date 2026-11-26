package ink.hoka.odyssey.server.maps;

import ink.hoka.odyssey.server.perms.Ops;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandContext;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.Player;

import java.util.Locale;

import static ink.hoka.odyssey.server.maps.HoMapCommand.err;
import static ink.hoka.odyssey.server.maps.HoMapCommand.info;
import static ink.hoka.odyssey.server.maps.HoMapCommand.ok;

/**
 * /hotime — время суток карты (только OP и консоль). Карта — та, где стоит игрок, или последним словом.
 *   /hotime query [карта]                                       — время, скорость, цикл
 *   /hotime set day|noon|sunset|night|midnight|sunrise|&lt;тики&gt; [карта]
 *   /hotime add &lt;тики&gt; [карта]
 *   /hotime speed &lt;множитель&gt; [карта]   — 1 = сутки 20 мин, 2 = 10 мин, 0 = стоп (сохраняется в map.yml)
 *   /hotime cycle on|off [карта]                                — идёт ли время (сохраняется в map.yml)
 * set при выключенном цикле запоминается как time.start (замерли на полдне — полдень и после перезапуска).
 */
public final class HoTimeCommand extends Command {

    private final Maps maps;

    public HoTimeCommand(Maps maps, Ops ops) {
        super("hotime");
        this.maps = maps;
        setCondition(ops.admin());
        setDefaultExecutor((s, c) -> help(s));

        var mapArg = ArgumentType.Word("карта").setSuggestionCallback((sender, ctx, sug) -> {
            for (var m : maps.loadedMaps()) sug.addEntry(new SuggestionEntry(m.name));
        });
        var whenArg = ArgumentType.Word("время").setSuggestionCallback((sender, ctx, sug) -> {
            for (String w : new String[]{"day", "noon", "sunset", "night", "midnight", "sunrise"}) sug.addEntry(new SuggestionEntry(w));
        });
        var ticksArg = ArgumentType.Long("тики");
        var speedArg = ArgumentType.Double("множитель").between(0.0, MapTime.MAX_SPEED);
        var onOff = ArgumentType.Word("вкл").from("on", "off");

        addSyntax((s, c) -> query(s, map(s, c, null)), ArgumentType.Literal("query"));
        addSyntax((s, c) -> query(s, map(s, c, mapArg.getId())), ArgumentType.Literal("query"), mapArg);
        addSyntax((s, c) -> set(s, map(s, c, null), c.get(whenArg)), ArgumentType.Literal("set"), whenArg);
        addSyntax((s, c) -> set(s, map(s, c, mapArg.getId()), c.get(whenArg)), ArgumentType.Literal("set"), whenArg, mapArg);
        addSyntax((s, c) -> add(s, map(s, c, null), c.get(ticksArg)), ArgumentType.Literal("add"), ticksArg);
        addSyntax((s, c) -> add(s, map(s, c, mapArg.getId()), c.get(ticksArg)), ArgumentType.Literal("add"), ticksArg, mapArg);
        addSyntax((s, c) -> speed(s, map(s, c, null), c.get(speedArg)), ArgumentType.Literal("speed"), speedArg);
        addSyntax((s, c) -> speed(s, map(s, c, mapArg.getId()), c.get(speedArg)), ArgumentType.Literal("speed"), speedArg, mapArg);
        addSyntax((s, c) -> cycle(s, map(s, c, null), "on".equals(c.get(onOff))), ArgumentType.Literal("cycle"), onOff);
        addSyntax((s, c) -> cycle(s, map(s, c, mapArg.getId()), "on".equals(c.get(onOff))), ArgumentType.Literal("cycle"), onOff, mapArg);
    }

    private void help(CommandSender s) {
        info(s, "/hotime query [карта] — время суток карты");
        info(s, "/hotime set day|noon|sunset|night|midnight|sunrise|<тики> [карта]");
        info(s, "/hotime add <тики> [карта]");
        info(s, "/hotime speed <множитель> [карта] — 1 = сутки 20 мин, 0 = стоп");
        info(s, "/hotime cycle on|off [карта] — идёт ли время");
    }

    /** Карта: по имени (должна быть загружена) или где стоит игрок. null — сообщение об ошибке уже отправлено. */
    private Maps.GameMap map(CommandSender s, CommandContext c, String argId) {
        if (argId != null) {
            String n = c.get(argId);
            var m = maps.loadedMap(n);
            if (m.isEmpty()) err(s, "карта «" + n + "» не загружена (зайдите на неё: /homap tp " + n + ")");
            return m.orElse(null);
        }
        if (s instanceof Player p && p.getInstance() != null)
            for (var m : maps.loadedMaps()) if (m.instance == p.getInstance()) return m;
        err(s, s instanceof Player ? "вы не на карте из maps/ — укажите карту последним словом" : "из консоли укажите карту последним словом");
        return null;
    }

    private void query(CommandSender s, Maps.GameMap m) {
        if (m == null) return;
        var c = MapTime.cfg(m.instance);
        info(s, "Карта «" + m.name + "»: " + MapTime.label(m.instance.getTime()) + ", цикл " + (c.cycle() ? "вкл" : "выкл")
                + ", скорость ×" + MapTime.fmt(c.speed()) + (c.running() ? ", сутки ≈ " + dayLen(c.speed()) : ", время стоит"));
    }

    private void set(CommandSender s, Maps.GameMap m, String when) {
        if (m == null) return;
        Long t = MapTime.parse(when);
        if (t == null) { err(s, "время: day, noon, sunset, night, midnight, sunrise или число тиков"); return; }
        // сохраняем день (номер суток) — меняется только время дня, луна/фазы идут своим чередом
        long day = Math.floorDiv(m.instance.getTime(), MapTime.DAY);
        m.instance.setTime(day * MapTime.DAY + Math.floorMod(t, MapTime.DAY));
        var c = MapTime.cfg(m.instance);
        String saved = "";
        if (!c.running()) {
            if (persist(s, m, c.withStart(Math.floorMod(t, MapTime.DAY)))) saved = " (время стоит — запомнено в map.yml)";
        }
        ok(s, "Карта «" + m.name + "»: " + MapTime.label(m.instance.getTime()) + saved);
    }

    private void add(CommandSender s, Maps.GameMap m, long ticks) {
        if (m == null) return;
        m.instance.setTime(m.instance.getTime() + ticks);
        ok(s, "Карта «" + m.name + "»: " + MapTime.label(m.instance.getTime()));
    }

    private void speed(CommandSender s, Maps.GameMap m, double sp) {
        if (m == null) return;
        var c = MapTime.cfg(m.instance).withSpeed(MapTime.clampSpeed(sp));
        if (sp > 0 && !c.cycle()) c = c.withCycle(true);                      // скорость > 0 — значит, время должно идти
        if (!c.running()) c = c.withStart(MapTime.dayTime(m.instance));        // speed 0 — запомнить время, как cycle off
        apply(s, m, c);
        ok(s, "Карта «" + m.name + "»: скорость ×" + MapTime.fmt(c.speed())
                + (c.running() ? ", сутки ≈ " + dayLen(c.speed()) : ", время стоит"));
    }

    private void cycle(CommandSender s, Maps.GameMap m, boolean on) {
        if (m == null) return;
        var c = MapTime.cfg(m.instance).withCycle(on);
        if (on && c.speed() <= 0) c = c.withSpeed(1.0);
        // остановили — запомнить текущее время как start (после перезапуска то же время)
        if (!on) c = c.withStart(MapTime.dayTime(m.instance));
        apply(s, m, c);
        ok(s, "Карта «" + m.name + "»: цикл суток " + (on ? "включён, сутки ≈ " + dayLen(c.speed()) : "выключен, " + MapTime.label(m.instance.getTime())));
    }

    private void apply(CommandSender s, Maps.GameMap m, MapTime.Cfg c) {
        MapTime.apply(m.instance, c, false);
        persist(s, m, c);
    }

    private boolean persist(CommandSender s, Maps.GameMap m, MapTime.Cfg c) {
        try {
            MapTime.save(m.meta(), c);
            MapTime.apply(m.instance, c, false);
            return true;
        } catch (Exception e) {
            err(s, "не записать map.yml: " + e.getMessage() + " (действует до перезапуска)");
            return false;
        }
    }

    private static String dayLen(double speed) {
        double min = 20.0 / speed;
        return min >= 1 ? String.format(Locale.ROOT, "%.1f мин", min) : String.format(Locale.ROOT, "%.0f с", min * 60);
    }
}
