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
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.instance.Instance;
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

    /**
     * Описание блока. size — клеток по X, Y, Z (многоклеточный блок, {@link Multi}); shape — хитбокс одной клетки
     * в пикселях {x1, y1, z1, x2, y2, z2} (null — полный куб); animation — анимация модели, которую мод проигрывает
     * в мире; window — окно houi, которое открывает ПКМ по блоку.
     */
    record Def(String id, String name, Kind kind, boolean rotatable, Map<String, String> faces, String model,
               String layer, String particle, boolean ao, Map<String, Object> sounds,
               int[] size, int[] shape, String animation, String window) {
        Def(String id, String name, Kind kind, boolean rotatable, Map<String, String> faces, String model,
            String layer, String particle, boolean ao, Map<String, Object> sounds) {
            this(id, name, kind, rotatable, faces, model, layer, particle, ao, sounds, new int[]{1, 1, 1}, null, null, null);
        }

        int parts() { return Multi.parts(size); }

        boolean multi() { return parts() > 1; }
    }

    /** Состояние-носитель → какой блок, как повёрнут и какая это часть (0 — главная клетка). */
    record Placed(Def def, int rot, int part) {}

    /** Выданные носители: id → поворот → носитель каждой части; и обратно. */
    record Alloc(Map<String, Map<Integer, Block[]>> blockOf, Map<Integer, Placed> byState, int fresh, List<String> problems,
                 Map<Kind, Integer> used) {}

    private volatile Map<String, Def> defs = Map.of();
    private volatile Map<String, Map<Integer, Block[]>> blockOf = Map.of();  // id → поворот → носитель каждой части
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
        events().addListener(PlayerBlockInteractEvent.class, this::onInteract);
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
            int[] size = ints(config().get(p + "size"), 3, 1, 4);
            if (size == null) size = new int[]{1, 1, 1};
            int[] shape = ints(config().get(p + "shape"), 6, 0, 32);
            if (shape != null && (shape[3] <= shape[0] || shape[4] <= shape[1] || shape[5] <= shape[2] || shape[3] > 16 || shape[5] > 16)) {
                logger().warn("блок {}: shape {} — нужно [x1, y1, z1, x2, y2, z2], 0..16 по X/Z, 0..32 по Y — будет полный куб", id, config().get(p + "shape"));
                shape = null;
            }
            String anim = config().getString(p + "animation", null);
            if (anim != null && model == null) { logger().warn("блок {}: animation без model — не будет", id); anim = null; }
            d.put(id, new Def(id, config().getString(p + "name", id), kind, config().getBoolean(p + "rotatable", false), faces,
                    model, config().getString(p + "layer", "cutout"), config().getString(p + "particle", null),
                    config().getBoolean(p + "ao", true), sounds(config().get(p + "sounds")),
                    size, shape, anim, config().getString(p + "window", null)));
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

    /** Список из n целых в пределах [min, max] или null (нет поля / не тот вид). */
    static int[] ints(Object o, int n, int min, int max) {
        if (!(o instanceof List<?> l) || l.size() != n) return null;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            if (!(l.get(i) instanceof Number num)) return null;
            out[i] = Math.max(min, Math.min(max, num.intValue()));
        }
        return out;
    }

    /** Ключ места в states.yml: id[@поворот][#часть] (часть 0 и неповоротный блок — как раньше, места не съезжают). */
    static String stateKey(Def def, int rot, int part) {
        return def.id + (def.rotatable ? "@" + rot : "") + (part > 0 ? "#" + part : "");
    }

    /** Выдать носители новым блокам; старые остаются на своих местах навсегда. */
    private String allocate(Map<String, Def> d) {
        Alloc a = allocate(new ModuleConfig(dataFolder().resolve("states.yml")), d.values());
        blockOf = a.blockOf;
        byState = a.byState;
        for (String p : a.problems) logger().warn(p);
        int clear = a.used.getOrDefault(Kind.TRANSPARENT, 0);
        String left = "запас transparent: занято " + clear + " из " + pool(Kind.TRANSPARENT).size() + " (у мода " + CLIENT_CLEAR + ")";
        if (clear > CLIENT_CLEAR) logger().warn("носителей transparent {} больше, чем блоков clear у мода ({}): лишние у игроков будут листвой", clear, CLIENT_CLEAR);
        logger().info(left);
        return "новых носителей " + a.fresh + (a.problems.isEmpty() ? "" : ", ошибок " + a.problems.size()) + "; " + left;
    }

    /** Сколько блоков запаса каждого вида у мода (PoolBlocks: solid 384, clear 48, pass 96). */
    static final int CLIENT_CLEAR = 48;

    /**
     * Выдать носители: на каждый поворот × часть — своё место. Уже выданные места (states.yml) не меняются, новые
     * дописываются в конец пула. Файл сохраняется, только если что-то выдано.
     */
    static Alloc allocate(ModuleConfig st, java.util.Collection<Def> defs) {
        Map<Kind, List<Block>> pools = new HashMap<>();
        for (Kind k : Kind.values()) pools.put(k, pool(k));
        Set<Integer> taken = new HashSet<>();
        for (String key : st.keys("states")) {
            Block b = state(st.getString("states." + key, ""));
            if (b != null) taken.add(b.stateId());
        }
        Map<String, Map<Integer, Block[]>> bo = new HashMap<>();
        Map<Integer, Placed> bs = new HashMap<>();
        int fresh = 0;
        List<String> problems = new ArrayList<>();
        for (Def def : defs) {
            int[] rots = def.rotatable ? new int[]{0, 90, 180, 270} : new int[]{0};
            Map<Integer, Block[]> m = new HashMap<>();
            rotations:
            for (int rot : rots) {
                Block[] parts = new Block[def.parts()];
                for (int part = 0; part < parts.length; part++) {
                    String key = stateKey(def, rot, part);
                    Block b = state(st.getString("states." + key, ""));
                    if (b == null || pools.get(def.kind).stream().noneMatch(x -> x.stateId() == b.stateId())) {
                        // нет места или тип сменили — новое место (старое остаётся занятым)
                        Block nb = pools.get(def.kind).stream().filter(x -> !taken.contains(x.stateId())).findFirst().orElse(null);
                        if (nb == null) { problems.add(def.id + ": кончились носители " + def.kind); continue rotations; }
                        taken.add(nb.stateId());
                        st.set("states." + key, nb.state());
                        fresh++;
                        parts[part] = nb;
                    } else parts[part] = b;
                }
                for (int part = 0; part < parts.length; part++) bs.put(parts[part].stateId(), new Placed(def, rot, part));
                m.put(rot, parts);
            }
            bo.put(def.id, m);
        }
        if (fresh > 0) st.save();
        Map<Kind, Integer> used = new HashMap<>();
        for (Kind k : Kind.values()) used.put(k, (int) pools.get(k).stream().filter(x -> taken.contains(x.stateId())).count());
        return new Alloc(bo, bs, fresh, problems, used);
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
            for (var re : blockOf.getOrDefault(def.id, Map.of()).entrySet()) for (int part = 0; part < re.getValue().length; part++) {
                var e = Map.entry(re.getKey(), re.getValue()[part]);
                JsonObject o = new JsonObject();
                o.addProperty("id", def.id);
                o.addProperty("name", def.name);                                   // 0.73: название блока мода
                Block b = e.getValue();
                JsonObject state = new JsonObject();
                state.addProperty("block", b.key().asString());
                JsonObject props = new JsonObject();
                for (var pe : b.properties().entrySet()) props.addProperty(pe.getKey(), pe.getValue());
                state.add("props", props);
                o.add("state", state);
                o.addProperty("rot", e.getKey());
                if (def.multi()) {                                                 // 0.84: многоклеточный блок
                    o.addProperty("part", part);
                    JsonArray sz = new JsonArray();
                    for (int v : def.size) sz.add(v);
                    o.add("size", sz);
                }
                if (def.shape != null) {                                           // 0.84: хитбокс клетки, px
                    JsonArray sh = new JsonArray();
                    for (int v : def.shape) sh.add(v);
                    o.add("shape", sh);
                }
                if (def.animation != null) o.addProperty("animation", def.animation);   // 0.84: анимация модели в мире
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
        Map<Integer, Block[]> m = blockOf.get(id);
        if (def == null || m == null || m.isEmpty()) { e.setCancelled(true); return; }
        int rot = def.rotatable ? Multi.rotOf(e.getPlayer().getPosition().yaw()) : 0;   // «лицом» к игроку
        if (!m.containsKey(rot)) rot = m.keySet().iterator().next();
        Block[] parts = m.get(rot);
        if (def.multi()) {
            var pos = e.getBlockPosition();
            Multi.World w = world(e.getInstance());
            String why = Multi.check(w, def.size, pos.blockX(), pos.blockY(), pos.blockZ(), rot);
            if (why != null) {
                e.setCancelled(true);
                e.getPlayer().sendActionBar(Component.text(def.name + ": " + why, NamedTextColor.RED));
                return;
            }
            Multi.place(w, parts, def.size, pos.blockX(), pos.blockY(), pos.blockZ(), rot, true);   // главную ставит событие
        }
        e.setBlock(parts[0]);
        e.setDoBlockUpdates(false);
    }

    /** Мир Minestom для {@link Multi}. */
    static Multi.World world(Instance in) {
        return new Multi.World() {
            @Override public Block get(int x, int y, int z) { return in.getBlock(x, y, z); }
            @Override public void set(int x, int y, int z, Block b) { in.setBlock(x, y, z, b, false); }
            @Override public boolean loaded(int x, int y, int z) { return in.isChunkLoaded(x >> 4, z >> 4); }
        };
    }

    private void onBreak(PlayerBlockBreakEvent e) {
        Placed p = byState.get(e.getBlock().stateId());
        if (p == null) return;
        Player pl = e.getPlayer();
        if (p.def.multi()) {
            // ломание могли запретить (mining и др. слушают то же событие) — смотрим на следующем тике, сломалось ли
            Instance in = e.getInstance();
            var pos = e.getBlockPosition();
            Block was = e.getBlock();
            Block[] parts = blockOf.getOrDefault(p.def.id, Map.of()).get(p.rot);
            if (parts == null) return;
            scheduler().later(() -> {
                if (!in.isRegistered() || !in.isChunkLoaded(pos.blockX() >> 4, pos.blockZ() >> 4)) return;
                if (in.getBlock(pos).stateId() == was.stateId()) return;           // не сломан
                Multi.remove(world(in), parts, p.def.size, pos.blockX(), pos.blockY(), pos.blockZ(), p.part, p.rot);
                if (pl.isOnline() && (pl.getGameMode() == GameMode.SURVIVAL || pl.getGameMode() == GameMode.ADVENTURE))
                    pl.getInventory().addItemStack(item(p.def, 1));                 // один предмет на весь блок
            }, 1);
            return;
        }
        if (pl.getGameMode() == GameMode.SURVIVAL || pl.getGameMode() == GameMode.ADVENTURE)
            pl.getInventory().addItemStack(item(p.def, 1));
    }

    /** ПКМ по своему блоку с окном (window): событие {@link HoBlockUseEvent} для главной клетки и окно houi. */
    private void onInteract(PlayerBlockInteractEvent e) {
        Placed p = byState.get(e.getBlock().stateId());
        if (p == null || p.def.window == null || e.getHand() != net.minestom.server.entity.PlayerHand.MAIN) return;
        Player pl = e.getPlayer();
        if (pl.isSneaking() && !pl.getItemInMainHand().isAir()) return;           // с Shift и предметом — ставить блок
        var pos = e.getBlockPosition();
        int[] mc = Multi.main(p.def.size, pos.blockX(), pos.blockY(), pos.blockZ(), p.part, p.rot);
        e.setBlockingItemUse(true);
        var ev = new HoBlockUseEvent(pl, e.getInstance(), p.def.id, new net.minestom.server.coordinate.BlockVec(mc[0], mc[1], mc[2]), p.rot);
        net.minestom.server.event.EventDispatcher.call(ev);
        if (ev.isCancelled()) return;
        try {
            var ui = services().get(ink.hoka.odyssey.houi.HoUi.class).orElse(null);
            if (ui == null) return;
            JsonObject data = new JsonObject();
            data.addProperty("title", p.def.name);
            data.addProperty("x", mc[0]);
            data.addProperty("y", mc[1]);
            data.addProperty("z", mc[2]);
            if (!ui.open(pl, p.def.window, data)) pl.sendActionBar(Component.text("Окно «" + p.def.window + "» не открыто (нет мода или раскладки)", NamedTextColor.RED));
        } catch (NoClassDefFoundError ignored) {
            // модуля houi нет — только событие
        }
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
                    String extra = (d.multi() ? ", " + d.size[0] + "×" + d.size[1] + "×" + d.size[2] : "") + (d.animation != null ? ", анимация " + d.animation : "");
                    s.sendMessage(Component.text("  " + d.id + " — " + d.name + " (" + d.kind.name().toLowerCase()
                            + (d.model != null ? ", модель" : "") + (d.rotatable ? ", поворот" : "") + (places == 0 ? ", НЕТ НОСИТЕЛЯ" : "") + extra + ")", NamedTextColor.GRAY));
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
