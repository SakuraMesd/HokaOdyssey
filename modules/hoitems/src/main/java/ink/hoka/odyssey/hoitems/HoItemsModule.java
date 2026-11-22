package ink.hoka.odyssey.hoitems;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ink.hoka.odyssey.api.OdysseyModule;
import ink.hoka.odyssey.clientmods.ClientModsApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandExecutor;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Свои предметы: описание в config.yml, иконки и модели в assets/ → моду (файл {@code items/items.json} + файлы
 * {@code items/…}); предмет помечен {@code hoka_item} в custom_data — мод рисует его сам. Другие модули получают
 * предмет через {@link #item(String, int)} (сервис {@link HoItems}).
 */
public final class HoItemsModule extends OdysseyModule implements HoItems {

    public static final Tag<String> TAG = Tag.String("hoka_item");

    record Def(String id, Component name, List<Component> lore, Material material, String icon, String model,
               String display, float scale, int stack, String layer) {}

    private volatile Map<String, Def> defs = Map.of();
    private ClientModsApi client;
    private int publishedTo;

    @Override
    protected void onEnable() {
        client = services().get(ClientModsApi.class).orElseThrow();
        logger().info("свои предметы: {}", reload());
        services().register(HoItems.class, this);
        registerCommand(new HoItemCommand());
        scheduler().repeat(() -> {                                     // clientmods перезагружен — отдать заново
            ClientModsApi now = services().get(ClientModsApi.class).orElse(null);
            if (now != null && System.identityHashCode(now) != publishedTo) { client = now; publish(); }
        }, 100, 100);
    }

    @Override
    protected void onDisable() {
        services().unregister(HoItems.class);
        try { client.clearFiles(id()); client.publishFiles(); } catch (Throwable ignored) { }
    }

    synchronized String reload() {
        config().reload();
        Map<String, Def> d = new LinkedHashMap<>();
        for (String id : config().keys("items")) {
            String p = "items." + id + ".";
            String icon = config().getString(p + "icon", null), model = config().getString(p + "model", null);
            if (icon == null && model == null) { logger().warn("предмет {}: нет ни icon, ни model", id); continue; }
            Material mat = Material.fromKey(config().getString(p + "material", "paper"));
            if (mat == null) { logger().warn("предмет {}: нет material {}", id, config().getString(p + "material", "")); mat = Material.PAPER; }
            List<Component> lore = new ArrayList<>();
            for (String l : config().getStringList(p + "lore")) lore.add(Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            NamedTextColor color = NamedTextColor.NAMES.valueOr(config().getString(p + "color", "white").toLowerCase(), NamedTextColor.WHITE);
            d.put(id, new Def(id, Component.text(config().getString(p + "name", id), color).decoration(TextDecoration.ITALIC, false),
                    lore, mat, icon, model, config().getString(p + "display", null), (float) config().getDouble(p + "scale", 1),
                    config().getInt(p + "stack", 0), config().getString(p + "layer", "cutout")));
        }
        defs = d;
        int files = publish();
        return d.size() + " предметов, " + files + " файлов";
    }

    private int publish() {
        ClientModsApi api = client;
        api.clearFiles(id());
        Path assets = dataFolder().resolve("assets");
        int files = 0;
        try {
            Files.createDirectories(assets);
            try (Stream<Path> s = Files.walk(assets)) {
                for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    api.putFile(id(), "items/" + assets.relativize(f).toString().replace(java.io.File.separatorChar, '/'), Files.readAllBytes(f));
                    files++;
                }
            }
        } catch (IOException e) {
            logger().error("assets не прочитаны: {}", e.toString());
        }
        JsonArray arr = new JsonArray();
        for (Def def : defs.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", def.id);
            if (def.icon != null) o.addProperty("icon", "items/" + def.icon);
            if (def.model != null) o.addProperty("model", "items/" + def.model);
            if (def.display != null) o.addProperty("display", def.display);
            if (def.scale != 1f) o.addProperty("scale", def.scale);
            o.addProperty("layer", def.layer);
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("items", arr);
        api.putFile(id(), "items/items.json", new Gson().toJson(root).getBytes(StandardCharsets.UTF_8));
        api.publishFiles();
        publishedTo = System.identityHashCode(api);
        return files;
    }

    @Override
    public ItemStack item(String id, int count) {
        Def d = defs.get(id);
        if (d == null) return null;
        ItemStack s = ItemStack.of(d.material, count).with(DataComponents.CUSTOM_NAME, d.name).withTag(TAG, d.id);
        if (!d.lore.isEmpty()) s = s.with(DataComponents.LORE, d.lore);
        if (d.stack > 0) s = s.with(DataComponents.MAX_STACK_SIZE, Math.clamp(d.stack, 1, 99));
        return s;
    }

    @Override
    public String idOf(ItemStack stack) {
        String id = stack.getTag(TAG);
        return id != null && defs.containsKey(id) ? id : null;
    }

    private final class HoItemCommand extends Command {
        HoItemCommand() {
            super("hoitem");
            setCondition((s, c) -> !(s instanceof Player p) || server().isOp(p));
            setDefaultExecutor((s, c) -> {
                s.sendMessage(Component.text("Свои предметы: " + defs.size() + ".  /hoitem give <id> [кол-во] · /hoitem reload", NamedTextColor.AQUA));
                for (Def d : defs.values())
                    s.sendMessage(Component.text("  " + d.id + " — ", NamedTextColor.GRAY).append(d.name)
                            .append(Component.text(" (" + d.material.key().value() + (d.icon != null ? ", иконка" : "") + (d.model != null ? ", модель" : "") + ")", NamedTextColor.GRAY)));
            });
            addSyntax((s, c) -> s.sendMessage(Component.text("Перечитано и отдано моду: " + reload(), NamedTextColor.GREEN)), ArgumentType.Literal("reload"));
            var idArg = ArgumentType.Word("id");
            idArg.setSuggestionCallback((s, c, sug) -> { for (String k : defs.keySet()) sug.addEntry(new SuggestionEntry(k)); });
            var count = ArgumentType.Integer("кол-во").between(1, 99);
            CommandExecutor give = (s, c) -> {
                if (!(s instanceof Player p)) return;
                ItemStack it = item(c.get(idArg), c.has(count) ? c.get(count) : 1);
                if (it == null) { s.sendMessage(Component.text("Нет предмета " + c.get(idArg), NamedTextColor.RED)); return; }
                p.getInventory().addItemStack(it);
                s.sendMessage(Component.text("Выдано: ", NamedTextColor.GREEN).append(defs.get(c.get(idArg)).name));
            };
            addSyntax(give, ArgumentType.Literal("give"), idArg);
            addSyntax(give, ArgumentType.Literal("give"), idArg, count);
        }
    }
}
