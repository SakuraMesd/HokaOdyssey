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
        ClientPlayNetworking.registerGlobalReceiver(Net.W.TYPE, (p, ctx) -> CubeReceiver.handle(p.data(), p.parsed()));
        // высокий мир: сигнал сервера на этапе входа; на каждом новом входе — сначала ванильный формат
        net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents.INIT.register((h, mc) -> TallWorld.net = false);
        net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking.registerGlobalReceiver(Net.Tall.TYPE, (p, ctx) -> {
            TallWorld.net = true;
            BetterSnowGuard.install();                                     // 0.50: чужой «Better Snow» — мимо (сборка сеток ×2,7)
            var fl = net.fabricmc.loader.api.FabricLoader.getInstance();
            var sodium = fl.getModContainer("sodium");
            ctx.responseSender().sendPacket(new Net.Hi(ModuleManager.coreVersion(),
                    sodium.isPresent(), sodium.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("")));
            // дальность — заново, уже с потолком TallWorld.VIEW_CAP (первая ушла до сигнала, без него)
            if (TallWorld.VIEW_CAP > 0) ctx.responseSender().sendPacket(new net.minecraft.network.protocol.common.ServerboundClientInformationPacket(
                    ctx.client().options.buildPlayerInformation()));
            org.slf4j.LoggerFactory.getLogger("HokaOdyssey").info("высокий мир: позиции блоков в формате Hoka (±{} по высоте)",
                    net.minecraft.world.level.dimension.DimensionType.MAX_Y);
        });
        // сервер объявил канал ядра — значит, это наш сервер: представляемся, он пришлёт список модулей
        C2SPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> {
            if (channels.contains(Net.C2S.TYPE.id())) client.execute(ModuleManager::hello);
            if (channels.contains(Net.WC.TYPE.id())) client.execute(() -> { VerticalView.send(true); CubeCache.channelReady(); });   // дальность по высоте и HAVE (0.51) — серверу
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { if (TallWorld.net) org.slf4j.LoggerFactory.getLogger("HokaOdyssey").info("высокий мир, секции: {}", SparseSections.stats() + "; " + CubeReceiver.stats() + "; " + ServerLight.stats() + "; " + LocalLight.stats() + "; " + LagReport.stats() + "; " + TallLight.stats() + "; " + TallHeight.stats() + "; " + ClientGen.stats()); TallWorld.net = false; client.execute(ModuleManager::unloadAll); client.execute(() -> { CubeCache.sync(null); CubeCache.disconnected(); }); client.execute(ServerLight::reset); client.execute(LocalLight::disconnected); client.execute(ContentRange::reset); client.execute(SectionDirty::clear); client.execute(CubeQueue::clear); client.execute(ColumnHeal::reset); client.execute(ClientGen::disconnected); client.execute(TallLight::reset); VerticalView.reset(); });
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
        ink.hoka.odyssey.client.lod.LodRenderer.register();               // 0.64: дальняя прорисовка
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.END_EXTRACTION.register(ink.hoka.odyssey.client.model.Models::extract);
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(ink.hoka.odyssey.client.model.Models::render);
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(Hooks::world);
        LOG.info("Hoka Odyssey {}: ждём сервер", ModuleManager.coreVersion());
    }
}
