package ink.hoka.odyssey.blocks;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ink.hoka.odyssey.api.ModuleConfig;
import ink.hoka.odyssey.api.OdysseyModule;
import ink.hoka.odyssey.clientmods.ClientModsApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerPickBlockEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Свои блоки. Каждому блоку (и каждому его повороту) раз и навсегда выдаётся ванильное состояние-носитель
 * (states.yml); моду уходит описание {@code blocks/blocks.json} и файлы из assets/ — он рисует носитель как наш
 * блок. Ставится предметом с меткой {@code hoka_block}, ломается как носитель, в выживании возвращает предмет.
 */
public final class BlocksModule extends OdysseyModule implements HoBlocks {

    static final Tag<String> ITEM_TAG = Tag.String("hoka_block");

    enum Kind { SOLID, TRANSPARENT, PASSABLE }

    record Def(String id, String name, Kind kind, boolean rotatable, Map<String, String> faces, String model,
               String layer, String particle, boolean ao, Map<String, Object> sounds) {}

    /** Состояние-носитель → какой блок и как повёрнут. */
    record Placed(Def def, int rot) {}

    private volatile Map<String, Def> defs = Map.of();
    private volatile Map<String, Map<Integer, Block>> blockOf = Map.of();    // id → поворот → носитель
    private volatile Map<Integer, Placed> byState = Map.of();               // stateId носителя → блок
    private ClientModsApi client;
    private int publishedTo;                                                // identityHashCode API, куда уже отдали файлы

    @Override
    protected void onEnable() {
        client = services().get(ClientModsApi.class).orElseThrow();
        String r = reload();
        logger().info("свои блоки: {}", r);
        services().register(HoBlocks.class, this);
        events().addListener(PlayerBlockPlaceEvent.class, this::onPlace);
        events().addListener(PlayerBlockBreakEvent.class, this::onBreak);
        events().addListener(PlayerPickBlockEvent.class, this::onPick);
        registerCommand(new HoBlockCommand());
        // модуль clientmods перезагрузили — его файлы пропали: отдать заново
        scheduler().repeat(() -> {
            ClientModsApi now = services().get(ClientModsApi.class).orElse(null);
            if (now != null && System.identityHashCode(now) != publishedTo) {
                client = now;
                publish();
            }
        }, 100, 100);
    }

    @Override
    protected void onDisable() {
        services().unregister(HoBlocks.class);
        try {
            client.clearFiles(id());
            client.publishFiles();
        } catch (Throwable ignored) { }
    }

    // ───────────────────────── описание и носители ─────────────────────────

    synchronized String reload() {
        config().reload();
        Map<String, Def> d = new LinkedHashMap<>();
        for (String id : config().keys("blocks")) {
            String p = "blocks." + id + ".";
            String model = config().getString(p + "model", null);
            String type = config().getString(p + "type", model != null ? "transparent" : "solid");
            Kind kind = switch (type.toLowerCase()) {
                case "transparent" -> Kind.TRANSPARENT;
                case "passable" -> Kind.PASSABLE;
                default -> Kind.SOLID;
            };
            Map<String, String> faces = new LinkedHashMap<>();
            String all = config().getString(p + "texture", null);
            String side = config().getString(p + "side", all), top = config().getString(p + "top", all), bottom = config().getString(p + "bottom", all);
            for (String f : new String[]{"north", "south", "east", "west"}) put(faces, f, config().getString(p + f, side));
            put(faces, "up", config().getString(p + "up", top));
            put(faces, "down", config().getString(p + "down", bottom));
            if (model == null && faces.isEmpty()) { logger().warn("блок {}: нет ни texture, ни model", id); continue; }
            d.put(id, new Def(id, config().getString(p + "name", id), kind, config().getBoolean(p + "rotatable", false), faces,
                    model, config().getString(p + "layer", "cutout"), config().getString(p + "particle", null),
                    config().getBoolean(p + "ao", true), sounds(config().get(p + "sounds"))));
        }
        String alloc = allocate(d);
        defs = d;
        publish();
        return d.size() + " блоков; " + alloc;
    }

    private static Block state(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Block.fromState(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** sounds: {type: stone, break: hoka:x, place: …, step: …, hit: …, fall: …, volume: 1, pitch: 1} */
    private static Map<String, Object> sounds(Object o) {
        if (!(o instanceof Map<?, ?> m)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (v instanceof Number) out.put(k, v);
            else if (v != null) {
                String s = String.valueOf(v);
                // короткое имя своего звука: crate_open → hoka:crate_open
                if (!k.equals("type") && !s.contains(":")) s = "hoka:" + s;
                out.put(k, s);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static void put(Map<String, String> m, String k, String v) { if (v != null) m.put(k, v); }

    /** Выдать носители новым блокам; старые остаются на своих местах навсегда. */
    private String allocate(Map<String, Def> d) {
        ModuleConfig st = new ModuleConfig(dataFolder().resolve("states.yml"));
        Map<Kind, List<Block>> pools = new HashMap<>();
        for (Kind k : Kind.values()) pools.put(k, pool(k));
        Set<Integer> taken = new HashSet<>();
        for (String key : st.keys("states")) {
            Block b = state(st.getString("states." + key, ""));
            if (b != null) taken.add(b.stateId());
        }
        Map<String, Map<Integer, Block>> bo = new HashMap<>();
        Map<Integer, Placed> bs = new HashMap<>();
        int fresh = 0;
        List<String> problems = new ArrayList<>();
        for (Def def : d.values()) {
            int[] rots = def.rotatable ? new int[]{0, 90, 180, 270} : new int[]{0};
            Map<Integer, Block> m = new HashMap<>();
            for (int rot : rots) {
                String key = def.id + (def.rotatable ? "@" + rot : "");
                Block b = state(st.getString("states." + key, ""));
                if (b == null || pools.get(def.kind).stream().noneMatch(x -> x.stateId() == b.stateId())) {
                    // нет места или тип сменили — новое место (старое остаётся занятым)
                    Block nb = pools.get(def.kind).stream().filter(x -> !taken.contains(x.stateId())).findFirst().orElse(null);
                    if (nb == null) { problems.add(def.id + ": кончились носители " + def.kind); continue; }
                    taken.add(nb.stateId());
                    st.set("states." + key, nb.state());
                    fresh++;
                    m.put(rot, nb);
                } else m.put(rot, b);
                bs.put(m.get(rot).stateId(), new Placed(def, rot));
            }
            bo.put(def.id, m);
        }
        if (fresh > 0) st.save();
        blockOf = bo;
        byState = bs;
        for (String p : problems) logger().warn(p);
        return "новых носителей " + fresh + (problems.isEmpty() ? "" : ", ошибок " + problems.size());
    }

    /** Состояния-носители типа (порядок постоянный). Обычные ванильные состояния в пул не входят. */
    static List<Block> pool(Kind k) {
        List<Block> out = new ArrayList<>();
        switch (k) {
            case SOLID -> {
                for (Block b : Block.NOTE_BLOCK.possibleStates())
                    if (!"harp".equals(b.getProperty("instrument"))) out.add(b);   // «арфа» — обычные нотные блоки
            }
            case TRANSPARENT -> {
                for (Block leaves : new Block[]{Block.OAK_LEAVES, Block.SPRUCE_LEAVES, Block.BIRCH_LEAVES, Block.JUNGLE_LEAVES,
                        Block.ACACIA_LEAVES, Block.DARK_OAK_LEAVES, Block.MANGROVE_LEAVES, Block.AZALEA_LEAVES, Block.FLOWERING_AZALEA_LEAVES})
                    for (Block b : leaves.possibleStates())
                        if ("true".equals(b.getProperty("persistent")) && "false".equals(b.getProperty("waterlogged"))) out.add(b);
            }
            case PASSABLE -> {
                for (Block b : Block.TRIPWIRE.possibleStates()) if (b.stateId() != Block.TRIPWIRE.stateId()) out.add(b);
            }
        }
        out.sort(Comparator.comparing(Block::state));
        return out;
    }

    /** Все состояния-носители (всех типов, и занятые, и свободные). */
    private static final Set<Integer> CARRIERS = carriers();

    private static Set<Integer> carriers() {
        Set<Integer> s = new HashSet<>();
        for (Kind k : Kind.values()) for (Block b : pool(k)) s.add(b.stateId());
        return Set.copyOf(s);
    }

    static boolean carrier(Block b) { return b != null && CARRIERS.contains(b.stateId()); }

    /**
     * Обычный (не наш) блок, попавший в состояние-носитель, → состояние по умолчанию того же блока: листва
     * distance=7,persistent=false (на сервере не опадает), нотный блок «арфа», нить без связей. Так носители
     * в мире — только свои блоки. Мод у игрока предсказывает установку так же (BlockItemMixin/LeavesBlockMixin).
     */
    static Block foreign(Block b) {
        if (!carrier(b)) return b;
        Block d = Block.fromKey(b.key());
        return d != null && !carrier(d) ? d : b;
    }

    // ───────────────────────── моду ─────────────────────────

    private void publish() {
        ClientModsApi api = client;
        api.clearFiles(id());
        Path assets = dataFolder().resolve("assets");
        int files = 0;
        try {
            Files.createDirectories(assets);
            try (Stream<Path> s = Files.walk(assets)) {
                for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    String rel = assets.relativize(f).toString().replace(java.io.File.separatorChar, '/');
                    api.putFile(id(), "blocks/" + rel, Files.readAllBytes(f));
                    files++;
                }
            }
        } catch (IOException e) {
            logger().error("assets не прочитаны: {}", e.toString());
        }
        JsonArray arr = new JsonArray();
        for (Def def : defs.values()) {
            for (var e : blockOf.getOrDefault(def.id, Map.of()).entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", def.id);
                Block b = e.getValue();
                JsonObject state = new JsonObject();
                state.addProperty("block", b.key().asString());
                JsonObject props = new JsonObject();
                for (var pe : b.properties().entrySet()) props.addProperty(pe.getKey(), pe.getValue());
                state.add("props", props);
                o.add("state", state);
                o.addProperty("rot", e.getKey());
                if (def.model != null) o.addProperty("model", "blocks/" + def.model);
                else {
                    JsonObject faces = new JsonObject();
                    for (var fe : def.faces.entrySet()) faces.addProperty(fe.getKey(), "blocks/" + fe.getValue());
                    o.add("faces", faces);
                }
                o.addProperty("layer", def.layer);
                if (def.particle != null) o.addProperty("particle", "blocks/" + def.particle);
                o.addProperty("ao", def.ao);
                if (def.sounds != null) {
                    JsonObject snd = new JsonObject();
                    for (var se : def.sounds.entrySet()) {
                        if (se.getValue() instanceof Number n) snd.addProperty(se.getKey(), n.floatValue());
                        else snd.addProperty(se.getKey(), String.valueOf(se.getValue()));
                    }
                    o.add("sounds", snd);
                }
                arr.add(o);
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("blocks", arr);
        api.putFile(id(), "blocks/blocks.json", new Gson().toJson(root).getBytes(StandardCharsets.UTF_8));
        api.publishFiles();
        publishedTo = System.identityHashCode(api);
        logger().info("моду отданы: {} состояний, {} файлов", arr.size(), files);
    }

    // ───────────────────────── в мире ─────────────────────────

    ItemStack item(Def def, int count) {
        Material m = switch (def.kind) {
            case SOLID -> Material.NOTE_BLOCK;
            case TRANSPARENT -> Material.OAK_LEAVES;
            case PASSABLE -> Material.STRING;
        };
        return ItemStack.of(m, count)
                .with(DataComponents.CUSTOM_NAME, Component.text(def.name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
                .withTag(ITEM_TAG, def.id);
    }

    private void onPlace(PlayerBlockPlaceEvent e) {
        ItemStack hand = e.getPlayer().getItemInHand(e.getHand());
        String id = hand.getTag(ITEM_TAG);
        if (id == null) {
            // обычный блок (листва, нотный блок, нить) не встаёт в состояние-носитель — иначе у игроков с модом это «Ступка»
            Block n = foreign(e.getBlock());
            if (n != e.getBlock()) e.setBlock(n);
            return;
        }
        Def def = defs.get(id);
        Map<Integer, Block> m = blockOf.get(id);
        if (def == null || m == null || m.isEmpty()) { e.setCancelled(true); return; }
        int rot = 0;
        if (def.rotatable) {
            float yaw = e.getPlayer().getPosition().yaw();
            rot = Math.floorMod(Math.round(yaw / 90f) * 90, 360);           // «лицом» к игроку
        }
        Block b = m.getOrDefault(rot, m.values().iterator().next());
        e.setBlock(b);
        e.setDoBlockUpdates(false);
    }

    private void onBreak(PlayerBlockBreakEvent e) {
        Placed p = byState.get(e.getBlock().stateId());
        if (p == null) return;
        if (e.getPlayer().getGameMode() == GameMode.SURVIVAL || e.getPlayer().getGameMode() == GameMode.ADVENTURE)
            e.getPlayer().getInventory().addItemStack(item(p.def, 1));
    }

    @Override
    public String idOf(Block block) {
        Placed p = byState.get(block.stateId());
        return p == null ? null : p.def.id;
    }

    private void onPick(PlayerPickBlockEvent e) {
        Placed p = byState.get(e.getBlock().stateId());
        if (p == null || e.getPlayer().getGameMode() != GameMode.CREATIVE) return;
        Player pl = e.getPlayer();
        // ваниль сама подсунула бы носитель — после неё кладём свой предмет
        scheduler().later(() -> pl.setItemInMainHand(item(p.def, 1)), 1);
    }

    // ───────────────────────── команда ─────────────────────────

    private final class HoBlockCommand extends Command {
        HoBlockCommand() {
            super("hoblock");
            setCondition((s, c) -> !(s instanceof Player p) || server().isOp(p));
            setDefaultExecutor((s, c) -> {
                s.sendMessage(Component.text("Свои блоки: " + defs.size() + ".  /hoblock give <id> [кол-во] · /hoblock reload", NamedTextColor.AQUA));
                for (Def d : defs.values()) {
                    int places = blockOf.getOrDefault(d.id, Map.of()).size();
                    s.sendMessage(Component.text("  " + d.id + " — " + d.name + " (" + d.kind.name().toLowerCase()
                            + (d.model != null ? ", модель" : "") + (d.rotatable ? ", поворот" : "") + (places == 0 ? ", НЕТ НОСИТЕЛЯ" : "") + ")", NamedTextColor.GRAY));
                }
            });
            addSyntax((s, c) -> s.sendMessage(Component.text("Перечитано и отдано моду: " + reload(), NamedTextColor.GREEN)),
                    ArgumentType.Literal("reload"));
            var idArg = ArgumentType.Word("id");
            idArg.setSuggestionCallback((s, c, sug) -> {
                for (String k : defs.keySet()) sug.addEntry(new net.minestom.server.command.builder.suggestion.SuggestionEntry(k));
            });
            var count = ArgumentType.Integer("кол-во").between(1, 64);
            net.minestom.server.command.builder.CommandExecutor give = (s, c) -> {
                if (!(s instanceof Player p)) return;
                Def d = defs.get(c.get(idArg));
                if (d == null) { s.sendMessage(Component.text("Нет блока " + c.get(idArg), NamedTextColor.RED)); return; }
                int n = c.has(count) ? c.get(count) : 1;
                p.getInventory().addItemStack(item(d, n));
                s.sendMessage(Component.text("Выдано: " + d.name + " ×" + n, NamedTextColor.GREEN));
            };
            addSyntax(give, ArgumentType.Literal("give"), idArg);
            addSyntax(give, ArgumentType.Literal("give"), idArg, count);
        }
    }
}
