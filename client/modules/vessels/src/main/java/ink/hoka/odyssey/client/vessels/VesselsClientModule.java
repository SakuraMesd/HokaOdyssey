package ink.hoka.odyssey.client.vessels;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import ink.hoka.odyssey.client.api.ModelHandle;
import ink.hoka.odyssey.client.model.BbModel;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Клиентский модуль «vessels»: корабли-модели. Протокол — {@code modules/vessels/.../VesselsClient} на сервере.
 * Чужой корабль рисуется по носителю (item display: положение + левый поворот с интерполяцией), свой — по позе пилота.
 * ПКМ по кораблю (луч против треугольников модели) — запрос посадки; клик забираем, блок за кораблём не трогается.
 */
public final class VesselsClientModule implements ClientModule {

    private ClientContext ctx;
    private final Map<String, Kind> kinds = new HashMap<>();
    private JsonObject common = new JsonObject();
    private final Map<Integer, Ship> ships = new HashMap<>();
    private final Set<String> watched = new HashSet<>();
    private Pilot pilot;

    /** Корабль, который видит игрок: носитель, вид, модель. */
    private final class Ship {
        final int id;
        int carrier;
        Kind kind;
        ModelHandle handle;

        Ship(int id) { this.id = id; }

        /** Поза корабля (середина модели в мире, поворот «система корабля → мир») на кадр; нет носителя — false. */
        boolean pose(float pt, Vector3d pos, Quaternionf rot) {
            if (pilot != null && pilot.vessel == id) {
                pilot.poseAt(pt, pos, rot);
                return true;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || !(mc.level.getEntity(carrier) instanceof Display d) || d.renderState() == null) return false;
            Vec3 p = d.getPosition(pt);
            pos.set(p.x, p.y, p.z);
            Matrix4f m = new Matrix4f(d.renderState().transformation().get(d.calculateInterpolationProgress(pt)).getMatrix());
            m.getNormalizedRotation(rot);
            return true;
        }

        /** Поставить модель (вид пришёл или сменился). */
        void show() {
            if (handle != null) { handle.remove(); handle = null; }
            if (kind == null) return;
            Kind k = kind;
            handle = ctx.model(k.model).scale(k.scale).pose((pt, pos, rot) -> {
                if (!pose(pt, pos, rot)) return false;
                // начало модели (0,0,0 Blockbench) = середина − повёрнутый центр модели в блоках
                Quaternionf full = rot.mul(k.modelTurn());
                float s = k.scale / 16f;
                Vector3f c = full.transform(new Vector3f(-k.center[0] * s, -k.center[1] * s, -k.center[2] * s));
                pos.add(c.x, c.y, c.z);
                return true;
            });
        }

        void remove() {
            if (handle != null) handle.remove();
            handle = null;
        }
    }

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        ctx.onMessage(this::onMessage);
        ctx.onTick(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (pilot != null && (mc.player == null || mc.level == null)) stopPilot();
            if (pilot != null) pilot.tick();
        });
        ctx.onMouse((dy, dp) -> pilot != null && pilot.mouse(dy, dp));
        ctx.onCamera((s, pt) -> { if (pilot != null) pilot.camera(s, pt); });
        ctx.onInput(in -> { if (pilot != null) ctx.setMove(in, 0, 0); });       // пилот не ходит — клавиши читает Pilot
        ctx.onClick((attack, held) -> {
            if (pilot != null) return true;                                     // из кабины ничего не ставим и не ломаем
            return !attack && !held && clickBoard();
        });
        ctx.onHud((g, d) -> { if (pilot != null) pilot.hud(g, d); });
    }

    @Override
    public void disable() {
        stopPilot();
        for (Ship s : ships.values()) s.remove();
        ships.clear();
    }

    private void stopPilot() {
        if (pilot != null) pilot.end();
        pilot = null;
    }

    // ───────────────────────── сервер → мод ─────────────────────────

    private void onMessage(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            switch (in.readUnsignedByte()) {
                case 1 -> {                                                     // KINDS
                    byte[] b = new byte[in.readInt()];
                    in.readFully(b);
                    kinds(JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject());
                }
                case 2 -> {                                                     // ATTACH
                    int id = in.readInt(), carrier = in.readInt();
                    String kind = in.readUTF();
                    Ship s = ships.computeIfAbsent(id, Ship::new);
                    s.carrier = carrier;
                    Kind k = kinds.get(kind);
                    if (s.kind != k || s.handle == null) { s.kind = k; s.show(); }
                }
                case 3 -> {                                                     // DETACH
                    Ship s = ships.remove(in.readInt());
                    if (s != null) s.remove();
                }
                case 4 -> {                                                     // START
                    int id = in.readInt();
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    Quaternionf q = new Quaternionf(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
                    Ship s = ships.get(id);
                    if (s == null || s.kind == null) { ctx.logger().warn("корабль {}: нет вида — полёт не начат", id); return; }
                    stopPilot();
                    pilot = new Pilot(ctx, id, s.kind, common, x, y, z, q);
                }
                case 5 -> stopPilot();                                          // END
                case 6 -> {                                                     // CORRECT
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    Quaternionf q = new Quaternionf(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
                    if (pilot != null) pilot.correct(x, y, z, q);
                }
                default -> { }
            }
        } catch (IOException | RuntimeException e) {
            ctx.logger().warn("пакет кораблей не разобран: {}", e.toString());
        }
    }

    /** Виды кораблей: пересоздать, модели (треугольники для луча и корпуса) — по файлу, и при его смене. */
    private void kinds(JsonObject all) {
        JsonObject c = all.getAsJsonObject("_");
        common = c != null ? c : new JsonObject();
        Map<String, Kind> old = new HashMap<>(kinds);
        kinds.clear();
        for (Map.Entry<String, JsonElement> e : all.entrySet()) {
            if (e.getKey().equals("_") || !e.getValue().isJsonObject()) continue;
            try {
                Kind k = new Kind(e.getKey(), e.getValue().getAsJsonObject());
                kinds.put(k.id, k);
                Kind was = old.get(k.id);
                if (was != null && was.model.equals(k.model) && was.tris != null && was.scale == k.scale && was.turn == k.turn
                        && java.util.Arrays.equals(was.center, k.center)) {
                    k.tris = was.tris;
                    k.hull = was.hull;
                    System.arraycopy(was.hullLo, 0, k.hullLo, 0, 3);
                    System.arraycopy(was.hullHi, 0, k.hullHi, 0, 3);
                } else {
                    ctx.file(k.model).ifPresent(b -> shape(k.model, b));
                }
                if (watched.add(k.model)) {
                    String path = k.model;
                    ctx.onFile(path, b -> shape(path, b));
                }
            } catch (RuntimeException ex) {
                ctx.logger().warn("вид корабля {} не разобран: {}", e.getKey(), ex.toString());
            }
        }
        for (Ship s : ships.values()) {                                         // виды сменились — модели заново
            if (s.kind == null) continue;
            s.kind = kinds.get(s.kind.id);
            s.show();
        }
    }

    /** Файл модели пришёл: форма для всех видов с этой моделью. */
    private void shape(String path, byte[] file) {
        try {
            float[] tris = BbModel.parse(path, JsonParser.parseString(new String(file, StandardCharsets.UTF_8)).getAsJsonObject()).restTriangles();
            for (Kind k : kinds.values()) if (k.model.equals(path)) k.shape(tris);
        } catch (RuntimeException e) {
            ctx.logger().warn("модель корабля {} не разобрана: {}", path, e.toString());
        }
    }

    // ───────────────────────── ПКМ — посадка ─────────────────────────

    private double reach() {
        try { return common.has("board-reach") ? common.get("board-reach").getAsDouble() : 6; } catch (Exception e) { return 6; }
    }

    /** Луч от глаз по взгляду против кораблей; ближе блока мира — запрос посадки. */
    private boolean clickBoard() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || ships.isEmpty()) return false;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 eye = mc.player.getEyePosition(pt), look = mc.player.getViewVector(pt);
        double max = reach();
        HitResult hr = mc.hitResult;
        if (hr != null && hr.getType() != HitResult.Type.MISS) max = Math.min(max, hr.getLocation().distanceTo(eye));
        Ship best = null;
        double bestT = Double.MAX_VALUE;
        Vector3d pos = new Vector3d();
        Quaternionf rot = new Quaternionf();
        for (Ship s : ships.values()) {
            if (s.kind == null || !s.pose(pt, pos, rot)) continue;
            Quaternionf inv = new Quaternionf(rot).conjugate();
            Vector3f o = inv.transform(new Vector3f((float) (eye.x - pos.x), (float) (eye.y - pos.y), (float) (eye.z - pos.z)));
            Vector3f d = inv.transform(new Vector3f((float) look.x, (float) look.y, (float) look.z)).normalize();
            double t = MeshRay.hit(new double[]{o.x, o.y, o.z}, new double[]{d.x, d.y, d.z}, max, s.kind.half, s.kind.tris);
            if (t >= 0 && t < bestT) { bestT = t; best = s; }
        }
        if (best == null) return false;
        int id = best.id;
        ctx.send(new byte[]{2, (byte) (id >>> 24), (byte) (id >>> 16), (byte) (id >>> 8), (byte) id});   // BOARD
        mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return true;
    }

}
