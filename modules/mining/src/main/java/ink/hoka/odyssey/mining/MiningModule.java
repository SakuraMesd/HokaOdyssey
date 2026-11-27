package ink.hoka.odyssey.mining;

import ink.hoka.odyssey.api.OdysseyModule;
import ink.hoka.odyssey.clientmods.ClientModsApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.server.play.BlockBreakAnimationPacket;
import net.minestom.server.network.packet.server.play.WorldEventPacket;
import net.minestom.server.tag.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Добыча: в выживании блоки ломаются только инструментами из config.yml (руками и прочим — нет: скорость ломания
 * игрока 0 + отмена события). Лазер (Аксиом): клиент мода шлёт цель луча (ядро op 11), сервер ведёт поломку по
 * времени блока — трещины видят все, обводку и луч рисует мод; луч рассылается другим игрокам. Выпадение — модулем
 * survival, как с инструментом drops_as (метка игрока {@code hoka_break_tool} на время ломания).
 */
public final class MiningModule extends OdysseyModule {

    static final int OP = 11;
    static final Tag<String> HOKA = Tag.String("hoka_item");
    static final Tag<String> BREAK_TOOL = Tag.String("hoka_break_tool");

    record Tool(boolean laser, double maxRange, String dropsAs, Map<String, Double> blocks) {}

    private static final class Sess {
        boolean hasBlock;
        Point pos, progressPos;
        double progress;
        int stage = -1;
        long last;
        boolean warned;
    }

    private volatile Map<String, Tool> tools = Map.of();
    private final Map<UUID, Sess> sessions = new ConcurrentHashMap<>();
    private volatile ClientModsApi client;
    private int hookedTo;
    private long tick;
    private volatile UUID breaking;

    @Override
    protected void onEnable() {
        logger().info("инструменты: {}", reload());
        hookClient();
        events().addListener(PlayerBlockBreakEvent.class, this::onBreak);
        events().addListener(PlayerDisconnectEvent.class, e -> stop(e.getPlayer()));
        scheduler().repeat(this::tick, 1, 1);
        scheduler().repeat(this::hookClient, 100, 100);                        // clientmods перезагружен — подписаться заново
        Command c = new Command("mining");
        c.setCondition((s, x) -> !(s instanceof Player p) || server().isOp(p));
        c.setDefaultExecutor((s, x) -> s.sendMessage(Component.text("Добыча: инструменты " + tools.keySet() + ". /mining reload", NamedTextColor.AQUA)));
        c.addSyntax((s, x) -> s.sendMessage(Component.text("Перечитано: " + reload(), NamedTextColor.GREEN)), ArgumentType.Literal("reload"));
        registerCommand(c);
    }

    @Override
    protected void onDisable() {
        try { if (client != null) client.offCore(OP); } catch (Throwable ignored) { }
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            stop(p);
            set(p, Attribute.BLOCK_BREAK_SPEED.defaultValue());
        }
    }

    private void hookClient() {
        ClientModsApi now = services().get(ClientModsApi.class).orElse(null);
        if (now == null || System.identityHashCode(now) == hookedTo) return;
        client = now;
        now.onCore(OP, this::onPacket);
        hookedTo = System.identityHashCode(now);
    }

    synchronized String reload() {
        config().reload();
        Map<String, Tool> t = new HashMap<>();
        for (String id : config().keys("tools")) {
            String p = "tools." + id + ".";
            Map<String, Double> blocks = new HashMap<>();
            for (String b : config().keys(p + "blocks")) blocks.put(key(b), Math.max(0.05, config().getDouble(p + "blocks." + b, 1)));
            t.put(id, new Tool(config().getBoolean(p + "laser", false), config().getDouble(p + "max_range", 64),
                    config().getString(p + "drops_as", "minecraft:diamond_pickaxe"), blocks));
        }
        tools = t;
        return t.keySet().toString();
    }

    private static String key(String b) { return b.contains(":") ? b : "minecraft:" + b; }

    /** Ключ блока для конфига: свой блок (модуль blocks) — «hoka:<id>», иначе ванильный «minecraft:…». */
    private String keyOf(Block b) {
        var hb = services().get(ink.hoka.odyssey.blocks.HoBlocks.class).orElse(null);
        String id = hb == null ? null : hb.idOf(b);
        return id != null ? "hoka:" + id : b.key().asString();
    }

    private Tool toolOf(Player p) {
        ItemStack hand = p.getItemInMainHand();
        String id = hand.getTag(HOKA);
        Tool t = id != null ? tools.get(id) : null;
        return t != null ? t : tools.get(hand.material().key().asString());
    }

    private static boolean free(Player p) {
        GameMode g = p.getGameMode();
        return g == GameMode.CREATIVE || g == GameMode.SPECTATOR;
    }

    // ───────── запрет ломать ─────────

    private void onBreak(PlayerBlockBreakEvent e) {
        Player p = e.getPlayer();
        if (free(p) || p.getUuid().equals(breaking)) return;
        Tool t = toolOf(p);
        if (t != null && !t.laser && t.blocks.containsKey(keyOf(e.getBlock()))) {
            p.setTag(BREAK_TOOL, t.dropsAs);                                    // выпадение как с drops_as
            scheduler().run(() -> p.removeTag(BREAK_TOOL));
            return;
        }
        e.setCancelled(true);
    }

    private static void set(Player p, double v) {
        var a = p.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (a.getBaseValue() != v) a.setBaseValue(v);
    }

    // ───────── лазер ─────────

    /** C2S op 11: byte активен; если да: byte есть блок, int x,y,z, float точка x,y,z. */
    private void onPacket(Player p, byte[] data) {
        try (var in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readByte() == 0) { stop(p); return; }
            boolean has = in.readByte() != 0;
            Vec pos = new Vec(in.readInt(), in.readInt(), in.readInt());
            Vec hit = new Vec(in.readFloat(), in.readFloat(), in.readFloat());
            Sess s = sessions.computeIfAbsent(p.getUuid(), k -> new Sess());
            synchronized (s) {
                s.hasBlock = has;
                s.pos = has ? pos : null;
                s.last = tick;
            }
            beam(p, true, hit);
        } catch (IOException ignored) { }
    }

    private void tick() {
        tick++;
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {   // скорость ломания: 0 без инструмента
            Tool t = toolOf(p);
            set(p, free(p) || (t != null && !t.laser) ? Attribute.BLOCK_BREAK_SPEED.defaultValue() : 0);
        }
        for (var en : sessions.entrySet()) {
            Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(en.getKey());
            Sess s = en.getValue();
            if (p == null) { sessions.remove(en.getKey()); continue; }
            synchronized (s) {
                Tool t = toolOf(p);
                if (tick - s.last > 15 || t == null || !t.laser || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) { stop(p); continue; }
                Instance inst = p.getInstance();
                if (!s.hasBlock || inst == null) { reset(p, s); continue; }
                if (!s.pos.equals(s.progressPos)) { reset(p, s); s.progressPos = s.pos; }
                Block b = inst.getBlock(s.pos);
                Double secs = t.blocks.get(keyOf(b));
                if (secs == null) {
                    if (!s.warned && !b.isAir()) {
                        p.sendActionBar(Component.text("Лазер этот блок не берёт", NamedTextColor.RED));
                        s.warned = true;
                    }
                    reset(p, s);
                    s.progressPos = s.pos;
                    continue;
                }
                s.warned = false;
                Vec center = new Vec(s.pos.blockX() + 0.5, s.pos.blockY() + 0.5, s.pos.blockZ() + 0.5);
                if (p.getPosition().add(0, p.getEyeHeight(), 0).distance(center) > t.maxRange + 1) { reset(p, s); continue; }
                s.progress += 1.0 / (secs * 20);
                int stage = (int) Math.min(9, s.progress * 10);
                if (stage != s.stage) { s.stage = stage; anim(p, inst, s.pos, stage); }
                if (s.progress >= 1) {
                    Point pos = s.pos;
                    anim(p, inst, pos, -1);
                    s.stage = -1;
                    s.progress = 0;
                    s.progressPos = null;
                    breaking = p.getUuid();
                    p.setTag(BREAK_TOOL, t.dropsAs);
                    try {
                        if (inst.breakBlock(p, pos, BlockFace.TOP))
                            p.sendPacket(new WorldEventPacket(2001, pos, b.stateId(), false));   // частицы и звук — и самому
                    } finally {
                        p.removeTag(BREAK_TOOL);
                        breaking = null;
                    }
                }
            }
        }
    }

    private void reset(Player p, Sess s) {
        if (s.stage >= 0 && s.progressPos != null && p.getInstance() != null) anim(p, p.getInstance(), s.progressPos, -1);
        s.stage = -1;
        s.progress = 0;
        s.progressPos = null;
    }

    private void stop(Player p) {
        Sess s = sessions.remove(p.getUuid());
        if (s == null) return;
        synchronized (s) { reset(p, s); }
        beam(p, false, Vec.ZERO);
    }

    /** Трещины всем рядом (stage −1 — убрать). */
    private static void anim(Player p, Instance inst, Point pos, int stage) {
        var pkt = new BlockBreakAnimationPacket(p.getEntityId() + 0x10000000, pos, (byte) stage);
        for (Player v : inst.getPlayers()) if (v.getPosition().distanceSquared(pos) < 96 * 96) v.sendPacket(pkt);
    }

    /** Луч игрока — тем, кто его видит (S2C op 11: int сущность, byte активен, float точка x,y,z). */
    private void beam(Player p, boolean on, Vec hit) {
        ClientModsApi c = client;
        if (c == null) return;
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(p.getEntityId());
            out.writeByte(on ? 1 : 0);
            out.writeFloat((float) hit.x());
            out.writeFloat((float) hit.y());
            out.writeFloat((float) hit.z());
        } catch (IOException ignored) { }
        byte[] b = bytes.toByteArray();
        for (Player v : p.getViewers()) c.sendCore(v, OP, b);
    }
}
