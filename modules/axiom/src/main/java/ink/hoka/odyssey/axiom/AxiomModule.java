package ink.hoka.odyssey.axiom;

import ink.hoka.odyssey.api.OdysseyModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerPluginMessageEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.AcknowledgeBlockChangePacket;
import net.minestom.server.world.DimensionType;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Axiom — серверная часть клиентского редактора мира Axiom 6.1.x (MC 1.21.11) по протоколу AxiomPaperPlugin
 * (ветка 1.21.11, MIT — LICENSE-axiom-paper.txt). Включается только операторам.
 * <p>
 * Рукопожатие: раз в секунду оператору без Axiom — {@code axiom:hello(long id)}; клиент отвечает hello (api 10,
 * версия данных, протокол, id) → {@code axiom:enable} + restrictions + бюджет секций + свойства мира (пусто).
 * Права пропали — {@code enable(false)}. Крупные пакеты клиент шлёт через {@code axiom:tunnel} (куски + zstd).
 * <p>
 * Что умеем (этап 1): set_block, set_buffer (блоки; биомы дочитываем и отбрасываем), request_chunk_data,
 * set_gamemode, set_fly_speed, set_no_physical_trigger. Остальные каналы Axiom принимаем и молча пропускаем.
 * Верфь кораблей (X ≥ deny-x-from) закрыта: рамкой в restrictions и проверкой на сервере.
 */
public final class AxiomModule extends OdysseyModule {

    static final int API_VERSION = 10;
    /** Протокол Minecraft 1.21.11 (MinecraftConstants.PROTOCOL_VERSION в Minestom — закрытый интерфейс). */
    static final int PROTOCOL_VERSION = 774;

    static final String HELLO = "axiom:hello", ENABLE = "axiom:enable", GOODBYE = "axiom:goodbye", TUNNEL = "axiom:tunnel",
            RESTRICTIONS = "axiom:restrictions", DISPATCH = "axiom:update_available_dispatch_sends",
            WORLD_PROPS = "axiom:register_world_properties",
            SET_BLOCK = "axiom:set_block", SET_BUFFER = "axiom:set_buffer", REQUEST_CHUNK = "axiom:request_chunk_data",
            SET_GAMEMODE = "axiom:set_gamemode", SET_FLY_SPEED = "axiom:set_fly_speed",
            SET_NO_PHYSICAL = "axiom:set_no_physical_trigger";

    /** C→S пакеты, которые мы обрабатываем: уходят клиенту в enable (шлёт только их) и в minecraft:register. */
    static final List<String> SUPPORTED = List.of(TUNNEL, HELLO, SET_GAMEMODE, SET_FLY_SPEED, SET_NO_PHYSICAL,
            REQUEST_CHUNK, SET_BLOCK, SET_BUFFER);

    private Settings cfg;
    private EditQueue edits;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<String> ignoredLogged = ConcurrentHashMap.newKeySet();
    private int timer;

    @Override
    protected void onEnable() {
        cfg = Settings.load(config());
        edits = new EditQueue(cfg, server());
        Packing.init(logger());
        try {
            ZstdCodec.init();
        } catch (Throwable t) {
            logger().error("zstd не поднялся ({}) — сжатые пакеты и блок-сущности Axiom работать не будут", t.toString());
        }

        events().addListener(PlayerSpawnEvent.class, e -> {
            Player p = e.getPlayer();
            Session s = session(p);
            if (e.isFirstSpawn() || !s.registered) register(p, s);
            else if (s.active) {
                p.sendPluginMessage(WORLD_PROPS, new byte[]{0});        // новый мир: свойства мира заново (у нас пусто)
                s.restrictionsKey = null;                               // рамки — по новому миру
            }
        });
        events().addListener(PlayerPluginMessageEvent.class, e -> {
            String ch = e.getIdentifier();
            if (ch.startsWith("axiom:")) onMessage(e.getPlayer(), ch, e.getMessage());
        });
        events().addListener(PlayerDisconnectEvent.class, e -> {
            sessions.remove(e.getPlayer().getUuid());
            edits.drop(e.getPlayer().getUuid());
        });
        scheduler().repeat(this::tick, 1, 1);

        // модуль перезагружен на живом сервере — объявить каналы заново (hello уйдёт на ближайшей проверке)
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) register(p, session(p));
        logger().info("Axiom: протокол api {}, высокий мир {}, zstd {}", API_VERSION, Packing.tall() ? "да" : "нет",
                ZstdCodec.ready() ? "есть" : "НЕТ");
    }

    @Override
    protected void onDisable() {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            Session s = sessions.get(p.getUuid());
            if (s != null && s.active) p.sendPluginMessage(ENABLE, new byte[]{0});
        }
        sessions.clear();
        if (edits != null) edits.clear();
        ZstdCodec.close();
    }

    private Session session(Player p) { return sessions.computeIfAbsent(p.getUuid(), u -> new Session()); }

    /** Объявить клиенту каналы, в которые он может слать (Fabric шлёт только в объявленные). */
    private void register(Player p, Session s) {
        p.sendPluginMessage("minecraft:register", String.join("\0", SUPPORTED).getBytes(StandardCharsets.UTF_8));
        s.registered = true;
    }

    // ───────────────────────── права ─────────────────────────

    private boolean allowed(Player p) { return server().isOp(p); }

    private boolean mapAllowed(Instance i) {
        if (cfg.allowedMaps().isEmpty()) return true;
        return i != null && server().mapName(i).map(cfg.allowedMaps()::contains).orElse(false);
    }

    /** Формат «сырых» long (BlockPos.asLong клиента). */
    private boolean rawTall() {
        return switch (cfg.rawPos()) {
            case "tall" -> true;
            case "vanilla" -> false;
            default -> Packing.tall();
        };
    }

    /** Метка конца списков для ответов клиенту. */
    private long term(Session s) {
        return switch (cfg.terminator()) {
            case "tall" -> Packing.TALL_TERM;
            case "vanilla" -> Packing.VANILLA_TERM;
            default -> s.clientTerm != 0 ? s.clientTerm : rawTall() ? Packing.TALL_TERM : Packing.VANILLA_TERM;
        };
    }

    // ───────────────────────── тик ─────────────────────────

    private void tick() {
        if (++timer >= 20) {
            timer = 0;
            for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) checkPlayer(p);
        }
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            Session s = sessions.get(p.getUuid());
            if (s != null && s.active) refillSends(p, s);
        }
        edits.tick(logger());
    }

    /** Раз в секунду: рукопожатие, выключение без прав, restrictions при изменении. */
    private void checkPlayer(Player p) {
        Session s = session(p);
        if (!s.registered || p.getInstance() == null) return;
        boolean ok = allowed(p);
        if (s.active) {
            if (!ok) {
                p.sendPluginMessage(ENABLE, new byte[]{0});
                s.deactivate();
                edits.drop(p.getUuid());
            } else {
                sendRestrictions(p, s);
            }
        } else if (ok) {
            if (!s.pending) {
                long id = ThreadLocalRandom.current().nextLong();
                if (id == 0) id = 1;
                s.handshakeId = id;
                s.pending = true;
                s.goodbyeSent = false;
                p.sendPluginMessage(HELLO, new Out(8).writeLong(id).toByteArray());
            }
        } else if (!s.goodbyeSent && cfg.goodbyeNonOp()) {
            goodbye(p, "Axiom на этом сервере — только для операторов");
            s.pending = false;
            s.goodbyeSent = true;
        }
    }

    private void goodbye(Player p, String reason) {
        p.sendPluginMessage(GOODBYE, new Out(64).writeUtf(reason).toByteArray());
    }

    /** Бюджет секций: как у плагина — запас ×20, пополнение каждый тик, клиенту — разница в целых секциях. */
    private void refillSends(Player p, Session s) {
        int per = cfg.sectionsPerSecond();
        if (s.sends20 == Session.UNSET) {
            s.sends20 = per * 20;
            p.sendPluginMessage(DISPATCH, new Out(8).writeVarInt(per).writeVarInt(per).toByteArray());
            return;
        }
        int prev = s.sends20;
        int now = Math.min(per * 20, prev + per);
        s.sends20 = now;
        if (prev / 20 != now / 20) p.sendPluginMessage(DISPATCH, new Out(8).writeVarInt(now / 20 - prev / 20).writeVarInt(per).toByteArray());
    }

    /** Списать секции; false — клиент шлёт быстрее, чем разрешено. */
    private boolean consumeSends(Session s, int sections, int clientAvailable) {
        int per = cfg.sectionsPerSecond();
        int cur = s.sends20 == Session.UNSET ? per * 20 : s.sends20;
        cur -= sections * 20;
        cur = Math.min(cur, clientAvailable * 20);
        s.sends20 = Math.max(cur, Session.UNSET + 1);
        return cur >= -per * 20;
    }

    // ───────────────────────── включение ─────────────────────────

    private void activate(Player p, Session s) {
        Out o = new Out(256);
        o.writeBool(true);
        o.writeByte(0);                                                 // версия ServerConfig
        o.writeVarInt(2);                                               // версия чертежей
        o.writeInt(cfg.splitSize());
        o.writeInt(cfg.maxTunnel());
        o.writeVarInt(0);                                               // blockWithCustomData — нет
        o.writeVarInt(0);                                               // ignoreRotationSet — нет
        o.writeVarInt(SUPPORTED.size());
        for (String id : SUPPORTED) o.writeUtf(id);
        p.sendPluginMessage(ENABLE, o.toByteArray());
        s.active = true;
        s.pending = false;
        s.sends20 = Session.UNSET;
        s.restrictionsKey = null;
        refillSends(p, s);
        sendRestrictions(p, s);
        p.sendPluginMessage(WORLD_PROPS, new byte[]{0});
        logger().info("Axiom включён: {}", p.getUsername());
    }

    /** restrictions: разрешения, дальность «бесконечной» руки, рамки зон правки (наша упаковка позиций). */
    private void sendRestrictions(Player p, Session s) {
        Instance inst = p.getInstance();
        if (inst == null) return;
        boolean net = Packing.tall();
        List<int[]> boxes = new ArrayList<>();
        if (!mapAllowed(inst)) {
            boxes.add(new int[]{0, 0, 0, 0, 0, 0});                     // как у плагина без своего участка: править нельзя нигде
        } else if (cfg.sendBounds() && cfg.denyXFrom() >= 0) {
            DimensionType d = inst.getCachedDimensionType();
            int h = net ? 8_388_607 : 33_554_431;
            int minY = d.minY(), maxY = d.minY() + d.height() - 1;
            if (!net) { minY = Math.max(minY, -2047); maxY = Math.min(maxY, 2047); }
            boxes.add(new int[]{-h, minY, -h, Math.min(h, cfg.denyXFrom() - 1), maxY, h});
        }
        StringBuilder key = new StringBuilder();
        for (int[] b : boxes) key.append(java.util.Arrays.toString(b));
        String k = key.toString();
        if (k.equals(s.restrictionsKey)) return;
        s.restrictionsKey = k;
        Out o = new Out(256);
        o.writeVarInt(cfg.allowedPerms().size());
        for (String a : cfg.allowedPerms()) o.writeUtf(a);
        o.writeVarInt(cfg.deniedPerms().size());
        for (String a : cfg.deniedPerms()) o.writeUtf(a);
        o.writeInt(cfg.reach());
        o.writeVarInt(boxes.size());
        for (int[] b : boxes) {
            o.writeLong(Packing.pack(b[0], b[1], b[2], net));
            o.writeLong(Packing.pack(b[3], b[4], b[5], net));
        }
        p.sendPluginMessage(RESTRICTIONS, o.toByteArray());
    }

    // ───────────────────────── пакеты ─────────────────────────

    private void onMessage(Player p, String ch, byte[] data) {
        Session s = session(p);
        try {
            if (ch.equals(TUNNEL)) onTunnel(p, s, data);
            else dispatch(p, s, ch, new Buf(data));
        } catch (Exception | LinkageError ex) {
            logger().warn("{}: пакет {} не разобран: {}", p.getUsername(), ch, ex.toString());
        }
    }

    /** Кусок туннеля: byte флаги (1 — первый, 2 — последний) + данные; склеенное — Identifier, int размер, byte флаги, тело. */
    private void onTunnel(Player p, Session s, byte[] b) {
        if (!s.active || b.length == 0) return;
        if (b.length > cfg.splitSize()) {
            logger().warn("{}: кусок туннеля {} байт больше {} — пропуск", p.getUsername(), b.length, cfg.splitSize());
            s.resetTunnel();
            s.skipTunnel = true;
            return;
        }
        boolean first = (b[0] & 1) != 0, last = (b[0] & 2) != 0;
        if (first && last) { tunnelPacket(p, s, new Buf(b, 1, b.length - 1)); return; }
        if (first) s.resetTunnel();
        else if (s.skipTunnel) return;
        else if (s.parts.isEmpty()) { s.skipTunnel = true; return; }     // середина без начала
        s.parts.add(b);
        s.partsSize += b.length - 1;
        if (s.partsSize > cfg.maxTunnel() + cfg.maxTunnel() / 64 + 4096) {
            logger().warn("{}: туннель больше {} байт — пропуск", p.getUsername(), cfg.maxTunnel());
            s.resetTunnel();
            s.skipTunnel = true;
            return;
        }
        if (!last) return;
        byte[] all = new byte[s.partsSize];
        int at = 0;
        for (byte[] part : s.parts) { System.arraycopy(part, 1, all, at, part.length - 1); at += part.length - 1; }
        s.resetTunnel();
        tunnelPacket(p, s, new Buf(all));
    }

    private void tunnelPacket(Player p, Session s, Buf b) {
        String id = b.readUtf();
        if (id.indexOf(':') < 0) id = "minecraft:" + id;
        int size = b.readInt();
        byte flags = b.readByte();
        if (size < 0 || size > cfg.maxTunnel()) {
            logger().warn("{}: пакет {} через туннель {} байт — больше предела", p.getUsername(), id, size);
            return;
        }
        Buf body;
        if ((flags & 1) != 0) {
            if (!ZstdCodec.ready()) { logger().warn("{}: сжатый {} — zstd нет", p.getUsername(), id); return; }
            body = new Buf(ZstdCodec.decompress(b.array(), b.pos(), b.remaining(), size));
        } else {
            body = new Buf(b.array(), b.pos(), b.remaining());
        }
        if (cfg.debug()) logger().info("{}: туннель {} ({} байт)", p.getUsername(), id, size);
        dispatch(p, s, id, body);
    }

    private void dispatch(Player p, Session s, String ch, Buf b) {
        if (ch.equals(HELLO)) { onHello(p, s, b); return; }
        if (!s.active) return;
        if (!allowed(p)) {                                               // права сняли между проверками
            if (ch.equals(REQUEST_CHUNK)) ChunkData.sendEmpty(p, b.readLong(), term(s));
            b.skipAll();
            return;
        }
        switch (ch) {
            case SET_GAMEMODE -> {
                GameMode gm = switch (b.readByte()) {
                    case 0 -> GameMode.SURVIVAL;
                    case 1 -> GameMode.CREATIVE;
                    case 2 -> GameMode.ADVENTURE;
                    case 3 -> GameMode.SPECTATOR;
                    default -> null;
                };
                if (gm != null) p.setGameMode(gm);
            }
            case SET_FLY_SPEED -> p.setFlyingSpeed(Math.max(-1f, Math.min(1f, b.readFloat())));
            case SET_NO_PHYSICAL -> s.noPhysicalTrigger = b.readBool();  // у Minestom нет плит/грядок — только помним
            case SET_BLOCK -> onSetBlock(p, b);
            case SET_BUFFER -> onSetBuffer(p, s, b);
            case REQUEST_CHUNK -> ChunkData.handle(p, b, cfg, rawTall(), term(s), mapAllowed(p.getInstance()), logger());
            default -> {
                if (ignoredLogged.add(ch)) logger().info("канал {} пока не поддержан — пропускаю", ch);
                b.skipAll();
            }
        }
        if (b.remaining() > 0 && !ch.equals(SET_BUFFER))
            logger().warn("{}: {} — недочитано {} байт", p.getUsername(), ch, b.remaining());
    }

    private void onHello(Player p, Session s, Buf b) {
        int api = b.readVarInt();
        if (api != API_VERSION) {
            goodbye(p, "Axiom: версия протокола клиента " + api + ", сервер ждёт " + API_VERSION + " (Axiom 6.1.x для 1.21.11)");
            b.skipAll();
            return;
        }
        int dataVersion = b.readVarInt();
        int protocol = b.readVarInt();
        long hid = b.readLong();
        if (!s.pending || hid != s.handshakeId) { goodbye(p, "Invalid handshake ID"); return; }
        s.pending = false;
        if (!allowed(p)) { goodbye(p, "Axiom на этом сервере — только для операторов"); return; }
        if (protocol != PROTOCOL_VERSION) {
            goodbye(p, "Axiom: другая версия Minecraft (данные клиента " + dataVersion + ", протокол " + protocol + ")");
            return;
        }
        activate(p, s);
    }

    /** set_block: Map&lt;BlockPos, VarInt&gt;, bool соседи, [Collection&lt;BlockPos&gt;], VarInt причина, bool ломание, BlockHitResult, VarInt рука, VarInt sequence. */
    private void onSetBlock(Player p, Buf b) {
        boolean net = Packing.tall();
        int n = b.readVarInt();
        if (n < 0 || n > b.remaining() / 9) throw new IllegalStateException("блоков " + n);
        long[] pos = new long[n];
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) { pos[i] = b.readLong(); ids[i] = b.readVarInt(); }
        boolean neighbors = b.readBool();
        if (neighbors) {
            int k = b.readVarInt();
            if (k < 0 || k > b.remaining() / 8) throw new IllegalStateException("позиций " + k);
            b.skip(k * 8);
        }
        b.readVarInt();                                                  // причина (replace mode / angel)
        b.readBool();                                                    // ломание
        b.readLong(); b.readVarInt(); b.readFloat(); b.readFloat(); b.readFloat(); b.readBool(); b.readBool();   // BlockHitResult
        b.readVarInt();                                                  // рука
        int seq = b.readVarInt();

        Instance inst = p.getInstance();
        if (inst == null || !mapAllowed(inst)) {
            if (seq >= 0) p.sendPacket(new AcknowledgeBlockChangePacket(seq));
            return;
        }
        DimensionType d = inst.getCachedDimensionType();
        boolean updates = cfg.neighborUpdates() && neighbors;
        int placed = 0;
        for (int i = 0; i < n; i++) {
            int x = Packing.x(pos[i], net), y = Packing.y(pos[i], net), z = Packing.z(pos[i], net);
            if (cfg.deniedX(x) || y < d.minY() || y >= d.minY() + d.height()) continue;
            if (!server().isCubeLoaded(inst, x, y, z)) continue;           // куб не загружен — не писать (§4.2)
            Block blk = Block.fromStateId(ids[i]);
            if (blk == null || blk.stateId() == BlockBuffer.VOID_AIR) continue;
            inst.setBlock(x, y, z, blk, updates);
            placed++;
        }
        // ack ПОСЛЕ блоков (как у ванили: ack в конце тика) — иначе клиент на миг откатит предсказание к старому блоку
        if (seq >= 0) p.sendPacket(new AcknowledgeBlockChangePacket(seq));
        if (cfg.debug()) logger().info("{}: set_block {} из {}", p.getUsername(), placed, n);
    }

    /** set_buffer: разбор в отдельном потоке (zstd блок-сущностей), применение — очередью по тикам. */
    private void onSetBuffer(Player p, Session s, Buf b) {
        boolean raw = rawTall();
        UUID uuid = p.getUuid();
        scheduler().async(() -> {
            BlockBuffer.Parsed parsed;
            try {
                parsed = BlockBuffer.parse(b, raw, cfg, ZstdCodec.ready());
                if (b.remaining() > 0) logger().warn("{}: set_buffer — недочитано {} байт", p.getUsername(), b.remaining());
            } catch (Exception | LinkageError ex) {
                logger().warn("{}: set_buffer не разобран: {}", p.getUsername(), ex.toString());
                return;
            }
            scheduler().run(() -> applyBuffer(p, uuid, parsed));
        });
    }

    private void applyBuffer(Player p, UUID uuid, BlockBuffer.Parsed buf) {
        Session s = sessions.get(uuid);
        if (s == null || !s.active || !p.isOnline()) return;
        s.clientTerm = buf.term();
        if (!consumeSends(s, buf.total(), buf.clientSends())) {
            logger().warn("{}: Axiom шлёт секции быстрее {}/с", p.getUsername(), cfg.sectionsPerSecond());
            if (cfg.kickTooFast()) p.kick(Component.text("Axiom: слишком быстро (лимит " + cfg.sectionsPerSecond() + " секций/с)"));
            return;
        }
        if (buf.type() != 0) return;                                     // биомы — этап 2
        Instance inst = p.getInstance();
        if (inst == null || !allowed(p) || !mapAllowed(inst)) return;
        if (!buf.dimension().equals(inst.getDimensionName())) {
            if (cfg.debug()) logger().info("{}: буфер для «{}», игрок в «{}» — пропуск", p.getUsername(), buf.dimension(), inst.getDimensionName());
            return;
        }
        if (edits.size() + buf.sections().size() > cfg.maxQueuedSections()) {
            logger().warn("{}: очередь правок полна ({} секций) — буфер на {} секций отклонён", p.getUsername(), edits.size(), buf.sections().size());
            p.sendMessage(Component.text("Axiom: сервер не успевает за правками — часть не применена, отмените (Ctrl+Z) и повторите позже",
                    NamedTextColor.RED));
            return;
        }
        WeakReference<Instance> ref = new WeakReference<>(inst);
        int queued = 0;
        for (BlockBuffer.Section sec : buf.sections()) {
            if (cfg.deniedX(sec.sx() << 4) && cfg.deniedX((sec.sx() << 4) + 15)) continue;
            edits.add(new EditQueue.Job(uuid, ref, sec));
            queued++;
        }
        if (buf.dropped() > 0)
            logger().warn("{}: в буфере {} секций, {} отброшено (max-buffer-sections)", p.getUsername(), buf.total(), buf.dropped());
        if (buf.nbtFailed() > 0) logger().warn("{}: блок-сущностей не распаковано: {}", p.getUsername(), buf.nbtFailed());
        if (cfg.debug()) logger().info("{}: set_buffer — секций {} в очередь (всего в очереди {})", p.getUsername(), queued, edits.size());
        if (queued > cfg.sectionsPerTick() * 40)
            p.sendMessage(Component.text("Axiom: большая правка, " + queued + " секций — применится за ~"
                    + (edits.size() / cfg.sectionsPerTick() / 20 + 1) + " с", NamedTextColor.GRAY));
    }
}
