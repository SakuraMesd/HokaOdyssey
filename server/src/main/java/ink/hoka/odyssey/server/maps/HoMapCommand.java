package ink.hoka.odyssey.server.maps;

import ink.hoka.odyssey.server.perms.Ops;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.Player;

/**
 * /homap — карты (только OP и консоль):
 *   /homap list                         — все карты (загруженные отмечены, с числом игроков)
 *   /homap tp &lt;карта&gt; [игрок]          — перейти на карту (на её точку появления)
 *   /homap create &lt;имя&gt; [flat|void]     — новая карта
 *   /homap delete &lt;карта&gt; confirm      — удалить карту с диска (игроки уйдут на основную)
 *   /homap setspawn                     — точка появления карты = где вы стоите
 */
public final class HoMapCommand extends Command {

    private final Maps maps;

    public HoMapCommand(Maps maps, Ops ops) {
        super("homap");
        this.maps = maps;
        setCondition(ops.admin());
        setDefaultExecutor((s, c) -> help(s));

        var mapArg = ArgumentType.Word("карта").setSuggestionCallback((sender, ctx, sug) -> {
            for (String n : maps.names()) sug.addEntry(new SuggestionEntry(n));
        });
        var nameArg = ArgumentType.Word("имя");
        var genArg = ArgumentType.Word("генерация").from("flat", "void");
        var playerArg = ArgumentType.Word("игрок").setSuggestionCallback((sender, ctx, sug) -> {
            for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) sug.addEntry(new SuggestionEntry(p.getUsername()));
        });

        addSyntax((s, c) -> list(s), ArgumentType.Literal("list"));
        addSyntax((s, c) -> {
            if (!(s instanceof Player p)) { err(s, "из консоли: /homap tp <карта> <игрок>"); return; }
            tp(s, p, c.get(mapArg));
        }, ArgumentType.Literal("tp"), mapArg);
        addSyntax((s, c) -> {
            Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(c.get(playerArg));
            if (p == null) { err(s, "игрок «" + c.get(playerArg) + "» не в сети"); return; }
            tp(s, p, c.get(mapArg));
        }, ArgumentType.Literal("tp"), mapArg, playerArg);
        addSyntax((s, c) -> create(s, c.get(nameArg), "flat"), ArgumentType.Literal("create"), nameArg);
        addSyntax((s, c) -> create(s, c.get(nameArg), c.get(genArg)), ArgumentType.Literal("create"), nameArg, genArg);
        addSyntax((s, c) -> err(s, "удаление стирает карту с диска. Подтвердите: /homap delete " + c.get(mapArg) + " confirm"),
                ArgumentType.Literal("delete"), mapArg);
        addSyntax((s, c) -> delete(s, c.get(mapArg)), ArgumentType.Literal("delete"), mapArg, ArgumentType.Literal("confirm"));
        addSyntax((s, c) -> setSpawn(s), ArgumentType.Literal("setspawn"));
    }

    private void help(CommandSender s) {
        info(s, "/homap list — карты");
        info(s, "/homap tp <карта> [игрок] — перейти на карту");
        info(s, "/homap create <имя> [flat|void] — новая карта");
        info(s, "/homap delete <карта> confirm — удалить карту");
        info(s, "/homap setspawn — точка появления карты здесь");
    }

    private void list(CommandSender s) {
        var names = maps.names();
        info(s, "Карты (" + names.size() + "):");
        for (String n : names) {
            var m = maps.loadedMap(n);
            String st = m.map(g -> "загружена, игроков: " + g.instance.getPlayers().size()).orElse("не загружена");
            s.sendMessage(Component.text("  " + n + (Maps.DEFAULT.equals(n) ? " (основная)" : "") + " — " + st, NamedTextColor.GRAY));
        }
    }

    private void tp(CommandSender s, Player target, String name) {
        Maps.GameMap m = maps.load(name);
        if (m == null) { err(s, "карты «" + name + "» нет. Список: /homap list"); return; }
        maps.touch(m);
        var move = target.getInstance() == m.instance ? target.teleport(m.spawn()) : target.setInstance(m.instance, m.spawn());   // уже на этой карте — на спавн
        move.thenRun(() -> {
            target.setRespawnPoint(m.spawn());
            ok(target, "Вы на карте «" + name + "»");
            if (s != target) ok(s, target.getUsername() + " → «" + name + "»");
        });
    }

    private void create(CommandSender s, String name, String gen) {
        Maps.Generator g = Maps.Generator.of(gen);
        if (g == null) { err(s, "генерация: flat или void"); return; }
        try {
            maps.create(name.toLowerCase(), g);
            ok(s, "Карта «" + name.toLowerCase() + "» создана (" + g.desc + "). Перейти: /homap tp " + name.toLowerCase());
        } catch (RuntimeException e) {
            err(s, e.getMessage());
        }
    }

    private void delete(CommandSender s, String name) {
        maps.delete(name).whenComplete((v, e) -> {
            if (e != null) err(s, (e.getCause() != null ? e.getCause() : e).getMessage());
            else ok(s, "Карта «" + name + "» удалена");
        });
    }

    private void setSpawn(CommandSender s) {
        if (!(s instanceof Player p) || p.getInstance() == null) { err(s, "только в игре"); return; }
        for (Maps.GameMap m : maps.loadedMaps()) {
            if (m.instance != p.getInstance()) continue;
            m.setSpawn(p.getPosition());
            ok(s, "Точка появления карты «" + m.name + "» — здесь");
            return;
        }
        err(s, "вы не на карте из maps/");
    }

    static void ok(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.GREEN)); }
    static void info(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.AQUA)); }
    static void err(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.RED)); }
}
