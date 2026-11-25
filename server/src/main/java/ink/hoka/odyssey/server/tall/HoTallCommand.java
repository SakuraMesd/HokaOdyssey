package ink.hoka.odyssey.server.tall;

import ink.hoka.odyssey.server.maps.Maps;
import ink.hoka.odyssey.server.perms.Ops;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;

/** /hotall [y] — на высокую карту «tall», на высоту y (площадки: ±2000, ±6000, ±12000, ±15000). */
public final class HoTallCommand extends Command {

    public HoTallCommand(Maps maps, Ops ops) {
        super("hotall");
        setCondition(ops.admin());
        var yArg = ArgumentType.Integer("y").between(TallWorld.MIN_Y, TallWorld.MIN_Y + TallWorld.HEIGHT - 3);
        setDefaultExecutor((s, c) -> go(s, maps, 41));
        addSyntax((s, c) -> go(s, maps, c.get(yArg) + 1), yArg);
    }

    private static void go(net.minestom.server.command.CommandSender s, Maps maps, int y) {
        if (!(s instanceof Player p)) return;
        if (!TallWorld.enabled) { s.sendMessage(Component.text("Высокий мир выключен (нет карты с tall: true в map.yml)", NamedTextColor.RED)); return; }
        Maps.GameMap m = maps.load("tall");
        if (m == null) { s.sendMessage(Component.text("Нет карты «tall»", NamedTextColor.RED)); return; }
        maps.touch(m);
        Pos to = new Pos(0.5, y, 0.5, p.getPosition().yaw(), p.getPosition().pitch());
        if (p.getInstance() == m.instance) p.teleport(to);
        else p.setInstance(m.instance, to);
        p.setFlying(true);
        s.sendMessage(Component.text("Высокий мир: y = " + (y - 1), NamedTextColor.GREEN));
    }
}
