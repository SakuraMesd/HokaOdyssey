package ink.hoka.odyssey.example;

import ink.hoka.odyssey.api.OdysseyModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerSpawnEvent;

/**
 * Шаблон модуля: приветствие из конфига, команда /hohello, периодическая задача и сервис для других модулей.
 */
public final class ExampleModule extends OdysseyModule {

    /** Пример API, которое модуль отдаёт другим через services(). */
    public interface Greeter {
        Component greeting(Player p);
    }

    @Override
    protected void onEnable() {
        String text = config().getString("greeting", "Привет, %player%!");
        Greeter greeter = p -> Component.text(text.replace("%player%", p.getUsername()), NamedTextColor.AQUA);
        services().register(Greeter.class, greeter);

        events().addListener(PlayerSpawnEvent.class, e -> {
            if (e.isFirstSpawn()) e.getPlayer().sendMessage(greeter.greeting(e.getPlayer()));
        });

        Command hello = new Command("hohello");
        hello.setDefaultExecutor((sender, ctx) -> sender.sendMessage(
                sender instanceof Player p ? greeter.greeting(p) : Component.text("Привет из модуля " + id())));
        registerCommand(hello);

        int every = config().getInt("report-seconds", 300);
        if (every > 0) scheduler().repeat(() ->
                logger().info("онлайн: {}", MinecraftServer.getConnectionManager().getOnlinePlayers().size()), every * 20, every * 20);

        logger().info("модуль-пример включён (ядро {})", server().version());
    }

    @Override
    protected void onDisable() {
        logger().info("модуль-пример выключен");
    }
}
