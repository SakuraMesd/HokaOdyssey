package ink.hoka.odyssey.ships;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Верфь → мод (SHIPYARD.md §3): блоки плота корабля — настоящими кубами 16×16×16 всем, кто видит корабль (рядом с
 * ним в мире), независимо от того, как далеко сам плот. Мод держит их в мире игры как обычные столбцы (ядро:
 * CubeReceiver + столбцы верфи), отсюда настоящая отрисовка, столкновения и клики. Формат — пакет кубов ядра
 * {@code hoka_odyssey:w}: op 2 CUBES (как CubeStreamer сервера), op 6 FORGET — забыть столбцы верфи.
 */
final class YardStreamer {

    static final String CHANNEL = "hoka_odyssey:w";
    private final ShipsModule mod;
    /** Кому какие корабли (владелец) уже отправлены. */
    private final Map<UUID, Set<String>> sent = new ConcurrentHashMap<>();

    YardStreamer(ShipsModule mod) { this.mod = mod; }

    void enable() {
        mod.scheduler().repeat(this::tick, 20, 10);
        mod.events().addListener(net.minestom.server.event.player.PlayerDisconnectEvent.class, e -> { sent.remove(e.getPlayer().getUuid()); hideAll(e.getPlayer()); });
        mod.events().addListener(net.minestom.server.event.player.PlayerSpawnEvent.class, e -> { sent.remove(e.getPlayer().getUuid()); hideAll(e.getPlayer()); });
    }

    /** Где корабль в мире сейчас (стоит моделью или летит) или null. */
    private static double[] pose(Ship s) {
        if (s.flight != null && s.yard == null) { double[] q = s.flight.pose(); return new double[]{q[0], q[1], q[2]}; }
        if (s.state == Ship.State.FLYING) return new double[]{s.mx, s.my, s.mz};  // из верфи: поза в m*
        if (s.state == Ship.State.MODEL) return new double[]{s.mx, s.my, s.mz};
        return null;
    }

    private void tick() {
        double range = mod.config().getDouble("yard.view-range", 320);
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            Set<String> mine = sent.computeIfAbsent(p.getUuid(), u -> new HashSet<>());
            for (Ship s : mod.ships.values()) {
                if (s.yard == null || s.mparts == null) continue;
                Instance inst = mod.instanceOf(s);
                double[] at = pose(s);
                boolean see = at != null && inst == p.getInstance()
                        && p.getPosition().distanceSquared(new net.minestom.server.coordinate.Vec(at[0], at[1], at[2])) < range * range;
                if (see && mine.add(s.owner)) sendAll(p, s, inst);
                else if (!see && mine.remove(s.owner)) forget(p, s);
            }
        }
    }

    /** Корабль изменился в клетке (dx,dy,dz): переслать её куб всем, кому корабль отправлен. */
    void changed(Ship s, int dx, int dy, int dz) {
        Instance inst = mod.instanceOf(s);
        if (inst == null || s.yard == null) return;
        BlockVec at = Shipyard.cell(s, dx, dy, dz);
        long key = sec(at.blockX() >> 4, at.blockY() >> 4, at.blockZ() >> 4);
        byte[] data = null;                                                     // пакет один на всех зрителей
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            Set<String> mine = sent.get(p.getUuid());
            if (mine == null || !mine.contains(s.owner)) continue;
            if (data == null) data = packet(inst, s, List.of(key));
            if (data != null) p.sendPacket(new PluginMessagePacket(CHANNEL, data));
        }
    }

    private void sendAll(Player p, Ship s, Instance inst) {
        Set<Long> secs = new HashSet<>();
        for (int[] q : s.mparts) {
            BlockVec b = Shipyard.cell(s, q[0], q[1], q[2]);
            secs.add(sec(b.blockX() >> 4, b.blockY() >> 4, b.blockZ() >> 4));
        }
        List<Long> all = new ArrayList<>(secs);
        List<java.util.concurrent.CompletableFuture<?>> loads = new ArrayList<>();
        for (long k : all) {                                                    // плот мог выгрузиться — загрузить его кубы
            var v = new net.minestom.server.coordinate.Vec(sx(k) << 4, sy(k) << 4, sz(k) << 4);
            loads.add(mod.server().loadCubes(inst, v, v));
        }
        java.util.concurrent.CompletableFuture.allOf(loads.toArray(java.util.concurrent.CompletableFuture[]::new)).thenRun(() -> {
            for (int i = 0; i < all.size(); i += 16) send(p, inst, s, all.subList(i, Math.min(all.size(), i + 16)));
            mod.scheduler().run(() -> showAll(p, s, inst, true));               // сущности палубы — после блоков
        });
    }

    /** Сущности плота корабля (предметы на палубе): показать/спрятать игроку — он в мире, плот далеко. */
    private void showAll(Player p, Ship s, Instance inst, boolean show) {
        if (inst == null || s.yard == null || (show && p.getInstance() != inst)) return;
        var c = new net.minestom.server.coordinate.Vec(s.yard[1], s.yard[2], s.yard[3]);
        inst.getEntityTracker().nearbyEntities(c, Shipyard.SIZE / 2.0, net.minestom.server.instance.EntityTracker.Target.ENTITIES, e -> {
            if (e instanceof Player) return;
            if (Math.floorDiv((int) Math.floor(e.getPosition().z()), Shipyard.SIZE) != s.yard[0]) return;
            e.removeViewer(p);                                                   // (заново: мог остаться зрителем с прошлого раза)
            if (show) e.addViewer(p);
        });
    }

    /** Игрок ушёл с карты / вышел: перестать быть зрителем сущностей всех плотов. */
    private void hideAll(Player p) {
        for (Ship s : mod.ships.values()) if (s.yard != null) showAll(p, s, mod.instanceOf(s), false);
    }

    /** Новая сущность в верфи: показать тем, кому отправлен её корабль (по номеру плота). */
    void showEntity(net.minestom.server.entity.Entity e) {
        if (e.isRemoved() || e.getInstance() == null) return;
        int k = Math.floorDiv((int) Math.floor(e.getPosition().z()), Shipyard.SIZE);
        for (Ship s : mod.ships.values()) {
            if (s.yard == null || s.yard[0] != k || mod.instanceOf(s) != e.getInstance()) continue;
            for (Player p : e.getInstance().getPlayers()) {
                Set<String> mine = sent.get(p.getUuid());
                if (mine != null && mine.contains(s.owner)) e.addViewer(p);
            }
        }
    }

    private void forget(Player p, Ship s) {
        showAll(p, s, mod.instanceOf(s), false);
        Set<Long> cols = new HashSet<>();
        for (int[] q : s.mparts) { BlockVec b = Shipyard.cell(s, q[0], q[1], q[2]); cols.add(((long) (b.blockX() >> 4) << 32) | ((b.blockZ() >> 4) & 0xFFFFFFFFL)); }
        byte[] data = NetworkBuffer.makeArray(buf -> {
            buf.write(NetworkBuffer.BYTE, (byte) 6);
            buf.write(NetworkBuffer.VAR_INT, cols.size());
            for (long c : cols) { buf.write(NetworkBuffer.INT, (int) (c >> 32)); buf.write(NetworkBuffer.INT, (int) c); }
        });
        p.sendPacket(new PluginMessagePacket(CHANNEL, data));
    }

    private static long sec(int x, int y, int z) { return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 20) | (y & 0xFFFFF); }

    private static int sx(long k) { return (int) (k >> 42); }
    private static int sy(long k) { return (int) (k << 44 >> 44); }
    private static int sz(long k) { return (int) (k << 22 >> 42); }

    /** Кубы (секции) плота → пакет CUBES (как CubeStreamer ядра: позиция, блоки, биомы, блок-энтити). */
    private void send(Player p, Instance inst, Ship s, List<Long> keys) {
        byte[] data = packet(inst, s, keys);
        if (data != null) p.sendPacket(new PluginMessagePacket(CHANNEL, data));
    }

    /** Пакет CUBES для кусков keys (null — нечего слать). */
    private byte[] packet(Instance inst, Ship s, List<Long> keys) {
        var biomeSer = Palette.biomeSerializer(MinecraftServer.getBiomeRegistry().size());
        Map<Long, List<int[]>> bes = new HashMap<>();
        if (keys.size() <= 2) {                                                 // один-два куска (изменилась клетка) — смотрим только их
            for (long k : keys) {
                int bx = sx(k) << 4, by = sy(k) << 4, bz = sz(k) << 4;
                List<int[]> l = new ArrayList<>();
                if (mod.server().isCubeLoaded(inst, bx, by, bz))                   // кусок = один куб
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                        if (inst.getBlock(bx + x, by + y, bz + z).registry().blockEntityType() != null) l.add(new int[]{bx + x, by + y, bz + z});
                bes.put(k, l);
            }
        } else for (int[] q : s.mparts) {                                       // весь корабль (отправка зрителю) — по клеткам корабля
            BlockVec b = Shipyard.cell(s, q[0], q[1], q[2]);
            bes.computeIfAbsent(sec(b.blockX() >> 4, b.blockY() >> 4, b.blockZ() >> 4), k -> new ArrayList<>()).add(new int[]{b.blockX(), b.blockY(), b.blockZ()});
        }
        List<Object[]> out = new ArrayList<>();
        for (long k : keys) {
            Chunk ch = inst.getChunk(sx(k), sz(k));
            if (ch == null) continue;
            int sy = sy(k);                                                   // getSection — по номеру секции (Y >> 4), не по индексу
            if (sy < ch.getMinSection() || sy >= ch.getMaxSection()) continue;
            Section sec = ch.getSection(sy);
            List<Object[]> be = new ArrayList<>();
            for (int[] b : bes.getOrDefault(k, List.of())) {
                Block bl = inst.getBlock(b[0], b[1], b[2]);
                if (bl.registry().blockEntityType() != null) be.add(new Object[]{b, bl});
            }
            out.add(new Object[]{k, sec, be});
        }
        if (out.isEmpty()) return null;
        return NetworkBuffer.makeArray(buf -> {
            buf.write(NetworkBuffer.BYTE, (byte) 2);
            buf.write(NetworkBuffer.VAR_INT, out.size());
            for (Object[] o : out) {
                long k = (long) o[0];
                Section sec = (Section) o[1];
                @SuppressWarnings("unchecked") List<Object[]> be = (List<Object[]>) o[2];
                buf.write(NetworkBuffer.LONG, k);
                buf.write(NetworkBuffer.SHORT, (short) sec.blockPalette().count());
                buf.write(Palette.BLOCK_SERIALIZER, sec.blockPalette());
                buf.write(biomeSer, sec.biomePalette());
                buf.write(NetworkBuffer.VAR_INT, be.size());
                for (Object[] e : be) {
                    int[] b = (int[]) e[0];
                    Block bl = (Block) e[1];
                    buf.write(NetworkBuffer.BYTE, (byte) ((b[0] & 15) << 4 | (b[2] & 15)));
                    buf.write(NetworkBuffer.SHORT, (short) b[1]);
                    buf.write(net.minestom.server.instance.block.BlockEntityType.NETWORK_TYPE, bl.registry().blockEntityType());
                    buf.write(NetworkBuffer.NBT, net.minestom.server.utils.block.BlockUtils.extractClientNbt(bl));
                }
            }
        });
    }
}
