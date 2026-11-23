package ink.hoka.odyssey.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ядро-мод Hoka Odyssey. Само ничего не рисует и не решает: здоровается с сервером, получает подписанный список
 * клиентских модулей, докачивает недостающие, загружает их и даёт им крючки (тик, HUD, мышь, камера, сообщения).
 * Новые возможности добавляются модулями с сервера — переустанавливать мод не нужно.
 */
public final class HokaOdysseyClient implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey");

    @Override
    public void onInitializeClient() {
        Net.register();
        if (Boolean.getBoolean("hoka.mixincheck")) {                            // проверка сборки: применить все миксины сразу
            for (String c : new String[]{"net.minecraft.client.player.KeyboardInput", "net.minecraft.client.player.ClientInput",
                    "net.minecraft.world.entity.LivingEntity", "net.minecraft.world.entity.Entity", "net.minecraft.client.Camera"}) {
                try { Class.forName(c); } catch (Throwable t) { throw new RuntimeException("mixincheck " + c, t); }
            }
            org.slf4j.LoggerFactory.getLogger("HokaOdyssey").info("mixincheck OK");
        }
        // пак ресурсов сервера больше не используется (0.10) — убрать остатки прежних версий
        try {
            java.nio.file.Path old = ModuleManager.ROOT.resolve("pack");
            if (java.nio.file.Files.isDirectory(old)) try (var w = java.nio.file.Files.walk(old)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
            java.nio.file.Files.deleteIfExists(ModuleManager.ROOT.resolve("active"));
        } catch (Exception ignored) {
        }
        ClientPlayNetworking.registerGlobalReceiver(Net.S2C.TYPE, (p, ctx) -> ModuleManager.handle(p.data()));
        // сервер объявил канал ядра — значит, это наш сервер: представляемся, он пришлёт список модулей
        C2SPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> {
            if (channels.contains(Net.C2S.TYPE.id())) client.execute(ModuleManager::hello);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ModuleManager::unloadAll));
        ClientTickEvents.END_CLIENT_TICK.register(c -> Hooks.tick());
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(Net.NS, "modules"), Hooks::hud);
        ink.hoka.odyssey.client.model.Models.init(new ink.hoka.odyssey.client.model.Models.Source() {
            @Override public byte[] get(String path) { return FileStore.get(path).orElse(null); }
            @Override public void listen(String path, java.util.function.Consumer<byte[]> fn) { FileStore.listen("core", path, fn); }
        });
        ink.hoka.odyssey.client.block.CustomBlocks.init(new ink.hoka.odyssey.client.block.CustomBlocks.Source() {
            @Override public byte[] get(String path) { return FileStore.get(path).orElse(null); }
            @Override public void listen(String path, java.util.function.Consumer<byte[]> fn) { FileStore.listen("core", path, fn); }
        });
        ClientTickEvents.END_CLIENT_TICK.register(c -> ink.hoka.odyssey.client.block.CustomBlocks.tick());
        ink.hoka.odyssey.client.sound.CustomSounds.init(new ink.hoka.odyssey.client.sound.CustomSounds.Source() {
            @Override public byte[] get(String path) { return FileStore.get(path).orElse(null); }
            @Override public String sha(String path) { return FileStore.sha(path); }
            @Override public java.nio.file.Path object(String sha) { return ModuleManager.object(sha); }
            @Override public void listen(String path, java.util.function.Consumer<byte[]> fn) { FileStore.listen("core", path, fn); }
        });
        ClientTickEvents.END_CLIENT_TICK.register(c -> ink.hoka.odyssey.client.sound.CustomSounds.tick());
        ink.hoka.odyssey.client.ui.UiScreen.init(new ink.hoka.odyssey.client.ui.UiScreen.Source() {
            @Override public byte[] get(String path) { return FileStore.get(path).orElse(null); }
            @Override public String sha(String path) { return FileStore.sha(path); }
            @Override public net.minecraft.resources.Identifier texture(String path) { return FileStore.texture(path); }
            @Override public void send(byte[] payload) { Net.send(5, out -> out.write(payload)); }
        });
        ink.hoka.odyssey.client.ui.UiHud.register();
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.END_EXTRACTION.register(ink.hoka.odyssey.client.model.Models::extract);
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(ink.hoka.odyssey.client.model.Models::render);
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(Hooks::world);
        LOG.info("Hoka Odyssey {}: ждём сервер", ModuleManager.coreVersion());
    }
}
