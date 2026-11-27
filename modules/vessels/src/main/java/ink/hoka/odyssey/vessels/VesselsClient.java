package ink.hoka.odyssey.vessels;

import com.google.gson.Gson;
import ink.hoka.odyssey.clientmods.ClientModsApi;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Связь с клиентским модулем {@code vessels}. Корабль рисует мод по носителю (item display: положение и поворот с
 * интерполяцией), свой корабль пилот ведёт сам.
 * <pre>
 * S2C 1 KINDS   UTF-JSON {вид: {model, scale, turn, center[3] (пиксели), half[3], seat[3], speed, boost-speed, accel,
 *               turn-speed, roll-speed}, "_": {aim, camera, hud}} — виды кораблей и общие настройки
 *     2 ATTACH  int корабль, int носитель, UTF вид — корабль на носителе (снова — заменить)
 *     3 DETACH  int корабль
 *     4 START   int корабль, double x y z, float qx qy qz qw — ты пилот (поза корабля сейчас)
 *     5 END     — ты больше не пилот
 *     6 CORRECT double x y z, float qx qy qz qw — сервер не принял позу: вернуться сюда
 * C2S 1 POSE    double x y z, float qx qy qz qw — поза своего корабля, 20 раз в секунду
 *     2 BOARD   int корабль — хочу сесть (ПКМ по кораблю)
 *     3 EXIT    — выйти (то же, что F)
 * </pre>
 * Поза — середина модели в мире и поворот «система корабля → мир» (нос +Z, верх +Y, левый борт +X).
 */
final class VesselsClient {

    static final String MODULE = "vessels";

    private final VesselsModule mod;
    private ClientModsApi api;

    VesselsClient(VesselsModule mod) { this.mod = mod; }

    void enable() {
        api = mod.services().get(ClientModsApi.class).orElse(null);
        if (api == null) { mod.logger().warn("нет clientmods — корабли не видны и не летают"); return; }
        api.onReady(MODULE, this::ready);
        api.on(MODULE, this::onMessage);
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) if (api.isReady(p, MODULE)) ready(p);   // модуль перезагрузили
    }

    void disable() {
        if (api == null) return;
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            for (Vessel v : mod.vessels()) send(p, detach(v));
        }
        api.off(MODULE);
    }

    boolean isReady(Player p) { return api != null && api.isReady(p, MODULE); }

    /** Клиентский модуль загрузился у игрока: виды и все корабли. */
    private void ready(Player p) {
        send(p, kinds());
        for (Vessel v : mod.vessels()) if (v.carrier != null && !v.carrier.isRemoved()) send(p, attach(v));
        for (Vessel v : mod.vessels()) if (v.pilot != null && v.pilot.player == p) start(v);   // модуль перезагрузился посреди полёта
    }

    private void send(Player p, byte[] b) {
        if (api != null && api.isReady(p, MODULE)) api.send(p, MODULE, b);
    }

    private void all(byte[] b) {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) send(p, b);
    }

    // ───────────────────────── сервер → мод ─────────────────────────

    void kindsToAll() { all(kinds()); }

    void attachToAll(Vessel v) { if (v.carrier != null) all(attach(v)); }

    void detachToAll(Vessel v) { all(detach(v)); }

    void start(Vessel v) {
        Player p = v.pilot.player;
        send(p, bytes(out -> {
            out.writeByte(4);
            out.writeInt(v.id);
            pose(out, v.x, v.y, v.z, v.rot);
        }));
    }

    void end(Player p) { send(p, new byte[]{5}); }

    void correct(Vessel v) {
        if (v.pilot == null) return;
        send(v.pilot.player, bytes(out -> {
            out.writeByte(6);
            pose(out, v.x, v.y, v.z, v.rot);
        }));
    }

    private static void pose(DataOutputStream out, double x, double y, double z, Quat q) throws IOException {
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
        out.writeFloat((float) q.x());
        out.writeFloat((float) q.y());
        out.writeFloat((float) q.z());
        out.writeFloat((float) q.w());
    }

    private byte[] kinds() {
        Map<String, Object> all = new LinkedHashMap<>();
        for (VesselKind k : mod.kinds().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", k.model);
            m.put("scale", k.scale);
            m.put("turn", k.turn);
            m.put("center", k.centerPx);
            m.put("half", k.half);
            m.put("seat", k.seat);
            m.put("speed", k.speed);
            m.put("boost-speed", k.boostSpeed);
            m.put("accel", k.accel);
            m.put("turn-speed", k.turnSpeed);
            m.put("roll-speed", k.rollSpeed);
            all.put(k.id, m);
        }
        Map<String, Object> common = new LinkedHashMap<>();                   // общие настройки мода (как у ships: aim, camera, hud)
        for (String s : new String[]{"aim", "camera", "hud", "control"}) {
            Object o = mod.config().get(s);
            if (o != null) common.put(s, o);
        }
        common.put("board-reach", mod.boardReach());
        all.put("_", common);
        String js = new Gson().toJson(all);
        return bytes(out -> {
            out.writeByte(1);
            byte[] b = js.getBytes(StandardCharsets.UTF_8);
            out.writeInt(b.length);
            out.write(b);
        });
    }

    private static byte[] attach(Vessel v) {
        return bytes(out -> {
            out.writeByte(2);
            out.writeInt(v.id);
            out.writeInt(v.carrier.getEntityId());
            out.writeUTF(v.kind);
        });
    }

    private static byte[] detach(Vessel v) {
        return bytes(out -> {
            out.writeByte(3);
            out.writeInt(v.id);
        });
    }

    // ───────────────────────── мод → сервер ─────────────────────────

    private void onMessage(Player p, byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int op = in.readUnsignedByte();
            switch (op) {
                case 1 -> {
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    Quat q = new Quat(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
                    mod.pose(p, x, y, z, q);
                }
                case 2 -> mod.board(p, in.readInt());
                case 3 -> mod.exit(p, true);
                default -> { }
            }
        } catch (IOException e) {
            mod.logger().warn("{}: пакет кораблей не разобран: {}", p.getUsername(), e.toString());
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
