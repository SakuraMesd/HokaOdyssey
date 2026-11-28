package ink.hoka.odyssey.stations;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * /hostation (только OP и консоль): выделение указкой или pos1/pos2, создание и правка регионов станций.
 * <pre>
 * wand | pos1 | pos2 | sel clear | sel expand &lt;блоков&gt; [up|down|all|sides]
 * create &lt;id&gt; [название] | add-box &lt;id&gt; | remove &lt;id&gt; | rename &lt;id&gt; &lt;название&gt;
 * list [карта] | info &lt;id&gt; | tp &lt;id&gt; | expand &lt;id&gt; &lt;блоков&gt; [up|down|all|sides]
 * gravity &lt;id&gt; normal|vacuum|map | show [id] | reload
 * </pre>
 */
final class StationCommand extends Command {

    private final StationsModule m;

    StationCommand(StationsModule m) {
        super("hostation");
        this.m = m;
        setCondition((s, c) -> !(s instanceof Player p) || m.server().isOp(p));
        setDefaultExecutor((s, c) -> help(s));

        ArgumentWord id = ArgumentType.Word("id");
        id.setSuggestionCallback((s, c, sug) -> { for (Region r : m.regions.all()) sug.addEntry(new SuggestionEntry(r.id(), Component.text(r.title()))); });
        var title = ArgumentType.StringArray("название");
        var blocks = ArgumentType.Integer("блоков");
        var dir = ArgumentType.Word("куда").from("up", "down", "all", "sides");
        var grav = ArgumentType.Word("гравитация").from("normal", "vacuum", "map");
        var map = ArgumentType.Word("карта");
        map.setSuggestionCallback((s, c, sug) -> { for (String n : m.server().mapNames()) sug.addEntry(new SuggestionEntry(n)); });

        addSyntax((s, c) -> player(s, p -> {
            p.getInventory().addItemStack(StationsModule.wandItem());
            p.sendMessage(Component.text("Указка станций: ЛКМ по блоку — точка 1, ПКМ — точка 2", NamedTextColor.YELLOW));
        }), ArgumentType.Literal("wand"));
        addSyntax((s, c) -> player(s, p -> point(p, 1)), ArgumentType.Literal("pos1"));
        addSyntax((s, c) -> player(s, p -> point(p, 2)), ArgumentType.Literal("pos2"));
        addSyntax((s, c) -> player(s, p -> { m.setSelection(p, Selection.NONE); p.sendMessage(Component.text("Выделение снято", NamedTextColor.GRAY)); }),
                ArgumentType.Literal("sel"), ArgumentType.Literal("clear"));
        addSyntax((s, c) -> player(s, p -> selExpand(p, c.get(blocks), "all")), ArgumentType.Literal("sel"), ArgumentType.Literal("expand"), blocks);
        addSyntax((s, c) -> player(s, p -> selExpand(p, c.get(blocks), c.get(dir))), ArgumentType.Literal("sel"), ArgumentType.Literal("expand"), blocks, dir);

        addSyntax((s, c) -> player(s, p -> create(p, c.get(id), null)), ArgumentType.Literal("create"), id);
        addSyntax((s, c) -> player(s, p -> create(p, c.get(id), String.join(" ", c.get(title)))), ArgumentType.Literal("create"), id, title);
        addSyntax((s, c) -> player(s, p -> edit(s, () -> {
            Selection sel = m.selection(p);
            Region r = m.regions.addBox(c.get(id), sel.map() == null ? "" : sel.map(), sel.box());
            m.changed(r.map());
            ok(s, "Регион «" + r.id() + "»: коробок " + r.boxes().size() + ", объём " + r.volume());
        })), ArgumentType.Literal("add-box"), id);
        addSyntax((s, c) -> edit(s, () -> {
            Region r = m.regions.remove(c.get(id));
            m.changed(r.map());
            ok(s, "Регион «" + r.id() + "» удалён");
        }), ArgumentType.Literal("remove"), id);
        addSyntax((s, c) -> edit(s, () -> {
            Region r = m.regions.rename(c.get(id), String.join(" ", c.get(title)));
            m.changed(r.map());
            ok(s, "Регион «" + r.id() + "» теперь «" + r.title() + "»");
        }), ArgumentType.Literal("rename"), id, title);
        addSyntax((s, c) -> list(s, null), ArgumentType.Literal("list"));
        addSyntax((s, c) -> list(s, c.get(map)), ArgumentType.Literal("list"), map);
        addSyntax((s, c) -> query(s, () -> info(s, m.regions.require(c.get(id)))), ArgumentType.Literal("info"), id);
        addSyntax((s, c) -> player(s, p -> query(s, () -> tp(p, m.regions.require(c.get(id))))), ArgumentType.Literal("tp"), id);
        addSyntax((s, c) -> expand(s, c.get(id), c.get(blocks), "all"), ArgumentType.Literal("expand"), id, blocks);
        addSyntax((s, c) -> expand(s, c.get(id), c.get(blocks), c.get(dir)), ArgumentType.Literal("expand"), id, blocks, dir);
        addSyntax((s, c) -> edit(s, () -> {
            Region r = m.regions.gravity(c.get(id), Grav.parse(c.get(grav)));
            m.changed(r.map());
            ok(s, "Регион «" + r.id() + "»: гравитация " + r.gravity().key() + (m.hasGravity() ? "" : " (модуля gravity нет — пока не действует)"));
        }), ArgumentType.Literal("gravity"), id, grav);
        addSyntax((s, c) -> player(s, p -> show(p, null)), ArgumentType.Literal("show"));
        addSyntax((s, c) -> player(s, p -> show(p, c.get(id))), ArgumentType.Literal("show"), id);
        addSyntax((s, c) -> {
            String err = m.loadRegions();
            m.resendMap(null);
            if (err == null) ok(s, "Регионы перечитаны: " + m.regions.all().size());
            else s.sendMessage(Component.text(err + " — регионы пусты, сохранение выключено", NamedTextColor.RED));
        }, ArgumentType.Literal("reload"));
    }

    private static void help(CommandSender s) {
        s.sendMessage(Component.text("""
                /hostation wand | pos1 | pos2 | sel clear | sel expand <блоков> [up|down|all|sides]
                /hostation create <id> [название] | add-box <id> | remove <id> | rename <id> <название>
                /hostation list [карта] | info <id> | tp <id> | expand <id> <блоков> [up|down|all|sides]
                /hostation gravity <id> normal|vacuum|map | show [id] | reload""", NamedTextColor.AQUA));
    }

    private interface PlayerAction { void run(Player p); }

    private static void player(CommandSender s, PlayerAction a) {
        if (s instanceof Player p) a.run(p);
        else s.sendMessage(Component.text("Только в игре", NamedTextColor.RED));
    }

    /** Правка регионов: отказ — красным; битый файл — запрет (иначе потеряем регионы при записи). */
    private void edit(CommandSender s, Runnable r) {
        query(s, () -> { guardBroken(); r.run(); });
    }

    /** Без правки (info, tp): отказ — красным. */
    private void query(CommandSender s, Runnable r) {
        try { r.run(); }
        catch (Regions.Refused e) { s.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED)); }
    }

    private void guardBroken() {
        if (m.broken()) throw new Regions.Refused("Файл regions.json битый — почините его и сделайте /hostation reload (правки сейчас не сохранятся)");
    }

    private static void ok(CommandSender s, String text) { s.sendMessage(Component.text(text, NamedTextColor.GREEN)); }

    private void point(Player p, int which) {
        Pos pos = p.getPosition();
        m.setPoint(p, which, pos.blockX(), pos.blockY(), pos.blockZ());
    }

    private void selExpand(Player p, int n, String dir) {
        Selection sel = m.selection(p);
        if (!sel.complete()) { p.sendMessage(Component.text("Нет выделения (две точки)", NamedTextColor.RED)); return; }
        int[] h = m.worldHeight(sel.map());
        Box b = sel.box().expand(n, dir(dir), h[0], h[1]);
        m.setSelection(p, Selection.of(sel.map(), b));
        p.sendMessage(Component.text("Выделено " + b.describe(), NamedTextColor.YELLOW));
    }

    static Box.Dir dir(String s) { return Box.Dir.valueOf(s.toUpperCase(Locale.ROOT)); }

    private void create(Player p, String id, String title) {
        edit(p, () -> {
            Selection sel = m.selection(p);
            Region r = m.regions.create(id, sel.map(), title, sel.box(), null);
            m.changed(r.map());
            ok(p, "Регион «" + r.id() + "» («" + r.title() + "») на карте " + r.map() + ": " + r.boxes().getFirst().describe());
        });
    }

    private void expand(CommandSender s, String id, int n, String dir) {
        edit(s, () -> {
            Region old = m.regions.require(id);
            int[] h = m.worldHeight(old.map());
            Region r = m.regions.expand(id, n, dir(dir), h[0], h[1]);
            m.changed(r.map());
            ok(s, "Регион «" + r.id() + "» расширен (" + dir + " " + n + "), объём " + r.volume());
        });
    }

    private void list(CommandSender s, String map) {
        List<Region> l = m.list(map);
        if (l.isEmpty()) { s.sendMessage(Component.text("Регионов нет" + (map == null ? "" : " на карте " + map), NamedTextColor.GRAY)); return; }
        s.sendMessage(Component.text("Регионы" + (map == null ? "" : " карты " + map) + ": " + l.size(), NamedTextColor.AQUA));
        for (Region r : l) {
            s.sendMessage(Component.text(" • " + r.id(), NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.runCommand("/hostation info " + r.id()))
                    .append(Component.text(" «" + r.title() + "» " + r.map() + ", " + r.gravity().key() + ", коробок " + r.boxes().size(), NamedTextColor.GRAY)));
        }
    }

    private void info(CommandSender s, Region r) {
        s.sendMessage(Component.text("Регион «" + r.id() + "» — " + r.title(), NamedTextColor.AQUA));
        s.sendMessage(Component.text(" карта " + r.map() + ", гравитация " + r.gravity().key() + ", объём " + r.volume()
                + (r.owner() == null ? "" : ", владелец " + r.owner()), NamedTextColor.GRAY));
        int i = 1;
        for (Box b : r.boxes()) s.sendMessage(Component.text(" коробка " + i++ + ": " + b.describe(), NamedTextColor.GRAY));
    }

    private void tp(Player p, Region r) {
        var inst = m.server().map(r.map()).orElseThrow(() -> new Regions.Refused("Нет карты " + r.map()));
        Box b = r.boxes().getFirst();
        Pos to = new Pos(b.centerX(), b.centerY(), b.centerZ(), p.getPosition().yaw(), p.getPosition().pitch());
        m.server().transfer(p, inst, to);
        ok(p, "Телепорт в регион «" + r.id() + "»");
    }

    private void show(Player p, String id) {
        if (id != null && m.regions.get(id) == null) { p.sendMessage(Component.text("Нет региона «" + id + "»", NamedTextColor.RED)); return; }
        if (!m.ready(p)) { p.sendMessage(Component.text("Границы рисует мод Hoka Odyssey — у вас клиентский модуль stations не загружен", NamedTextColor.RED)); return; }
        boolean on = m.toggleShow(p, id);
        p.sendMessage(Component.text(on ? "Показ регионов на " + m.showSeconds + " с" + (id == null ? "" : " («" + id + "»)") : "Показ регионов выключен", NamedTextColor.YELLOW));
    }
}
