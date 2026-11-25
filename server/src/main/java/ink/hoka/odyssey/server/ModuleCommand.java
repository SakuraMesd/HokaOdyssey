package ink.hoka.odyssey.server;

import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.arguments.ArgumentString;
import net.minestom.server.entity.Player;

/**
 * /homodule — управление модулями (консоль и OP):
 *   homodule list               — список: включённые и не включившиеся (с причиной)
 *   homodule info &lt;id&gt;         — подробности
 *   homodule reload &lt;id&gt;       — перечитать jar из module/ и перезапустить (вместе с зависящими модулями)
 *   homodule unload &lt;id&gt;       — выключить (вместе с зависящими)
 *   homodule load &lt;файл.jar&gt;   — включить новый jar, положенный в module/
 */
final class ModuleCommand extends Command {

    ModuleCommand(ModuleManager mm, ink.hoka.odyssey.server.perms.Ops ops) {
        super("homodule");
        setCondition(ops.admin());                                             // консоль и OP
        setDefaultExecutor((s, c) -> list(s, mm));

        ArgumentWord action = ArgumentType.Word("action").from("list", "info", "reload", "unload", "load");
        ArgumentString target = ArgumentType.String("target");
        addSyntax((s, c) -> {
            if (!c.get(action).equals("list")) s.sendMessage("Нужно имя: homodule " + c.get(action) + " <id>");
            else list(s, mm);
        }, action);
        addSyntax((s, c) -> {
            String t = c.get(target);
            switch (c.get(action)) {
                case "info" -> info(s, mm, t);
                case "reload" -> s.sendMessage("reload " + t + ": " + mm.reload(t));
                case "unload" -> {
                    try {
                        var off = mm.unload(t);
                        s.sendMessage(off.isEmpty() ? "модуль «" + t + "» не включён" : "выключены: " + String.join(", ", off));
                    } catch (ModuleManager.Refused e) { s.sendMessage("нельзя: " + e.getMessage()); }
                }
                case "load" -> s.sendMessage(mm.loadFile(t));
                default -> list(s, mm);
            }
        }, action, target);
    }

    private static void list(CommandSender s, ModuleManager mm) {
        s.sendMessage("Модули (" + mm.dir() + "): включено " + mm.enabledList().size() + ", с ошибкой " + mm.failedList().size());
        for (var l : mm.enabledList()) s.sendMessage("  ✔ " + l.d.display());
        for (var l : mm.failedList()) s.sendMessage("  ✘ " + l.d.display() + " — " + l.error);
    }

    private static void info(CommandSender s, ModuleManager mm, String id) {
        var all = new java.util.ArrayList<ModuleManager.Loaded>(mm.enabledList());
        all.addAll(mm.failedList());
        for (var l : all) {
            if (!l.d.id().equals(id)) continue;
            s.sendMessage(l.d.display() + " — " + (l.state == ModuleManager.State.ENABLED ? "включён" : "ошибка: " + l.error));
            s.sendMessage("  jar: " + l.jar.getFileName() + ", main: " + l.d.main());
            if (!l.d.authors().isEmpty()) s.sendMessage("  авторы: " + String.join(", ", l.d.authors()));
            if (!l.d.description().isEmpty()) s.sendMessage("  " + l.d.description());
            if (!l.d.depends().isEmpty()) s.sendMessage("  depends: " + String.join(", ", l.d.depends()));
            if (!l.d.softDepends().isEmpty()) s.sendMessage("  softdepends: " + String.join(", ", l.d.softDepends()));
            return;
        }
        s.sendMessage("модуль «" + id + "» не найден");
    }
}
