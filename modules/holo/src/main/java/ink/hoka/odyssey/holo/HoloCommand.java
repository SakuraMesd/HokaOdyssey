package ink.hoka.odyssey.holo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;

import java.util.function.Consumer;

/**
 * /hoholo (только OP и консоль). Главное — редактор в моде ({@code edit} или клавиша J), команды — минимум:
 * <pre>
 * edit [id] | create &lt;id&gt; | remove &lt;id&gt; | list | tp &lt;id&gt; | movehere &lt;id&gt; | copy &lt;id&gt; &lt;новый&gt;
 * addline &lt;id&gt; &lt;текст MiniMessage&gt; | reload
 * </pre>
 */
final class HoloCommand extends Command {

    private final HoloModule m;

    HoloCommand(HoloModule m) {
        super("hoholo", "holo");
        this.m = m;
        setCondition((s, c) -> !(s instanceof Player p) || m.isOp(p));
        setDefaultExecutor((s, c) -> help(s));

        ArgumentWord id = ArgumentType.Word("id");
        id.setSuggestionCallback((s, c, sug) -> { for (Hologram h : m.sorted()) sug.addEntry(new SuggestionEntry(h.id)); });
        ArgumentWord fresh = ArgumentType.Word("новый");
        var text = ArgumentType.StringArray("текст");

        addSyntax((s, c) -> player(s, p -> m.openEditor(p, "")), ArgumentType.Literal("edit"));
        addSyntax((s, c) -> player(s, p -> m.openEditor(p, c.get(id))), ArgumentType.Literal("edit"), id);
        addSyntax((s, c) -> player(s, p -> create(p, c.get(id))), ArgumentType.Literal("create"), id);
        addSyntax((s, c) -> {
            Hologram h = m.holos.get(c.get(id));
            if (h != null && h.temporary) { err(s, "«" + h.id + "» — временная голограмма модуля"); return; }
            if (m.remove(c.get(id))) ok(s, "Голограмма «" + c.get(id) + "» удалена");
            else err(s, "Нет голограммы «" + c.get(id) + "»");
        }, ArgumentType.Literal("remove"), id);
        addSyntax((s, c) -> list(s), ArgumentType.Literal("list"));
        addSyntax((s, c) -> player(s, p -> with(s, c.get(id), h -> m.teleport(p, h))), ArgumentType.Literal("tp"), id);
        addSyntax((s, c) -> player(s, p -> with(s, c.get(id), h -> {
            place(p, h);
            m.update(h);
            ok(s, "«" + h.id + "» перед вами");
        })), ArgumentType.Literal("movehere"), id);
        addSyntax((s, c) -> with(s, c.get(id), h -> {
            String n = c.get(fresh);
            if (!Hologram.validId(n)) { err(s, "Плохой id «" + n + "» (a-z 0-9 _ -)"); return; }
            if (m.holos.containsKey(n)) { err(s, "«" + n + "» уже есть"); return; }
            Hologram copy = HoloCodec.copy(h, n);
            if (s instanceof Player p) place(p, copy);
            copy.creatorOp = true;
            m.put(copy, true);
            ok(s, "Копия «" + n + "» создана");
        }), ArgumentType.Literal("copy"), id, fresh);
        addSyntax((s, c) -> with(s, c.get(id), h -> {
            Hologram.Page pg = h.pages.get(0);
            if (pg.lines.size() >= HoloCodec.MAX_LINES) { err(s, "Строк уже " + HoloCodec.MAX_LINES); return; }
            pg.lines.add(new Hologram.Line(Hologram.Element.text(String.join(" ", c.get(text)))));
            h.creatorOp |= !(s instanceof Player) || m.isOp((Player) s);
            m.update(h);
            ok(s, "Строка добавлена в «" + h.id + "»");
        }), ArgumentType.Literal("addline"), id, text);
        addSyntax((s, c) -> m.reloadAll(s), ArgumentType.Literal("reload"));
    }

    private void create(Player p, String id) {
        if (!Hologram.validId(id)) { err(p, "Плохой id «" + id + "» (a-z 0-9 _ -)"); return; }
        if (m.holos.containsKey(id)) { err(p, "«" + id + "» уже есть — /hoholo edit " + id); return; }
        Hologram h = new Hologram(id);
        place(p, h);
        h.creatorOp = true;
        Hologram.Page pg = new Hologram.Page();
        pg.lines.add(new Hologram.Line(Hologram.Element.text("<b><#8FD8FF>" + id.toUpperCase() + "</#8FD8FF></b>")));
        pg.lines.add(new Hologram.Line(new Hologram.Element(Hologram.Type.SEPARATOR)));
        pg.lines.add(new Hologram.Line(Hologram.Element.text("<gray>Правка: </gray><#FFC46B>/hoholo edit " + id)));
        h.pages.add(pg);
        m.put(h, true);
        p.sendMessage(Component.text("Голограмма «" + id + "» создана. ", NamedTextColor.GREEN)
                .append(Component.text("[Открыть редактор]", NamedTextColor.GOLD).clickEvent(ClickEvent.runCommand("/hoholo edit " + id))));
    }

    /** Перед игроком на 2.5 блока на уровне глаз, лицом к нему. */
    private void place(Player p, Hologram h) {
        Pos pos = p.getPosition();
        Vec dir = pos.direction().withY(0);
        dir = dir.lengthSquared() < 1e-6 ? new Vec(0, 0, 1) : dir.normalize();
        h.x = Math.round((pos.x() + dir.x() * 2.5) * 100) / 100.0;
        h.y = Math.round((pos.y() + p.getEyeHeight()) * 100) / 100.0;
        h.z = Math.round((pos.z() + dir.z() * 2.5) * 100) / 100.0;
        h.yaw = Math.round(pos.yaw());
        h.pitch = 0;
        String map = m.mapOf(p);
        if (map != null) h.map = map;
    }

    private void list(CommandSender s) {
        if (m.holos.isEmpty()) { s.sendMessage(Component.text("Голограмм нет. /hoholo create <id>", NamedTextColor.GRAY)); return; }
        s.sendMessage(Component.text("Голограммы (" + m.holos.size() + "):", NamedTextColor.AQUA));
        for (Hologram h : m.sorted()) {
            Component line = Component.text(" • " + h.id, NamedTextColor.WHITE)
                    .append(Component.text(String.format(" %s %.1f %.1f %.1f", h.map, h.x, h.y, h.z), NamedTextColor.GRAY))
                    .append(Component.text(h.temporary ? " (временная)" : "", NamedTextColor.DARK_GRAY));
            if (s instanceof Player)
                line = line.append(Component.text(" [tp]", NamedTextColor.GOLD).clickEvent(ClickEvent.runCommand("/hoholo tp " + h.id)))
                        .append(Component.text(" [ред.]", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/hoholo edit " + h.id)));
            s.sendMessage(line);
        }
    }

    private void with(CommandSender s, String id, Consumer<Hologram> f) {
        Hologram h = m.holos.get(id);
        if (h == null) { err(s, "Нет голограммы «" + id + "»"); return; }
        if (h.temporary) { err(s, "«" + id + "» — временная голограмма модуля"); return; }
        f.accept(h);
    }

    private static void player(CommandSender s, Consumer<Player> f) {
        if (s instanceof Player p) f.accept(p);
        else err(s, "Только из игры");
    }

    private static void ok(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.GREEN)); }

    private static void err(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.RED)); }

    private static void help(CommandSender s) {
        s.sendMessage(Component.text("""
                /hoholo edit [id] — редактор (или клавиша J, глядя на голограмму)
                /hoholo create <id> | remove <id> | list | tp <id> | movehere <id>
                /hoholo copy <id> <новый> | addline <id> <текст> | reload""", NamedTextColor.AQUA));
    }
}
