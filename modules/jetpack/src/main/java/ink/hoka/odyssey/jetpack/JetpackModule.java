package ink.hoka.odyssey.jetpack;

import ink.hoka.odyssey.api.OdysseyModule;
import ink.hoka.odyssey.clientmods.ClientModsApi;
import ink.hoka.odyssey.hoitems.HoItems;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.Equippable;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.tag.Tag;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реактивный ранец. Предмет «ранец» (hoitems id {@code jetpack}) надевается в слот нагрудника — у него компонент
 * equippable без вида брони, так что ванильная броня не рисуется, а модель на спине рисует клиентский модуль jetpack.
 * Топливо — в самом ранце (метка {@link #FUEL}, «тиков тяги»), заправка — канистрой (hoitems id {@code jetpack_fuel}):
 * ПКМ канистрой — в надетый ранец.
 * <p>
 * Полёт считает мод (сервер Minestom движение не проверяет): пробел в воздухе — тяга, Shift в воздухе — плавный спуск.
 * Мод шлёт состояние (0 — нет, 1 — тяга, 2 — спуск), сервер списывает топливо, ставит метку «без урона от падения»
 * (survival) на время тяги, рассылает всем модам, кто летит (анимация fly, пламя), и шлёт хозяину запас топлива.
 * <p>
 * Протокол модуля jetpack: сервер → мод: 0 CONFIG (5 float), 1 FUEL (int запас, int ёмкость, bool надет),
 * 2 STATE (int id сущности, byte состояние). Мод → сервер: 1 STATE (byte).
 */
public final class JetpackModule extends OdysseyModule {

    static final String MODULE = "jetpack";
    /** Запас топлива ранца, «тиков тяги». */
    static final Tag<Integer> FUEL = Tag.Integer("hoka_jet_fuel");
    /** Своя метка предмета: «pack» — ранец, «fuel» — канистра (запасные предметы без hoitems). */
    static final Tag<String> OWN = Tag.String("hoka_jetpack");
    /** Метка модуля hoitems (тот же ключ, что HoItemsModule.TAG). */
    static final Tag<String> HOITEM = Tag.String("hoka_item");
    /** Метка survival: падение не считается до этого времени (мс). */
    static final Tag<Long> FALL_SAFE = Tag.Long("hoka_fall_safe_until");
    static final String ID_PACK = "jetpack", ID_FUEL = "jetpack_fuel";

    private ClientModsApi api;
    private int capacity, canister;
    private double burnThrust, burnDescent;
    private boolean startFull;
    private float upAccel, maxUp, sideAccel, maxSide, descent;

    /** Кто сейчас летит: игрок → состояние (1 тяга, 2 спуск). */
    private final Map<UUID, Byte> states = new ConcurrentHashMap<>();
    /** Запас в памяти, пока летит (в предмет пишется раз в секунду и при остановке). */
    private final Map<UUID, Double> live = new ConcurrentHashMap<>();
    private long tick;

    @Override
    protected void onEnable() {
        readConfig();
        api = services().get(ClientModsApi.class).orElse(null);
        if (api == null) logger().warn("нет clientmods — ранец не полетит");
        else {
            api.onReady(MODULE, this::ready);
            api.on(MODULE, this::onMessage);
            for (Player p : online()) if (api.isReady(p, MODULE)) ready(p);
        }
        events().addListener(PlayerUseItemEvent.class, this::use);
        events().addListener(PlayerDisconnectEvent.class, e -> stop(e.getPlayer(), false));
        scheduler().repeat(this::tickAll, 1, 1);
        registerCommand(new HoJetCommand());
        logger().info("ранец: бак {} (≈{} с тяги), канистра {}", capacity, capacity / 20, canister);
    }

    @Override
    protected void onDisable() {
        for (Player p : online()) stop(p, false);
        if (api != null) api.off(MODULE);
    }

    private void readConfig() {
        var c = config();
        capacity = Math.max(1, c.getInt("fuel.capacity", 1200));
        canister = Math.max(1, c.getInt("fuel.canister", 400));
        burnThrust = c.getDouble("fuel.burn-thrust", 1.0);
        burnDescent = c.getDouble("fuel.burn-descent", 0.35);
        startFull = c.getBoolean("fuel.start-full", true);
        upAccel = (float) c.getDouble("flight.up-accel", 0.13);
        maxUp = (float) c.getDouble("flight.max-up", 0.42);
        sideAccel = (float) c.getDouble("flight.side-accel", 0.035);
        maxSide = (float) c.getDouble("flight.max-side", 0.55);
        descent = (float) c.getDouble("flight.descent", 0.16);
    }

    private static java.util.Collection<Player> online() { return MinecraftServer.getConnectionManager().getOnlinePlayers(); }

    // ───────────────────────── предметы ─────────────────────────

    private HoItems hoitems() { return services().get(HoItems.class).orElse(null); }

    static String kindOf(ItemStack it) {
        if (it == null || it.isAir()) return null;
        String own = it.getTag(OWN);
        if (own != null) return own;
        String ho = it.getTag(HOITEM);
        if (ID_PACK.equals(ho)) return "pack";
        if (ID_FUEL.equals(ho)) return "fuel";
        return null;
    }

    static boolean isPack(ItemStack it) { return "pack".equals(kindOf(it)); }

    static boolean isCanister(ItemStack it) { return "fuel".equals(kindOf(it)); }

    private static final Equippable CHEST = new Equippable(EquipmentSlot.CHESTPLATE, SoundEvent.ITEM_ARMOR_EQUIP_IRON,
            null, null, null, true, true, false, true, false, Equippable.DEFAULT_SHEARING_SOUND);

    /** Ранец с запасом fuel (надеваемый, лор с запасом). */
    ItemStack pack(int fuel) {
        HoItems h = hoitems();
        ItemStack base = h == null ? null : h.item(ID_PACK, 1);
        if (base == null) base = ItemStack.builder(Material.IRON_INGOT)
                .set(DataComponents.ITEM_NAME, Component.text("Реактивный ранец", NamedTextColor.AQUA)).build().withTag(OWN, "pack");
        return withFuel(base.with(DataComponents.EQUIPPABLE, CHEST).with(DataComponents.MAX_STACK_SIZE, 1), fuel);
    }

    private ItemStack withFuel(ItemStack it, int fuel) {
        fuel = Math.clamp(fuel, 0, capacity);
        int pct = Math.round(fuel * 100f / capacity);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Топливо: " + pct + "% (" + fuel / 20 + " с тяги)", pct > 25 ? NamedTextColor.GOLD : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(gray("Надеть — ПКМ с ранцем в руке (слот нагрудника)."));
        lore.add(gray("Пробел в воздухе — тяга, Shift — плавный спуск."));
        lore.add(gray("Заправка — ПКМ канистрой топлива."));
        return it.withTag(FUEL, fuel).with(DataComponents.LORE, lore);
    }

    private static Component gray(String s) { return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false); }

    ItemStack canister(int count) {
        HoItems h = hoitems();
        ItemStack it = h == null ? null : h.item(ID_FUEL, count);
        if (it == null) it = ItemStack.builder(Material.BLAZE_POWDER).amount(count)
                .set(DataComponents.ITEM_NAME, Component.text("Топливо для ранца", NamedTextColor.GOLD)).build().withTag(OWN, "fuel");
        return it;
    }

    private int fuelOf(ItemStack it) {
        Integer f = it.getTag(FUEL);
        return f == null ? (startFull ? capacity : 0) : f;
    }

    /** Надетый ранец или null. */
    private static ItemStack worn(Player p) {
        ItemStack c = p.getEquipment(EquipmentSlot.CHESTPLATE);
        return isPack(c) ? c : null;
    }

    /** Ранцы, выданные /hoitem give (без equippable и запаса), — довести до настоящих. */
    private void normalize(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItemStack(i);
            if (isPack(it) && (!it.has(DataComponents.EQUIPPABLE) || it.getTag(FUEL) == null))
                inv.setItemStack(i, withFuel(it.with(DataComponents.EQUIPPABLE, CHEST).with(DataComponents.MAX_STACK_SIZE, 1), fuelOf(it)));
        }
    }

    private void use(PlayerUseItemEvent e) {
        Player p = e.getPlayer();
        ItemStack hand = e.getItemStack();
        if (isPack(hand)) {                                                    // надеть (снять прежний нагрудник в руку)
            e.setCancelled(true);
            ItemStack old = p.getEquipment(EquipmentSlot.CHESTPLATE);
            ItemStack pack = hand.has(DataComponents.EQUIPPABLE) && hand.getTag(FUEL) != null ? hand
                    : withFuel(hand.with(DataComponents.EQUIPPABLE, CHEST).with(DataComponents.MAX_STACK_SIZE, 1), fuelOf(hand));
            p.setEquipment(EquipmentSlot.CHESTPLATE, pack);
            p.setItemInHand(e.getHand(), old);
            p.playSound(Sound.sound(SoundEvent.ITEM_ARMOR_EQUIP_IRON, Sound.Source.PLAYER, 1f, 1f));
            sendFuel(p);
            return;
        }
        if (!isCanister(hand)) return;
        e.setCancelled(true);
        ItemStack pack = worn(p);
        if (pack == null) { p.sendActionBar(Component.text("Сначала наденьте ранец", NamedTextColor.RED)); return; }
        flush(p);
        pack = worn(p);
        int f = fuelOf(pack);
        if (f >= capacity) { p.sendActionBar(Component.text("Бак ранца полон", NamedTextColor.YELLOW)); return; }
        p.setEquipment(EquipmentSlot.CHESTPLATE, withFuel(pack, f + canister));
        p.setItemInHand(e.getHand(), hand.amount() > 1 ? hand.withAmount(hand.amount() - 1) : ItemStack.AIR);
        p.playSound(Sound.sound(SoundEvent.ITEM_BUCKET_FILL_LAVA, Sound.Source.PLAYER, 0.8f, 1.3f));
        p.sendActionBar(Component.text("Ранец заправлен: " + Math.round(Math.min(capacity, f + canister) * 100f / capacity) + "%", NamedTextColor.GOLD));
        sendFuel(p);
    }

    // ───────────────────────── полёт ─────────────────────────

    private void onMessage(Player p, byte[] b) {
        if (b.length < 2 || b[0] != 1) return;
        byte s = b[1];
        if (s < 0 || s > 2) return;
        if (s == 0) { stop(p, true); return; }
        ItemStack pack = worn(p);
        if (pack == null || current(p, pack) <= 0) { stop(p, true); sendFuel(p); return; }
        Byte was = states.put(p.getUuid(), s);
        if (was == null || was != s) broadcastState(p, s);
    }

    private double current(Player p, ItemStack pack) {
        Double d = live.get(p.getUuid());
        return d != null ? d : fuelOf(pack);
    }

    private void tickAll() {
        tick++;
        if (tick % 20 == 0) for (Player p : online()) normalize(p);
        for (var en : states.entrySet()) {
            Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(en.getKey());
            if (p == null) { states.remove(en.getKey()); live.remove(en.getKey()); continue; }
            ItemStack pack = worn(p);
            if (pack == null) { stop(p, true); continue; }
            double f = current(p, pack) - (en.getValue() == 1 ? burnThrust : burnDescent);
            p.setTag(FALL_SAFE, System.currentTimeMillis() + 400);              // падение считается от конца тяги
            if (f <= 0) {
                live.put(p.getUuid(), 0.0);
                stop(p, true);
                p.sendActionBar(Component.text("Топливо ранца кончилось", NamedTextColor.RED));
                continue;
            }
            live.put(p.getUuid(), f);
            if (tick % 20 == 0) flush(p);
            if (tick % 5 == 0) sendFuel(p);
        }
    }

    /** Записать запас из памяти в надетый ранец. */
    private void flush(Player p) {
        Double d = live.get(p.getUuid());
        if (d == null) return;
        ItemStack pack = worn(p);
        if (pack != null) p.setEquipment(EquipmentSlot.CHESTPLATE, withFuel(pack, (int) Math.floor(d)));
        if (!states.containsKey(p.getUuid())) live.remove(p.getUuid());
    }

    private void stop(Player p, boolean notify) {
        Byte was = states.remove(p.getUuid());
        flush(p);
        live.remove(p.getUuid());
        if (was != null) broadcastState(p, (byte) 0);
        if (notify) sendFuel(p);
    }

    // ───────────────────────── мод ─────────────────────────

    private void ready(Player p) {
        send(p, packet(0, o -> { o.writeFloat(upAccel); o.writeFloat(maxUp); o.writeFloat(sideAccel); o.writeFloat(maxSide); o.writeFloat(descent); }));
        sendFuel(p);
        for (var en : states.entrySet()) {
            Player q = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(en.getKey());
            if (q != null) send(p, statePacket(q, en.getValue()));
        }
    }

    private void sendFuel(Player p) {
        ItemStack pack = worn(p);
        int f = pack == null ? 0 : (int) Math.floor(current(p, pack));
        send(p, packet(1, o -> { o.writeInt(f); o.writeInt(capacity); o.writeBoolean(pack != null); }));
    }

    private void broadcastState(Player who, byte s) {
        byte[] b = statePacket(who, s);
        for (Player p : online()) send(p, b);
    }

    private static byte[] statePacket(Player who, byte s) {
        return packet(2, o -> { o.writeInt(who.getEntityId()); o.writeByte(s); });
    }

    private void send(Player p, byte[] b) {
        if (api != null && api.isReady(p, MODULE)) api.send(p, MODULE, b);
    }

    interface Writer { void write(DataOutputStream o) throws IOException; }

    static byte[] packet(int op, Writer w) {
        try {
            var bo = new ByteArrayOutputStream();
            var o = new DataOutputStream(bo);
            o.writeByte(op);
            w.write(o);
            return bo.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    // ───────────────────────── команда ─────────────────────────

    private final class HoJetCommand extends Command {
        HoJetCommand() {
            super("hojet", "jetpack");
            setCondition((s, c) -> !(s instanceof Player p) || server().isOp(p));
            setDefaultExecutor((s, c) -> {
                s.sendMessage(Component.text("/hojet give [игрок] — ранец (полный бак)", NamedTextColor.GRAY));
                s.sendMessage(Component.text("/hojet fuel <кол-во> [игрок] — канистры топлива", NamedTextColor.GRAY));
                s.sendMessage(Component.text("/hojet refill [игрок] — заправить надетый ранец полностью", NamedTextColor.GRAY));
                s.sendMessage(Component.text("/hojet reload — перечитать config.yml", NamedTextColor.GRAY));
            });
            var who = ArgumentType.Word("игрок").setSuggestionCallback((sender, ctx, sug) -> {
                for (Player p : online()) sug.addEntry(new net.minestom.server.command.builder.suggestion.SuggestionEntry(p.getUsername()));
            });
            var count = ArgumentType.Integer("кол-во").min(1).max(64);
            addSyntax((s, c) -> give(s, s instanceof Player p ? p : null, true, 0), ArgumentType.Literal("give"));
            addSyntax((s, c) -> give(s, byName(c.get(who)), true, 0), ArgumentType.Literal("give"), who);
            addSyntax((s, c) -> give(s, s instanceof Player p ? p : null, false, c.get(count)), ArgumentType.Literal("fuel"), count);
            addSyntax((s, c) -> give(s, byName(c.get(who)), false, c.get(count)), ArgumentType.Literal("fuel"), count, who);
            addSyntax((s, c) -> refill(s, s instanceof Player p ? p : null), ArgumentType.Literal("refill"));
            addSyntax((s, c) -> refill(s, byName(c.get(who))), ArgumentType.Literal("refill"), who);
            addSyntax((s, c) -> {
                readConfig();
                for (Player p : online()) if (api != null && api.isReady(p, MODULE)) ready(p);
                s.sendMessage(Component.text("Ранец: конфиг перечитан", NamedTextColor.GREEN));
            }, ArgumentType.Literal("reload"));
        }

        private Player byName(String n) { return MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(n); }

        private void give(net.minestom.server.command.CommandSender s, Player to, boolean pack, int n) {
            if (to == null) { s.sendMessage(Component.text("Игрок не найден (из консоли — укажите ник)", NamedTextColor.RED)); return; }
            to.getInventory().addItemStack(pack ? pack(startFull ? capacity : 0) : canister(n));
            s.sendMessage(Component.text(pack ? "Ранец выдан: " + to.getUsername() : "Канистр " + n + " → " + to.getUsername(), NamedTextColor.GREEN));
        }

        private void refill(net.minestom.server.command.CommandSender s, Player to) {
            if (to == null) { s.sendMessage(Component.text("Игрок не найден", NamedTextColor.RED)); return; }
            ItemStack pack = worn(to);
            if (pack == null) { s.sendMessage(Component.text("На " + to.getUsername() + " нет ранца", NamedTextColor.RED)); return; }
            live.remove(to.getUuid());
            to.setEquipment(EquipmentSlot.CHESTPLATE, withFuel(pack, capacity));
            sendFuel(to);
            s.sendMessage(Component.text("Ранец заправлен", NamedTextColor.GREEN));
        }
    }
}
