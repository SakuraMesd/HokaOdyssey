package ink.hoka.odyssey.server.perms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.Player;

/**
 * /hoop &lt;ник&gt; — дать или забрать OP (только OP и консоль). /hoop — список операторов.
 * Игрок может быть не в сети: OP применится при входе.
 */
public final class HoOpCommand extends Command {

    public HoOpCommand(Ops ops) {
        super("hoop");
        setCondition(ops.admin());
        setDefaultExecutor((s, c) -> {
            var names = ops.names();
            s.sendMessage(Component.text(names.isEmpty() ? "Операторов нет. Дать: /hoop <ник>" : "Операторы: " + String.join(", ", names), NamedTextColor.AQUA));
        });
        var nick = ArgumentType.Word("ник").setSuggestionCallback((sender, ctx, sug) -> {
            for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) sug.addEntry(new SuggestionEntry(p.getUsername()));
        });
        addSyntax((s, c) -> {
            String name = c.get(nick);
            if (!name.matches("[A-Za-z0-9_]{3,16}")) { s.sendMessage(Component.text("Неверный ник", NamedTextColor.RED)); return; }
            if (s instanceof Player p && p.getUsername().equalsIgnoreCase(name) && ops.isOp(name)) {
                s.sendMessage(Component.text("Себя снять нельзя — попросите другого OP или консоль", NamedTextColor.RED));
                return;
            }
            boolean now = ops.toggle(name);
            Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
            s.sendMessage(Component.text(name + (now ? " теперь OP" : " больше не OP") + (target == null ? " (применится при входе)" : ""),
                    now ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            if (target != null && target != s)
                target.sendMessage(Component.text(now ? "Вам выданы права оператора" : "С вас сняты права оператора", now ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        }, nick);
    }
}
