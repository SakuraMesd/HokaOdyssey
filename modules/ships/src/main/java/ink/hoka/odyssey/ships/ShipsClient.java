package ink.hoka.odyssey.ships;

import com.google.gson.Gson;
import ink.hoka.odyssey.clientmods.ClientModsApi;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Связь с клиентским модулем {@code ships}. Корабль ведёт мод пилота, остальные видят его на «якоре».
 * <pre>
 * S2C 1 ATTACH  int якорь, int -1, float seatH, float курс взлёта, блоки — показать чужой корабль на якоре
 *     2 DETACH  int якорь
 *     3 START   int якорь, double x y z, float курс взлёта, float seatH, UTF-JSON настроек, блоки — ты пилот
 *     4 END     — полёт окончен (посадка/авария)
 *     5 CORRECT double x y z, float курс наклон крен — сервер не принял положение: вернуться сюда
 *     6 CELL    int якорь, short dx dy dz, int stateId (0 — пусто) — клетка корабля изменилась (дверь, стройка)
 *     блоки: int n, n × (short dx, short dy, short dz, int stateId) — от клетки пилота, мир при взлёте
 * C2S 1 POSE    double x y z, float курс наклон крен — каждый тик
 *     2 LAND    — посадка (то же, что F)
 *     3 INSIDE  byte 1/0 — игрок внутри корабля (гравитация корабля) / вышел: на это время вакуум его не трогает
 *     4 USE     int якорь, short dx dy dz, byte грань, float точка x y z (0..1 в клетке) — ПКМ по кораблю-модели
 *     5 BREAK   int якорь, short dx dy dz — ЛКМ (сломать блок)
 * </pre>
 */
final class ShipsClient {

    static final String MODULE = "ships";

    private final ShipsModule mod;
    private ClientModsApi api;

    ShipsClient(ShipsModule mod) { this.mod = mod; }

    void enable() {
        api = mod.services().get(ClientModsApi.class).orElse(null);
        if (api == null) { mod.logger().warn("нет clientmods — летать нельзя"); return; }
        // игрок зашёл / клиентский модуль обновился — показать летящие корабли
        api.onReady(MODULE, p -> {
            for (Ship s : mod.ships.values()) {
                if (s.flight == null || s.flight.anchorAll() == null) continue;   // летящие
                if (s.flight.pilot == p) startPilot(s.flight);                  // модуль перезагрузился посреди полёта — продолжить
                else send(p, attach(s.flight));
            }
            for (Ship s : mod.ships.values()) {                                // корабли-модели на стоянке
                Entity a = mod.parked.get(s.owner);
                if (a != null && s.state == Ship.State.MODEL) send(p, modelPacket(s, a));
            }
        });
        api.on(MODULE, this::onMessage);
    }

    void disable() {
        if (api != null) api.off(MODULE);
    }

    boolean ready(Player p) { return api != null && api.isReady(p, MODULE); }

    private void send(Player p, byte[] b) {
        if (api != null && api.isReady(p, MODULE)) api.send(p, MODULE, b);
    }

    // ───────────────────────── сервер → мод ─────────────────────────

    void start(Flight f) {
        startPilot(f);
        byte[] b = attach(f);
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) if (p != f.pilot()) send(p, b);
        hideVanilla(f.pilot(), List.of("mount", "crosshair", "hotbar", "health", "armor", "food", "air", "xp", "xp_level", "held_item"));   // низ экрана — приборы корабля
    }

    /** Пилоту: «ты летишь» — с текущим положением корабля (при взлёте и если модуль перезагрузился в полёте). */
    private void startPilot(Flight f) {
        double[] q = f.pose();
        String settings = new Gson().toJson(settings());
        send(f.pilot(), bytes(out -> {
            out.writeByte(3);
            out.writeInt(f.anchorAll().getEntityId());
            out.writeDouble(q[0]);
            out.writeDouble(q[1]);
            out.writeDouble(q[2]);
            out.writeFloat((float) f.blockTurn());
            out.writeFloat((float) f.seatHeight());
            byte[] js = settings.getBytes(StandardCharsets.UTF_8);
            out.writeInt(js.length);
            out.write(js);
            parts(out, f);
        }));
        if (q[4] != 0 || q[5] != 0 || q[3] != f.blockTurn()) correct(f, q[0], q[1], q[2], q[3], q[4], q[5]);   // текущий курс/наклон/крен
    }

    void stop(Flight f) {
        if (f.pilot().isOnline()) {
            send(f.pilot(), new byte[]{4});
            hideVanilla(f.pilot(), List.of());
        }
        Entity a = f.anchorAll();
        if (a == null) return;
        int id = a.getEntityId();
        byte[] b = bytes(out -> { out.writeByte(2); out.writeInt(id); });
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) if (p != f.pilot()) send(p, b);
    }

    void correct(Flight f, double x, double y, double z, double yaw, double pitch, double roll) {
        send(f.pilot(), bytes(out -> {
            out.writeByte(5);
            out.writeDouble(x);
            out.writeDouble(y);
            out.writeDouble(z);
            out.writeFloat((float) yaw);
            out.writeFloat((float) pitch);
            out.writeFloat((float) roll);
        }));
    }

    /** Корабль-модель на стоянке — всем (и бывшему пилоту). */
    void attachModel(Ship s, Entity anchor) {
        byte[] b = modelPacket(s, anchor);
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) send(p, b);
    }

    /** Клетка корабля изменилась — всем с модом. */
    void cell(int anchor, int dx, int dy, int dz, int stateId) {
        byte[] b = bytes(out -> {
            out.writeByte(6);
            out.writeInt(anchor);
            out.writeShort(dx);
            out.writeShort(dy);
            out.writeShort(dz);
            out.writeInt(stateId);
        });
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) send(p, b);
    }

    void detachModel(Entity anchor) {
        int id = anchor.getEntityId();
        byte[] b = bytes(out -> { out.writeByte(2); out.writeInt(id); });
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) send(p, b);
    }

    private static byte[] modelPacket(Ship s, Entity anchor) {
        return bytes(out -> {
            out.writeByte(1);
            out.writeInt(anchor.getEntityId());
            out.writeInt(-1);
            out.writeFloat(s.mseatH);
            out.writeFloat(s.mturn);
            int n = s.mparts.size();
            out.writeInt(n);
            for (int i = 0; i < n; i++) {
                int[] q = s.mparts.get(i);
                net.minestom.server.instance.block.Block b = net.minestom.server.instance.block.Block.fromState(s.mblocks.get(i));
                out.writeShort(q[0]);
                out.writeShort(q[1]);
                out.writeShort(q[2]);
                out.writeInt(b == null ? 0 : b.stateId());
            }
        });
    }

    private static byte[] attach(Flight f) {
        return bytes(out -> {
            out.writeByte(1);
            out.writeInt(f.anchorAll().getEntityId());
            out.writeInt(-1);
            out.writeFloat((float) f.seatHeight());
            out.writeFloat((float) f.blockTurn());
            parts(out, f);
        });
    }

    private static void parts(DataOutputStream out, Flight f) throws IOException {
        List<int[]> parts = f.partsForClient();
        out.writeInt(parts.size());
        for (int[] p : parts) {
            out.writeShort(p[0]);
            out.writeShort(p[1]);
            out.writeShort(p[2]);
            out.writeInt(p[3]);
        }
    }

    /** Настройки полёта для мода — разделы flight, aim, camera, hud конфига (мод читает их по тем же именам). */
    private Map<String, Object> settings() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : new String[]{"flight", "aim", "camera", "hud"}) {
            Object o = mod.config().get(k);
            if (o != null) m.put(k, o);
        }
        return m;
    }

    // ───────────────────────── мод → сервер ─────────────────────────

    private void onMessage(Player p, byte[] data) {
        Flight f = mod.flightOf(p);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int op = in.readUnsignedByte();
            if (op == 3) { mod.inside(p, in.readUnsignedByte() != 0); return; }
            if (op == 4) {
                int a = in.readInt(), dx = in.readShort(), dy = in.readShort(), dz = in.readShort(), face = in.readByte();
                float hx = in.readFloat(), hy = in.readFloat(), hz = in.readFloat();
                mod.interact.use(p, a, dx, dy, dz, face, hx, hy, hz);
                return;
            }
            if (op == 5) {
                int a = in.readInt(), dx = in.readShort(), dy = in.readShort(), dz = in.readShort();
                mod.interact.breakBlock(p, a, dx, dy, dz);
                return;
            }
            if (f == null) return;
            if (op == 1) {
                double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                float yaw = in.readFloat(), pitch = in.readFloat(), roll = in.readFloat();
                f.pose(x, y, z, yaw, pitch, roll);
            } else if (op == 2) {
                f.land();
            }
        } catch (IOException e) {
            mod.logger().warn("{}: пакет корабля не разобран: {}", p.getUsername(), e.toString());
        }
    }

    /** Ванильные части экрана на время полёта (модуль houi, если есть). */
    private void hideVanilla(Player p, List<String> parts) {
        try {
            mod.services().get(ink.hoka.odyssey.houi.HoHud.class).ifPresent(h -> h.hideVanilla(p, parts));
        } catch (Throwable ignored) {                                       // модуля houi нет
        }
    }

    private interface W { void write(DataOutputStream out) throws IOException; }

    private static byte[] bytes(W w) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) {
            w.write(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return b.toByteArray();
    }
}
