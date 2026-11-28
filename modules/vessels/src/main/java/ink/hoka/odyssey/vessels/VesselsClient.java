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
 *     6 CORRECT double x y z, float qx qy qz qw — сервер не принял позу (или варп): встать сюда
 *     7 STATE   UTF-JSON {fuel, capacity, warp-cost, speed-mul, empty, gun-reload (тики), gun-range, warp-phase
 *               (0 нет, 1 зарядка, 2 наводка), warp-left (тики до конца зарядки), warp-charge (тики), warp-reload-left,
 *               warp-reload (тики)} — пилоту: HUD (топливо, перезарядки, варп)
 *     8 SHOT    int корабль, double x y z (ствол), double x y z (куда), byte что (0 мимо, 1 блок, 2 сущность,
 *               3 корабль, 4 незагружено) — всем рядом: болт, вспышка, звук, искры
 *     9 WARP    int корабль, byte что (0 отмена, 1 зарядка: int тиков, 2 прыжок) — пилоту и стоящим на корабле
 *    10 MSG     UTF текст, byte 1 — предупреждение — строка в HUD пилота (или над хотбаром)
 *    11 MENU    UTF-JSON {window (номер окна Minestom), title (заголовок окна текстом), id, kind, owner, fuel, capacity,
 *               warp-cost, warp-distance, warp-charge (с), warp-reload (с), gun-damage, gun-reload (с), gun-range,
 *               speed-mul, burn (в минуту), upgrades (сколько стоит), mul {speed, warp-cost, gun-reload, gun-damage,
 *               warp-reload}} — перед открытием окна корабля и при смене груза (тем, кто смотрит): мод рисует окно
 *               по-своему (vessels-3); старый мод пакет пропускает — у него обычный сундук
 *    12 HIT     byte 1 — попадание по цели, 2 — убийство, 3 — по кораблю, 4 — корабль разрушен — стрелявшему, когда
 *               болт долетел (маркер у прицела; по кораблю — своим цветом)
 *    13 HULL    UTF-JSON {hp, max, zones[5] (целость зон 0…1: нос, корпус, левый борт, правый борт, двигатели), hit
 *               (true — попадание/удар: встряска и красный край), zone (куда, −1)} — пилоту: прочность в HUD (vessels-4)
 *    14 SHOP    UTF-JSON {window, title, id, recipes:[{name, count, ok, needs:[{name, need, have}]}]} — перед окном
 *               мастерской и при смене груза: мод рисует окно мастерской по-своему
 *    15 WORLD   UTF-JSON {planet, clearance, soft, beam-reach} — карта игрока: планета (подушка у пилота, лучи) или
 *               космос; при входе в модуль и смене мира
 * C2S 1 POSE    double x y z, float qx qy qz qw — поза своего корабля, 20 раз в секунду
 *     2 BOARD   int корабль — хочу сесть (ПКМ по кораблю)
 *     3 EXIT    — выйти (то же, что F)
 *     4 MENU    int корабль — открыть меню (G): пилот — свой, иначе — на который смотрит (на планете — до beam-reach)
 *     5 FIRE    float x y z — выстрел по направлению прицела (ЛКМ пилота)
 *     6 WARP    — начать зарядку варпа / отменить (R)
 *     7 IMPACT  float скорость (бл/с), float x y z (направление, мир) — мод пилота врезался в блоки на скорости
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
        world(p, p.getInstance());
        for (Vessel v : mod.vessels()) if (v.pilot != null && v.pilot.player == p) { start(v); state(v); hull(v, false, -1); }   // модуль перезагрузился посреди полёта
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

    /** Пилоту: сводка для HUD. */
    void state(Vessel v) {
        if (v.pilot == null) return;
        VesselsModule.Status s = mod.status(v);
        long now = mod.ticks();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fuel", s.fuel());
        m.put("capacity", s.capacity());
        m.put("warp-cost", s.warpCost());
        m.put("speed-mul", s.speedMul());
        m.put("empty", s.empty());
        m.put("gun-reload", Math.round(s.gunReloadSec() * 20));
        m.put("gun-range", s.gunRange());
        m.put("warp-phase", v.warpPhase);
        m.put("warp-left", v.warpPhase == WarpDrive.CHARGING ? Math.max(0, v.warpAt - now) : 0);
        m.put("warp-charge", Math.round(s.warpChargeSec() * 20));
        m.put("warp-reload-left", Math.max(0, v.warpReady - now));
        m.put("warp-reload", Math.round(s.warpReloadSec() * 20));
        String js = new Gson().toJson(m);
        send(v.pilot.player, bytes(out -> {
            out.writeByte(7);
            out.writeUTF(js);
        }));
    }

    /** Выстрел — всем в мире в 512 блоках от ствола. */
    void shot(net.minestom.server.instance.Instance inst, Vessel v, double[] from, double[] to, ShotRay.Kind kind) {
        byte[] b = bytes(out -> {
            out.writeByte(8);
            out.writeInt(v.id);
            for (double d : from) out.writeDouble(d);
            for (double d : to) out.writeDouble(d);
            out.writeByte(kind.ordinal());
        });
        for (Player p : inst.getPlayers()) {
            var pp = p.getPosition();
            double dx = pp.x() - from[0], dy = pp.y() - from[1], dz = pp.z() - from[2];
            if (dx * dx + dy * dy + dz * dz <= 512 * 512) send(p, b);
        }
    }

    /** Варп: пилоту (и стоящим на корабле — прыжок, см. warpFxTo). what: 0 отмена, 1 зарядка (ticks), 2 прыжок. */
    void warpFx(Vessel v, int what, int ticks) {
        if (v.pilot != null) send(v.pilot.player, warp(v, what, ticks));
    }

    void warpFxTo(Player p, Vessel v, int what) { send(p, warp(v, what, 0)); }

    private static byte[] warp(Vessel v, int what, int ticks) {
        return bytes(out -> {
            out.writeByte(9);
            out.writeInt(v.id);
            out.writeByte(what);
            out.writeInt(ticks);
        });
    }

    /** Сводка окна корабля (S2C MENU) — до открытия окна и при смене груза. */
    void menu(Player p, Vessel v, VesselMenu m) {
        if (!isReady(p)) return;
        VesselsModule.Status s = mod.status(v);
        Upgrades.Stats st = mod.stats(v);
        int ups = 0;
        for (int c : v.cargo.upgradeCounts()) ups += c;
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("window", (int) m.inv.getWindowId());
        o.put("title", m.title);
        o.put("id", v.id);
        o.put("kind", v.kind);
        if (v.ownerName != null) o.put("owner", v.ownerName);
        o.put("fuel", s.fuel());
        o.put("capacity", s.capacity());
        o.put("warp-cost", s.warpCost());
        o.put("warp-distance", s.warpDistance());
        o.put("warp-charge", s.warpChargeSec());
        o.put("warp-reload", s.warpReloadSec());
        o.put("gun-damage", s.gunDamage());
        o.put("gun-reload", s.gunReloadSec());
        o.put("gun-range", s.gunRange());
        o.put("speed-mul", s.speedMul());
        o.put("burn", s.burnPerMinute());
        o.put("upgrades", ups);
        double max = mod.hull.maxHp(v);
        o.put("hp", Math.round(v.hp(max) * 10) / 10.0);
        o.put("hp-max", max);
        o.put("zones", mod.hull.zoneHealth(v));
        Map<String, Object> mul = new LinkedHashMap<>();
        mul.put("speed", st.speed());
        mul.put("warp-cost", st.warpCost());
        mul.put("gun-reload", st.gunReload());
        mul.put("gun-damage", st.gunDamage());
        mul.put("warp-reload", st.warpReload());
        o.put("mul", mul);
        String js = new Gson().toJson(o);
        send(p, bytes(out -> {
            out.writeByte(11);
            out.writeUTF(js);
        }));
    }

    /** Пилоту: прочность и зоны (hit — попадание: встряска, красный край). */
    void hull(Vessel v, boolean hit, int zone) {
        if (v.pilot == null) return;
        double max = mod.hull.maxHp(v);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hp", Math.round(v.hp(max) * 10) / 10.0);
        m.put("max", max);
        m.put("zones", mod.hull.zoneHealth(v));
        m.put("hit", hit);
        m.put("zone", zone);
        String js = new Gson().toJson(m);
        send(v.pilot.player, bytes(out -> {
            out.writeByte(13);
            out.writeUTF(js);
        }));
    }

    /** Окно мастерской (S2C SHOP) — до открытия и при смене груза. */
    void shop(Player p, String json) {
        send(p, bytes(out -> {
            out.writeByte(14);
            out.writeUTF(json);
        }));
    }

    /** Карта игрока: планета (подушка, лучи) или космос. */
    void world(Player p, net.minestom.server.instance.Instance inst) {
        if (p == null || inst == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planet", mod.isPlanet(inst));
        m.put("clearance", mod.clearance());
        m.put("soft", mod.config().getDouble("planet.soft", 6));
        m.put("beam-reach", mod.beamReach());
        String js = new Gson().toJson(m);
        send(p, bytes(out -> {
            out.writeByte(15);
            out.writeUTF(js);
        }));
    }

    /** Пилоту: болт попал (1 — цель, 2 — убийство, 3 — корабль, 4 — корабль разрушен) — маркер у прицела. */
    void hit(Player p, int what) {
        send(p, new byte[]{12, (byte) what});
    }

    /** Строка в HUD пилота; у игрока без модуля — над хотбаром. */
    void message(Player p, String text, boolean warn) {
        if (!isReady(p)) {
            p.sendActionBar(net.kyori.adventure.text.Component.text(text, warn ? net.kyori.adventure.text.format.NamedTextColor.RED
                    : net.kyori.adventure.text.format.NamedTextColor.AQUA));
            return;
        }
        send(p, bytes(out -> {
            out.writeByte(10);
            out.writeUTF(text);
            out.writeByte(warn ? 1 : 0);
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
            m.put("hp", k.hp);
            m.put("zones", Map.of("side", k.zones.side(), "nose", k.zones.nose(), "tail", k.zones.tail(), "nose-from", k.noseFrom));
            all.put(k.id, m);
        }
        Map<String, Object> common = new LinkedHashMap<>();                   // общие настройки мода (как у ships: aim, camera, hud)
        for (String s : new String[]{"aim", "camera", "hud", "control", "hull", "gun"}) {
            Object o = mod.config().get(s);
            if (o != null) common.put(s, o);
        }
        common.put("board-reach", mod.boardReach());
        common.put("menu-reach", mod.menuReach());
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
                case 4 -> mod.openMenu(p, in.readInt());
                case 5 -> mod.combat.fire(p, in.readFloat(), in.readFloat(), in.readFloat());
                case 6 -> mod.warp.toggle(p);
                case 7 -> mod.hull.impact(p, in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
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
